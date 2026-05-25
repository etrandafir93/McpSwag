package com.etrandafir.mcpswag.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.springframework.stereotype.Component

import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

@Component
class SchemaConverter(mapper: ObjectMapper):

  def toJsonSchema(schema: Schema[?], openApi: OpenAPI): Option[String] =
    Option(schema).map(s => mapper.writeValueAsString(convert(s, openApi, depth = 0)))

  private def convert(schema: Schema[?], openApi: OpenAPI, depth: Int): ListMap[String, Any] =
    val resolved = Option(schema.get$ref()) match
      case Some(ref) if depth < 4 => resolveRef(ref, openApi).getOrElse(schema)
      case _                      => schema

    val entries: List[(String, Any)] = List(
      Option(resolved.getType).map("type" -> _),
      Option(resolved.getFormat).map("format" -> _),
      Option(resolved.getDescription).map("description" -> _),
      Option(resolved.getEnum).map(e => "enum" -> e.asScala.toList),
      Option(resolved.getProperties).map { props =>
        val converted = props.asScala.foldLeft(ListMap.empty[String, Any]) { case (acc, (k, v)) =>
          acc.updated(k, convert(v, openApi, depth + 1))
        }
        "properties" -> converted
      },
      Option(resolved.getRequired).map(r => "required" -> r.asScala.toList),
      Option(resolved.getItems).map(items => "items" -> convert(items, openApi, depth + 1))
    ).flatten

    ListMap.from(entries)

  private def resolveRef(ref: String, openApi: OpenAPI): Option[Schema[?]] =
    val prefix = "#/components/schemas/"
    Option.when(ref.startsWith(prefix))(ref.stripPrefix(prefix)).flatMap { name =>
      for
        comps   <- Option(openApi.getComponents)
        schemas <- Option(comps.getSchemas)
        s       <- Option(schemas.get(name))
      yield s
    }
