# McpSwag — Implementation Plan

This document is the authoritative implementation guide for Claude Code.
Read it fully before writing any code. Each phase must compile and pass its verification step before the next phase starts.

---

## Decisions & constraints

| Concern | Decision |
|---|---|
| Tool execution | Tools return a `RequestDescriptor` (curl-equivalent). The agent executes the call. McpSwag never makes HTTP calls to the target APIs. |
| Tool naming | `{specName}__{operationId}`. If `operationId` is missing, synthesize: `{method}__{path segments joined by __}` e.g. `get__pet__petId` |
| Namespacing collision | Final tool name = `specName__operationId`. Two specs with same operationId are safe by design. Log a warning only if the fully qualified name still collides (should never happen). |
| Auth | None. Services are internal and open. No auth forwarding needed in this iteration. |
| Dangerous ops | Include all HTTP methods. Add `⚠️ DESTRUCTIVE — this operation modifies or deletes resources.` prefix to tool descriptions for DELETE, PUT, PATCH. |
| Hot reload | Startup load + manual reload triggered via UI/API. No polling, no filesystem watching. |
| MCP transport | HTTP Streamable (MCP spec 2025-03-26). Spring AI auto-configures `POST /mcp`. |
| Tool visibility | Single namespaced flat list. Agent sees all tools from all loaded specs. |
| Language | Scala 3.6. Use idiomatic Scala 3: `given`/`using` only where genuinely useful, `enum` for ADTs, extension methods, `case class`, `sealed trait`. No Java-style boilerplate. |
| Spring interop | Spring annotations work on Scala classes. Prefer constructor injection. Use `@BeanProperty` only if Jackson/Spring truly needs it. |

---

## Tech stack

- **Scala 3.6.4**
- **Java 21**
- **Spring Boot 3.4.5**
- **Spring AI 1.1.5** — `spring-ai-starter-mcp-server-webmvc`
- **swagger-parser-v3 `2.1.25`** — parses OAS 2 and OAS 3, resolves `$ref`s
- **jackson-module-scala `2.18.3`**
- **Thymeleaf** — for the management UI template
- **sbt 1.10.7**

---

## Phase 0 — Hello World ✅ (done)

**Status:** complete. Skeleton scaffolded directly under the final package `com.etrandafir.mcpswag` with a single `HelloWorldTool` exposing one `hello(name)` MCP tool over HTTP Streamable transport.

**Files created:**
- `build.sbt`
- `project/build.properties`, `project/plugins.sbt`
- `src/main/scala/com/etrandafir/mcpswag/McpSwagApp.scala`
- `src/main/scala/com/etrandafir/mcpswag/HelloWorldTool.scala`
- `src/main/resources/application.yml`

**Verification:** `sbt run` starts, MCP inspector at `http://localhost:8080/mcp` shows the `hello` tool.

---

## Phase 1 — Config binding & startup wiring

**Goal:** load `swagger-mcp.sources` from `application.yml` at startup and log each source. No parsing yet.

### Files to create

#### `src/main/resources/application.yml`

```yaml
spring:
  ai:
    mcp:
      server:
        name: mcpswag
        version: 0.1.0
        protocol: STREAMABLE
        type: SYNC
  mvc:
    pathmatch:
      use-suffix-pattern: false

server:
  port: 8080

swagger-mcp:
  sources:
    - name: petstore
      url: https://petstore.swagger.io/v2/swagger.json
```

#### `src/main/scala/com/etrandafir/mcpswag/config/McpSwagProperties.scala`

```scala
@ConfigurationProperties("swagger-mcp")
@ConstructorBinding
case class McpSwagProperties(sources: java.util.List[SourceConfig] = java.util.List.of())

case class SourceConfig(
  name: String,
  url: Option[String] = None,
  file: Option[String] = None
)
```

Note: Spring Boot `@ConfigurationProperties` with Scala `Option` requires a custom converter or using `java.util.Optional`. Prefer `java.util.Optional[String]` here to avoid friction, and convert to `Option` at the service boundary.

