package com.etrandafir.mcpswag.spec

import com.etrandafir.mcpswag.mcp.SchemaConverter
import io.swagger.v3.oas.models.{OpenAPI, Operation, PathItem}
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.{ParseOptions, SwaggerParseResult}
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import scala.util.Try

@Component
class SpecParser(schemaConverter: SchemaConverter):

  private val logger = LoggerFactory.getLogger(classOf[SpecParser])

  def parse(source: SpecSource): Either[String, List[OperationDef]] =
    Try(doParse(source)).toEither.left.map(t =>
      Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
    )

  private def doParse(source: SpecSource): List[OperationDef] =
    val (specName, location) = source match
      case SpecSource.Url(n, u)  => (n, u)
      case SpecSource.File(n, p) => (n, p)

    val opts = new ParseOptions()
    opts.setResolveFully(true)

    val openApi = readOas3(location, opts)
      .orElse(readSwagger20(location))  // ServiceLoader for v2 converter not picked up in all environments
      .getOrElse {
        sys.error(s"Failed to parse spec '$specName' from $location: unable to read as OAS 3 or Swagger 2.0")
      }

    val baseUrl = resolveBaseUrl(openApi, source)

    Option(openApi.getPaths).map(_.asScala.toList).getOrElse(Nil).flatMap { case (path, item) =>
      operationsOf(item).map { case (method, op) =>
        toOperationDef(specName, baseUrl, openApi, path, method, op)
      }
    }

  private def readOas3(location: String, opts: ParseOptions): Option[OpenAPI] =
    val result =
      if location.startsWith("classpath:") then
        val resourcePath = location.stripPrefix("classpath:")
        val res = new ClassPathResource(resourcePath)
        if !res.exists() then sys.error(s"Classpath resource not found: $resourcePath")
        val content = new String(res.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        new OpenAPIV3Parser().readContents(content, null, opts)
      else
        new OpenAPIV3Parser().readLocation(location, null, opts)
    Option(result.getOpenAPI)

  // OpenAPIV3Parser's ServiceLoader-based v2 converter isn't picked up in all JVM environments;
  // call SwaggerParser + SwaggerConverter directly as a reliable OAS 2 fallback.
  private def readSwagger20(location: String): Option[OpenAPI] =
    val swaggerParser = new io.swagger.parser.SwaggerParser()
    val oas2Result =
      if location.startsWith("classpath:") then
        val resourcePath = location.stripPrefix("classpath:")
        val res = new ClassPathResource(resourcePath)
        if !res.exists() then return None
        val content = new String(res.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        swaggerParser.readWithInfo(content, true)
      else
        swaggerParser.readWithInfo(location, new java.util.ArrayList(), true)
    Option(oas2Result.getSwagger)
      .map(s => new io.swagger.v3.parser.converter.SwaggerConverter().convert(oas2Result))
      .flatMap(r => Option(r.getOpenAPI))

  private def toOperationDef(
    specName: String,
    baseUrl: String,
    openApi: OpenAPI,
    path: String,
    method: HttpMethod,
    op: Operation
  ): OperationDef =
    val operationId = nonEmpty(op.getOperationId).getOrElse(SpecParser.synthesizeOperationId(method, path))
    val toolName    = s"${specName}__$operationId"

    val params = Option(op.getParameters).map(_.asScala.toList).getOrElse(Nil)
      .flatMap(p => SpecParser.paramLocation(p.getIn).map((p, _)))
      .map { case (p, loc) =>
        ParamDef(
          name        = p.getName,
          in          = loc,
          required    = Option(p.getRequired).exists(_.booleanValue),
          description = Option(p.getDescription),
          schema      = Option(p.getSchema).flatMap(s => schemaConverter.toJsonSchema(s, openApi))
        )
      }

    val bodySchema = Option(op.getRequestBody)
      .flatMap(rb => Option(rb.getContent))
      .flatMap(_.asScala.headOption)
      .flatMap { case (_, mt) => Option(mt.getSchema) }
      .flatMap(s => schemaConverter.toJsonSchema(s, openApi))

    val isDestructive = method match
      case HttpMethod.DELETE | HttpMethod.PUT | HttpMethod.PATCH => true
      case _                                                     => false

    OperationDef(
      specName          = specName,
      toolName          = toolName,
      method            = method,
      path              = path,
      summary           = nonEmpty(op.getSummary),
      description       = nonEmpty(op.getDescription),
      parameters        = params,
      requestBodySchema = bodySchema,
      baseUrl           = baseUrl,
      isDestructive     = isDestructive
    )

  private def operationsOf(item: PathItem): List[(HttpMethod, Operation)] =
    List(
      HttpMethod.GET     -> item.getGet,
      HttpMethod.POST    -> item.getPost,
      HttpMethod.PUT     -> item.getPut,
      HttpMethod.PATCH   -> item.getPatch,
      HttpMethod.DELETE  -> item.getDelete,
      HttpMethod.HEAD    -> item.getHead,
      HttpMethod.OPTIONS -> item.getOptions,
      HttpMethod.TRACE   -> item.getTrace
    ).flatMap { case (m, op) => Option(op).map(m -> _) }

  private def resolveBaseUrl(openApi: OpenAPI, source: SpecSource): String =
    Option(openApi.getServers).map(_.asScala.toList).getOrElse(Nil).headOption
      .flatMap(s => Option(s.getUrl))
      .filterNot(u => u.isEmpty || u == "/") // OAS 3 default "/" is relative — fall back to source URL
      .getOrElse(fallbackBaseUrl(source))

  private def fallbackBaseUrl(source: SpecSource): String = source match
    case SpecSource.Url(_, u) =>
      Try(URI.create(u)).toOption
        .flatMap(uri => Option(uri.getAuthority).map(a => s"${uri.getScheme}://$a"))
        .getOrElse {
          logger.warn(s"Could not derive base URL from $u; falling back to http://localhost:8080")
          "http://localhost:8080"
        }
    case SpecSource.File(name, _) =>
      logger.warn(s"Spec '$name' has no servers[] entry; falling back to http://localhost:8080")
      "http://localhost:8080"

  private def nonEmpty(s: String): Option[String] = Option(s).filter(_.nonEmpty)

object SpecParser:

  def paramLocation(in: String): Option[ParamLocation] =
    Option(in).map(_.toLowerCase).collect {
      case "path"   => ParamLocation.Path
      case "query"  => ParamLocation.Query
      case "header" => ParamLocation.Header
    }

  def synthesizeOperationId(method: HttpMethod, path: String): String =
    val segments = path.split("/").filter(_.nonEmpty).map { s =>
      if s.startsWith("{") && s.endsWith("}") then s.drop(1).dropRight(1) else s
    }
    (method.toString.toLowerCase +: segments).mkString("__")
