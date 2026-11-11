package com.example.todo.http

import com.example.todo.domain.*
import sttp.model.StatusCode
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.zio.*

import java.time.LocalDate

/** Descriptions of every HTTP endpoint: paths, inputs, outputs and errors, with no behaviour.
  *
  * Because these are plain values, the same definitions produce both the running server
  * ([[ApiRoutes]] attaches logic to each one) and the OpenAPI document, from which Swagger UI and
  * the frontend's TypeScript types are generated.
  */
object Endpoints:

  /** One response variant per HTTP status, all sharing the `ErrorResponse` body. */
  private val errorOutput: EndpointOutput.OneOf[ErrorResponse, ErrorResponse] =
    val variants = ErrorCode.values.toList.map(_.status).distinct.map { status =>
      oneOfVariantValueMatcher(status, jsonBody[ErrorResponse]) { case e: ErrorResponse =>
        e.code.status == status
      }
    }
    oneOf[ErrorResponse](variants.head, variants.tail*)

  private val base = endpoint.in("api").errorOut(errorOutput)

  /** Protected endpoints read the session cookie. It's optional at this level so a missing cookie
    * produces our own 401 JSON error rather than a generic decode failure.
    */
  private val secured =
    base.securityIn(
      auth
        .apiKey(cookie[Option[String]](SessionCookie.Name))
        .description("HttpOnly session cookie set by /api/auth/login")
    )

  private val todoId = path[TodoId]("id").description("Todo ID")

  // ---- System --------------------------------------------------------------------------------

  val health: PublicEndpoint[Unit, ErrorResponse, HealthResponse, Any] =
    base.get
      .in("health")
      .out(jsonBody[HealthResponse])
      .tag("system")
      .summary("Service and database health")

  // ---- Auth ----------------------------------------------------------------------------------

  private val sessionCookie = setCookie(SessionCookie.Name).description("The session cookie")

  val register
    : PublicEndpoint[RegisterRequest, ErrorResponse, (CookieValueWithMeta, UserResponse), Any] =
    base.post
      .in("auth" / "register")
      .in(jsonBody[RegisterRequest])
      .out(statusCode(StatusCode.Created).and(sessionCookie).and(jsonBody[UserResponse]))
      .tag("auth")
      .summary("Create an account and sign in")

  /** `clientIp` is the caller's address, used to rate-limit failed sign-ins per client. Behind the
    * Nginx proxy it comes from `X-Forwarded-For`, which the proxy overwrites with the real address.
    */
  val login: PublicEndpoint[
    (LoginRequest, Option[String]),
    ErrorResponse,
    (CookieValueWithMeta, UserResponse),
    Any
  ] =
    base.post
      .in("auth" / "login")
      .in(jsonBody[LoginRequest])
      .in(clientIp)
      .out(sessionCookie.and(jsonBody[UserResponse]))
      .tag("auth")
      .summary("Sign in with username and password")
      .description("Failed attempts are limited per account and per client address (429).")

  val logout: PublicEndpoint[Option[String], ErrorResponse, CookieValueWithMeta, Any] =
    base.post
      .in("auth" / "logout")
      .in(cookie[Option[String]](SessionCookie.Name))
      .out(statusCode(StatusCode.NoContent).and(sessionCookie))
      .tag("auth")
      .summary("Sign out: end this session and clear the cookie")

  val demoInfo: PublicEndpoint[Unit, ErrorResponse, DemoInfoResponse, Any] =
    base.get
      .in("auth" / "demo")
      .out(jsonBody[DemoInfoResponse])
      .tag("auth")
      .summary("Whether the shared demo account is available")

  val demoSignIn: PublicEndpoint[Unit, ErrorResponse, (CookieValueWithMeta, UserResponse), Any] =
    base.post
      .in("auth" / "demo")
      .out(sessionCookie.and(jsonBody[UserResponse]))
      .tag("auth")
      .summary("Sign in to the shared demo account (404 if it isn't enabled)")

  val logoutEverywhere: Endpoint[Option[String], Unit, ErrorResponse, CookieValueWithMeta, Any] =
    secured.post
      .in("auth" / "logout-all")
      .out(statusCode(StatusCode.NoContent).and(sessionCookie))
      .tag("auth")
      .summary("Sign out on every device: end all of the user's sessions")

  val me: Endpoint[Option[String], Unit, ErrorResponse, UserResponse, Any] =
    secured.get
      .in("auth" / "me")
      .out(jsonBody[UserResponse])
      .tag("auth")
      .summary("The signed-in user")

  // ---- Todos ---------------------------------------------------------------------------------

  private val listParams: EndpointInput[TodoListParams] =
    query[Option[Boolean]]("completed")
      .description("Only completed (true) or only open (false) todos")
      .and(query[Option[Priority]]("priority"))
      .and(query[Option[String]]("tag").description("Only todos with this tag"))
      .and(query[Option[String]]("q").description("Case-insensitive search in titles"))
      .and(query[SortField]("sort").default(SortField.CreatedAt))
      .and(query[SortOrder]("order").default(SortOrder.Desc))
      .and(query[Int]("page").default(1).validate(Validator.min(1)))
      .and(query[Int]("pageSize").default(20).validate(Validator.inRange(1, 100)))
      .mapTo[TodoListParams]

  val listTodos: Endpoint[Option[String], TodoListParams, ErrorResponse, TodoPageResponse, Any] =
    secured.get
      .in("todos")
      .in(listParams)
      .out(jsonBody[TodoPageResponse])
      .tag("todos")
      .summary("List todos with filtering, sorting and paging")

  val todoStats
    : Endpoint[Option[String], Option[LocalDate], ErrorResponse, TodoStatsResponse, Any] =
    secured.get
      .in("todos" / "stats")
      .in(
        query[Option[LocalDate]]("today")
          .description("The client's local date, used to decide what's overdue. Defaults to UTC.")
      )
      .out(jsonBody[TodoStatsResponse])
      .tag("todos")
      .summary("Counts of total, completed, active and overdue todos")

  val listTags: Endpoint[Option[String], Unit, ErrorResponse, List[String], Any] =
    secured.get
      .in("tags")
      .out(jsonBody[List[String]])
      .tag("todos")
      .summary("Every tag the user has used, sorted")

  val getTodo: Endpoint[Option[String], TodoId, ErrorResponse, TodoResponse, Any] =
    secured.get
      .in("todos" / todoId)
      .out(jsonBody[TodoResponse])
      .tag("todos")
      .summary("Get one todo")

  val createTodo: Endpoint[Option[String], TodoRequest, ErrorResponse, TodoResponse, Any] =
    secured.post
      .in("todos")
      .in(jsonBody[TodoRequest])
      .out(statusCode(StatusCode.Created).and(jsonBody[TodoResponse]))
      .tag("todos")
      .summary("Create a todo")

  val updateTodo
    : Endpoint[Option[String], (TodoId, TodoRequest), ErrorResponse, TodoResponse, Any] =
    secured.put
      .in("todos" / todoId)
      .in(jsonBody[TodoRequest])
      .out(jsonBody[TodoResponse])
      .tag("todos")
      .summary("Replace a todo")

  val setTodoStatus
    : Endpoint[Option[String], (TodoId, TodoStatusRequest), ErrorResponse, TodoResponse, Any] =
    secured.patch
      .in("todos" / todoId / "status")
      .in(jsonBody[TodoStatusRequest])
      .out(jsonBody[TodoResponse])
      .tag("todos")
      .summary("Mark a todo complete or not complete")

  val deleteTodo: Endpoint[Option[String], TodoId, ErrorResponse, Unit, Any] =
    secured.delete
      .in("todos" / todoId)
      .out(statusCode(StatusCode.NoContent))
      .tag("todos")
      .summary("Delete a todo")

  val all: List[AnyEndpoint] = List(
    health,
    register,
    login,
    logout,
    logoutEverywhere,
    demoInfo,
    demoSignIn,
    me,
    listTodos,
    todoStats,
    listTags,
    getTodo,
    createTodo,
    updateTodo,
    setTodoStatus,
    deleteTodo
  )
