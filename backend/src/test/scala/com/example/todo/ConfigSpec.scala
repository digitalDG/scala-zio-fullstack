package com.example.todo

import com.example.todo.config.*
import zio.*
import zio.test.*

/** Reads configuration from a map shaped exactly like environment variables. */
object ConfigSpec extends ZIOSpecDefault:

  private def load(env: Map[String, String]) =
    ZIO
      .config(AppConfig.descriptor)
      .withConfigProvider(ConfigProvider.fromMap(env, pathDelim = "_"))

  def spec = suite("Configuration")(
    test("only DB_PASSWORD is required; everything else has a default") {
      for config <- load(Map("DB_PASSWORD" -> "s3cret"))
      yield assertTrue(
        config.http == HttpConfig(),
        config.db.url == "jdbc:postgresql://localhost:5432/todo",
        config.db.maxPoolSize == 10,
        config.session == SessionConfig(),
        config.login == LoginConfig(),
        !config.demo.enabled
      )
    },
    test("any setting can be overridden by its environment variable") {
      for config <- load(
          Map(
            "DB_PASSWORD"                    -> "s3cret",
            "HTTP_PORT"                      -> "9000",
            "HTTP_SECURE_COOKIES"            -> "false",
            "DB_URL"                         -> "jdbc:postgresql://db:5432/todo",
            "DB_MAX_POOL_SIZE"               -> "4",
            "SESSION_TTL_MINUTES"            -> "30",
            "LOGIN_MAX_ATTEMPTS_PER_ACCOUNT" -> "3"
          )
        )
      yield assertTrue(
        config.http == HttpConfig(9000, secureCookies = false),
        config.db.url == "jdbc:postgresql://db:5432/todo",
        config.db.maxPoolSize == 4,
        config.session.ttlMinutes == 30,
        config.login.maxAttemptsPerAccount == 3
      )
    },
    test("a missing DB_PASSWORD stops startup") {
      for result <- load(Map.empty).either
      yield assertTrue(result.isLeft)
    },
    test("a malformed value is an error, not a silent fallback to the default") {
      for result <- load(Map("DB_PASSWORD" -> "s3cret", "HTTP_PORT" -> "eighty")).either
      yield assertTrue(result.isLeft)
    },
    test("the password is redacted when the config is printed") {
      for config <- load(Map("DB_PASSWORD" -> "s3cret"))
      yield assertTrue(!config.toString.contains("s3cret"))
    }
  )
