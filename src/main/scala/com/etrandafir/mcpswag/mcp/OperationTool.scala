package com.etrandafir.mcpswag.mcp

import com.etrandafir.mcpswag.spec.{HttpMethod, OperationDef, ParamDef, ParamLocation}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.{DefaultToolDefinition, ToolDefinition}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

class OperationTool(op: OperationDef, mapper: ObjectMapper, executor: HttpExecutor) extends ToolCallback:

  private val definition: ToolDefinition =
    DefaultToolDefinition.builder()
      .name(op.toolName)
      .description(OperationTool.buildDescription(op))
      .inputSchema(buildInputSchema())
      .build()

  override def getToolDefinition: ToolDefinition = definition

  override def call(toolInput: String): String =
    val args       = parseArgs(toolInput)
    val descriptor = buildDescriptor(args)
    val response =
      if op.isDestructive && !confirmed(args) then
        ToolResponse(
          status    = 0,
          headers   = Map.empty,
          body      = "",
          truncated = false,
          error     = Some("DESTRUCTIVE operation refused. Pass `confirm: true` in the tool arguments to execute."),
          request   = descriptor
        )
      else
        executor.execute(descriptor)
    mapper.writeValueAsString(response)

  private def parseArgs(toolInput: String): Map[String, Any] =
    Option(toolInput).filterNot(_.isBlank) match
      case None => Map.empty
      case Some(input) =>
        mapper.readTree(input) match
          case obj: ObjectNode =>
            obj.properties.asScala.iterator.flatMap { e =>
              nodeToScala(e.getValue).map(e.getKey -> _)
            }.toMap
          case _ => Map.empty

  private def confirmed(args: Map[String, Any]): Boolean =
    args.get("confirm").exists {
      case b: java.lang.Boolean => b.booleanValue
      case b: Boolean           => b
      case _                    => false
    }

  private def nodeToScala(n: JsonNode): Option[Any] =
    Option(n).filterNot(_.isNull).map { node =>
      if node.isTextual then node.asText
      else if node.isBoolean then node.asBoolean
      else if node.isIntegralNumber then node.asLong
      else if node.isFloatingPointNumber then node.asDouble
      else node // arrays/objects: keep as JsonNode, serialize back when needed
    }

  private def buildInputSchema(): String =
    val paramEntries: List[(String, Any)] =
      op.parameters.map(p => p.name -> paramSchema(p))

    val bodyEntry: Option[(String, Any)] =
      op.requestBodySchema.map(json => "body" -> mapper.readTree(json))

    val confirmEntry: Option[(String, Any)] =
      Option.when(op.isDestructive) {
        "confirm" -> ListMap[String, Any](
          "type"        -> "boolean",
          "description" -> "Required to execute this destructive operation. Pass true to proceed."
        )
      }

    val properties: ListMap[String, Any] =
      ListMap.from(paramEntries ++ bodyEntry ++ confirmEntry)

    val required: List[String] =
      op.parameters.filter(_.required).map(_.name) ++
        bodyEntry.map(_ => "body") ++
        confirmEntry.map(_ => "confirm")

    val root: ListMap[String, Any] =
      ListMap[String, Any]("type" -> "object", "properties" -> properties) ++
        Option.when(required.nonEmpty)("required" -> required)

    mapper.writeValueAsString(root)

  private def paramSchema(p: ParamDef): ListMap[String, Any] =
    val base = p.schema match
      case Some(json) => jsonToMap(json)
      case None       => ListMap[String, Any]("type" -> "string")
    val rewritten = rewriteInt64ToString(base)
    p.description match
      case Some(d) if !rewritten.contains("description") => rewritten.updated("description", d)
      case _                                              => rewritten

  private def jsonToMap(json: String): ListMap[String, Any] =
    mapper.readTree(json) match
      case obj: ObjectNode =>
        obj.properties.asScala.foldLeft(ListMap.empty[String, Any]) { (acc, e) =>
          acc.updated(e.getKey, e.getValue)
        }
      case _ => ListMap.empty

  private def rewriteInt64ToString(schema: ListMap[String, Any]): ListMap[String, Any] =
    val isInt64 =
      schema.get("type").flatMap(stringValue).contains("integer") &&
        schema.get("format").flatMap(stringValue).contains("int64")
    if !isInt64 then schema
    else
      val note    = "int64 as string — JSON number precision loss for values > 2^53."
      val newDesc = schema.get("description").flatMap(stringValue).fold(note)(d => s"$d ($note)")
      schema
        .updated("type", "string")
        .removed("format")
        .updated("pattern", "^-?\\d+$")
        .updated("description", newDesc)

  private def stringValue(v: Any): Option[String] = v match
    case s: String                          => Some(s)
    case n: JsonNode if n.isTextual         => Some(n.asText)
    case _                                  => None

  private def buildDescriptor(args: Map[String, Any]): RequestDescriptor =
    val pathParamDefs   = op.parameters.filter(_.in == ParamLocation.Path)
    val queryParamDefs  = op.parameters.filter(_.in == ParamLocation.Query)
    val headerParamDefs = op.parameters.filter(_.in == ParamLocation.Header)

    val substitutedPath = pathParamDefs.foldLeft(op.path) { (acc, p) =>
      args.get(p.name)
        .map(v => acc.replace(s"{${p.name}}", URLEncoder.encode(stringify(v), StandardCharsets.UTF_8)))
        .getOrElse(acc)
    }

    val queryParams: Map[String, String] = queryParamDefs.flatMap { p =>
      args.get(p.name).map(v => p.name -> stringify(v))
    }.toMap

    val headers: Map[String, String] = headerParamDefs.flatMap { p =>
      args.get(p.name).map(v => p.name -> stringify(v))
    }.toMap

    val queryString =
      if queryParams.isEmpty then ""
      else "?" + queryParams.map { case (k, v) =>
        s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
      }.mkString("&")

    val url = op.baseUrl.stripSuffix("/") + substitutedPath + queryString

    val body: Option[String] = args.get("body").map {
      case s: String => s
      case other     => mapper.writeValueAsString(other)
    }

    RequestDescriptor(
      method      = op.method.toString,
      url         = url,
      headers     = headers,
      queryParams = queryParams,
      body        = body,
      curl        = buildCurl(op.method, url, headers, body)
    )

  private def stringify(v: Any): String = v match
    case s: String => s
    case other     => other.toString

  private def buildCurl(method: HttpMethod, url: String, headers: Map[String, String], body: Option[String]): String =
    val headerParts: List[String] =
      headers.toList.flatMap { case (k, v) => List("-H", s"'$k: $v'") }

    val bodyParts: List[String] =
      body.toList.flatMap { b =>
        List("-H", "'Content-Type: application/json'", "-d", s"'${b.replace("'", "'\\''")}'")
      }

    (List("curl", "-X", method.toString, s"'$url'") ++ headerParts ++ bodyParts).mkString(" ")

object OperationTool:

  private val DestructivePrefix =
    "⚠️ DESTRUCTIVE — this operation modifies or deletes resources. Confirm intent before executing.\n\n"

  def buildDescription(op: OperationDef): String =
    val base = op.summary.orElse(op.description).filter(_.nonEmpty).getOrElse(op.toolName)
    if op.isDestructive then DestructivePrefix + base else base
