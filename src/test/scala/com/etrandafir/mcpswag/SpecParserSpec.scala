package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.spec.{HttpMethod, ParamLocation, SpecParser}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks.*

class SpecParserSpec extends AnyFunSuite with Matchers:

  private val synthesizeCases = Table(
    ("method",          "path",                  "expected"),
    (HttpMethod.GET,    "/pet/{petId}",          "get__pet__petId"),
    (HttpMethod.POST,   "/pet",                  "post__pet"),
    (HttpMethod.DELETE, "/store/order/{orderId}","delete__store__order__orderId"),
    (HttpMethod.PUT,    "/user/{username}",      "put__user__username"),
    (HttpMethod.GET,    "/",                     "get"),
    (HttpMethod.GET,    "/pet/{petId}/uploadImage", "get__pet__petId__uploadImage")
  )

  test("synthesizeOperationId derives a stable id from method + path"):
    forAll(synthesizeCases) { (method, path, expected) =>
      SpecParser.synthesizeOperationId(method, path) shouldBe expected
    }

  private val paramLocationCases = Table(
    ("input",   "expected"),
    ("path",    Some(ParamLocation.Path)),
    ("PATH",    Some(ParamLocation.Path)),
    ("Path",    Some(ParamLocation.Path)),
    ("query",   Some(ParamLocation.Query)),
    ("QUERY",   Some(ParamLocation.Query)),
    ("header",  Some(ParamLocation.Header)),
    ("cookie",  None),
    ("",        None),
    (null,      None)
  )

  test("paramLocation accepts the three supported `in` values case-insensitively, rejects others and null"):
    forAll(paramLocationCases) { (input, expected) =>
      SpecParser.paramLocation(input) shouldBe expected
    }
