package com.example.todo

import com.example.todo.auth.*
import com.example.todo.config.LoginConfig
import com.example.todo.db.UserRepository
import com.example.todo.domain.*
import zio.*
import zio.test.*

import java.security.SecureRandom
import java.time.Instant

object SessionSpec extends ZIOSpecDefault:

  private val newUser =
    NewUser(
      Username.unsafe("alice"),
      Email.unsafe("a@example.com"),
      PasswordHash("x"),
      Instant.EPOCH
    )

  final case class Fixture(service: SessionService, repo: InMemorySessionRepository, user: User)

  /** A session service over in-memory storage, plus a user to sign in as. */
  private val sessions =
    for
      repo <- ZIO.service[InMemorySessionRepository]
      user <- ZIO.serviceWithZIO[UserRepository](_.create(newUser))
    yield Fixture(SessionService.Live(repo, 30.minutes, SecureRandom()), repo, user)

  private val sessionLayers = InMemoryUserRepository.layer >+> InMemorySessionRepository.layer

  private def limiter(perAccount: Int = 3, perIp: Int = 10) =
    Ref
      .make(Map.empty[AttemptKey, LoginRateLimiter.Window])
      .map(LoginRateLimiter.InMemory(_, LoginConfig(perAccount, perIp, windowMinutes = 15)))

  private val alice = AttemptKey.Account("alice")
  private val bob   = AttemptKey.Account("bob")
  private val ip    = AttemptKey.Address("10.0.0.1")

  def spec = suite("Sessions and sign-in limits")(
    suite("SessionService")(
      test("a new session authenticates as its user") {
        for
          fx     <- sessions
          token  <- fx.service.create(fx.user.id)
          authed <- fx.service.authenticate(token.value)
        yield assertTrue(authed == AuthUser(fx.user.id, fx.user.username))
      },
      test("only a hash of the token is stored") {
        for
          fx     <- sessions
          token  <- fx.service.create(fx.user.id)
          stored <- fx.repo.storedHashes
        yield assertTrue(
          !stored.contains(token.value),
          stored == Set(SessionService.hash(token.value))
        )
      },
      test("sessions expire") {
        for
          fx     <- sessions
          token  <- fx.service.create(fx.user.id)
          _      <- TestClock.adjust(31.minutes)
          result <- fx.service.authenticate(token.value).either
        yield assertTrue(result.isLeft)
      },
      test("a revoked session stops working immediately") {
        for
          fx     <- sessions
          token  <- fx.service.create(fx.user.id)
          _      <- fx.service.revoke(token.value)
          result <- fx.service.authenticate(token.value).either
        yield assertTrue(result.isLeft)
      },
      test("revoking all sessions ends every device's session") {
        for
          fx      <- sessions
          laptop  <- fx.service.create(fx.user.id)
          phone   <- fx.service.create(fx.user.id)
          revoked <- fx.service.revokeAll(fx.user.id)
          a       <- fx.service.authenticate(laptop.value).either
          b       <- fx.service.authenticate(phone.value).either
        yield assertTrue(revoked == 2L, a.isLeft, b.isLeft)
      },
      test("expired sessions are purged") {
        for
          fx        <- sessions
          _         <- fx.service.create(fx.user.id)
          _         <- TestClock.adjust(31.minutes)
          live      <- fx.service.create(fx.user.id)
          purged    <- fx.service.purgeExpired
          remaining <- fx.repo.storedHashes
        yield assertTrue(purged == 1L, remaining == Set(SessionService.hash(live.value)))
      },
      test("tokens are unique and unguessably long") {
        for
          fx     <- sessions
          tokens <- ZIO.foreach(List.fill(50)(()))(_ => fx.service.create(fx.user.id))
        yield assertTrue(tokens.distinct.size == 50, tokens.forall(_.value.length >= 43))
      }
    ).provide(sessionLayers),
    suite("LoginRateLimiter")(
      test("allows attempts up to the limit, then blocks") {
        for
          l      <- limiter(perAccount = 3)
          _      <- l.recordFailure(List(alice)).repeatN(1)
          before <- l.check(List(alice)).either
          _      <- l.recordFailure(List(alice))
          after  <- l.check(List(alice)).either
        yield assertTrue(before.isRight, after.isLeft)
      },
      test("unblocks once the window has passed") {
        for
          l       <- limiter(perAccount = 1)
          _       <- l.recordFailure(List(alice))
          blocked <- l.check(List(alice)).either
          _       <- TestClock.adjust(15.minutes)
          open    <- l.check(List(alice)).either
        yield assertTrue(blocked.isLeft, open.isRight)
      },
      test("counts accounts separately, and an address across accounts") {
        for
          l     <- limiter(perAccount = 2, perIp = 3)
          _     <- l.recordFailure(List(alice, ip)).repeatN(1)
          _     <- l.recordFailure(List(bob, ip))
          bobOk <- l.check(List(bob)).either
          ipHit <- l.check(List(bob, ip)).either
        yield assertTrue(bobOk.isRight, ipHit.isLeft)
      },
      test("a successful sign-in resets the account's counter") {
        for
          l  <- limiter(perAccount = 2)
          _  <- l.recordFailure(List(alice))
          _  <- l.reset(alice)
          _  <- l.recordFailure(List(alice))
          ok <- l.check(List(alice)).either
        yield assertTrue(ok.isRight)
      },
      test("concurrent failures can't slip past the limit") {
        for
          l       <- limiter(perAccount = 5)
          _       <- ZIO.foreachParDiscard(1 to 100)(_ => l.recordFailure(List(alice)))
          blocked <- l.check(List(alice)).either
        yield assertTrue(blocked.isLeft)
      }
    )
  )
