package com.example.todo

import com.example.todo.auth.*
import com.example.todo.config.AppConfig
import com.example.todo.db.*
import com.example.todo.demo.DemoService
import com.example.todo.http.{ApiRoutes, HttpServer, SessionCookie}
import com.example.todo.todo.TodoService
import zio.*
import zio.http.Server
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault:

  /** Send ZIO's logs to SLF4J/Logback and read all configuration from environment variables. */
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>>
      Runtime.setConfigProvider(ConfigProvider.envProvider)

  private val server: ZLayer[AppConfig, Throwable, Server] =
    ZLayer.service[AppConfig].flatMap(config => Server.defaultWithPort(config.get.http.port))

  /** Runs in a scope so background jobs started here (the demo reset schedule) stop with the app.
    */
  private val app
    : ZIO[Migrations & DemoService & ApiRoutes & AppConfig & Server, Throwable, Nothing] =
    ZIO.scoped {
      for
        _ <- ZIO.serviceWithZIO[Migrations](_.migrate)
        // After migrations, because it writes to the tables they create.
        _       <- ZIO.serviceWithZIO[DemoService](_.start)
        api     <- ZIO.service[ApiRoutes]
        port    <- ZIO.serviceWith[AppConfig](_.http.port)
        _       <- ZIO.logInfo(s"Todo API listening on http://localhost:$port (docs at /docs)")
        nothing <- Server.serve(HttpServer.routes(api.all))
      yield nothing
    }

  /** The whole dependency graph. ZIO checks at compile time that every service's dependencies are
    * provided, and builds them in the right order.
    */
  def run =
    app.provide(
      AppConfig.layer,
      Database.dataSource,
      Database.quill,
      Migrations.layer,
      HealthCheck.live,
      UserRepository.postgres,
      TodoRepository.postgres,
      SessionRepository.postgres,
      PasswordHasher.live,
      SessionService.live,
      LoginRateLimiter.live,
      AuthService.live,
      DemoService.live,
      TodoService.live,
      SessionCookie.layer,
      ApiRoutes.layer,
      server
    )
