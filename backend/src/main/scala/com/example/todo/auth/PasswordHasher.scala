package com.example.todo.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.example.todo.domain.{PasswordHash, PlainPassword}
import zio.*

trait PasswordHasher:
  def hash(password: PlainPassword): UIO[PasswordHash]
  def verify(password: String, hash: PasswordHash): UIO[Boolean]

object PasswordHasher:
  /** bcrypt is deliberately slow, so it runs on ZIO's blocking thread pool. */
  final class Bcrypt(cost: Int) extends PasswordHasher:
    def hash(password: PlainPassword): UIO[PasswordHash] =
      ZIO
        .attemptBlocking(BCrypt.withDefaults().hashToString(cost, password.value.toCharArray))
        .orDie
        .map(PasswordHash(_))

    def verify(password: String, hash: PasswordHash): UIO[Boolean] =
      ZIO
        .attemptBlocking(BCrypt.verifyer().verify(password.toCharArray, hash.value).verified)
        .orDie

  val live: ULayer[PasswordHasher] = ZLayer.succeed(Bcrypt(cost = 12))

  /** The minimum bcrypt cost, so tests stay fast. */
  val fast: ULayer[PasswordHasher] = ZLayer.succeed(Bcrypt(cost = 4))
