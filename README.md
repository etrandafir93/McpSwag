# McpSwag

**OpenAPI → MCP Gateway**

McpSwag turns your existing REST APIs into MCP tools — automatically, at runtime, with no code changes to your services. Point it at a Swagger/OpenAPI spec (file or URL), and your AI agent gets a fully typed, namespaced MCP tool for every operation.

```
petstore.yaml  ──▶  McpSwag  ──▶  petstore__getPetById
inventory.yaml ──▶           ──▶  petstore__addPet
orders URL     ──▶           ──▶  inventory__getItems
                             ──▶  orders__createOrder
                             ──▶  ...
```

---

## What it does

- Reads OpenAPI 3.x and Swagger 2.0 specs from files or live URLs
- Exposes every operation as a namespaced MCP tool (`specName__operationId`)
- Tools return a **request descriptor** (URL, method, headers, body, curl equivalent) — the agent decides how and whether to execute the call
- Serves tools over **HTTP Streamable MCP transport** (MCP spec 2025-03-26)
- Provides a minimal web UI to add/remove/reload specs at runtime
- Supports static configuration via `application.yml` for startup sources
- Marks destructive operations (DELETE, PUT, PATCH) clearly in tool descriptions

---

## Quick start

### Prerequisites

- Java 21+
- Scala 3.6+
- sbt 1.10+

### Run

```bash
git clone https://github.com/etrandafir93/mcpswag
cd mcpswag
sbt run
```

The MCP server starts on `http://localhost:8080/mcp`.
The management UI is at `http://localhost:8080`.

### Connect your agent

In Claude Desktop or any MCP-compatible client:

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

In Claude Code:

```bash
claude mcp add mcpswag --transport http http://localhost:8080/mcp
```

---

## Configuration

### Static sources (application.yml)

```yaml
spring:
  ai:
    mcp:
      server:
        name: mcpswag
        version: 0.1.0
        protocol: STREAMABLE

swagger-mcp:
  sources:
    - name: petstore
      url: https://petstore.swagger.io/v2/swagger.json

    - name: inventory
      url: https://inventory.internal:8081/v3/api-docs

    - name: orders
      file: classpath:specs/orders.yaml
```

### Runtime (UI or API)

Sources can also be added, removed, and reloaded at runtime via the web UI at `http://localhost:8080` or directly via the management REST API — no restart required.

---

## Tool naming

Every MCP tool is named `{specName}__{operationId}`.

| Spec name | Operation | Tool name |
|---|---|---|
| `petstore` | `getPetById` | `petstore__getPetById` |
| `inventory` | `listItems` | `inventory__listItems` |
| `orders` | `createOrder` | `orders__createOrder` |

If a spec has no `operationId` on an operation, McpSwag synthesizes one from the method and path: `get__pet__petId`.

---

## Tool output

Tools do **not** execute HTTP calls themselves. Each tool returns a `RequestDescriptor`:

```json
{
  "method": "GET",
  "url": "https://petstore.swagger.io/v2/pet/42",
  "headers": {},
  "queryParams": {},
  "body": null,
  "curl": "curl -X GET 'https://petstore.swagger.io/v2/pet/42'"
}
```

The agent receives this descriptor and decides how to proceed. This keeps McpSwag stateless with respect to actual API calls and lets the agent apply its own auth, retry, and execution logic.

---

## Destructive operations

DELETE, PUT, and PATCH tools include a warning in their description:

```
DESTRUCTIVE — this operation modifies or deletes resources. Confirm intent before executing.
```

All HTTP methods are included by default. Filtering can be configured per spec source (see PLAN.md).

---

## Tech stack

| Layer | Choice |
|---|---|
| Language | Scala 3.6 |
| Runtime | Java 21 |
| Framework | Spring Boot 3.4 |
| MCP | Spring AI 1.1 (`spring-ai-starter-mcp-server-webmvc`) |
| Spec parsing | `swagger-parser-v3` (OAS 2 + OAS 3) |
| Build | sbt 1.10 |
| UI | Thymeleaf + vanilla JS |

---

## Project structure

```
mcpswag/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── src/main/
│   ├── scala/com/etrandafir/mcpswag/
│   │   ├── McpSwagApp.scala                  # Spring Boot entry point
│   │   ├── config/
│   │   │   └── McpSwagProperties.scala       # @ConfigurationProperties binding
│   │   ├── spec/
│   │   │   ├── SpecSource.scala              # sealed trait: UrlSource | FileSource
│   │   │   ├── SpecRegistry.scala            # load/remove/reload, holds parsed state
│   │   │   └── SpecParser.scala              # swagger-parser → List[OperationDef]
│   │   ├── mcp/
│   │   │   ├── DynamicToolRegistry.scala     # builds ToolCallback list from OperationDefs
│   │   │   ├── OperationTool.scala           # one MCP tool per OAS operation
│   │   │   ├── RequestDescriptor.scala       # the tool's return type (curl-equivalent)
│   │   │   └── SchemaConverter.scala         # OAS Schema → JSON Schema string
│   │   └── web/
│   │       ├── UiController.scala            # GET / → index.html
│   │       └── ApiController.scala           # /api/specs CRUD + /api/tools list
│   └── resources/
│       ├── application.yml
│       └── templates/
│           └── index.html                    # management UI
└── PLAN.md
```

---

## Roadmap

- [x] Phase 0 — Hello World MCP tool, Streamable HTTP transport working
- [ ] Phase 1 — Skeleton, config binding, startup logging
- [ ] Phase 2 — Spec loading and parsing (OAS 2 + OAS 3), `SpecRegistry`
- [ ] Phase 3 — MCP tool generation, `RequestDescriptor`, `DynamicToolRegistry`
- [ ] Phase 4 — Web UI + management REST API
- [ ] Phase 5 — Edge cases, error states, operationId synthesis
- [ ] Future — AsyncAPI support, per-source auth headers, tool filtering by method
