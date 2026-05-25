package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.mcp.OperationTool
import com.etrandafir.mcpswag.spec.{HttpMethod, OperationDef}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks.*

class OperationToolSpec extends AnyFunSuite with Matchers:

  private def op(
    method: HttpMethod,
    summary: Option[String] = None,
    description: Option[String] = None,
    toolName: String = "spec__op"
  ): OperationDef =
    OperationDef(
      specName          = "spec",
      toolName          = toolName,
      method            = method,
      path              = "/x",
      summary           = summary,
      description       = description,
      parameters        = Nil,
      requestBodySchema = None,
      baseUrl           = "http://example.com",
      isDestructive     = method match
        case HttpMethod.DELETE | HttpMethod.PUT | HttpMethod.PATCH => true
        case _                                                     => false
    )

  private val destructivePrefix =
    "⚠️ DESTRUCTIVE — this operation modifies or deletes resources. Confirm intent before executing.\n\n"

  private val cases = Table[OperationDef, String](
    ("operation",                                            "expected description"),
    (op(HttpMethod.GET, summary = Some("Find pet")),          "Find pet"),
    (op(HttpMethod.GET, description = Some("Long desc")),     "Long desc"),
    (op(HttpMethod.GET, summary = Some("S"), description = Some("D")), "S"),                              // summary wins
    (op(HttpMethod.GET, toolName = "spec__only"),             "spec__only"),                              // no summary/desc → toolName
    (op(HttpMethod.DELETE, summary = Some("Delete pet")),     destructivePrefix + "Delete pet"),
    (op(HttpMethod.PUT,    summary = Some("Update pet")),     destructivePrefix + "Update pet"),
    (op(HttpMethod.PATCH,  summary = Some("Patch pet")),      destructivePrefix + "Patch pet"),
    (op(HttpMethod.DELETE, toolName = "spec__del"),           destructivePrefix + "spec__del")
  )

  test("buildDescription falls back through summary → description → toolName and prefixes destructive ops"):
    forAll(cases) { (operation, expected) =>
      OperationTool.buildDescription(operation) shouldBe expected
    }