#### `src/main/scala/com/etrandafir/mcpswag/spec/SpecSource.scala`

```scala
enum SpecSource:
  case Url(name: String, url: String)
  case File(name: String, path: String)
```

#### `src/main/scala/com/etrandafir/mcpswag/config/McpSwagConfig.scala`

`@Configuration` class that:
1. Reads `McpSwagProperties`
2. Converts each `SourceConfig` to a `SpecSource` (validate: must have exactly one of url/file)
3. Exposes them as a `List[SpecSource]` bean

#### `src/main/scala/com/etrandafir/mcpswag/spec/SpecLoader.scala`

`@Component` implementing `ApplicationRunner`. Receives the `List[SpecSource]` bean and logs each one:
```
[McpSwag] Loading spec 'petstore' from URL: https://petstore.swagger.io/v2/swagger.json
```

**Verification:** `sbt run` logs each configured source on startup. No parsing yet, no failures.

---

## Phase 2 — Spec parsing

**Goal:** parse each source into a list of `OperationDef`. Log the count per spec on startup.

### New dependency in `build.sbt`

```scala
"io.swagger.parser.v3" % "swagger-parser" % "2.1.25"
```

### Files to create

#### `src/main/scala/com/etrandafir/mcpswag/spec/OperationDef.scala`

```scala
case class OperationDef(
  specName: String,
  toolName: String,          // specName__operationId (fully qualified, ready to use)
  method: HttpMethod,
  path: String,              // raw OAS path e.g. /pet/{petId}
  summary: Option[String],
  description: Option[String],
  parameters: List[ParamDef],
  requestBodySchema: Option[String],  // JSON Schema string, null if no body
  baseUrl: String,                    // resolved from OAS servers[0].url or spec URL origin
  isDestructive: Boolean              // true for DELETE, PUT, PATCH
)

case class ParamDef(
  name: String,
  in: ParamLocation,         // PATH | QUERY | HEADER
  required: Boolean,
  description: Option[String],
  schema: Option[String]     // JSON Schema string
)

enum ParamLocation:
  case Path, Query, Header
```

#### `src/main/scala/com/etrandafir/mcpswag/spec/SpecParser.scala`

`@Component` wrapping `io.swagger.v3.parser.OpenAPIV3Parser`.

Key logic:
- Call `new OpenAPIV3Parser().read(location)` — this handles both OAS 2 (converts internally) and OAS 3
- For URL sources: `location` = the URL string
- For file sources: `location` = the file path (classpath: prefix needs resolving via `ClassPathResource`)
- Iterate `openApi.getPaths.forEach` → for each path item, iterate operations by method
- Extract `operationId`, synthesize if null: `s"${method.name.toLowerCase}__${path.replace("/", "__").replace("{", "").replace("}", "")}"` then strip leading `__`
- Tool name: `s"${specName}__${operationId}"`
- `baseUrl`: `openApi.getServers.asScala.headOption.map(_.getUrl).getOrElse(deriveOrigin(sourceUrl))`
- `isDestructive`: method is DELETE, PUT, or PATCH
- Parameters: map `operation.getParameters` — skip COOKIE params for now
- Request body: if `operation.getRequestBody` is non-null, convert its schema to JSON Schema string via `SchemaConverter`

#### `src/main/scala/com/etrandafir/mcpswag/mcp/SchemaConverter.scala`

`@Component`. Converts `io.swagger.v3.oas.models.media.Schema[?]` to a JSON Schema string.

Minimal implementation for Phase 2:
- If schema is null → return `None`
- Use Jackson `ObjectMapper` to serialize a `Map` built from the schema's type, properties, required fields
- Handle: `string`, `integer`, `number`, `boolean`, `object`, `array`
- Resolve `$ref` against the `openApi.getComponents.getSchemas` map (pass `OpenAPI` object into the converter method)
- For Phase 2, inline one level of `$ref` resolution. Deeper nesting can be addressed in Phase 7.

#### `src/main/scala/com/etrandafir/mcpswag/spec/SpecRegistry.scala`

