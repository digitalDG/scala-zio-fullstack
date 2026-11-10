package com.example.todo

import com.example.todo.domain.*
import com.example.todo.http.{RegisterRequest, TodoRequest}
import zio.test.*

object ValidationSpec extends ZIOSpecDefault:

  private def fields[A](v: Validated[A]): Set[String] =
    v.left.toOption.map(_.map(_.field).toSet).getOrElse(Set.empty)

  def spec = suite("Validation")(
    test("usernames are trimmed and lowercased") {
      assertTrue(Username.make("  Alice_01 ").map(_.value) == Right("alice_01"))
    },
    test("usernames with invalid characters or length are rejected") {
      assertTrue(
        Username.make("ab").isLeft,
        Username.make("has space").isLeft,
        Username.make("x" * 33).isLeft
      )
    },
    test("emails are normalized and checked") {
      assertTrue(
        Email.make(" Bob@Example.COM ").map(_.value) == Right("bob@example.com"),
        Email.make("not-an-email").isLeft
      )
    },
    test("passwords are never printed") {
      val password = PlainPassword.make("correct horse battery").toOption.get
      assertTrue(!password.toString.contains("horse"))
    },
    test("tags are normalized, de-duplicated and limited") {
      assertTrue(
        TodoTag.makeAll(List("Work", "work", " home ")).map(_.map(_.value)) ==
          Right(List("work", "home")),
        TodoTag.makeAll(List.tabulate(11)(i => s"t$i")).isLeft
      )
    },
    test("registration reports every invalid field at once") {
      val result = RegisterRequest("x", "nope", "short").validated
      assertTrue(fields(result) == Set("username", "email", "password"))
    },
    test("todo requests accumulate errors across title, description and tags") {
      val request = TodoRequest(
        title = "   ",
        description = Some("d" * (Description.MaxLength + 1)),
        priority = Priority.High,
        dueDate = None,
        tags = List("ok", "not ok!"),
        completed = false
      )
      assertTrue(fields(request.validated) == Set("title", "description", "tags"))
    },
    test("blank descriptions become None") {
      val request = TodoRequest("Title", Some("   "), Priority.Low, None, Nil, false)
      assertTrue(request.validated.map(_.description) == Right(None))
    }
  )
