package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.config.{HttpConfig, McpSwagProperties}
import com.etrandafir.mcpswag.mcp.{HttpExecutor, OperationTool}
import com.etrandafir.mcpswag.spec.{HttpMethod, OperationDef, ParamDef, ParamLocation}
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

class HttpExecutionTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val mapper: ObjectMapper =
    JsonMapper.builder().addModule(DefaultScalaModule).build()

  private var wireMock: WireMockServer = scala.compiletime.uninitialized
  private var baseUrl: String          = scala.compiletime.uninitialized
  private var executor: HttpExecutor   = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())
    wireMock.start()
    baseUrl = s"http://localhost:${wireMock.port()}"
    executor = HttpExecutor(McpSwagProperties(http = HttpConfig(timeoutSeconds = 2, maxBodyBytes = 64)))

  override def afterAll(): Unit =
    if wireMock != null then wireMock.stop()

  private def getPetByIdOp = OperationDef(
    specName = "petstore",
    toolName = "petstore__getPetById",
    method = HttpMethod.GET,
    path = "/pet/{petId}",
    summary = Some("Find pet by ID"),
    description = None,
    parameters = List(ParamDef("petId", ParamLocation.Path, required = true, None, None)),
    requestBodySchema = None,
    baseUrl = baseUrl,
    isDestructive = false
  )

  private def deletePetOp = OperationDef(
    specName = "petstore",
    toolName = "petstore__deletePet",
    method = HttpMethod.DELETE,
    path = "/pet/{petId}",
    summary = Some("Delete a pet"),
    description = None,
    parameters = List(ParamDef("petId", ParamLocation.Path, required = true, None, None)),
    requestBodySchema = None,
    baseUrl = baseUrl,
    isDestructive = true
  )

  test("GET 200 returns body verbatim and status 200"):
    wireMock.resetAll()
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/pet/1"))
      .willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("""{"id":1,"name":"doggie"}""")))

    val tool = OperationTool(getPetByIdOp, mapper, executor)
    val node = mapper.readTree(tool.call("""{"petId": 1}"""))

    node.get("status").asInt shouldBe 200
    node.get("body").asText shouldBe """{"id":1,"name":"doggie"}"""
    node.get("error").isNull shouldBe true
    node.get("truncated").asBoolean shouldBe false
    node.get("request").get("url").asText shouldBe s"$baseUrl/pet/1"

  test("404 is returned as a ToolResponse, not an exception"):
    wireMock.resetAll()
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/pet/999"))
      .willReturn(aResponse().withStatus(404)
        .withHeader("Content-Type", "application/json")
        .withBody("""{"message":"not found"}""")))

    val tool = OperationTool(getPetByIdOp, mapper, executor)
    val node = mapper.readTree(tool.call("""{"petId": 999}"""))

    node.get("status").asInt shouldBe 404
    node.get("body").asText shouldBe """{"message":"not found"}"""
    node.get("error").isNull shouldBe true

  test("destructive DELETE without confirm is refused and makes no HTTP call"):
    wireMock.resetAll()
    val tool = OperationTool(deletePetOp, mapper, executor)
    val node = mapper.readTree(tool.call("""{"petId": 1}"""))

    node.get("status").asInt shouldBe 0
    node.get("error").asText should include ("DESTRUCTIVE")
    wireMock.findAll(anyRequestedFor(anyUrl())).size shouldBe 0

  test("destructive DELETE with confirm=true actually fires the request"):
    wireMock.resetAll()
    wireMock.stubFor(WireMock.delete(urlPathEqualTo("/pet/7"))
      .willReturn(aResponse().withStatus(204)))

    val tool = OperationTool(deletePetOp, mapper, executor)
    val node = mapper.readTree(tool.call("""{"petId": 7, "confirm": true}"""))

    node.get("status").asInt shouldBe 204
    node.get("error").isNull shouldBe true
    wireMock.verify(deleteRequestedFor(urlPathEqualTo("/pet/7")))

  test("response body larger than maxBodyBytes is truncated"):
    wireMock.resetAll()
    val big = "x" * 200
    wireMock.stubFor(WireMock.get(urlPathEqualTo("/pet/2"))
      .willReturn(aResponse().withStatus(200).withBody(big)))

    val tool = OperationTool(getPetByIdOp, mapper, executor)
    val node = mapper.readTree(tool.call("""{"petId": 2}"""))

    node.get("truncated").asBoolean shouldBe true
    node.get("body").asText.length shouldBe 64

  test("destructive op has confirm in input schema required list"):
    val tool = OperationTool(deletePetOp, mapper, executor)
    val schema = mapper.readTree(tool.getToolDefinition.inputSchema)
    schema.get("properties").has("confirm") shouldBe true
    val required = (0 until schema.get("required").size).map(schema.get("required").get(_).asText)
    required should contain ("confirm")

  private def getOrderByIdOp = OperationDef(
    specName = "petstore",
    toolName = "petstore__getOrderById",
    method = HttpMethod.GET,
    path = "/store/order/{orderId}",
    summary = Some("Find purchase order by ID"),
    description = None,
    parameters = List(ParamDef(
      "orderId",
      ParamLocation.Path,
      required = true,
      schema = Some("""{"type":"integer","format":"int64"}"""),
      description = None
    )),
    requestBodySchema = None,
    baseUrl = baseUrl,
    isDestructive = false
  )

  test("int64 path param is exposed as string in the input schema to avoid JSON number precision loss"):
    val tool = OperationTool(getOrderByIdOp, mapper, executor)
    val schema = mapper.readTree(tool.getToolDefinition.inputSchema)
    val orderId = schema.get("properties").get("orderId")
    orderId.get("type").asText shouldBe "string"
    orderId.has("format") shouldBe false
    orderId.get("pattern").asText shouldBe "^-?\\d+$"
    orderId.get("description").asText should include ("int64")

  test("int64 path param sent as string preserves all digits in the URL"):
    wireMock.resetAll()
    val bigId = "8762099875811304519" // > 2^53, would round to ...4000 as a JS double
    wireMock.stubFor(WireMock.get(urlPathEqualTo(s"/store/order/$bigId"))
      .willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(s"""{"id":$bigId,"status":"placed"}""")))

    val tool = OperationTool(getOrderByIdOp, mapper, executor)
    val node = mapper.readTree(tool.call(s"""{"orderId": "$bigId"}"""))

    node.get("status").asInt shouldBe 200
    node.get("request").get("url").asText shouldBe s"$baseUrl/store/order/$bigId"
    wireMock.verify(getRequestedFor(urlPathEqualTo(s"/store/order/$bigId")))
