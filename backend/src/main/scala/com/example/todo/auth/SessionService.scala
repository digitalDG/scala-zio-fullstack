package com.example.todo.auth

import com.example.todo.config.AppConfig
import com.example.todo.db.SessionRepository
import com.example.todo.domain.*
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, HexFormat}

/** A session token as handed to the browser. Redacted in `toString` so it never reaches logs. */
final case class SessionToken(value: String):
  override def toString: String = "SessionToken(***)"

/** Server-side sessions.
  *
  * A session is a random 256-bit token. The browser keeps the token (in an HttpOnly cookie); the
  * database keeps only its SHA-256 hash. Because every request is checked against the database,
  * signing out takes effect immediately, and all of a user's sessions can be ended at once.
  */
trait SessionService:
  def create(user: UserId): UIO[SessionToken]
  def authenticate(token: String): IO[AppError.Unauthorized, AuthUser]
  def revoke(token: String): UIO[Unit]
  def revokeAll(user: UserId): UIO[Long]
  def purgeExpired: UIO[Long]

object SessionService:
  final class Live(repo: SessionRepository, ttl: Duration, random: SecureRandom)
      extends SessionService:

    def create(user: UserId): UIO[SessionToken] =
      for
        token <- ZIO.succeed(newToken())
        now   <- Clock.instant
        _     <- repo.create(hash(token.value), user, now, now.plus(ttl))
      yield token

    def authenticate(token: String): IO[AppError.Unauthorized, AuthUser] =
      for
        now <- Clock.instant
        user <- repo
          .findUser(hash(token), now)
          .someOrFail(AppError.Unauthorized("Your session has ended. Please sign in again."))
      yield user

    def revoke(token: String): UIO[Unit]   = repo.delete(hash(token))
    def revokeAll(user: UserId): UIO[Long] = repo.deleteAllForUser(user)
    def purgeExpired: UIO[Long]            = Clock.instant.flatMap(repo.deleteExpired)

    private def newToken(): SessionToken =
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      SessionToken(Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))

  /** Tokens are long and random, so a fast hash is enough; no salt or slow hash is needed. */
  def hash(token: String): String =
    HexFormat.of.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)))

  /** Also starts a background fiber that deletes expired sessions every hour. The fiber is tied to
    * the layer's scope, so it stops automatically when the app shuts down.
    */
  val live: URLayer[SessionRepository & AppConfig, SessionService] = ZLayer.scoped {
    for
      repo   <- ZIO.service[SessionRepository]
      config <- ZIO.service[AppConfig]
      service = Live(repo, config.session.ttlMinutes.minutes, SecureRandom())
      _ <- service.purgeExpired
        .flatMap(n => ZIO.logInfo(s"Deleted $n expired sessions").when(n > 0))
        .catchAllCause(cause => ZIO.logErrorCause("Session cleanup failed", cause))
        .schedule(Schedule.spaced(1.hour))
        .forkScoped
    yield service
  }