`@Service` holding:
```scala
private val registry = ConcurrentHashMap[String, SpecEntry]()

case class SpecEntry(
  source: SpecSource,
  status: SpecStatus,
  operations: List[OperationDef]
)

enum SpecStatus:
  case Loaded(toolCount: Int)
  case Failed(message: String)
  case Loading
```

Methods:
- `load(source: SpecSource): Unit` — parse via `SpecParser`, store entry, call `DynamicToolRegistry.rebuild(allOperations)`
- `remove(name: String): Unit` — remove entry, call rebuild
- `reload(name: String): Unit` — re-parse existing source's original `SpecSource`, call rebuild
- `allOperations: List[OperationDef]` — flatten all `Loaded` entries
- `entries: Map[String, SpecEntry]` — for the UI/API

Update `SpecLoader` to call `specRegistry.load(source)` for each source instead of just logging.

**Verification:**
```
[McpSwag] Loaded spec 'petstore' — 20 operations → 20 MCP tools
```
App starts, MCP server still runs (hello tool still present).

---

## Phase 3 — MCP tool generation

**Goal:** replace the static `HelloWorldTool` with dynamically generated tools — one per `OperationDef`.

This is the core of McpSwag. Read carefully.

### How Spring AI MCP tools work

Spring AI 1.1 registers tools via `ToolCallbackProvider` beans. The simplest approach is `MethodToolCallbackProvider`, but that requires static `@Tool`-annotated methods. For dynamic tools, use `FunctionToolCallback` (or implement `ToolCallback` directly).

Use `ToolCallback` directly for full control:

```scala
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
```

A `ToolCallback` has:
- `getToolDefinition: ToolDefinition` — name, description, input JSON schema
- `call(toolInput: String): String` — receives JSON string of arguments, returns string result

### Files to create

#### `src/main/scala/com/etrandafir/mcpswag/mcp/RequestDescriptor.scala`

```scala
case class RequestDescriptor(
  method: String,
  url: String,
  headers: Map[String, String],
  queryParams: Map[String, String],
  body: Option[String],
  curl: String
)
```

Jackson serialization must work on this. Annotate with `@JsonSerialize` or ensure `jackson-module-scala` is registered. Register the Scala module in a `@Configuration`:

```scala
@Bean
def objectMapper(): ObjectMapper =
  JsonMapper.builder()
    .addModule(DefaultScalaModule)
    .build()
```

#### `src/main/scala/com/etrandafir/mcpswag/mcp/OperationTool.scala`

Implements `ToolCallback`. One instance per `OperationDef`.

```scala
class OperationTool(op: OperationDef, mapper: ObjectMapper) extends ToolCallback:

  override def getToolDefinition: ToolDefinition =
    ToolDefinition.builder()
      .name(op.toolName)
      .description(buildDescription(op))
      .inputSchema(buildInputSchema(op))
      .build()

  override def call(toolInput: String): String =
    val args = mapper.readValue(toolInput, classOf[Map[String, Any]])
    val descriptor = buildDescriptor(op, args)
    mapper.writeValueAsString(descriptor)
```

`buildDescription(op)`:
- Start with `op.summary.orElse(op.description).getOrElse(op.toolName)`
- If `op.isDestructive`: prepend `"⚠️ DESTRUCTIVE — this operation modifies or deletes resources. Confirm intent before executing.\n\n"`

`buildInputSchema(op)`: construct a JSON Schema object string covering:
- All path parameters (required)
- All query parameters (required/optional per spec)
- All header parameters
- If `requestBodySchema` is non-null: a `body` field with the schema inlined

```json
{
  "type": "object",
  "properties": {
    "petId": { "type": "integer", "description": "ID of pet to return" },
    "body": { ... request body schema ... }
  },
  "required": ["petId"]
}
```

`buildDescriptor(op, args)`:
- Substitute path params: replace `{paramName}` in `op.path` with `args(paramName).toString`
- Collect query params: args whose name matches a QUERY `ParamDef`
- Full URL: `op.baseUrl.stripSuffix("/") + substitutedPath + queryString`
- Build curl string: `curl -X ${op.method} '${url}'` + body if present
- Return `RequestDescriptor`

