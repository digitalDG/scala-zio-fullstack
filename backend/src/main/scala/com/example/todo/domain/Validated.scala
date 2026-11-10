package com.example.todo.domain

import zio.{NonEmptyChunk, Zippable}

/** A problem with one field of user input. */
final case class FieldError(field: String, message: String)

/** The result of validating input: either the valid value, or every problem found.
  *
  * Unlike a plain `Either`, combining two `Validated` values with [[Validated.zipPar]] keeps the
  * errors from *both* sides, so a form with three bad fields reports all three at once.
  */
type Validated[+A] = Either[NonEmptyChunk[FieldError], A]

object Validated:
  def valid[A](value: A): Validated[A] = Right(value)

  def invalid(field: String, message: String): Validated[Nothing] =
    Left(NonEmptyChunk(FieldError(field, message)))

  /** Succeeds with `value` when `condition` holds, otherwise fails with a field error. */
  def check[A](condition: Boolean, field: String, message: => String)(value: => A): Validated[A] =
    if condition then Right(value) else invalid(field, message)

  /** Validates every element, accumulating errors across all of them. */
  def traverse[A, B](as: List[A])(f: A => Validated[B]): Validated[List[B]] =
    as.foldRight(valid(List.empty[B])) { (a, acc) =>
      f(a).zipPar(acc).map(_ :: _)
    }

  extension [A](self: Validated[A])
    /** Combines two validations, accumulating the errors of both.
      *
      * ZIO's `Zippable` flattens nested tuples, so `a.zipPar(b).zipPar(c)` yields `(A, B, C)`
      * rather than `((A, B), C)`.
      */
    def zipPar[B](that: Validated[B])(using z: Zippable[A, B]): Validated[z.Out] =
      (self, that) match
        case (Right(a), Right(b)) => Right(z.zip(a, b))
        case (Left(e1), Left(e2)) => Left(e1 ++ e2)
        case (Left(e), Right(_))  => Left(e)
        case (Right(_), Left(e))  => Left(e)
