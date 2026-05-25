package com.etrandafir.mcpswag.spec

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

enum SpecStatus:
  case Loading
  case Loaded(toolCount: Int)
  case Failed(message: String)

case class SpecEntry(
  source: SpecSource,
  status: SpecStatus,
  operations: List[OperationDef]
)

@Service
class SpecRegistry(parser: SpecParser):

  private val logger = LoggerFactory.getLogger(classOf[SpecRegistry])
  private val registry = new ConcurrentHashMap[String, SpecEntry]()

  def load(source: SpecSource): SpecEntry =
    val name = source.name
    val entry = parser.parse(source) match
      case Right(ops) =>
        logger.info(s"[McpSwag] Loaded spec '$name' — ${ops.size} operations → ${ops.size} MCP tools")
        SpecEntry(source, SpecStatus.Loaded(ops.size), ops)
      case Left(msg) =>
        logger.error(s"[McpSwag] Failed to load spec '$name': $msg")
        SpecEntry(source, SpecStatus.Failed(msg), Nil)
    registry.put(name, entry)
    entry

  def remove(name: String): Unit =
    registry.remove(name)

  def reload(name: String): Option[SpecEntry] =
    Option(registry.get(name)).map(e => load(e.source))

  def entries: Map[String, SpecEntry] =
    registry.asScala.toMap

  def allOperations: List[OperationDef] =
    registry.values.asScala.toList.flatMap(_.operations)