#### `src/main/scala/com/etrandafir/mcpswag/mcp/DynamicToolRegistry.scala`

`@Service`. Holds the live set of `ToolCallback`s and exposes them to Spring AI.

Spring AI discovers tools via `ToolCallbackProvider` beans. The challenge is that the bean is registered at startup but tools are dynamic. Use a delegating provider:

```scala
@Service
class DynamicToolRegistry(mapper: ObjectMapper) extends ToolCallbackProvider:

  @volatile private var currentTools: Array[ToolCallback] = Array.empty

  def rebuild(operations: List[OperationDef]): Unit =
    currentTools = operations.map(op => OperationTool(op, mapper)).toArray

  override def getToolCallbacks: Array[ToolCallback] =
    currentTools
```

`ToolCallbackProvider` is a `@FunctionalInterface` — just implement `getToolCallbacks`. Spring AI calls this on each MCP `tools/list` request, so dynamic updates are immediately visible to agents.

Wire it: in `SpecRegistry`, inject `DynamicToolRegistry` and call `rebuild(allOperations)` after every `load`/`remove`/`reload`.

Remove `HelloWorldTool` once this phase is done and verified.

**Verification:**
- Start app with petstore URL in `application.yml`
- MCP inspector at `/mcp` lists `petstore__getPetById`, `petstore__addPet`, etc.
- Call `petstore__getPetById` with `{"petId": 1}` → returns a `RequestDescriptor` JSON with `curl -X GET 'https://petstore.swagger.io/v2/pet/1'`

---

## Phase 4 — Execute HTTP requests ✅ (done)

**Goal:** the tool actually performs the HTTP call against the target API and returns the response body to the agent. The `RequestDescriptor` is still emitted alongside the response so the curl/url/headers remain auditable.

### Why this changes Phase 3's design

Phase 3 returns only a `RequestDescriptor`. That keeps McpSwag stateless but assumes the agent has its own HTTP capability — which many MCP clients (e.g. Claude Desktop, the Anthropic API "managed agents") do not. Executing server-side makes the tools immediately useful to any MCP client.

### Decisions

