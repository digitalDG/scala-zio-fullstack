package com.example.todo

import com.example.todo.auth.*
import com.example.todo.config.*
import com.example.todo.db.HealthCheck
import com.example.todo.demo.DemoService
import com.example.todo.domain.Priority
import com.example.todo.http.*
import com.example.todo.todo.TodoService
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.{MediaType, Method, StatusCode, Uri}
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.RIOMonadError
import zio.*
import zio.json.*
import zio.test.*

import java.time.LocalDate

/** End-to-end tests of the HTTP API: real routing, JSON, validation, auth and error handling, with
  * in-memory repositories in place of Postgres.
  */
object ApiSpec extends ZIOSpecDefault:

  final case class Reply(status: StatusCode, body: String, setCookie: Option[String]):
    def as[A: JsonDecoder]: Task[A] =
      ZIO.fromEither(body.fromJson[A]).mapError(e => RuntimeException(s"$e in: $body"))

    /** The value of the session cookie set by this response, if any. */
    def session: Task[String] =
      ZIO
        .fromOption(
          setCookie.flatMap(_.split(';').headOption).map(_.stripPrefix(s"${SessionCookie.Name}="))
        )
        .orElseFail(RuntimeException("No session cookie in response"))

  final class Client(backend: SttpBackend[Task, Any]):
    /** `token` is the session cookie value, sent the way a browser would send it. */
    def send(
      method: Method,
      path: String,
      body: Option[String] = None,
      token: Option[String] = None,
      headers: Map[String, String] = Map.empty
    ): Task[Reply] =
      val base = basicRequest
        .method(method, Uri.unsafeParse(s"http://localhost$path"))
        .headers(headers)
      val authed  = token.fold(base)(t => base.header("Cookie", s"${SessionCookie.Name}=$t"))
      val request = body.fold(authed)(b => authed.body(b).contentType(MediaType.ApplicationJson))
      request
        .send(backend)
        .map(r => Reply(r.code, r.body.merge, r.header("Set-Cookie")))

    def get(path: String, token: String): Task[Reply] = send(Method.GET, path, token = Some(token))

    def register(name: String): Task[String] =
      send(
        Method.POST,
        "/api/auth/register",
        Some(RegisterRequest(name, s"$name@example.com", "password123").toJson)
      ).flatMap(_.session)

    def create(token: String, request: TodoRequest): Task[TodoResponse] =
      send(Method.POST, "/api/todos", Some(request.toJson), Some(token)).flatMap(_.as[TodoResponse])

  private def todo(title: String, priority: Priority = Priority.Medium, tags: List[String] = Nil) =
    TodoRequest(title, None, priority, None, tags, completed = false)

  private val client: URIO[ApiRoutes, Client] = ZIO.serviceWith[ApiRoutes] { api =>
    Client(
      TapirStubInterpreter(HttpServer.interceptors, SttpBackendStub(new RIOMonadError[Any]))
        .whenServerEndpointsRunLogic(api.all)
        .backend()
    )
  }

  private val alwaysHealthy: ULayer[HealthCheck] = ZLayer.succeed(
    new HealthCheck:
      def databaseUp: UIO[Boolean] = ZIO.succeed(true)
  )

  private val testConfig = AppConfig(
    HttpConfig(),
    DbConfig(password = Config.Secret("unused")),
    SessionConfig(ttlMinutes = 60),
    LoginConfig(maxAttemptsPerAccount = 5, maxAttemptsPerIp = 20, windowMinutes = 15),
    DemoConfig(enabled = true, username = "demo", resetIntervalHours = 6)
  )

  /** The production wiring, except storage is in memory and bcrypt runs at its cheapest cost. */
  private val testLayer: ULayer[ApiRoutes & DemoService] = ZLayer.make[ApiRoutes & DemoService](
    ZLayer.succeed(testConfig),
    InMemoryUserRepository.layer,
    InMemoryTodoRepository.layer,
    InMemorySessionRepository.asRepository,
    PasswordHasher.fast,
    SessionService.live,
    LoginRateLimiter.live,
    AuthService.live,
    DemoService.live,
    TodoService.live,
    alwaysHealthy,
    SessionCookie.layer,
    ApiRoutes.layer
  )

  /** The same wiring with the demo account switched off. */
  private val demoDisabled: ULayer[ApiRoutes] = ZLayer.make[ApiRoutes](
    ZLayer.succeed(testConfig.copy(demo = DemoConfig(enabled = false))),
    InMemoryUserRepository.layer,
    InMemoryTodoRepository.layer,
    InMemorySessionRepository.asRepository,
    PasswordHasher.fast,
    SessionService.live,
    LoginRateLimiter.live,
    AuthService.live,
    DemoService.live,
    TodoService.live,
    alwaysHealthy,
    SessionCookie.layer,
    ApiRoutes.layer
  )

  private def demoSession(c: Client): Task[String] =
    c.send(Method.POST, "/api/auth/demo").flatMap(_.session)

  private def login(c: Client, name: String, password: String, ip: String = "10.0.0.1") =
    c.send(
      Method.POST,
      "/api/auth/login",
      Some(LoginRequest(name, password).toJson),
      headers = Map("X-Forwarded-For" -> ip)
    )

  def spec = suite("HTTP API")(
    suite("demo account")(
      test("is advertised, and one click signs in with sample todos") {
        for
          c       <- client
          info    <- c.send(Method.GET, "/api/auth/demo").flatMap(_.as[DemoInfoResponse])
          session <- demoSession(c)
          me      <- c.get("/api/auth/me", session).flatMap(_.as[UserResponse])
          todos   <- c.get("/api/todos", session).flatMap(_.as[TodoPageResponse])
          stats   <- c.get("/api/todos/stats", session).flatMap(_.as[TodoStatsResponse])
        yield assertTrue(
          info == DemoInfoResponse(enabled = true, resetIntervalHours = 6),
          me.username == "demo",
          me.demo,
          todos.total == 8L,
          stats.overdue == 1L
        )
      },
      test("has no password anyone knows, so the lockout can't block it") {
        for
          c       <- client
          _       <- demoSession(c)
          guesses <- login(c, "demo", "password123").repeatN(9)
          stillIn <- c.send(Method.POST, "/api/auth/demo")
        yield assertTrue(
          guesses.status == StatusCode.TooManyRequests,
          stillIn.status == StatusCode.Ok
        )
      },
      test("can't sign out every other visitor") {
        for
          c       <- client
          visitor <- demoSession(c)
          other   <- demoSession(c)
          reply   <- c.send(Method.POST, "/api/auth/logout-all", token = Some(visitor))
          error   <- reply.as[ErrorResponse]
          stillIn <- c.get("/api/auth/me", other)
        yield assertTrue(
          reply.status == StatusCode.Forbidden,
          error.code == ErrorCode.Forbidden,
          stillIn.status == StatusCode.Ok
        )
      },
      test("a reset wipes visitors' changes and restores the samples") {
        for
          c       <- client
          session <- demoSession(c)
          _       <- c.create(session, todo("Left behind by a visitor"))
          before  <- c.get("/api/todos", session).flatMap(_.as[TodoPageResponse])
          _       <- ZIO.serviceWithZIO[DemoService](_.reset)
          after   <- c.get("/api/todos", session).flatMap(_.as[TodoPageResponse])
        yield assertTrue(
          before.total == 9L,
          after.total == 8L,
          !after.items.exists(_.title == "Left behind by a visitor")
        )
      },
      test("regular users are never flagged as demo") {
        for
          c       <- client
          session <- c.register("regular")
          me      <- c.get("/api/auth/me", session).flatMap(_.as[UserResponse])
        yield assertTrue(!me.demo)
      },
      test("is a 404 when disabled") {
        for
          c     <- client
          info  <- c.send(Method.GET, "/api/auth/demo").flatMap(_.as[DemoInfoResponse])
          reply <- c.send(Method.POST, "/api/auth/demo")
        yield assertTrue(!info.enabled, reply.status == StatusCode.NotFound)
      }.provideLayer(demoDisabled)
    ),
    suite("auth")(
      test("registering returns 201 and a session cookie that identifies the new user") {
        for
          c <- client
          reply <- c.send(
            Method.POST,
            "/api/auth/register",
            Some(RegisterRequest("alice", "alice@example.com", "password123").toJson)
          )
          session <- reply.session
          me      <- c.get("/api/auth/me", session).flatMap(_.as[UserResponse])
        yield assertTrue(reply.status == StatusCode.Created, me.username == "alice")
      },
      test("the session cookie can't be read by scripts or sent cross-site") {
        for
          c <- client
          reply <- c.send(
            Method.POST,
            "/api/auth/register",
            Some(RegisterRequest("zed", "zed@example.com", "password123").toJson)
          )
          cookie = reply.setCookie.getOrElse("")
        yield assertTrue(
          cookie.contains("HttpOnly"),
          cookie.contains("Secure"),
          cookie.contains("SameSite=Strict"),
          cookie.contains("Path=/api"),
          cookie.contains("Max-Age=3600"),
          // The token travels only in the cookie, never in a body JavaScript could read.
          !reply.body.contains(cookie.takeWhile(_ != ';').stripPrefix(s"${SessionCookie.Name}="))
        )
      },
      test("logging out ends the session on the server, not just in the browser") {
        for
          c       <- client
          session <- c.register("olivia")
          reply   <- c.send(Method.POST, "/api/auth/logout", token = Some(session))
          cookie = reply.setCookie.getOrElse("")
          // Replaying the old cookie, as a thief with a copy would, no longer works.
          replay <- c.get("/api/auth/me", session)
        yield assertTrue(
          reply.status == StatusCode.NoContent,
          cookie.startsWith(s"${SessionCookie.Name}=;"),
          cookie.contains("Max-Age=0"),
          replay.status == StatusCode.Unauthorized
        )
      },
      test("signing out everywhere ends every session of that user only") {
        for
          c      <- client
          laptop <- c.register("pat")
          phone  <- login(c, "pat", "password123").flatMap(_.session)
          other  <- c.register("quinn")
          reply  <- c.send(Method.POST, "/api/auth/logout-all", token = Some(laptop))
          a      <- c.get("/api/auth/me", laptop)
          b      <- c.get("/api/auth/me", phone)
          q      <- c.get("/api/auth/me", other)
        yield assertTrue(
          reply.status == StatusCode.NoContent,
          a.status == StatusCode.Unauthorized,
          b.status == StatusCode.Unauthorized,
          q.status == StatusCode.Ok
        )
      },
      test("repeated wrong passwords lock the account, even against the right one") {
        for
          c       <- client
          _       <- c.register("rita")
          _       <- login(c, "rita", "wrong-password").repeatN(4)
          locked  <- login(c, "rita", "password123")
          error   <- locked.as[ErrorResponse]
          otherOk <- c.register("sam").zipRight(login(c, "sam", "password123"))
        yield assertTrue(
          locked.status == StatusCode.TooManyRequests,
          error.code == ErrorCode.TooManyRequests,
          otherOk.status == StatusCode.Ok
        )
      },
      test("one address trying many accounts is limited too") {
        for
          c <- client
          _ <- ZIO.foreachDiscard(1 to 20)(i =>
            login(c, s"nobody$i", "guess-guess", ip = "10.9.9.9")
          )
          blocked   <- login(c, "nobody21", "guess-guess", ip = "10.9.9.9")
          elsewhere <- login(c, "nobody21", "guess-guess", ip = "10.1.1.1")
        yield assertTrue(
          blocked.status == StatusCode.TooManyRequests,
          elsewhere.status == StatusCode.Unauthorized
        )
      },
      test("a taken username is a 409 conflict") {
        for
          c <- client
          _ <- c.register("alice")
          reply <- c.send(
            Method.POST,
            "/api/auth/register",
            Some(RegisterRequest("ALICE", "other@example.com", "password123").toJson)
          )
          error <- reply.as[ErrorResponse]
        yield assertTrue(reply.status == StatusCode.Conflict, error.code == ErrorCode.Conflict)
      },
      test("invalid registration reports every bad field") {
        for
          c <- client
          reply <- c.send(
            Method.POST,
            "/api/auth/register",
            Some(RegisterRequest("x", "bad", "short").toJson)
          )
          error <- reply.as[ErrorResponse]
        yield assertTrue(
          reply.status == StatusCode.BadRequest,
          error.code == ErrorCode.ValidationFailed,
          error.fieldErrors.map(_.field).toSet == Set("username", "email", "password")
        )
      },
      test("login succeeds with the right password and fails with the wrong one") {
        for
          c <- client
          _ <- c.register("bob")
          good <- c.send(
            Method.POST,
            "/api/auth/login",
            Some(LoginRequest("bob", "password123").toJson)
          )
          bad <- c.send(
            Method.POST,
            "/api/auth/login",
            Some(LoginRequest("bob", "wrong-password").toJson)
          )
          none <- c.send(
            Method.POST,
            "/api/auth/login",
            Some(LoginRequest("nobody", "password123").toJson)
          )
        yield assertTrue(
          good.status == StatusCode.Ok,
          bad.status == StatusCode.Unauthorized,
          none.status == StatusCode.Unauthorized
        )
      },
      test("todo endpoints reject missing and invalid tokens") {
        for
          c       <- client
          missing <- c.send(Method.GET, "/api/todos")
          invalid <- c.get("/api/todos", "not-a-token")
          error   <- invalid.as[ErrorResponse]
        yield assertTrue(
          missing.status == StatusCode.Unauthorized,
          invalid.status == StatusCode.Unauthorized,
          error.code == ErrorCode.Unauthorized
        )
      }
    ),
    suite("todos")(
      test("create, read, update, complete and delete a todo") {
        for
          c       <- client
          token   <- c.register("carol")
          created <- c.create(token, todo("Write README", Priority.High, List("Docs")))
          fetched <- c.get(s"/api/todos/${created.id}", token).flatMap(_.as[TodoResponse])
          updated <- c
            .send(
              Method.PUT,
              s"/api/todos/${created.id}",
              Some(todo("Write a better README").toJson),
              Some(token)
            )
            .flatMap(_.as[TodoResponse])
          done <- c
            .send(
              Method.PATCH,
              s"/api/todos/${created.id}/status",
              Some(TodoStatusRequest(true).toJson),
              Some(token)
            )
            .flatMap(_.as[TodoResponse])
          deleted <- c.send(Method.DELETE, s"/api/todos/${created.id}", token = Some(token))
          gone    <- c.get(s"/api/todos/${created.id}", token)
        yield assertTrue(
          created.tags == List("docs"),
          fetched == created,
          updated.title == "Write a better README",
          done.completed,
          deleted.status == StatusCode.NoContent,
          gone.status == StatusCode.NotFound
        )
      },
      test("users can't see or change each other's todos") {
        for
          c       <- client
          alice   <- c.register("alice")
          mallory <- c.register("mallory")
          secret  <- c.create(alice, todo("Alice's secret"))
          read    <- c.get(s"/api/todos/${secret.id}", mallory)
          write <- c.send(
            Method.PUT,
            s"/api/todos/${secret.id}",
            Some(todo("hacked").toJson),
            Some(mallory)
          )
          remove <- c.send(Method.DELETE, s"/api/todos/${secret.id}", token = Some(mallory))
          list   <- c.get("/api/todos", mallory).flatMap(_.as[TodoPageResponse])
          still  <- c.get(s"/api/todos/${secret.id}", alice).flatMap(_.as[TodoResponse])
        yield assertTrue(
          read.status == StatusCode.NotFound,
          write.status == StatusCode.NotFound,
          remove.status == StatusCode.NotFound,
          list.total == 0L,
          still.title == "Alice's secret"
        )
      },
      test("the list can be filtered, searched, sorted and paged") {
        for
          c       <- client
          token   <- c.register("dave")
          _       <- c.create(token, todo("Buy milk", Priority.Low, List("home")))
          _       <- c.create(token, todo("Ship release", Priority.High, List("work")))
          _       <- c.create(token, todo("Review PR", Priority.Medium, List("work")))
          _       <- c.create(token, todo("Pay rent", Priority.High, List("home")))
          high    <- c.get("/api/todos?priority=high", token).flatMap(_.as[TodoPageResponse])
          work    <- c.get("/api/todos?tag=work", token).flatMap(_.as[TodoPageResponse])
          found   <- c.get("/api/todos?q=MILK", token).flatMap(_.as[TodoPageResponse])
          byTitle <- c.get("/api/todos?sort=title&order=asc", token).flatMap(_.as[TodoPageResponse])
          page2 <- c
            .get("/api/todos?sort=title&order=asc&pageSize=3&page=2", token)
            .flatMap(_.as[TodoPageResponse])
        yield assertTrue(
          high.items.map(_.title).toSet == Set("Ship release", "Pay rent"),
          work.total == 2L,
          found.items.map(_.title) == List("Buy milk"),
          byTitle.items.map(_.title) == List("Buy milk", "Pay rent", "Review PR", "Ship release"),
          page2.items.map(_.title) == List("Ship release"),
          page2.totalPages == 2
        )
      },
      test("stats count overdue todos relative to the client's date") {
        val today = LocalDate.of(2026, 1, 15)
        for
          c     <- client
          token <- c.register("erin")
          _     <- c.create(token, todo("Late").copy(dueDate = Some(today.minusDays(1))))
          _     <- c.create(token, todo("On time").copy(dueDate = Some(today.plusDays(1))))
          _ <- c.create(
            token,
            todo("Done late").copy(dueDate = Some(today.minusDays(3)), completed = true)
          )
          stats <- c.get(s"/api/todos/stats?today=$today", token).flatMap(_.as[TodoStatsResponse])
        yield assertTrue(
          stats == TodoStatsResponse(total = 3, completed = 1, active = 2, overdue = 1)
        )
      },
      test("tags lists each tag once, sorted") {
        for
          c     <- client
          token <- c.register("frank")
          _     <- c.create(token, todo("a", tags = List("work", "urgent")))
          _     <- c.create(token, todo("b", tags = List("home", "work")))
          tags  <- c.get("/api/tags", token).flatMap(_.as[List[String]])
        yield assertTrue(tags == List("home", "urgent", "work"))
      }
    ),
    suite("errors")(
      test("an out-of-range query parameter is a 400 with a JSON error body") {
        for
          c     <- client
          token <- c.register("gina")
          reply <- c.get("/api/todos?pageSize=500", token)
          error <- reply.as[ErrorResponse]
        yield assertTrue(reply.status == StatusCode.BadRequest, error.code == ErrorCode.BadRequest)
      },
      test("malformed JSON is a 400 with a JSON error body") {
        for
          c     <- client
          token <- c.register("hank")
          reply <- c.send(Method.POST, "/api/todos", Some("{not json"), Some(token))
          error <- reply.as[ErrorResponse]
        yield assertTrue(reply.status == StatusCode.BadRequest, error.code == ErrorCode.BadRequest)
      },
      test("an unknown todo is a 404") {
        for
          c     <- client
          token <- c.register("ivy")
          reply <- c.get("/api/todos/999", token)
          error <- reply.as[ErrorResponse]
        yield assertTrue(reply.status == StatusCode.NotFound, error.code == ErrorCode.NotFound)
      }
    ),
    test("health reports the database status") {
      for
        c     <- client
        reply <- c.send(Method.GET, "/api/health").flatMap(_.as[HealthResponse])
      yield assertTrue(reply == HealthResponse("ok", "up"))
    }
  ).provide(testLayer)
