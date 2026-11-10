package com.example.todo.http

import com.example.todo.auth.{AuthResult, AuthService}
import com.example.todo.db.HealthCheck
import com.example.todo.domain.*
import com.example.todo.todo.TodoService
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.ztapir.*
import zio.*

import java.time.{LocalDate, ZoneOffset}

/** Attaches behaviour to each endpoint in [[Endpoints]].
  *
  * This is the boundary between HTTP and the domain. Each route validates the raw request into
  * domain types, calls a service, then maps the result to a response and any `AppError` to an
  * `ErrorResponse`.
  */
final class ApiRoutes(
  authService: AuthService,
  todoService: TodoService,
  health: HealthCheck,
  session: SessionCookie
):
  import ApiRoutes.*

  private def authenticate(sessionToken: Option[String]): IO[ErrorResponse, AuthUser] =
    sessionToken match
      case Some(token) => authService.authenticate(token).toApi
      case None        => ZIO.fail(ErrorResponse(ErrorCode.Unauthorized, "Not signed in"))

  // ---- System --------------------------------------------------------------------------------

  private val healthRoute: ZServerEndpoint[Any, Any] = Endpoints.health.zServerLogic { _ =>
    health.databaseUp.map(up => HealthResponse("ok", if up then "up" else "down"))
  }

  // ---- Auth ----------------------------------------------------------------------------------

  private val register: ZServerEndpoint[Any, Any] = Endpoints.register.zServerLogic { request =>
    (for
      registration <- validate(request.validated)
      result       <- authService.register(registration)
    yield signedIn(result)).toApi
  }

  private val login: ZServerEndpoint[Any, Any] = Endpoints.login.zServerLogic { (request, ip) =>
    authService.login(request.username, request.password, ip).map(signedIn).toApi
  }

  private val logout: ZServerEndpoint[Any, Any] = Endpoints.logout.zServerLogic { token =>
    ZIO.foreachDiscard(token)(authService.logout).as(session.cleared)
  }

  private val logoutEverywhere: ZServerEndpoint[Any, Any] = Endpoints.logoutEverywhere
    .zServerSecurityLogic(authenticate)
    .serverLogic(user => _ => authService.logoutEverywhere(user).as(session.cleared))

  /** The token goes only into the HttpOnly cookie, never into the response body. */
  private def signedIn(result: AuthResult): (CookieValueWithMeta, UserResponse) =
    (session.issue(result.session.value), UserResponse.from(result.user))

  private val me: ZServerEndpoint[Any, Any] = Endpoints.me
    .zServerSecurityLogic(authenticate)
    .serverLogic(user => _ => authService.currentUser(user).map(UserResponse.from).toApi)

  // ---- Todos ---------------------------------------------------------------------------------

  private val listTodos: ZServerEndpoint[Any, Any] = Endpoints.listTodos
    .zServerSecurityLogic(authenticate)
    .serverLogic { user => params =>
      (for
        query <- validate(params.validated)
        page  <- todoService.list(user.id, query)
      yield TodoPageResponse.from(page)).toApi
    }

  private val todoStats: ZServerEndpoint[Any, Any] = Endpoints.todoStats
    .zServerSecurityLogic(authenticate)
    .serverLogic { user => today =>
      for
        date <- today
          .fold(Clock.instant.map(LocalDate.ofInstant(_, ZoneOffset.UTC)))(ZIO.succeed(_))
        stats <- todoService.stats(user.id, date)
      yield TodoStatsResponse.from(stats)
    }

  private val listTags: ZServerEndpoint[Any, Any] = Endpoints.listTags
    .zServerSecurityLogic(authenticate)
    .serverLogic(user => _ => todoService.tags(user.id).map(_.map(_.value)))

  private val getTodo: ZServerEndpoint[Any, Any] = Endpoints.getTodo
    .zServerSecurityLogic(authenticate)
    .serverLogic(user => id => todoService.get(user.id, id).map(TodoResponse.from).toApi)

  private val createTodo: ZServerEndpoint[Any, Any] = Endpoints.createTodo
    .zServerSecurityLogic(authenticate)
    .serverLogic { user => request =>
      (for
        draft <- validate(request.validated)
        todo  <- todoService.create(user.id, draft)
      yield TodoResponse.from(todo)).toApi
    }

  private val updateTodo: ZServerEndpoint[Any, Any] = Endpoints.updateTodo
    .zServerSecurityLogic(authenticate)
    .serverLogic { user => (id, request) =>
      (for
        draft <- validate(request.validated)
        todo  <- todoService.update(user.id, id, draft)
      yield TodoResponse.from(todo)).toApi
    }

  private val setTodoStatus: ZServerEndpoint[Any, Any] = Endpoints.setTodoStatus
    .zServerSecurityLogic(authenticate)
    .serverLogic { user => (id, request) =>
      todoService.setCompleted(user.id, id, request.completed).map(TodoResponse.from).toApi
    }

  private val deleteTodo: ZServerEndpoint[Any, Any] = Endpoints.deleteTodo
    .zServerSecurityLogic(authenticate)
    .serverLogic(user => id => todoService.delete(user.id, id).toApi)

  val all: List[ZServerEndpoint[Any, Any]] = List(
    healthRoute,
    register,
    login,
    logout,
    logoutEverywhere,
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

object ApiRoutes:
  val layer: URLayer[AuthService & TodoService & HealthCheck & SessionCookie, ApiRoutes] =
    ZLayer.derive[ApiRoutes]

  private def validate[A](validated: Validated[A]): IO[AppError.InvalidInput, A] =
    ZIO.fromEither(validated).mapError(AppError.InvalidInput(_))

  extension [A](effect: IO[AppError, A])
    /** Converts expected errors to API errors, and defects (bugs, lost database connections) to a
      * logged 500 that reveals no internal details to the client.
      */
    private def toApi: IO[ErrorResponse, A] =
      effect
        .mapError(ErrorResponse.from)
        .catchAllDefect(defect =>
          ZIO.logErrorCause("Unhandled defect", Cause.die(defect)) *>
            ZIO.fail(ErrorResponse.internal)
        )
