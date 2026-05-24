package com.etrandafir.mcpswag

import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class McpSwagApp:

  @Bean
  def staticToolProvider(listSpecsTool: ListSpecsTool): ToolCallbackProvider =
    MethodToolCallbackProvider.builder()
      .toolObjects(listSpecsTool)
      .build()

object McpSwagApp:
  def main(args: Array[String]): Unit =
    SpringApplication.run(classOf[McpSwagApp], args*)
