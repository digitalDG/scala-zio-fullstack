val scala3Version = "3.3.4"

val V = new {
  val zio            = "2.1.14"
  val zioConfig      = "4.0.2"
  val zioLogging     = "2.4.0"
  val zioJson        = "0.7.3"
  val tapir          = "1.11.10"
  val apispec        = "0.11.3"
  val quill          = "4.8.4"
  val postgres       = "42.7.4"
  val flyway         = "13.12.1"
  val bcrypt         = "0.10.2"
  val logback        = "1.5.12"
  val testcontainers = "0.41.4"
}

addCommandAlias("openapi", "runMain com.example.todo.GenerateOpenApi")

/** Reads `KEY=value` lines from the repo's git-ignored `.env`, so `sbt run` gets the same secrets
  * as Docker Compose without them ever being written into the build or config files.
  */
def dotEnv(file: File): Map[String, String] =
  if (!file.exists) Map.empty
  else
    IO.readLines(file)
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#") && line.contains("="))
      .map { line =>
        val (key, value) = line.splitAt(line.indexOf('='))
        key.trim -> value.drop(1).trim
      }
      .toMap
addCommandAlias("check", "; scalafmtCheckAll; scalafmtSbtCheck; test")

lazy val root = project
  .in(file("."))
  .settings(
    name         := "todo-backend",
    version      := "0.1.0",
    scalaVersion := scala3Version,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
      // A non-exhaustive match is a bug waiting to happen, so make it a compile error, not a warning.
      "-Wconf:id=E029:error"
    ),
    libraryDependencies ++= Seq(
      // Effects & runtime
      "dev.zio" %% "zio"                 % V.zio,
      "dev.zio" %% "zio-json"            % V.zioJson,
      "dev.zio" %% "zio-config"          % V.zioConfig,
      "dev.zio" %% "zio-config-magnolia" % V.zioConfig,
      "dev.zio" %% "zio-logging-slf4j2"  % V.zioLogging,
      // HTTP
      "com.softwaremill.sttp.tapir"   %% "tapir-zio-http-server"   % V.tapir,
      "com.softwaremill.sttp.tapir"   %% "tapir-json-zio"          % V.tapir,
      "com.softwaremill.sttp.tapir"   %% "tapir-swagger-ui-bundle" % V.tapir,
      "com.softwaremill.sttp.tapir"   %% "tapir-openapi-docs"      % V.tapir,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml"      % V.apispec,
      // Persistence
      "io.getquill"   %% "quill-jdbc-zio"             % V.quill,
      "org.postgresql" % "postgresql"                 % V.postgres,
      "org.flywaydb"   % "flyway-core"                % V.flyway,
      "org.flywaydb"   % "flyway-database-postgresql" % V.flyway,
      // Security
      "at.favre.lib" % "bcrypt" % V.bcrypt,
      // Logging
      "ch.qos.logback" % "logback-classic" % V.logback,
      // Tests
      "dev.zio"                     %% "zio-test"                        % V.zio            % Test,
      "dev.zio"                     %% "zio-test-sbt"                    % V.zio            % Test,
      "dev.zio"                     %% "zio-test-magnolia"               % V.zio            % Test,
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub-server"          % V.tapir          % Test,
      "com.dimafeng"                %% "testcontainers-scala-postgresql" % V.testcontainers % Test
    ),
    Compile / run / mainClass := Some("com.example.todo.Main"),
    run / fork                := true,
    run / envVars             := dotEnv(baseDirectory.value / ".." / ".env"),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true,
    // Docker Engine 29+ rejects the old API version Testcontainers requests by default.
    Test / javaOptions += "-Dapi.version=1.44",
    assembly / assemblyJarName := "todo-backend.jar",
    assembly / mainClass       := Some("com.example.todo.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)             => MergeStrategy.concat
      case PathList("META-INF", "maven", "org.webjars", _*) => MergeStrategy.first
      case PathList("META-INF", "resources", "webjars", _*) => MergeStrategy.first
      case PathList("META-INF", "versions", _*)             => MergeStrategy.first
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) =>
        MergeStrategy.discard
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class"      => MergeStrategy.discard
      case "reference.conf"         => MergeStrategy.concat
      // Quill publishes a few identical classes in both quill-engine and quill-sql.
      case PathList("io", "getquill", _*) => MergeStrategy.first
      case "deriving.conf"                => MergeStrategy.first
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )
