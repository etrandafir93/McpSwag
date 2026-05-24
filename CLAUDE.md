# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This repo (McpSwag — OpenAPI → MCP Gateway) is in **Phase 0** per `PLAN.md`. A minimal Spring Boot + Spring AI MCP server is wired up with a single `hello` tool over HTTP Streamable transport. None of the spec-loading / dynamic-tool machinery exists yet — the README describes the target state, `PLAN.md` is the authoritative phase-by-phase implementation guide.

## Commands (sbt project)

- `sbt run` — start the Spring Boot app (MCP endpoint on `http://localhost:8080/mcp`)
- `sbt compile` / `sbt test`
- `sbt "testOnly *SpecParserSpec"` — run a single test class

## Architecture (target — per PLAN.md)

McpSwag is a **stateless gateway**: it parses OpenAPI/Swagger specs and exposes each operation as an MCP tool, but it does **not** execute HTTP calls. Tools return a `RequestDescriptor` (method/url/headers/body/curl) and the calling agent decides whether/how to execute.

Key structural points future contributors must preserve:

- **Tool naming is `{specName}__{operationId}`** — namespacing prevents collisions across multiple loaded specs. If an OAS operation lacks `operationId`, synthesize from method+path (e.g. `get__pet__petId`).
- **Two spec sources**: `Url` and `File`, modeled as a Scala 3 `enum SpecSource`.
- **Spec lifecycle is mutable at runtime**: `SpecRegistry` supports add/remove/reload without restart, driven by both static `application.yml` config and the runtime UI/REST API (`/api/specs`).
- **`DynamicToolRegistry`** implements Spring AI's `ToolCallbackProvider` and rebuilds the `ToolCallback` array (held in a `@volatile var`) on every spec change — tools are not statically registered at boot. Spring AI calls `getToolCallbacks` per MCP `tools/list` request, so updates are immediately visible to agents.
- **Destructive ops (DELETE/PUT/PATCH)** must be marked with a warning prefix in their MCP tool description.
- **Transport is HTTP Streamable MCP** (spec 2025-03-26) via `spring-ai-starter-mcp-server-webmvc` — not stdio.

Package layout under `src/main/scala/com/etrandafir/mcpswag/`: `config/`, `spec/` (sources + registry + parser), `mcp/` (tool registry, per-operation tool, descriptor, schema converter), `web/` (UI + API controllers). UI is Thymeleaf + vanilla JS at `resources/templates/index.html`.

## Scala 3 conventions (from PLAN.md §Coding conventions)

- Root package `com.etrandafir.mcpswag`
- No `var` unless guarded (`@volatile`, `AtomicReference`, `ConcurrentHashMap`). `DynamicToolRegistry.currentTools` is the canonical exception.
- No `null` — wrap Java API returns with `Option(javaValue)` at the boundary.
- Convert Java collections from Spring/swagger-parser immediately: `javaList.asScala.toList`.
- Constructor injection only — no `@Autowired` fields.
- For Spring `@ConfigurationProperties` with optional fields, use `java.util.Optional[String]` (not Scala `Option`) and convert at the service boundary — Scala `Option` needs a custom converter.
