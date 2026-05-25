package com.etrandafir.mcpswag

import com.etrandafir.mcpswag.spec.SpecRegistry
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestContextManager

import java.nio.file.{Files, Path}
import scala.annotation.meta.field

// `properties` adds a unique marker so this test's ApplicationContext is not
// shared with other @SpringBootTest classes (whose contexts boot without
// MCPSWAG_CONFIG set and would mask the behaviour we want to verify).
@SpringBootTest(properties = Array("mcpswag.test=external-config-env-var"))
class ExternalConfigEnvVarTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  @(Autowired @field)
  private var specRegistry: SpecRegistry = scala.compiletime.uninitialized

  private val testContextManager = TestContextManager(classOf[ExternalConfigEnvVarTest])

  private var tmpConfig: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    tmpConfig = Files.createTempFile("mcpswag-external-", ".yml")
    Files.writeString(tmpConfig,
      """swagger-mcp:
        |  sources:
        |    - name: external-spec
        |      file: classpath:openapi/petstore.yml
        |""".stripMargin
    )
    // application.yml has `spring.config.import: "optional:file:${MCPSWAG_CONFIG:}"`.
    // Setting the system property here before Spring boots makes the placeholder
    // resolve to the temp file, mimicking how a Docker user would set the env var.
    System.setProperty("MCPSWAG_CONFIG", tmpConfig.toAbsolutePath.toString)
    testContextManager.prepareTestInstance(this)

  override def afterAll(): Unit =
    System.clearProperty("MCPSWAG_CONFIG")
    Files.deleteIfExists(tmpConfig)

  test("MCPSWAG_CONFIG points the app at an external YAML and its swagger-mcp.sources are loaded"):
    val names = specRegistry.entries.keys.toSet
    names should contain ("external-spec")

  test("external config overrides — not appends — the bundled default sources"):
    // application.yml's bundled list has `petstore`; the external file declares
    // only `external-spec`. Spring's later property sources win on conflict,
    // so `petstore` should no longer be present once MCPSWAG_CONFIG is set.
    specRegistry.entries.keys should not contain "petstore"
