package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.spec.SpecSource
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

import scala.jdk.CollectionConverters.*

@Component
class ListSpecsTool(specSourcesJava: java.util.List[SpecSource]):

  private val sources: List[SpecSource] = specSourcesJava.asScala.toList

  @Tool(description =
    "List all OpenAPI/Swagger specs currently registered with McpSwag. " +
    "Returns each spec's name and the location it was loaded from (file path or URL)."
  )
  def listSpecs(): String =
    if sources.isEmpty then "No specs registered."
    else
      val lines = sources.map {
        case SpecSource.Url(name, url)   => s"- $name (url:  $url)"
        case SpecSource.File(name, path) => s"- $name (file: $path)"
      }
      s"${sources.size} spec(s) registered:\n${lines.mkString("\n")}"
