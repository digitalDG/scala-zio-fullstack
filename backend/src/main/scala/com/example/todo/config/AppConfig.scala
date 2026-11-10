package com.example.todo.config

import zio.*
import zio.config.*
import zio.config.magnolia.deriveConfig

// Every setting comes from an environment variable named after its path, e.g. `db.maxPoolSize`
// is `DB_MAX_POOL_SIZE`. Defaults live here, next to the types, so there's one place to look.
// The only required value is `DB_PASSWORD`, which deliberately has no default.

final case class HttpConfig(
  port: Int = 8080,
  /** Only turn off for a plain-HTTP host other than `localhost`. */
  secureCookies: Boolean = true
)

final case class DbConfig(
  url: String = "jdbc:postgresql://localhost:5432/todo",
  user: String = "todo",
  /** `Config.Secret` is redacted in `toString`, so the password can't leak into logs. */
  password: Config.Secret,
  maxPoolSize: Int = 10
)

final case class SessionConfig(ttlMinutes: Int = 720)

final case class LoginConfig(
  maxAttemptsPerAccount: Int = 5,
  maxAttemptsPerIp: Int = 20,
  windowMinutes: Int = 15
)

final case class AppConfig(
  http: HttpConfig,
  db: DbConfig,
  session: SessionConfig,
  login: LoginConfig
)

object AppConfig:
  val descriptor: Config[AppConfig] =
    deriveConfig[AppConfig]
      .mapKey(toSnakeCase)
      .mapKey(_.toUpperCase)
      .validate("SESSION_TTL_MINUTES must be positive")(_.session.ttlMinutes > 0)
      .validate("LOGIN_* limits must be positive")(c =>
        c.login.maxAttemptsPerAccount > 0 && c.login.maxAttemptsPerIp > 0 &&
          c.login.windowMinutes > 0
      )

  /** Reads the configuration from whatever `ConfigProvider` is installed: environment variables in
    * production (see `Main`), a plain map in tests.
    */
  val layer: ZLayer[Any, Config.Error, AppConfig] = ZLayer.fromZIO(ZIO.config(descriptor))
