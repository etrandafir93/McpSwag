# McpSwag

**OpenAPI → MCP Gateway**

McpSwag turns existing REST APIs into MCP tools — automatically, at startup, with no code changes to your services. Point it at an OpenAPI / Swagger spec (file or URL) and your AI agent gets one fully typed, namespaced MCP tool per operation.

```
petstore.yaml  ──▶  McpSwag  ──▶  petstore__getPetById
inventory URL  ──▶           ──▶  petstore__addPet
                             ──▶  inventory__getItems
                             ──▶  ...
```

[![tests](https://github.com/etrandafir93/McpSwag/actions/workflows/test.yml/badge.svg)](https://github.com/etrandafir93/McpSwag/actions/workflows/test.yml)

---

## What it does today

- Reads OpenAPI 3.x and Swagger 2.0 specs from file or URL sources listed in `application.yml`
- Generates one namespaced MCP tool per operation: `{specName}__{operationId}` (e.g. `petstore__getPetById`)
- **Executes the HTTP call** when the agent invokes a tool and returns the response body, status, and headers (along with the request descriptor for auditability)
- Refuses to execute destructive operations (`DELETE` / `PUT` / `PATCH`) unless the agent passes `confirm: true`, and prefixes their description with a `⚠️ DESTRUCTIVE` warning
- Serves tools over **HTTP Streamable MCP transport** (MCP spec 2025-03-26) at `POST /mcp`
- Exposes `int64` parameters as JSON strings to avoid silent precision loss on values above 2^53

### Not built yet

- Web UI / management REST API for adding, removing, or reloading specs at runtime (Phase 6 — see `PLAN.md`)
- AsyncAPI support, per-source auth header forwarding, method filtering (Phase 9)

---

## Quick start

### Option A — Docker (no Java toolchain needed)

```bash
docker pull ghcr.io/etrandafir93/mcpswag:latest
docker run --rm -p 8080:8080 ghcr.io/etrandafir93/mcpswag:latest
```

The image ships with a bundled Swagger Petstore spec so you can see ~19 MCP tools on first run. To load your own specs, mount a config file:

```bash
docker run --rm -p 8080:8080 \
  -v $PWD/my-app.yml:/app/config/application.yml \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=/app/config/application.yml \
  ghcr.io/etrandafir93/mcpswag:latest
```

Published images live at <https://github.com/etrandafir93/McpSwag/pkgs/container/mcpswag>.

### Option B — Build from source

Prereqs: **Java 21+** and **sbt 1.10+** (Scala 3.6 is pulled in by sbt).

```bash
git clone https://github.com/etrandafir93/McpSwag
cd McpSwag
sbt run
```

MCP endpoint: `http://localhost:8080/mcp`.

### Connect your agent

Claude Code:

```bash
claude mcp add mcpswag --transport http http://localhost:8080/mcp
```

Claude Desktop or another MCP client:

```json
{
  "mcpServers": {
    "mcpswag": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

---

## Configuration

Specs are listed under `swagger-mcp.sources` in `application.yml`. Each source has a `name` and either a `url` or a `file`.

```yaml
swagger-mcp:
  sources:
    - name: petstore
      file: classpath:openapi/petstore.yml

    - name: inventory
      url: https://inventory.internal/v3/api-docs
```

HTTP-execution settings:

```yaml
swagger-mcp:
  http:
    timeoutSeconds: 30      # per request
    maxBodyBytes: 262144    # response bodies larger than this are truncated
```

Runtime mutation (adding / removing specs without a restart) is planned for Phase 6 but not built yet — changes to `application.yml` currently require a process restart.

---

## How a tool call works

When the agent invokes `petstore__getPetById` with `{"petId": "1"}`:

1. McpSwag substitutes path / query / header parameters into the request URL.
2. It performs the upstream HTTP call (with timeout and body-size caps).
3. It returns a `ToolResponse` JSON containing the status, headers, body, a `truncated` flag, and the full `RequestDescriptor` (curl-equivalent) used to make the call.

```json
{
  "status": 200,
  "headers": { "content-type": "application/json" },
  "body": "{\"id\":1,\"name\":\"doggie\",\"status\":\"available\"}",
  "truncated": false,
  "error": null,
  "request": {
    "method": "GET",
    "url": "https://petstore.swagger.io/v2/pet/1",
    "headers": {},
    "queryParams": {},
    "body": null,
    "curl": "curl -X GET 'https://petstore.swagger.io/v2/pet/1'"
  }
}
```

For destructive operations (`DELETE` / `PUT` / `PATCH`), the agent must additionally pass `"confirm": true`. Otherwise the request is refused and no upstream call is made.

---

## Tool naming

Every MCP tool is named `{specName}__{operationId}`.

| Spec | Operation | Tool |
|---|---|---|
| `petstore` | `getPetById` | `petstore__getPetById` |
| `inventory` | `listItems` | `inventory__listItems` |

If a spec has no `operationId` on an operation, McpSwag synthesizes one from method + path segments (e.g. `get__pet__petId`).

---

## Tech stack

| Layer | Choice |
|---|---|
| Language | Scala 3.6 |
| Runtime | Java 21 |
| Framework | Spring Boot 3.5 |
| MCP | Spring AI 1.1 (`spring-ai-starter-mcp-server-webmvc`) |
| HTTP client | `java.net.http.HttpClient` (JDK) |
| Spec parsing | `swagger-parser-v3` (OAS 2 + OAS 3) |
| Build | sbt 1.10 |
| Packaging | `sbt-native-packager` → Docker image on `eclipse-temurin:21-jre-jammy` |
| CI | GitHub Actions (tests on every push, image publish to GHCR on manual trigger) |

---

## Project layout

```
McpSwag/
├── build.sbt
├── project/
├── Dockerfile
├── .github/workflows/
│   ├── test.yml           # sbt test on every push / PR, JUnit report
│   └── docker.yml         # build and push image to GHCR, manual trigger only
├── src/main/scala/com/etrandafir/mcpswag/
│   ├── McpSwagApp.scala               # Spring Boot entry point
│   ├── ListSpecsTool.scala            # built-in MCP tool that lists loaded specs
│   ├── config/                        # @ConfigurationProperties + beans
│   ├── spec/                          # SpecSource, SpecParser, SpecRegistry, OperationDef
│   └── mcp/                           # DynamicToolRegistry, OperationTool, HttpExecutor,
│                                      # RequestDescriptor, ToolResponse, SchemaConverter
├── src/test/scala/com/etrandafir/mcpswag/
│   ├── DynamicToolRegistryTest.scala  # @SpringBootTest — registry bootstrap
│   ├── HttpExecutionTest.scala        # WireMock-backed execution + destructive + int64 tests
│   └── ListSpecsToolTest.scala
├── src/main/resources/
│   ├── application.yml
│   └── openapi/petstore.yml           # bundled sample spec
└── PLAN.md                            # authoritative phase-by-phase implementation guide
```

---

## Roadmap

Authoritative source is [`PLAN.md`](PLAN.md). Snapshot:

- [x] **Phase 0** — Hello-world MCP tool over HTTP Streamable transport
- [x] **Phase 1** — Config binding (`swagger-mcp.sources`) and startup wiring
- [x] **Phase 2** — Spec parsing (OAS 2 + OAS 3) → `OperationDef` registry
- [x] **Phase 3** — Dynamic MCP tool generation from parsed specs
- [x] **Phase 4** — Tools execute HTTP requests; `ToolResponse` with destructive-op confirm gate
- [x] **Phase 5** — CI: `sbt test` on every push / PR with JUnit report
- [ ] **Phase 6** — Web UI + management REST API (add / remove / reload at runtime) ← next
- [ ] **Phase 7** — Edge cases & hardening (operationId synthesis, base-URL fallback, deep `$ref`)
- [x] **Phase 8** — Docker image + GHCR publishing (manual workflow trigger)
- [ ] **Phase 9** — Future: AsyncAPI, per-source auth, method filtering
