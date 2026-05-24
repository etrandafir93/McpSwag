package com.etrandafir.mcpswag.spec

enum HttpMethod:
  case GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE

enum ParamLocation:
  case Path, Query, Header

case class ParamDef(
  name: String,
  in: ParamLocation,
  required: Boolean,
  description: Option[String],
  schema: Option[String]
)

case class OperationDef(
  specName: String,
  toolName: String,
  method: HttpMethod,
  path: String,
  summary: Option[String],
  description: Option[String],
  parameters: List[ParamDef],
  requestBodySchema: Option[String],
  baseUrl: String,
  isDestructive: Boolean
)
