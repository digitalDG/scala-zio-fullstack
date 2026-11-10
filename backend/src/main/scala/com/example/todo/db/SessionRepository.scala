package com.example.todo.db

import com.example.todo.domain.*
import io.getquill.*
import io.getquill.jdbczio.Quill
import zio.*

import java.time.Instant

trait SessionRepository:
  def create(tokenHash: String, user: UserId, createdAt: Instant, expiresAt: Instant): UIO[Unit]

  /** The user owning an unexpired session with this hash, if any. */
  def findUser(tokenHash: String, now: Instant): UIO[Option[AuthUser]]
  def delete(tokenHash: String): UIO[Unit]
  def deleteAllForUser(user: UserId): UIO[Long]
  def deleteExpired(now: Instant): UIO[Long]

object SessionRepository:
  val postgres: URLayer[Quill.Postgres[SnakeCase], SessionRepository] =
    ZLayer.derive[PostgresSessionRepository]

final case class SessionRow(tokenHash: String, userId: Long, createdAt: Instant, expiresAt: Instant)

final class PostgresSessionRepository(quill: Quill.Postgres[SnakeCase]) extends SessionRepository:
  import quill.*

  private inline def sessions = quote(querySchema[SessionRow]("sessions"))
  private inline def users    = quote(querySchema[UserRow]("users"))

  def create(tokenHash: String, user: UserId, createdAt: Instant, expiresAt: Instant): UIO[Unit] =
    val row = SessionRow(tokenHash, user.value, createdAt, expiresAt)
    run(sessions.insertValue(lift(row))).orDie.unit

  def findUser(tokenHash: String, now: Instant): UIO[Option[AuthUser]] =
    run(
      sessions
        .join(users)
        .on((s, u) => s.userId == u.id)
        .filter((s, _) =>
          s.tokenHash == lift(tokenHash) && sql"${s.expiresAt} > ${lift(now)}".asCondition
        )
        .map((_, u) => (u.id, u.username))
    ).orDie.map(_.headOption.map((id, name) => AuthUser(UserId(id), Username.unsafe(name))))

  def delete(tokenHash: String): UIO[Unit] =
    run(sessions.filter(_.tokenHash == lift(tokenHash)).delete).orDie.unit

  def deleteAllForUser(user: UserId): UIO[Long] =
    run(sessions.filter(_.userId == lift(user.value)).delete).orDie

  def deleteExpired(now: Instant): UIO[Long] =
    run(sessions.filter(s => sql"${s.expiresAt} <= ${lift(now)}".asCondition).delete).orDie
