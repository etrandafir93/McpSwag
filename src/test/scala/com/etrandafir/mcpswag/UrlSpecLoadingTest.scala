package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.config.{HttpConfig, McpSwagProperties}
import com.etrandafir.mcpswag.mcp.{HttpExecutor, OperationTool, SchemaConverter}
import com.etrandafir.mcpswag.spec.{SpecParser, SpecSource}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class UrlSpecLoadingTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val mapper: ObjectMapper =
    JsonMapper.builder().addModule(DefaultScalaModule).build()

  private var wireMock: WireMockServer = scala.compiletime.uninitialized
  private var baseUrl: String          = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())
    wireMock.start()
    baseUrl = s"http://localhost:${wireMock.port()}"

    // The OpenAPI spec itself, served from the same WireMock instance whose
    // base URL is referenced in its `servers[]` block. Two GET operations.
    val spec =
      s"""openapi: 3.0.3
         |info:
         |  title: Mini API
         |  version: 1.0.0
         |servers:
         |  - url: $baseUrl
         |paths:
         |  /hello:
         |    get:
         |      operationId: getHello
         |      summary: Says hello
         |      responses:
         |        '200':
         |          description: ok
         |  /status:
         |    get:
         |      operationId: getStatus
         |      summary: Returns service status
         |      responses:
         |        '200':
         |          description: ok
         |""".stripMargin

    wireMock.stubFor(WireMock.get(urlPathEqualTo("/spec.yml"))
      .willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/yaml")
        .withBody(spec)))

    wireMock.stubFor(WireMock.get(urlPathEqualTo("/hello"))
      .willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("""{"message":"hello"}""")))

    wireMock.stubFor(WireMock.get(urlPathEqualTo("/status"))
      .willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("""{"status":"ok"}""")))

  override def afterAll(): Unit =
    if wireMock != null then wireMock.stop()

  private val parser   = SpecParser(SchemaConverter(mapper))
  private val executor = HttpExecutor(McpSwagProperties(http = HttpConfig(timeoutSeconds = 5, maxBodyBytes = 64 * 1024)))

  test("loads an OpenAPI spec from an HTTP URL and registers one tool per operation"):
    val source = SpecSource.Url("mini", s"$baseUrl/spec.yml")

    val operations = parser.parse(source).fold(
      err  => fail(s"spec failed to load: $err"),
      ops  => ops
    )

    operations.map(_.toolName).sorted shouldBe List("mini__getHello", "mini__getStatus")
    all (operations.map(_.baseUrl)) shouldBe baseUrl
    wireMock.verify(getRequestedFor(urlPathEqualTo("/spec.yml")))

  test("tools generated from a URL spec actually hit the upstream endpoints"):
    val source = SpecSource.Url("mini", s"$baseUrl/spec.yml")
    val operations = parser.parse(source).getOrElse(fail("spec failed to load"))

    val tools = operations.map(op => op.toolName -> OperationTool(op, mapper, executor)).toMap

    val helloResp = mapper.readTree(tools("mini__getHello").call("{}"))
    helloResp.get("status").asInt shouldBe 200
    helloResp.get("body").asText shouldBe """{"message":"hello"}"""
    helloResp.get("request").get("url").asText shouldBe s"$baseUrl/hello"

    val statusResp = mapper.readTree(tools("mini__getStatus").call("{}"))
    statusResp.get("status").asInt shouldBe 200
    statusResp.get("body").asText shouldBe """{"status":"ok"}"""

    wireMock.verify(getRequestedFor(urlPathEqualTo("/hello")))
    wireMock.verify(getRequestedFor(urlPathEqualTo("/status")))
