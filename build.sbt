ThisBuild / scalaVersion := "3.6.4"
ThisBuild / organization := "com.etrandafir"
ThisBuild / version      := "0.1.0"

lazy val springBootVersion = "3.4.5"
lazy val springAiVersion   = "1.1.5"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "mcpswag",
    Compile / mainClass := Some("com.etrandafir.mcpswag.McpSwagApp"),
    executableScriptName := "mcpswag",
    run / fork := true,
    javacOptions ++= Seq("--release", "21"),

    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", "target/test-reports"),

    // ExternalConfigEnvVarTest sets the MCPSWAG_CONFIG system property to verify
    // the env-var indirection. JVM-wide state leaks across concurrent test classes,
    // so tests are sequenced.
    Test / parallelExecution := false,



    resolvers += "Spring Milestones" at "https://repo.spring.io/milestone",

    libraryDependencies ++= Seq(
      "org.springframework.boot" %  "spring-boot-starter-web"                  % springBootVersion,
      "org.springframework.ai"   %  "spring-ai-starter-mcp-server-webmvc"      % springAiVersion,
      "com.fasterxml.jackson.module" %% "jackson-module-scala"                 % "2.21.2",
      "io.swagger.parser.v3"     %  "swagger-parser"                           % "2.1.25",

      "org.springframework.boot" %  "spring-boot-starter-test"                 % springBootVersion % Test,
      "org.scalatest"            %% "scalatest"                                % "3.2.19"          % Test,
      "org.wiremock"             %  "wiremock"                                 % "3.10.0"          % Test
    )
  )
