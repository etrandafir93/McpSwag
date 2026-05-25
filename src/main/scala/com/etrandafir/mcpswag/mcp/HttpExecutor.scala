package com.etrandafir.mcpswag.mcp

import com.etrandafir.mcpswag.config.{HttpConfig, McpSwagProperties}
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.jdk.CollectionConverters.*

@Component
class HttpExecutor(props: McpSwagProperties):

  private val logger = LoggerFactory.getLogger(classOf[HttpExecutor])

  private val http: HttpConfig = Option(props.http).getOrElse(HttpConfig())
  private val timeoutSeconds: Int = http.timeoutSeconds
  private val maxBodyBytes:   Int = http.maxBodyBytes

  private val client: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
    .build()

  def execute(descriptor: RequestDescriptor): ToolResponse =
    try
      val builder = HttpRequest.newBuilder()
        .uri(URI.create(descriptor.url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
      descriptor.headers.foreach { case (k, v) => builder.header(k, v) }
      val publisher = descriptor.body match
        case Some(b) =>
          if !descriptor.headers.keys.exists(_.equalsIgnoreCase("Content-Type")) then
            builder.header("Content-Type", "application/json")
          HttpRequest.BodyPublishers.ofString(b, StandardCharsets.UTF_8)
        case None    => HttpRequest.BodyPublishers.noBody()
      builder.method(descriptor.method, publisher)

      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
      val bytes = response.body
      val truncated = bytes.length > maxBodyBytes
      val effective = if truncated then bytes.take(maxBodyBytes) else bytes
      val headers = response.headers.map.asScala.map { case (k, vs) =>
        k -> vs.asScala.mkString(",")
      }.toMap
      ToolResponse(
        status = response.statusCode,
        headers = headers,
        body = new String(effective, StandardCharsets.UTF_8),
        truncated = truncated,
        error = None,
        request = descriptor
      )
    catch
      case _: HttpTimeoutException =>
        ToolResponse(0, Map.empty, "", truncated = false,
          Some(s"Request timed out after ${timeoutSeconds}s"), descriptor)
      case t: Throwable =>
        logger.warn(s"[McpSwag] HTTP execution failed for ${descriptor.method} ${descriptor.url}: ${t.getMessage}")
        ToolResponse(0, Map.empty, "", truncated = false,
          Some(s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}"), descriptor)