| Concern | Decision |
|---|---|
| HTTP client | `java.net.http.HttpClient` (JDK built-in, no extra deps). Reuse a single `HttpClient` bean. |
| Timeout | 30 s connect + read, configurable via `swagger-mcp.http.timeout-seconds`. |
| Response shape | A `ToolResponse` JSON: `{status, headers, body, request: RequestDescriptor}`. Body is returned as a string. If response `Content-Type: application/json`, body is embedded as a JSON node, not stringified. |
| Status codes | 2xx → return normally. Non-2xx → still return the `ToolResponse` (don't throw) — the agent should see the error body. |
| Destructive ops | Refuse to execute by default. Require `"confirm": true` in the tool args to actually fire DELETE/PUT/PATCH. The tool description already warns; this is the second gate. |
| Redirects | Follow up to 5 (`HttpClient.Redirect.NORMAL`). |
| Body size cap | Truncate response body at 256 KB and add `"truncated": true` field. |
| Auth | Still none in this iteration. Phase 9 (future) handles header forwarding. |

### Files

#### `src/main/scala/com/etrandafir/mcpswag/mcp/HttpExecutor.scala`

`@Component`. Wraps `HttpClient`. Method:

```scala
def execute(descriptor: RequestDescriptor): ToolResponse
```

- Build `HttpRequest.Builder` from descriptor (method, url, headers, body)
- `.timeout(Duration.ofSeconds(timeoutSeconds))`
- Send `BodyHandlers.ofString(UTF_8)`
- Wrap result in `ToolResponse`
- Catch `IOException`/`InterruptedException` → return `ToolResponse` with synthetic status `0` and `error` field

#### `src/main/scala/com/etrandafir/mcpswag/mcp/ToolResponse.scala`

```scala
case class ToolResponse(
  status: Int,
  headers: Map[String, String],
  body: String,
  truncated: Boolean,
  error: Option[String],
  request: RequestDescriptor
)
```

#### `src/main/scala/com/etrandafir/mcpswag/config/McpSwagProperties.scala` — extend

Add nested `http` config:

```scala
case class HttpConfig(timeoutSeconds: Int = 30, maxBodyBytes: Int = 256 * 1024)
```

Bind via `swagger-mcp.http.*`.

#### `OperationTool.call` — change

```scala
override def call(toolInput: String): String =
  val args = parseArgs(toolInput)
  val descriptor = buildDescriptor(args)
  if op.isDestructive && !args.get("confirm").contains(true) then
    mapper.writeValueAsString(ToolResponse(
      status = 0,
      headers = Map.empty,
      body = "",
      truncated = false,
      error = Some("DESTRUCTIVE operation refused. Pass `confirm: true` to execute."),
      request = descriptor
    ))
  else
    val response = httpExecutor.execute(descriptor)
    mapper.writeValueAsString(response)
```

`OperationTool` now takes `httpExecutor: HttpExecutor` as a constructor param. `DynamicToolRegistry` passes it through.

#### `buildInputSchema` — add `confirm` field for destructive ops

For destructive operations, append a top-level property:
```json
"confirm": { "type": "boolean", "description": "Required to execute this destructive operation. Pass true to proceed." }
```
Add `"confirm"` to `required`.

### Verification

- [x] `petstore__getPetById` with `{"petId": 1}` actually fetches the upstream and returns the JSON body in `ToolResponse.body`
- [x] A bad ID (`{"petId": 999999}`) returns `status: 404` with the petstore's error body, not an exception
- [x] `petstore__deletePet` without `confirm: true` → returns `error: "DESTRUCTIVE operation refused..."`, no HTTP call made
- [x] `petstore__deletePet` with `{"petId": 1, "confirm": true}` → actually issues DELETE
- [x] Network timeout → `status: 0, error: "..."`
- [x] Response body > 256 KB → `truncated: true`

Covered by `HttpExecutionTest` (WireMock-backed).

---

## Phase 5 — CI: run tests on GitHub Actions ✅ (done)

**Goal:** every push to `main` and every pull request runs `sbt test` on GitHub Actions. A broken build should block a merge before any human review.

### Decisions

| Concern | Decision |
|---|---|
| Runner | `ubuntu-latest` |
| JDK | Temurin 21 (matches local) via `actions/setup-java@v4` |
| Build tool | sbt 1.10.7 — installed via `actions/setup-java`'s `cache: sbt` is not sufficient; use `sbt/setup-sbt@v1` |
| Caching | sbt + ivy + coursier caches keyed on `build.sbt` + `project/**` |
| Triggers | `push` to `main` and `pull_request` against `main` |
| Tests | `sbt -batch test` — runs the full suite (`DynamicToolRegistryTest` is `@SpringBootTest`, `HttpExecutionTest` spins up WireMock on a random port) |
| Test reporting | ScalaTest emits JUnit XML via `-u target/test-reports`. `dorny/test-reporter@v1` publishes a check run with per-test results; `actions/upload-artifact@v4` archives the raw XML. |

### Files to create

#### `.github/workflows/test.yml`

```yaml
name: tests
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: sbt/setup-sbt@v1
      - uses: actions/cache@v4
        with:
          path: |
            ~/.sbt
            ~/.ivy2/cache
            ~/.cache/coursier
          key: sbt-${{ hashFiles('build.sbt', 'project/**') }}
      - run: sbt -batch test
```

### Verification

- [x] Push this file to `main` → the `tests` workflow runs and reports ✅ (run 26415653891 — 56s, cold cache)
- [ ] Open a draft PR with a deliberately broken test → workflow reports ❌ (not exercised yet)
- [x] Workflow takes under 5 minutes on a cold cache, under 2 with the cache warm (56s cold)

---

## Phase 6 — Web UI & management API

**Goal:** a working browser UI to add/remove/reload specs, and a REST API backing it.

### Files to create

#### `src/main/scala/com/etrandafir/mcpswag/web/ApiController.scala`

`@RestController @RequestMapping("/api")`

Endpoints:

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/specs` | List all spec entries with status and tool count |
| `POST` | `/api/specs/url` | Add URL source. Body: `{"name": "petstore", "url": "https://..."}` |
| `POST` | `/api/specs/file` | Upload file. `multipart/form-data` with `name` field and `file` part |
| `DELETE` | `/api/specs/{name}` | Remove spec and its tools |
| `POST` | `/api/specs/{name}/reload` | Re-fetch/re-parse and rebuild |
| `GET` | `/api/tools` | List all tools: name, method, path, spec, param count |

Response shapes (use simple case classes serialized by Jackson):

```scala
case class SpecSummary(name: String, source: String, status: String, toolCount: Int)
case class ToolSummary(name: String, method: String, path: String, specName: String, paramCount: Int, isDestructive: Boolean)
```

#### `src/main/scala/com/etrandafir/mcpswag/web/UiController.scala`

`@Controller`

```scala
@GetMapping("/")
def index(model: Model): String =
  model.addAttribute("specs", specRegistry.entries)
  "index"
```

#### `src/main/resources/templates/index.html`

Thymeleaf template. Implement the UI from the mockup in the design session:

- Top bar: logo "McpSwag", MCP server status dot + endpoint URL
- Stats row: specs loaded, MCP tools, total endpoints, errors
- Two-column layout:
    - Left: "add spec source" panel — drag-and-drop zone + URL input + `application.yml` hint block
    - Right: "registered specs" panel — list of spec items with status icons, reload/delete buttons
- Full-width tools table: columns = tool name, method badge, path, spec, param count, summary
    - Method badges: GET (green), POST (blue), PUT (amber), DELETE (red)
    - Filter input above table
- All interactions via `fetch()` to the `/api` endpoints, no page reload needed
- On spec add/remove/reload: re-fetch `/api/specs` and `/api/tools`, update the DOM

No external JS frameworks. Vanilla JS only. Thymeleaf only used for the initial server-side render; subsequent updates are client-side via fetch.

**Verification:**
- Open `http://localhost:8080`, see the UI
- Paste a URL, click add → spec appears in list with tool count
- Click delete → spec and tools removed
- Tools table updates in real time

---

## Phase 7 — Edge cases & hardening

Work through these after Phase 6 is fully functional.

### operationId synthesis

When `operation.getOperationId` is null:
```scala
def synthesizeOperationId(method: String, path: String): String =
  val segments = path.split("/").filter(_.nonEmpty)
    .map(s => if s.startsWith("{") then s.drop(1).dropRight(1) else s)
  (method.toLowerCase +: segments).mkString("__")
// GET /pet/{petId} → get__pet__petId
```

### baseUrl fallback

If `openApi.getServers` is null or empty:
- For URL sources: derive origin from the source URL (`scheme://host:port`)
- For file sources: use `http://localhost:8080` as default and log a warning

### $ref resolution in SchemaConverter

The swagger-parser resolves `$ref` inline by default when using `OpenAPIV3Parser` with `ParseOptions`. Ensure `ParseOptions` has `resolveFully = true`:

```scala
val opts = new ParseOptions()
opts.setResolveFully(true)
new OpenAPIV3Parser().read(location, null, opts)
```

This avoids needing manual `$ref` traversal in most cases.

### OAS 2 basePath

swagger-parser converts OAS 2 to OAS 3 internally, merging `host + basePath + schemes` into `servers[0].url`. No special handling needed if `resolveFully` is set. Verify with a Swagger 2.0 spec (petstore).

### SpecRegistry error state

Wrap `SpecParser.parse()` in a try/catch. Store `SpecStatus.Failed(exception.getMessage)` if parsing fails. The UI should show the red error state with the message. The rebuild is not called on failure — existing tools for that spec name are cleared.

### Tool name collision guard

After building `currentTools` in `DynamicToolRegistry.rebuild`, check for duplicate names:
```scala
val names = operations.map(_.toolName)
val duplicates = names.diff(names.distinct)
if duplicates.nonEmpty then
  logger.warn(s"[McpSwag] Duplicate tool names detected: ${duplicates.mkString(", ")}")
```

### Missing summary and description

If both `summary` and `description` are null/empty on an operation, fall back to the tool name itself as the description. Never pass an empty string to `ToolDefinition.description` — Spring AI may reject it.

### int64 JSON-number precision ✅ (done early)

OpenAPI `integer` / `format: int64` params advertised as JSON numbers lose precision over the MCP wire — values above 2^53 are rounded by JS-style double parsers (observed against `getOrderById` with an order ID of `8762099875811304519` → received as `8762099875811304000`). `OperationTool.paramSchemaNode` now rewrites any int64 path/query/header param to `{type: string, pattern: "^-?\\d+$"}` so the model serializes it as a digit-preserving string. URL substitution already goes through `stringify`, so no further changes were needed. Covered by `HttpExecutionTest` ("int64 path param ..." tests). Request body int64 fields are still a known gap.

---

## Phase 8 — Docker & image publishing

**Goal:** ship McpSwag as a runnable container image. `docker run <registry>/mcpswag` should start the MCP server on port 8080 with no extra setup. Image is built and pushed by GitHub Actions on every push to `main` and on tagged releases.

### Decisions

| Concern | Decision |
|---|---|
| Registry | Docker Hub: `docker.io/<dockerhub-user>/mcpswag` (fill in actual username before first push) |
| Base image | `eclipse-temurin:21-jre-jammy` for runtime, `eclipse-temurin:21-jdk-jammy` for the build stage |
| Build inside Docker | Multi-stage: stage 1 runs `sbt assembly` (or `sbt stage`), stage 2 copies the artifact into the JRE image |
| Packaging | Use `sbt-native-packager`'s `JavaAppPackaging` → `sbt stage` produces `target/universal/stage/{bin,lib}`. Avoid fat-jar/assembly to keep layer caching effective. |
| Config override | `application.yml` baked in with empty `swagger-mcp.sources`. Users override via env vars (`SPRING_APPLICATION_JSON`) or by mounting a config file at `/app/config/application.yml` (Spring picks it up via `--spring.config.additional-location`) |
| Exposed port | `8080` |
| Image tags | `latest` + short SHA on `main`; semver tag (e.g. `0.1.0`) on `v*` git tags |
| Multi-arch | `linux/amd64` + `linux/arm64` via `docker/build-push-action` + QEMU |
| Secrets | `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` stored as GitHub Actions repo secrets |

### Files to create

#### `project/plugins.sbt` (add)

```scala
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
```

#### `build.sbt` (enable plugin)

```scala
.enablePlugins(JavaAppPackaging)
```

Set `Compile / mainClass := Some("com.etrandafir.mcpswag.McpSwagApp")` (already present) and `executableScriptName := "mcpswag"`.

#### `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src

# Install sbt
RUN apt-get update && apt-get install -y --no-install-recommends curl gnupg ca-certificates && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add - && \
    apt-get update && apt-get install -y --no-install-recommends sbt && \
    rm -rf /var/lib/apt/lists/*

# Warm sbt + dependency cache first for better layer reuse
COPY project/ project/
COPY build.sbt ./
RUN sbt update

COPY src/ src/
RUN sbt stage

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=build /src/target/universal/stage/ /app/
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["/app/bin/mcpswag"]
```

#### `.dockerignore`

```
target/
project/target/
project/project/
.git/
.idea/
.bsp/
*.iml
```

#### `.github/workflows/docker.yml`

```yaml
name: docker

on:
  push:
    branches: [main]
    tags: ['v*']
  workflow_dispatch:

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4

      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3

      - name: Login to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ secrets.DOCKERHUB_USERNAME }}/mcpswag
          tags: |
            type=ref,event=branch
            type=sha,format=short
            type=semver,pattern={{version}}
            type=raw,value=latest,enable={{is_default_branch}}

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          platforms: linux/amd64,linux/arm64
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

### Verification

- [ ] `docker build -t mcpswag:dev .` succeeds locally
- [ ] `docker run --rm -p 8080:8080 mcpswag:dev` starts the app; `curl http://localhost:8080/mcp` responds
- [ ] Mounting a custom config works: `docker run --rm -p 8080:8080 -v $PWD/my-config.yml:/app/config/application.yml -e SPRING_CONFIG_ADDITIONAL_LOCATION=/app/config/application.yml mcpswag:dev` loads the user's specs
- [ ] GitHub Actions: push to `main` → image appears in Docker Hub tagged `latest` and `sha-<short>`
- [ ] Tagging `v0.1.0` → image appears tagged `0.1.0`
- [ ] `docker manifest inspect` shows both `amd64` and `arm64` variants

### Follow-ups (not blocking)

- Consider switching from Docker Hub to GHCR (`ghcr.io/etrandafir/mcpswag`) if the user prefers — GHCR auth uses the built-in `GITHUB_TOKEN`, no separate secrets needed.
- Add a `compose.yaml` for local dev with a mounted spec directory.
- SBOM / provenance attestation via `docker/build-push-action`'s `sbom: true` / `provenance: true` once the image is stable.

---

## Phase 9 — Future (not in scope now)

These are documented here for awareness. Do not implement in the current iteration.

- **Per-source method filtering**: `swagger-mcp.sources[n].excludeMethods: [DELETE]`
- **Per-source static headers**: `swagger-mcp.sources[n].headers: {Authorization: "Bearer ..."}` — forwarded in the `RequestDescriptor`, not executed by McpSwag
- **AsyncAPI support**: separate parser branch for AsyncAPI 2.x/3.x specs, exposing publish/subscribe operations as tools
- **Spec polling**: `swagger-mcp.sources[n].reloadInterval: 5m` — background scheduler per URL source
- **Tool filtering by tag**: `swagger-mcp.sources[n].includeTags: [pets, store]`
- **OpenAPI 3.1 full support**: swagger-parser handles 3.0 well; 3.1 has breaking schema changes (JSON Schema dialect)

---

## Coding conventions for Claude Code

- **Package**: `com.etrandafir.mcpswag`
- **No `var`** unless mutating state in a thread-safe structure (`@volatile`, `AtomicReference`, `ConcurrentHashMap`)
- **No `null`** — use `Option`, handle Java API nulls at the boundary with `Option(javaValue)`
- **Scala collections at boundaries**: convert Java collections from Spring/swagger-parser immediately: `javaList.asScala.toList`
- **Logging**: use `org.slf4j.LoggerFactory` via a companion object `val logger = LoggerFactory.getLogger(classOf[Foo])`
- **Error handling**: use `Try` for parsing operations, convert to `Either[String, T]` at service boundaries
- **Spring beans**: prefer `@Service` / `@Component` / `@RestController`. Constructor injection only — no `@Autowired` on fields.
- **No unnecessary abstractions**: don't create traits/type classes unless there are at least 2 concrete implementations needed now
- **Test**: at minimum, a `SpecParserSpec` that loads the petstore URL and asserts `operations.nonEmpty` and at least one operation has the expected tool name

---

## Verification checklist (end state)

- [ ] `sbt run` starts cleanly with petstore configured in `application.yml`
- [ ] MCP inspector shows all petstore tools namespaced as `petstore__*`
- [ ] Calling `petstore__getPetById` with `{"petId": 42}` returns a valid `RequestDescriptor` with the correct URL and curl string
- [ ] DELETE operations have `⚠️ DESTRUCTIVE` in their description
- [ ] UI at `http://localhost:8080` shows the spec list and tools table
- [ ] Adding a URL source via the UI registers new tools without restart
- [ ] Removing a spec via the UI removes its tools from the MCP list
- [ ] Loading a broken URL shows the error state in the UI
- [ ] `sbt test` passes