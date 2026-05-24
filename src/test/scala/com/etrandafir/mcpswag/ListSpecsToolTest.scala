package com.etrandafir.mcpswag

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestContextManager

import scala.annotation.meta.field

@SpringBootTest
class ListSpecsToolTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  @(Autowired @field)
  private var listSpecsTool: ListSpecsTool = scala.compiletime.uninitialized

  private val testContextManager = TestContextManager(classOf[ListSpecsToolTest])

  override def beforeAll(): Unit =
    testContextManager.prepareTestInstance(this)

  test("listSpecs reports the petstore spec from application.yml"):
    val response = listSpecsTool.listSpecs()
    response should include ("1 spec(s) registered")
    response should include ("petstore")
    response should include ("classpath:openapi/petstore.yml")
