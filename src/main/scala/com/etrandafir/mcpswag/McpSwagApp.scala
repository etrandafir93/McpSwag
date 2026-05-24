package com.etrandafir.mcpswag

import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class McpSwagApp:

  @Bean
  def helloWorldToolProvider(helloWorldTool: HelloWorldTool): ToolCallbackProvider =
    MethodToolCallbackProvider.builder()
      .toolObjects(helloWorldTool)
      .build()

object McpSwagApp:
  def main(args: Array[String]): Unit =
    SpringApplication.run(classOf[McpSwagApp], args*)
