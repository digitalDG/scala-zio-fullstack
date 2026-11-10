package com.example.todo.auth

import com.example.todo.config.{AppConfig, LoginConfig}
import com.example.todo.domain.AppError
import zio.*

import java.time.Instant

/** What a failed sign-in counts against. */
enum AttemptKey:
  /** Stops guessing one account's password. */
  case Account(username: String)

  /** Stops one client trying a few passwords against many accounts. */
  case Address(ip: String)

/** Limits failed sign-ins per account and per client address within a time window. */
trait LoginRateLimiter:
  /** Fails if any of the keys has used up its attempts in the current window. */
  def check(keys: List[AttemptKey]): IO[AppError.TooManyRequests, Unit]
  def recordFailure(keys: List[AttemptKey]): UIO[Unit]
  def reset(key: AttemptKey): UIO[Unit]

object LoginRateLimiter:
  final case class Window(failures: Int, startedAt: Instant)

  /** In-memory, fixed-window counters held in a `Ref`, which updates atomically, so concurrent
    * requests can't race past the limit. State is per server instance; running several instances
    * would need a shared store such as Redis.
    */
  final class InMemory(state: Ref[Map[AttemptKey, Window]], config: LoginConfig)
      extends LoginRateLimiter:

    private val window = config.windowMinutes.minutes

    private def limit(key: AttemptKey): Int = key match
      case AttemptKey.Account(_) => config.maxAttemptsPerAccount
      case AttemptKey.Address(_) => config.maxAttemptsPerIp

    private def isOpen(w: Window, now: Instant): Boolean = now.isBefore(w.startedAt.plus(window))

    def check(keys: List[AttemptKey]): IO[AppError.TooManyRequests, Unit] =
      for
        now     <- Clock.instant
        windows <- state.get
        blockedUntil = keys
          .flatMap { key =>
            windows.get(key).filter(w => isOpen(w, now) && w.failures >= limit(key))
          }
          .map(_.startedAt.plus(window))
          .maxOption
        _ <- ZIO.foreachDiscard(blockedUntil) { until =>
          val minutes = math.max(1L, (until.getEpochSecond - now.getEpochSecond + 59) / 60)
          val unit    = if minutes == 1 then "minute" else "minutes"
          ZIO.fail(
            AppError.TooManyRequests(
              s"Too many failed sign-in attempts. Try again in $minutes $unit."
            )
          )
        }
      yield ()

    def recordFailure(keys: List[AttemptKey]): UIO[Unit] =
      Clock.instant.flatMap { now =>
        state.update { windows =>
          // Dropping closed windows here keeps the map from growing without bound.
          val open = windows.filter((_, w) => isOpen(w, now))
          keys.foldLeft(open) { (acc, key) =>
            acc.updated(
              key,
              acc.get(key).fold(Window(1, now))(w => w.copy(failures = w.failures + 1))
            )
          }
        }
      }

    def reset(key: AttemptKey): UIO[Unit] = state.update(_ - key)

  val live: URLayer[AppConfig, LoginRateLimiter] = ZLayer {
    for
      config <- ZIO.serviceWith[AppConfig](_.login)
      state  <- Ref.make(Map.empty[AttemptKey, Window])
    yield InMemory(state, config)
  }
