package com.example.todo.domain

import zio.NonEmptyChunk

/** Expected failures the application handles and reports to the client.
  *
  * Each case is its own type, so service signatures can say exactly which failures they produce,
  * using union types such as `IO[AppError.NotFound | AppError.InvalidInput, Todo]`.
  *
  * Unexpected failures (a lost database connection, a bug) are *not* modelled here. They travel as
  * ZIO defects and are turned into a generic 500 at the HTTP boundary.
  */
sealed trait AppError extends Product with Serializable:
  def message: String

object AppError:
  final case class InvalidInput(errors: NonEmptyChunk[FieldError]) extends AppError:
    def message = "The request contains invalid fields"

  final case class Unauthorized(message: String) extends AppError

  final case class NotFound(message: String) extends AppError

  final case class Conflict(message: String) extends AppError

  final case class TooManyRequests(message: String) extends AppError

  /** The caller is signed in but isn't allowed to do this. */
  final case class Forbidden(message: String) extends AppError

  def todoNotFound(id: TodoId): NotFound = NotFound(s"Todo ${id.value} not found")
