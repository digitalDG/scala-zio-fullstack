package com.example.todo.http

import com.example.todo.domain.*
import com.example.todo.domain.Validated.zipPar
import sttp.model.StatusCode
import sttp.tapir.Schema
import zio.json.JsonCodec

import java.time.{Instant, LocalDate}

// The JSON shapes of the public API. They are deliberately separate from the domain model: the
// API stays stable while the domain evolves, and request types carry raw input that has not been
// validated yet.

/** Tapir marks collections as optional by default. Responses always include their lists, so the
  * generated TypeScript types should say so.
  */
private def required[T](schema: Schema[T]): Schema[T] = schema.copy(isOptional = false)

// ---- Requests --------------------------------------------------------------------------------

final case class RegisterRequest(username: String, email: String, password: String)
    derives JsonCodec,
      Schema:
  def validated: Validated[Registration] =
    Username
      .make(username)
      .zipPar(Email.make(email))
      .zipPar(PlainPassword.make(password))
      .map(Registration.apply)

final case class LoginRequest(username: String, password: String) derives JsonCodec, Schema

final case class TodoRequest(
  title: String,
  description: Option[String],
  priority: Priority,
  dueDate: Option[LocalDate],
  tags: List[String] = Nil,
  completed: Boolean = false
) derives JsonCodec,
      Schema:
  def validated: Validated[TodoDraft] =
    Title
      .make(title)
      .zipPar(Description.make(description))
      .zipPar(TodoTag.makeAll(tags))
      .map((title, description, tags) =>
        TodoDraft(title, description, priority, dueDate, tags, completed)
      )

final case class TodoStatusRequest(completed: Boolean) derives JsonCodec, Schema

/** Query parameters of `GET /api/todos`. Ranges are already enforced by Tapir validators. */
final case class TodoListParams(
  completed: Option[Boolean],
  priority: Option[Priority],
  tag: Option[String],
  search: Option[String],
  sort: SortField,
  order: SortOrder,
  page: Int,
  pageSize: Int
):
  def validated: Validated[TodoQuery] =
    tag
      .map(TodoTag.make)
      .fold(Validated.valid(None))(_.map(Some(_)))
      .map(tag =>
        TodoQuery(
          TodoFilter(completed, priority, tag, search.map(_.trim).filter(_.nonEmpty)),
          sort,
          order,
          PageRequest(page, pageSize)
        )
      )

// ---- Responses -------------------------------------------------------------------------------

final case class UserResponse(id: Long, username: String, email: String, createdAt: Instant)
    derives JsonCodec,
      Schema

object UserResponse:
  def from(user: User): UserResponse =
    UserResponse(user.id.value, user.username.value, user.email.value, user.createdAt)

final case class TodoResponse(
  id: Long,
  title: String,
  description: Option[String],
  completed: Boolean,
  priority: Priority,
  dueDate: Option[LocalDate],
  tags: List[String],
  createdAt: Instant,
  updatedAt: Instant
) derives JsonCodec

object TodoResponse:
  given Schema[TodoResponse] = Schema.derived[TodoResponse].modify(_.tags)(required)

  def from(todo: Todo): TodoResponse =
    TodoResponse(
      todo.id.value,
      todo.title.value,
      todo.description,
      todo.completed,
      todo.priority,
      todo.dueDate,
      todo.tags.map(_.value),
      todo.createdAt,
      todo.updatedAt
    )

final case class TodoPageResponse(
  items: List[TodoResponse],
  total: Long,
  page: Int,
  pageSize: Int,
  totalPages: Int
) derives JsonCodec

object TodoPageResponse:
  given Schema[TodoPageResponse] = Schema.derived[TodoPageResponse].modify(_.items)(required)

  def from(page: Page[Todo]): TodoPageResponse =
    TodoPageResponse(
      page.items.map(TodoResponse.from),
      page.total,
      page.request.page,
      page.request.size,
      page.totalPages
    )

final case class TodoStatsResponse(total: Long, completed: Long, active: Long, overdue: Long)
    derives JsonCodec,
      Schema

object TodoStatsResponse:
  def from(stats: TodoStats): TodoStatsResponse =
    TodoStatsResponse(stats.total, stats.completed, stats.active, stats.overdue)

final case class HealthResponse(status: String, database: String) derives JsonCodec, Schema

// ---- Errors ----------------------------------------------------------------------------------

enum ErrorCode(val status: StatusCode):
  case ValidationFailed extends ErrorCode(StatusCode.BadRequest)
  case BadRequest       extends ErrorCode(StatusCode.BadRequest)
  case Unauthorized     extends ErrorCode(StatusCode.Unauthorized)
  case NotFound         extends ErrorCode(StatusCode.NotFound)
  case Conflict         extends ErrorCode(StatusCode.Conflict)
  case TooManyRequests  extends ErrorCode(StatusCode.TooManyRequests)
  case Internal         extends ErrorCode(StatusCode.InternalServerError)

object ErrorCode extends LabeledEnum[ErrorCode]:
  def label(value: ErrorCode): String = value match
    case ValidationFailed => "validation_failed"
    case BadRequest       => "bad_request"
    case Unauthorized     => "unauthorized"
    case NotFound         => "not_found"
    case Conflict         => "conflict"
    case TooManyRequests  => "too_many_requests"
    case Internal         => "internal_error"
  protected def typeName = "ErrorCode"

final case class FieldErrorResponse(field: String, message: String) derives JsonCodec, Schema

/** The body of every error response. `code` is stable and machine-readable; `message` is for
  * humans; `fieldErrors` lists each invalid input field when `code` is `validation_failed`.
  */
final case class ErrorResponse(
  code: ErrorCode,
  message: String,
  fieldErrors: List[FieldErrorResponse]
) derives JsonCodec

object ErrorResponse:
  given Schema[ErrorResponse] = Schema.derived[ErrorResponse].modify(_.fieldErrors)(required)

  def apply(code: ErrorCode, message: String): ErrorResponse = ErrorResponse(code, message, Nil)

  val internal: ErrorResponse = ErrorResponse(ErrorCode.Internal, "Internal server error")

  /** Exhaustive: adding a new `AppError` case is a compile error here until it's mapped. */
  def from(error: AppError): ErrorResponse = error match
    case AppError.InvalidInput(errors) =>
      ErrorResponse(
        ErrorCode.ValidationFailed,
        error.message,
        errors.toList.map(e => FieldErrorResponse(e.field, e.message))
      )
    case AppError.Unauthorized(message)    => ErrorResponse(ErrorCode.Unauthorized, message)
    case AppError.NotFound(message)        => ErrorResponse(ErrorCode.NotFound, message)
    case AppError.Conflict(message)        => ErrorResponse(ErrorCode.Conflict, message)
    case AppError.TooManyRequests(message) => ErrorResponse(ErrorCode.TooManyRequests, message)
