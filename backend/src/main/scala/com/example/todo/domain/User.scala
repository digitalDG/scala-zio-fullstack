package com.example.todo.domain

import java.time.Instant

/** A lowercase login name of 3 to 32 letters, digits or underscores. */
opaque type Username = String

object Username:
  private val Pattern = "^[a-z0-9_]{3,32}$".r

  def make(raw: String): Validated[Username] =
    val normalized = raw.trim.toLowerCase
    Validated.check(
      Pattern.matches(normalized),
      "username",
      "must be 3-32 characters: letters, digits or underscores"
    )(normalized)

  /** For values that are already known to be valid, such as rows read from the database. */
  def unsafe(value: String): Username = value

  extension (username: Username) def value: String = username

/** A syntactically plausible, lowercased email address. */
opaque type Email = String

object Email:
  private val Pattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  def make(raw: String): Validated[Email] =
    val normalized = raw.trim.toLowerCase
    Validated.check(
      normalized.length <= 254 && Pattern.matches(normalized),
      "email",
      "must be a valid email address"
    )(normalized)

  def unsafe(value: String): Email = value

  extension (email: Email) def value: String = email

/** A password as typed by the user. `toString` is redacted so it can't leak into logs. */
final case class PlainPassword private (value: String):
  override def toString: String = "PlainPassword(***)"

object PlainPassword:
  /** bcrypt only uses the first 72 bytes of input, so longer passwords are rejected. */
  def make(raw: String): Validated[PlainPassword] =
    Validated.check(
      raw.length >= 8 && raw.getBytes("UTF-8").length <= 72,
      "password",
      "must be 8-72 characters"
    )(PlainPassword(raw))

/** A bcrypt hash of a password. */
opaque type PasswordHash = String

object PasswordHash:
  def apply(value: String): PasswordHash = value

  extension (hash: PasswordHash) def value: String = hash

final case class User(
  id: UserId,
  username: Username,
  email: Email,
  passwordHash: PasswordHash,
  createdAt: Instant
)

final case class NewUser(
  username: Username,
  email: Email,
  passwordHash: PasswordHash,
  createdAt: Instant
)

/** Validated input for creating an account. */
final case class Registration(username: Username, email: Email, password: PlainPassword)

/** The identity carried by a valid access token. */
final case class AuthUser(id: UserId, username: Username)
