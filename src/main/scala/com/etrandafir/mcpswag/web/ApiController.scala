package com.etrandafir.mcpswag.web

import com.etrandafir.mcpswag.mcp.DynamicToolRegistry
import com.etrandafir.mcpswag.spec.{SpecRegistry, SpecSource, SpecStatus}
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

import java.nio.file.{Files, StandardCopyOption}

case class SpecSummary(
  name: String,
  source: String,
  sourceType: String,
  status: String,
  toolCount: Int,
  errorMessage: Option[String]
)

case class ToolSummary(
  name: String,
  method: String,
  path: String,
  specName: String,
  paramCount: Int,
  isDestructive: Boolean,
  summary: Option[String]
)

case class AddUrlRequest(name: String, url: String)

@RestController
@RequestMapping(Array("/api"))
class ApiController(registry: DynamicToolRegistry, specs: SpecRegistry):

  @GetMapping(Array("/specs"))
  def listSpecs(): List[SpecSummary] =
    specs.entries.map((name, entry) => toSummary(name, entry)).toList

  @PostMapping(Array("/specs/url"))
  def addUrlSpec(@RequestBody req: AddUrlRequest): SpecSummary =
    val entry = registry.loadSpec(SpecSource.Url(req.name, req.url))
    toSummary(req.name, entry)

  @PostMapping(Array("/specs/file"))
  def addFileSpec(@RequestParam("name") name: String,
                  @RequestParam("file") file: MultipartFile): SpecSummary =
    val tmp = Files.createTempFile(s"mcpswag-upload-$name-", ".yaml")
    Files.copy(file.getInputStream, tmp, StandardCopyOption.REPLACE_EXISTING)
    val entry = registry.loadSpec(SpecSource.File(name, tmp.toAbsolutePath.toString))
    toSummary(name, entry)

  @DeleteMapping(Array("/specs/{name}"))
  def removeSpec(@PathVariable("name") name: String): ResponseEntity[?] =
    if specs.entries.contains(name) then
      registry.removeSpec(name)
      ResponseEntity.noContent().build()
    else
      ResponseEntity.notFound().build()

  @PostMapping(Array("/specs/{name}/reload"))
  def reloadSpec(@PathVariable("name") name: String): ResponseEntity[SpecSummary] =
    registry.reloadSpec(name) match
      case Some(entry) => ResponseEntity.ok(toSummary(name, entry))
      case None        => ResponseEntity.notFound().build()

  @GetMapping(Array("/tools"))
  def listTools(): List[ToolSummary] =
    specs.allOperations.map: op =>
      ToolSummary(op.toolName, op.method.toString, op.path, op.specName,
                  op.parameters.size, op.isDestructive, op.summary)

  private def toSummary(name: String, entry: com.etrandafir.mcpswag.spec.SpecEntry): SpecSummary =
    val (src, srcType) = entry.source match
      case SpecSource.Url(_, url)   => (url, "url")
      case SpecSource.File(_, path) => (path, "file")
    val (status, toolCount, errMsg) = entry.status match
      case SpecStatus.Loading     => ("loading", 0, None)
      case SpecStatus.Loaded(n)   => ("loaded", n, None)
      case SpecStatus.Failed(msg) => ("failed", 0, Some(msg))
    SpecSummary(name, src, srcType, status, toolCount, errMsg)
