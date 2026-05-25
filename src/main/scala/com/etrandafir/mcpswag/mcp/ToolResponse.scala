package com.etrandafir.mcpswag.mcp

case class ToolResponse(
  status: Int,
  headers: Map[String, String],
  body: String,
  truncated: Boolean,
  error: Option[String],
  request: RequestDescriptor
)
