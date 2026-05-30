package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.mcp.SchemaConverter
import com.etrandafir.mcpswag.spec.{SpecParser, SpecRegistry, SpecSource, SpecStatus}
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SpecRegistrySpec extends AnyFunSuite with Matchers:

  private val mapper   = JsonMapper.builder().addModule(DefaultScalaModule).build()
  private val parser   = SpecParser(SchemaConverter(mapper))
  private val registry = SpecRegistry(parser)

  test("loading a nonexistent file stores SpecStatus.Failed with a non-empty message"):
    val source = SpecSource.File("bad-spec", "/nonexistent/does-not-exist.yml")
    val entry  = registry.load(source)
    entry.status match
      case SpecStatus.Failed(msg) => msg should not be empty
      case other                  => fail(s"Expected SpecStatus.Failed, got $other")

  test("a failed spec stores no operations"):
    val source = SpecSource.File("bad-spec-2", "/nonexistent/also-missing.yml")
    val entry  = registry.load(source)
    entry.operations shouldBe Nil

  test("allOperations excludes operations from failed specs"):
    val source = SpecSource.File("bad-spec-3", "/nonexistent/still-missing.yml")
    registry.load(source)
    registry.allOperations.map(_.specName) should not contain "bad-spec-3"
