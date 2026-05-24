package com.etrandafir.mcpswag.mcp

case class RequestDescriptor(
  method: String,
  url: String,
  headers: Map[String, String],
  queryParams: Map[String, String],
  body: Option[String],
  curl: String
)
