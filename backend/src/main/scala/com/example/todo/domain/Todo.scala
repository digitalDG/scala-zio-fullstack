package com.example.todo.domain

import java.time.{Instant, LocalDate}

enum Priority:
  case Low, Medium, High

object Priority extends LabeledEnum[Priority]:
  def label(value: Priority): String = value.toString.toLowerCase
  protected def typeName             = "Priority"

/** A todo title: 1 to 200 characters after trimming. */
opaque type Title = String

object Title:
  val MaxLength = 200

  def make(raw: String): Validated[Title] =
    val trimmed = raw.trim
    Validated.check(
      trimmed.nonEmpty && trimmed.length <= MaxLength,
      "title",
      s"must be 1-$MaxLength characters"
    )(trimmed)

  def unsafe(value: String): Title = value

  extension (title: Title) def value: String = title

/** A lowercase tag of 1 to 30 letters, digits, `-` or `_`. */
opaque type TodoTag = String

object TodoTag:
  private val Pattern = "^[a-z0-9_-]{1,30}$".r
  val MaxPerTodo      = 10

  def make(raw: String): Validated[TodoTag] =
    val normalized = raw.trim.toLowerCase
    Validated.check(
      Pattern.matches(normalized),
      "tags",
      s"'$raw' is not a valid tag: use 1-30 letters, digits, '-' or '_'"
    )(normalized)

  /** Validates every tag, removes duplicates and enforces the per-todo limit. */
  def makeAll(raw: List[String]): Validated[List[TodoTag]] =
    Validated.traverse(raw)(make).flatMap { tags =>
      val distinct = tags.distinct
      Validated.check(
        distinct.size <= MaxPerTodo,
        "tags",
        s"a todo can have at most $MaxPerTodo tags"
      )(distinct)
    }

  def unsafe(value: String): TodoTag = value

  extension (tag: TodoTag) def value: String = tag

object Description:
  val MaxLength = 2000

  /** Blank descriptions are treated as absent. */
  def make(raw: Option[String]): Validated[Option[String]] =
    val cleaned = raw.map(_.trim).filter(_.nonEmpty)
    Validated.check(
      cleaned.forall(_.length <= MaxLength),
      "description",
      s"must be at most $MaxLength characters"
    )(cleaned)

final case class Todo(
  id: TodoId,
  ownerId: UserId,
  title: Title,
  description: Option[String],
  completed: Boolean,
  priority: Priority,
  dueDate: Option[LocalDate],
  tags: List[TodoTag],
  createdAt: Instant,
  updatedAt: Instant
):
  def isOverdue(today: LocalDate): Boolean = !completed && dueDate.exists(_.isBefore(today))

  def toDraft: TodoDraft = TodoDraft(title, description, priority, dueDate, tags, completed)

/** Validated, user-editable fields of a todo, used for both create and update. */
final case class TodoDraft(
  title: Title,
  description: Option[String],
  priority: Priority,
  dueDate: Option[LocalDate],
  tags: List[TodoTag],
  completed: Boolean
)

final case class TodoStats(total: Long, completed: Long, overdue: Long):
  def active: Long = total - completed
