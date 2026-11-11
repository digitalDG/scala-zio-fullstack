package com.example.todo.demo

import com.example.todo.auth.{AuthResult, PasswordHasher, SessionService}
import com.example.todo.config.{AppConfig, DemoConfig}
import com.example.todo.db.{TodoRepository, UserRepository}
import com.example.todo.domain.*
import zio.*

import java.security.SecureRandom
import java.time.{LocalDate, ZoneOffset}
import java.util.Base64

/** A shared demo account that visitors enter with one click, filled with sample todos and reset on
  * a schedule.
  *
  * Nobody can sign in to it with a password: each reset gives it a new random password that is
  * never stored or shown. That means the only way in is [[signIn]], and the sign-in lockout (which
  * counts failed passwords) can't be used to lock visitors out of it.
  */
trait DemoService:
  def enabled: Boolean
  def resetIntervalHours: Int
  def isDemo(username: Username): Boolean
  def signIn: IO[AppError.NotFound, AuthResult]
  def reset: UIO[Unit]

  /** Resets the account now, then again on every interval, until the enclosing scope closes. */
  def start: URIO[Scope, Unit]

object DemoService:
  final class Live(
    config: DemoConfig,
    username: Username,
    users: UserRepository,
    todos: TodoRepository,
    hasher: PasswordHasher,
    sessions: SessionService,
    random: SecureRandom
  ) extends DemoService:

    def enabled: Boolean        = config.enabled
    def resetIntervalHours: Int = config.resetIntervalHours

    def isDemo(name: Username): Boolean = enabled && name == username

    def signIn: IO[AppError.NotFound, AuthResult] =
      for
        _ <- ZIO.fail(AppError.NotFound("The demo account is not enabled")).unless(enabled)
        // Normally the startup reset has created the account; if not, create and fill it now.
        user    <- users.findByUsername(username).someOrElseZIO(resetAccount)
        session <- sessions.create(user.id)
      yield AuthResult(user, session)

    def reset: UIO[Unit] = ZIO.when(enabled)(resetAccount).unit

    private def resetAccount: UIO[User] =
      for
        user <- ensureUser
        _    <- todos.deleteAll(user.id)
        now  <- Clock.instant
        today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        // Insert newest-last with staggered timestamps, so "Date added" lists them in this order.
        samples = SampleTodos(today).reverse
        _ <- ZIO.foreachDiscard(samples.zipWithIndex) { (draft, i) =>
          todos.insert(user.id, draft, now.plusMillis(i.toLong))
        }
        _ <- ZIO.logInfo(s"Demo account '${username.value}' reset with ${samples.size} todos")
      yield user

    def start: URIO[Scope, Unit] =
      ZIO
        .when(enabled) {
          val safeReset =
            reset.catchAllCause(cause => ZIO.logErrorCause("Demo reset failed", cause))
          safeReset *> safeReset
            .schedule(Schedule.spaced(config.resetIntervalHours.hours))
            .forkScoped
        }
        .unit

    /** Finds or creates the demo user, and gives it a fresh, unknowable password. */
    private def ensureUser: UIO[User] =
      for
        hash     <- randomPasswordHash
        existing <- users.findByUsername(username)
        user <- existing match
          case Some(user) => users.setPasswordHash(user.id, hash).as(user.copy(passwordHash = hash))
          case None =>
            Clock.instant.flatMap { now =>
              users
                .create(
                  NewUser(username, Email.unsafe(s"${username.value}@example.com"), hash, now)
                )
                .orDieWith(conflict => IllegalStateException(conflict.message))
            }
      yield user

    private def randomPasswordHash: UIO[PasswordHash] =
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      PlainPassword.make(Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)) match
        case Right(password) => hasher.hash(password)
        case Left(errors)    => ZIO.dieMessage(s"Generated an invalid demo password: $errors")

  val live: URLayer[
    AppConfig & UserRepository & TodoRepository & PasswordHasher & SessionService,
    DemoService
  ] = ZLayer.fromZIO {
    for
      config <- ZIO.serviceWith[AppConfig](_.demo)
      username <- ZIO
        .fromEither(Username.make(config.username))
        .orDieWith(_ => IllegalArgumentException(s"DEMO_USERNAME '${config.username}' is invalid"))
      users    <- ZIO.service[UserRepository]
      todos    <- ZIO.service[TodoRepository]
      hasher   <- ZIO.service[PasswordHasher]
      sessions <- ZIO.service[SessionService]
    yield Live(config, username, users, todos, hasher, sessions, SecureRandom())
  }
