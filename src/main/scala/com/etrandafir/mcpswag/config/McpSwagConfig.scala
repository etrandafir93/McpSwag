package com.etrandafir.mcpswag.config

import com.etrandafir.mcpswag.spec.SpecSource
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.{Bean, Configuration}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

@Configuration
@EnableConfigurationProperties(Array(classOf[McpSwagProperties]))
class McpSwagConfig:

  private val logger = LoggerFactory.getLogger(classOf[McpSwagConfig])

  @Bean
  def specSources(props: McpSwagProperties): java.util.List[SpecSource] =
    val fromConfig = props.sources.asScala.toList.map(toSpecSource)
    val fromScan   = scanDir(props.scanDir)
    (fromConfig ++ fromScan).asJava

  private def scanDir(dir: String): List[SpecSource] =
    if dir == null then return Nil
    val path = Paths.get(dir)
    if !Files.isDirectory(path) then Nil
    else
      val found = Files.list(path).iterator().asScala.toList
        .filter(p => p.toString.matches(".*\\.(ya?ml|json)$"))
        .sortBy(_.getFileName.toString)
        .map: p =>
          val name = p.getFileName.toString.replaceAll("\\.(ya?ml|json)$", "")
          SpecSource.File(name, p.toAbsolutePath.toString)
      if found.nonEmpty then
        logger.info(s"[McpSwag] Scanned '$dir' — found ${found.size} spec file(s): ${found.map(_.name).mkString(", ")}")
      found

  private def toSpecSource(cfg: SourceConfig): SpecSource =
    val url  = Option(cfg.url).flatMap(_.toScala)
    val file = Option(cfg.file).flatMap(_.toScala)
    (url, file) match
      case (Some(u), None)    => SpecSource.Url(cfg.name, u)
      case (None,    Some(f)) => SpecSource.File(cfg.name, f)
      case (Some(_), Some(_)) =>
        throw IllegalStateException(s"Source '${cfg.name}': specify exactly one of 'url' or 'file', not both")
      case (None, None)       =>
        throw IllegalStateException(s"Source '${cfg.name}': must specify either 'url' or 'file'")
