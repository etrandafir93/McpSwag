package com.etrandafir.mcpswag.config

import org.springframework.boot.context.properties.ConfigurationProperties

import java.util as ju

@ConfigurationProperties("swagger-mcp")
case class McpSwagProperties(
  sources: ju.List[SourceConfig] = ju.List.of(),
  http:    HttpConfig            = HttpConfig(),
  scanDir: String                = "./specs"
)

case class SourceConfig(
  name: String,
  url:  ju.Optional[String] = ju.Optional.empty(),
  file: ju.Optional[String] = ju.Optional.empty()
)

case class HttpConfig(
  timeoutSeconds: Int = 30,
  maxBodyBytes:   Int = 256 * 1024
)
