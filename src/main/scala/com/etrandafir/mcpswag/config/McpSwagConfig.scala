package com.etrandafir.mcpswag.config

import com.etrandafir.mcpswag.spec.SpecSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.{Bean, Configuration}

import scala.jdk.CollectionConverters.*

@Configuration
@EnableConfigurationProperties(Array(classOf[McpSwagProperties]))
class McpSwagConfig:

  @Bean
  def specSources(props: McpSwagProperties): java.util.List[SpecSource] =
    props.sources.asScala.toList.map(toSpecSource).asJava

  private def toSpecSource(cfg: SourceConfig): SpecSource =
    (Option(cfg.url), Option(cfg.file)) match
      case (Some(u), None)    => SpecSource.Url(cfg.name, u)
      case (None,    Some(f)) => SpecSource.File(cfg.name, f)
      case (Some(_), Some(_)) =>
        throw IllegalStateException(s"Source '${cfg.name}': specify exactly one of 'url' or 'file', not both")
      case (None, None)       =>
        throw IllegalStateException(s"Source '${cfg.name}': must specify either 'url' or 'file'")
