package com.etrandafir.mcpswag.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.springframework.stereotype.Component

import scala.jdk.CollectionConverters.*

@Component
class SchemaConverter(mapper: ObjectMapper):

  def toJsonSchema(schema: Schema[?], openApi: OpenAPI): Option[String] =
    Option(schema).map(s => mapper.writeValueAsString(convert(s, openApi, depth = 0)))

  private def convert(schema: Schema[?], openApi: OpenAPI, depth: Int): java.util.Map[String, Any] =
    val result = new java.util.LinkedHashMap[String, Any]()

    val resolved = Option(schema.get$ref()) match
      case Some(ref) if depth < 4 => resolveRef(ref, openApi).getOrElse(schema)
      case _                      => schema

    Option(resolved.getType).foreach(t => result.put("type", t))
    Option(resolved.getFormat).foreach(f => result.put("format", f))
    Option(resolved.getDescription).foreach(d => result.put("description", d))
    Option(resolved.getEnum).foreach(e => result.put("enum", e))

    Option(resolved.getProperties).foreach { props =>
      val out = new java.util.LinkedHashMap[String, Any]()
      props.asScala.foreach { case (k, v) =>
        out.put(k, convert(v, openApi, depth + 1))
      }
      result.put("properties", out)
    }

    Option(resolved.getRequired).foreach(r => result.put("required", r))

    Option(resolved.getItems).foreach { items =>
      result.put("items", convert(items, openApi, depth + 1))
    }

    result

  private def resolveRef(ref: String, openApi: OpenAPI): Option[Schema[?]] =
    val prefix = "#/components/schemas/"
    if !ref.startsWith(prefix) then None
    else
      val name = ref.stripPrefix(prefix)
      for
        comps   <- Option(openApi.getComponents)
        schemas <- Option(comps.getSchemas)
        s       <- Option(schemas.get(name))
      yield s
