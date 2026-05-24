package com.etrandafir.mcpswag

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestContextManager

import scala.annotation.meta.field

@SpringBootTest
class HelloWorldToolTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  @(Autowired @field)
  private var helloWorldTool: HelloWorldTool = scala.compiletime.uninitialized

  private val testContextManager = TestContextManager(classOf[HelloWorldToolTest])

  override def beforeAll(): Unit =
    testContextManager.prepareTestInstance(this)

  test("hello returns a greeting containing the provided name"):
    val greeting = helloWorldTool.hello("Emanuel")
    greeting should startWith ("Hello, Emanuel")
    greeting should include ("Welcome to McpSwag")
