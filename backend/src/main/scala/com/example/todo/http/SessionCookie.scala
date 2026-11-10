package com.example.todo.http

import com.example.todo.config.AppConfig
import sttp.model.headers.{Cookie, CookieValueWithMeta}
import zio.*

/** Builds the cookie that carries the session token.
  *
  *   - `HttpOnly`: page scripts can't read it, so an XSS bug can't steal the session.
  *   - `SameSite=Strict`: the browser never sends it on requests started by another site, which
  *     blocks cross-site request forgery.
  *   - `Secure`: only sent over HTTPS (browsers treat `localhost` as secure, so this works in local
  *     development too).
  *   - `Path=/api`: not sent with requests for pages and static files.
  *   - `Max-Age` matches the session lifetime, so the cookie and the session expire together.
  */
final class SessionCookie(secure: Boolean, ttl: Duration):
  def issue(token: String): CookieValueWithMeta = build(token, ttl.toSeconds)

  /** Overwrites the cookie with an empty, already-expired one. */
  val cleared: CookieValueWithMeta = build("", 0)

  private def build(value: String, maxAgeSeconds: Long): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value,
      maxAge = Some(maxAgeSeconds),
      path = Some("/api"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(Cookie.SameSite.Strict)
    )

object SessionCookie:
  val Name = "todo_session"

  val layer: URLayer[AppConfig, SessionCookie] = ZLayer.fromFunction { (config: AppConfig) =>
    SessionCookie(config.http.secureCookies, config.session.ttlMinutes.minutes)
  }
