package com.example.todo.auth

import com.example.todo.db.UserRepository
import com.example.todo.domain.*
import zio.*

final case class AuthResult(user: User, session: SessionToken)

trait AuthService:
  def register(registration: Registration): IO[AppError.Conflict, AuthResult]

  /** The error type is a union: a sign-in fails either because the credentials are wrong or because
    * there have been too many failed attempts, and callers can tell which.
    */
  def login(
    username: String,
    password: String,
    clientIp: Option[String]
  ): IO[AppError.Unauthorized | AppError.TooManyRequests, AuthResult]

  def authenticate(sessionToken: String): IO[AppError.Unauthorized, AuthUser]
  def currentUser(user: AuthUser): IO[AppError.Unauthorized, User]
  def logout(sessionToken: String): UIO[Unit]
  def logoutEverywhere(user: AuthUser): UIO[Unit]

object AuthService:
  final class Live(
    users: UserRepository,
    hasher: PasswordHasher,
    sessions: SessionService,
    limiter: LoginRateLimiter,
    dummyHash: PasswordHash
  ) extends AuthService:

    private val invalidCredentials = AppError.Unauthorized("Invalid username or password")

    def register(registration: Registration): IO[AppError.Conflict, AuthResult] =
      for
        hash    <- hasher.hash(registration.password)
        now     <- Clock.instant
        user    <- users.create(NewUser(registration.username, registration.email, hash, now))
        session <- sessions.create(user.id)
      yield AuthResult(user, session)

    def login(
      username: String,
      password: String,
      clientIp: Option[String]
    ): IO[AppError.Unauthorized | AppError.TooManyRequests, AuthResult] =
      val account = AttemptKey.Account(username.trim.toLowerCase)
      val keys    = account :: clientIp.map(AttemptKey.Address(_)).toList
      for
        // Checked before the password, so a locked-out caller learns nothing, even when correct.
        _ <- limiter.check(keys)
        user <- verify(username, password)
          .tapError(_ => limiter.recordFailure(keys))
        _       <- limiter.reset(account)
        session <- sessions.create(user.id)
      yield AuthResult(user, session)

    private def verify(username: String, password: String): IO[AppError.Unauthorized, User] =
      for
        found <- Username.make(username) match
          case Right(name) => users.findByUsername(name)
          case Left(_)     => ZIO.none
        // Check a password even when the user doesn't exist, so response time doesn't reveal
        // which usernames are registered.
        matches <- hasher.verify(password, found.fold(dummyHash)(_.passwordHash))
        user    <- ZIO.fromOption(found.filter(_ => matches)).orElseFail(invalidCredentials)
      yield user

    def authenticate(sessionToken: String): IO[AppError.Unauthorized, AuthUser] =
      sessions.authenticate(sessionToken)

    def currentUser(user: AuthUser): IO[AppError.Unauthorized, User] =
      users.findById(user.id).someOrFail(AppError.Unauthorized("Account no longer exists"))

    def logout(sessionToken: String): UIO[Unit] = sessions.revoke(sessionToken)

    def logoutEverywhere(user: AuthUser): UIO[Unit] = sessions.revokeAll(user.id).unit

  /** Building the service is itself an effect: it hashes a throwaway password once at startup. */
  val live: URLayer[
    UserRepository & PasswordHasher & SessionService & LoginRateLimiter,
    AuthService
  ] =
    ZLayer.fromZIO {
      for
        users    <- ZIO.service[UserRepository]
        hasher   <- ZIO.service[PasswordHasher]
        sessions <- ZIO.service[SessionService]
        limiter  <- ZIO.service[LoginRateLimiter]
        dummy <- PlainPassword.make("timing-equalizer-password") match
          case Right(password) => hasher.hash(password)
          case Left(errors)    => ZIO.dieMessage(s"Invalid dummy password: $errors")
      yield Live(users, hasher, sessions, limiter, dummy)
    }
