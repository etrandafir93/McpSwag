package com.etrandafir.mcpswag.spec

import org.slf4j.LoggerFactory
import org.springframework.boot.{ApplicationArguments, ApplicationRunner}
import org.springframework.stereotype.Component

import scala.jdk.CollectionConverters.*

@Component
class SpecLoader(specSources: java.util.List[SpecSource]) extends ApplicationRunner:

  private val logger = LoggerFactory.getLogger(classOf[SpecLoader])

  override def run(args: ApplicationArguments): Unit =
    val sources = specSources.asScala.toList
    logger.info(s"[McpSwag] Found ${sources.size} configured spec source(s)")
    sources.foreach {
      case SpecSource.Url(name, url)   =>
        logger.info(s"[McpSwag] Loading spec '$name' from URL: $url")
      case SpecSource.File(name, path) =>
        logger.info(s"[McpSwag] Loading spec '$name' from FILE: $path")
    }
