package com.etrandafir.mcpswag.config

import org.springframework.boot.context.properties.ConfigurationProperties

import java.util as ju

@ConfigurationProperties("swagger-mcp")
case class McpSwagProperties(sources: ju.List[SourceConfig] = ju.List.of())

case class SourceConfig(
  name: String,
  url:  String = null,
  file: String = null
)
