package com.etrandafir.mcpswag.mcp

import com.etrandafir.mcpswag.spec.{HttpMethod, OperationDef, ParamDef, ParamLocation}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.{DefaultToolDefinition, ToolDefinition}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

class OperationTool(op: OperationDef, mapper: ObjectMapper) extends ToolCallback:

  private val definition: ToolDefinition =
    DefaultToolDefinition.builder()
      .name(op.toolName)
      .description(OperationTool.buildDescription(op))
      .inputSchema(buildInputSchema())
      .build()

  override def getToolDefinition: ToolDefinition = definition

  override def call(toolInput: String): String =
    val args: Map[String, Any] =
      if toolInput == null || toolInput.isBlank then Map.empty
      else
        val node = mapper.readTree(toolInput)
        if node.isObject then
          node.asInstanceOf[ObjectNode].properties.asScala
            .map(e => e.getKey -> nodeToScala(e.getValue))
            .toMap
        else Map.empty
    val descriptor = buildDescriptor(args)
    mapper.writeValueAsString(descriptor)

  private def nodeToScala(n: com.fasterxml.jackson.databind.JsonNode): Any =
    if n == null || n.isNull then null
    else if n.isTextual then n.asText
    else if n.isBoolean then n.asBoolean
    else if n.isIntegralNumber then n.asLong
    else if n.isFloatingPointNumber then n.asDouble
    else n // arrays/objects: keep as JsonNode, serialize back to JSON when needed

  private def buildInputSchema(): String =
    val props = new java.util.LinkedHashMap[String, Any]()
    val required = new java.util.ArrayList[String]()

    op.parameters.foreach { p =>
      props.put(p.name, paramSchemaNode(p))
      if p.required then required.add(p.name)
    }

    op.requestBodySchema.foreach { bodyJson =>
      val bodyNode = mapper.readTree(bodyJson)
      props.put("body", bodyNode)
      required.add("body")
    }

    val root = new java.util.LinkedHashMap[String, Any]()
    root.put("type", "object")
    root.put("properties", props)
    if !required.isEmpty then root.put("required", required)
    mapper.writeValueAsString(root)

  private def paramSchemaNode(p: ParamDef): Any =
    val base: java.util.Map[String, Any] = p.schema match
      case Some(json) =>
        val node = mapper.readTree(json)
        if node.isObject then
          val m = new java.util.LinkedHashMap[String, Any]()
          node.asInstanceOf[ObjectNode].properties.asScala.foreach(e => m.put(e.getKey, e.getValue))
          m
        else new java.util.LinkedHashMap[String, Any]()
      case None =>
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("type", "string")
        m
    p.description.foreach { d =>
      if !base.containsKey("description") then base.put("description", d)
    }
    base

  private def buildDescriptor(args: Map[String, Any]): RequestDescriptor =
    val pathParamDefs   = op.parameters.filter(_.in == ParamLocation.Path)
    val queryParamDefs  = op.parameters.filter(_.in == ParamLocation.Query)
    val headerParamDefs = op.parameters.filter(_.in == ParamLocation.Header)

    val substitutedPath = pathParamDefs.foldLeft(op.path) { (acc, p) =>
      args.get(p.name).map(v => acc.replace(s"{${p.name}}", URLEncoder.encode(stringify(v), StandardCharsets.UTF_8))).getOrElse(acc)
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
      case s: String                            => s
      case other if other != null               => mapper.writeValueAsString(other)
      case _                                    => ""
    }

    val curl = buildCurl(op.method, url, headers, body)

    RequestDescriptor(
      method = op.method.toString,
      url = url,
      headers = headers,
      queryParams = queryParams,
      body = body,
      curl = curl
    )

  private def stringify(v: Any): String = v match
    case null  => ""
    case s: String => s
    case other => other.toString

  private def buildCurl(method: HttpMethod, url: String, headers: Map[String, String], body: Option[String]): String =
    val parts = scala.collection.mutable.ArrayBuffer[String]("curl", "-X", method.toString, s"'$url'")
    headers.foreach { case (k, v) => parts += "-H"; parts += s"'$k: $v'" }
    body.foreach { b =>
      parts += "-H"; parts += "'Content-Type: application/json'"
      parts += "-d"; parts += s"'${b.replace("'", "'\\''")}'"
    }
    parts.mkString(" ")

object OperationTool:

  private val DestructivePrefix =
    "⚠️ DESTRUCTIVE — this operation modifies or deletes resources. Confirm intent before executing.\n\n"

  def buildDescription(op: OperationDef): String =
    val base = op.summary.orElse(op.description).filter(_.nonEmpty).getOrElse(op.toolName)
    if op.isDestructive then DestructivePrefix + base else base
