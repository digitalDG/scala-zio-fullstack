package com.example.todo.demo

import com.example.todo.domain.*

import java.time.LocalDate

/** The demo account's sample todos. Due dates are relative to `today` (UTC), so after every reset
  * there is something overdue and something coming up.
  *
  * Nothing is due "today": visitors' local dates differ from UTC by up to a day, so a todo due
  * today in UTC would show as tomorrow or yesterday for many of them. Two days back is overdue
  * everywhere, and the future dates are in the future everywhere.
  */
object SampleTodos:
  def apply(today: LocalDate): List[TodoDraft] = List(
    todo(
      "Explore the demo",
      Priority.High,
      tags = List("getting-started"),
      description = Some(
        "This is a shared account with sample data. It resets on a schedule, so feel free to " +
          "add, edit, complete or delete anything."
      )
    ),
    todo(
      "Try the filters",
      Priority.Medium,
      tags = List("getting-started"),
      description = Some(
        "Click a #tag, search titles, or sort by due date. The URL keeps your view, so you can " +
          "bookmark it."
      )
    ),
    todo(
      "Ship the release notes",
      Priority.High,
      due = Some(today.minusDays(2)),
      tags = List("work")
    ),
    todo(
      "Review pull requests",
      Priority.Medium,
      due = Some(today.plusDays(1)),
      tags = List("work")
    ),
    todo(
      "Write a blog post about ZIO layers",
      Priority.Medium,
      due = Some(today.plusDays(7)),
      tags = List("writing", "scala")
    ),
    todo(
      "Renew passport",
      Priority.Low,
      due = Some(today.plusDays(21)),
      tags = List("home", "errands")
    ),
    todo("Buy groceries", Priority.Low, tags = List("home"), completed = true),
    todo("Set up the project", Priority.Medium, tags = List("work"), completed = true)
  )

  private def todo(
    title: String,
    priority: Priority,
    due: Option[LocalDate] = None,
    tags: List[String] = Nil,
    description: Option[String] = None,
    completed: Boolean = false
  ): TodoDraft =
    TodoDraft(Title.unsafe(title), description, priority, due, tags.map(TodoTag.unsafe), completed)
