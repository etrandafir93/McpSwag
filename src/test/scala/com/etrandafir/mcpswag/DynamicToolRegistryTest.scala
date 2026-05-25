package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.mcp.DynamicToolRegistry
import com.etrandafir.mcpswag.spec.SpecRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestContextManager

import scala.annotation.meta.field

@SpringBootTest
class DynamicToolRegistryTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  @(Autowired @field)
  private var toolRegistry: DynamicToolRegistry = scala.compiletime.uninitialized

  @(Autowired @field)
  private var specRegistry: SpecRegistry = scala.compiletime.uninitialized

  @(Autowired @field)
  private var mapper: ObjectMapper = scala.compiletime.uninitialized

  private val testContextManager = TestContextManager(classOf[DynamicToolRegistryTest])

  override def beforeAll(): Unit =
    testContextManager.prepareTestInstance(this)

  test("bootstraps petstore spec and exposes 19 dynamic tools"):
    val callbacks = toolRegistry.getToolCallbacks
    callbacks.length shouldBe 19

  test("every tool name is namespaced with the petstore spec prefix"):
    val names = toolRegistry.getToolCallbacks.map(_.getToolDefinition.name).toList
    all (names) should startWith ("petstore__")
    names.distinct.length shouldBe names.length

  test("petstore__getPetById is present with petId in its input schema"):
    val tool = toolRegistry.getToolCallbacks
      .find(_.getToolDefinition.name == "petstore__getPetById")
      .getOrElse(fail("petstore__getPetById not registered"))
    
    val schemaNode = mapper.readTree(tool.getToolDefinition.inputSchema)
    schemaNode.get("type").asText shouldBe "object"
    schemaNode.get("properties").has("petId") shouldBe true
    
    val required = (0 until schemaNode.get("required").size).map(schemaNode.get("required").get(_).asText)
    required should contain ("petId")

  test("destructive DELETE tool has the warning prefix in its description"):
    val tool = toolRegistry.getToolCallbacks
      .find(_.getToolDefinition.name == "petstore__deletePet")
      .getOrElse(fail("petstore__deletePet not registered"))
    tool.getToolDefinition.description should startWith ("⚠️ DESTRUCTIVE")

  test("non-destructive GET tool has no destructive prefix"):
    val tool = toolRegistry.getToolCallbacks
      .find(_.getToolDefinition.name == "petstore__getPetById")
      .getOrElse(fail("petstore__getPetById not registered"))
    tool.getToolDefinition.description should not startWith "⚠️"

  test("destructive op without confirm refuses execution and embeds the descriptor"):
    val tool = toolRegistry.getToolCallbacks
      .find(_.getToolDefinition.name == "petstore__deletePet")
      .getOrElse(fail("petstore__deletePet not registered"))

    val response = tool.call("""{"petId": 42}""")
    val node = mapper.readTree(response)

    node.get("status").asInt shouldBe 0
    node.get("error").asText should include ("DESTRUCTIVE")
    val request = node.get("request")
    request.get("method").asText shouldBe "DELETE"
    request.get("url").asText should endWith ("/pet/42")
    request.get("curl").asText should startWith ("curl -X DELETE")

  test("removing a spec clears all its tools"):
    val original = specRegistry.entries("petstore").source
    toolRegistry.getToolCallbacks.length shouldBe 19
    toolRegistry.removeSpec("petstore")
    try
      toolRegistry.getToolCallbacks.length shouldBe 0
    finally
      toolRegistry.loadSpec(original) // restore for any subsequent tests
