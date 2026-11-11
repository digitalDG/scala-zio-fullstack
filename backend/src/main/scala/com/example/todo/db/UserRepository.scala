package com.example.todo.db

import com.example.todo.domain.*
import io.getquill.*
import io.getquill.jdbczio.Quill
import zio.*

import java.sql.SQLException
import java.time.Instant

trait UserRepository:
  /** Fails with `Conflict` when the username or email is already taken. */
  def create(user: NewUser): IO[AppError.Conflict, User]
  def findByUsername(username: Username): UIO[Option[User]]
  def findById(id: UserId): UIO[Option[User]]
  def setPasswordHash(id: UserId, hash: PasswordHash): UIO[Unit]

object UserRepository:
  val postgres: URLayer[Quill.Postgres[SnakeCase], UserRepository] =
    ZLayer.derive[PostgresUserRepository]

/** The shape of a `users` row. Kept separate from the domain `User` so the schema can evolve
  * independently of the domain model.
  */
final case class UserRow(
  id: Long,
  username: String,
  email: String,
  passwordHash: String,
  createdAt: Instant
):
  def toDomain: User =
    User(
      UserId(id),
      Username.unsafe(username),
      Email.unsafe(email),
      PasswordHash(passwordHash),
      createdAt
    )

final class PostgresUserRepository(quill: Quill.Postgres[SnakeCase]) extends UserRepository:
  import quill.*

  private inline def users = quote(querySchema[UserRow]("users"))

  private val UniqueViolation = "23505"

  def create(user: NewUser): IO[AppError.Conflict, User] =
    val row = UserRow(
      0L,
      user.username.value,
      user.email.value,
      user.passwordHash.value,
      user.createdAt
    )
    run(users.insertValue(lift(row)).returningGenerated(_.id))
      .map(id => row.copy(id = id).toDomain)
      // A unique-constraint violation is an expected outcome; any other SQL error is a defect.
      .refineOrDie {
        case e: SQLException if e.getSQLState == UniqueViolation =>
          if e.getMessage.contains("users_email_key") then
            AppError.Conflict("That email is already registered")
          else AppError.Conflict("That username is already taken")
      }

  def findByUsername(username: Username): UIO[Option[User]] =
    run(users.filter(_.username == lift(username.value))).orDie.map(_.headOption.map(_.toDomain))

  def findById(id: UserId): UIO[Option[User]] =
    run(users.filter(_.id == lift(id.value))).orDie.map(_.headOption.map(_.toDomain))

  def setPasswordHash(id: UserId, hash: PasswordHash): UIO[Unit] =
    run(users.filter(_.id == lift(id.value)).update(_.passwordHash -> lift(hash.value))).orDie.unit
