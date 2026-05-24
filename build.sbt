ThisBuild / scalaVersion := "3.6.4"
ThisBuild / organization := "com.etrandafir"
ThisBuild / version      := "0.1.0"

lazy val springBootVersion = "3.4.5"
lazy val springAiVersion   = "1.1.5"

lazy val root = (project in file("."))
  .settings(
    name := "mcpswag",
    Compile / mainClass := Some("com.etrandafir.mcpswag.McpSwagApp"),
    run / fork := true,
    javacOptions ++= Seq("--release", "21"),

    resolvers += "Spring Milestones" at "https://repo.spring.io/milestone",

    libraryDependencies ++= Seq(
      "org.springframework.boot" %  "spring-boot-starter-web"                  % springBootVersion,
      "org.springframework.ai"   %  "spring-ai-starter-mcp-server-webmvc"      % springAiVersion,
      "com.fasterxml.jackson.module" %% "jackson-module-scala"                 % "2.18.3"
    )
  )
