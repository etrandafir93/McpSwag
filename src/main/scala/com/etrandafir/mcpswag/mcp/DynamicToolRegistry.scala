package com.etrandafir.mcpswag.mcp

import com.etrandafir.mcpswag.spec.{OperationDef, SpecEntry, SpecRegistry, SpecSource}
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.{ToolCallback, ToolCallbackProvider}
import org.springframework.stereotype.Service

import scala.jdk.CollectionConverters.*

@Service
class DynamicToolRegistry(
  mapper: ObjectMapper,
  specRegistry: SpecRegistry,
  configuredSourcesJava: java.util.List[SpecSource],
  executor: HttpExecutor
) extends ToolCallbackProvider:

  private val logger = LoggerFactory.getLogger(classOf[DynamicToolRegistry])

  private val configuredSources: List[SpecSource] = configuredSourcesJava.asScala.toList

  @volatile private var currentTools: Array[ToolCallback] = Array.empty

  @PostConstruct
  def bootstrap(): Unit =
    logger.info(s"[McpSwag] Bootstrapping ${configuredSources.size} configured spec source(s)")
    configuredSources.foreach {
      case src @ SpecSource.Url(name, url) =>
        logger.info(s"[McpSwag] Loading spec '$name' from URL: $url")
        specRegistry.load(src)
      case src @ SpecSource.File(name, path) =>
        logger.info(s"[McpSwag] Loading spec '$name' from FILE: $path")
        specRegistry.load(src)
    }
    rebuild()

  def loadSpec(source: SpecSource): SpecEntry =
    val entry = specRegistry.load(source)
    rebuild()
    entry

  def removeSpec(name: String): Unit =
    specRegistry.remove(name)
    rebuild()

  def reloadSpec(name: String): Option[SpecEntry] =
    val entry = specRegistry.reload(name)
    rebuild()
    entry

  private def rebuild(): Unit =
    val operations = specRegistry.allOperations
    val names = operations.map(_.toolName)
    val duplicates = names.diff(names.distinct).distinct
    if duplicates.nonEmpty then
      logger.warn(s"[McpSwag] Duplicate tool names detected: ${duplicates.mkString(", ")}")
    currentTools = operations.map(op => new OperationTool(op, mapper, executor)).toArray
    logger.info(s"[McpSwag] DynamicToolRegistry rebuilt with ${currentTools.length} tool(s)")

  override def getToolCallbacks: Array[ToolCallback] = currentTools
