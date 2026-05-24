package com.etrandafir.mcpswag

import org.springframework.ai.tool.annotation.{Tool, ToolParam}
import org.springframework.stereotype.Component

@Component
class HelloWorldTool:

  @Tool(description = "Say hello to a given name. Returns a friendly greeting.")
  def hello(
    @ToolParam(description = "The name to greet") name: String
  ): String =
    s"Hello, $name! Welcome to McpSwag."
