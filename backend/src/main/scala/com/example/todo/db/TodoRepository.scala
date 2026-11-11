package com.example.todo.db

import com.example.todo.domain.*
import io.getquill.*
import io.getquill.jdbczio.Quill
import zio.*

import java.time.{Instant, LocalDate}

trait TodoRepository:
  def list(owner: UserId, query: TodoQuery): UIO[Page[Todo]]
  def find(owner: UserId, id: TodoId): UIO[Option[Todo]]
  def insert(owner: UserId, draft: TodoDraft, now: Instant): UIO[Todo]
  def update(owner: UserId, id: TodoId, draft: TodoDraft, now: Instant): UIO[Option[Todo]]
  def delete(owner: UserId, id: TodoId): UIO[Boolean]
  def deleteAll(owner: UserId): UIO[Long]
  def stats(owner: UserId, today: LocalDate): UIO[TodoStats]
  def tags(owner: UserId): UIO[List[TodoTag]]

object TodoRepository:
  val postgres: URLayer[Quill.Postgres[SnakeCase], TodoRepository] =
    ZLayer.derive[PostgresTodoRepository]

final case class TodoRow(
  id: Long,
  ownerId: Long,
  title: String,
  description: Option[String],
  completed: Boolean,
  priority: Int,
  dueDate: Option[LocalDate],
  tags: List[String],
  createdAt: Instant,
  updatedAt: Instant
):
  def toDomain: Todo =
    Todo(
      TodoId(id),
      UserId(ownerId),
      Title.unsafe(title),
      description,
      completed,
      Priority.fromOrdinal(priority),
      dueDate,
      tags.map(TodoTag.unsafe),
      createdAt,
      updatedAt
    )

/** Postgres implementation using Quill, which turns the Scala queries below into SQL.
  *
  * Database errors are unexpected here (every input has already been validated), so they're
  * converted to defects with `orDie` rather than appearing in the method signatures.
  */
final class PostgresTodoRepository(quill: Quill.Postgres[SnakeCase]) extends TodoRepository:
  import quill.*

  private inline def todos = quote(querySchema[TodoRow]("todos"))

  def list(owner: UserId, query: TodoQuery): UIO[Page[Todo]] =
    val f         = query.filter
    val completed = f.completed
    val priority  = f.priority.map(_.ordinal)
    val tag       = f.tag.map(_.value)
    val search    = f.search.map(s => s"%${escapeLike(s)}%")

    val filtered = quote {
      todos.filter(t =>
        t.ownerId == lift(owner.value) &&
          sql"(${lift(completed)}::boolean IS NULL OR ${t.completed} = ${lift(completed)})".asCondition &&
          sql"(${lift(priority)}::int IS NULL OR ${t.priority} = ${lift(priority)})".asCondition &&
          sql"(${lift(tag)}::text IS NULL OR ${lift(tag)} = ANY(${t.tags}))".asCondition &&
          sql"(${lift(search)}::text IS NULL OR ${t.title} ILIKE ${lift(search)})".asCondition
      )
    }

    val asc = query.order == SortOrder.Asc

    // Each sort is a separate, fully static query. `id` breaks ties so paging is stable.
    val sorted = query.sort match
      case SortField.CreatedAt =>
        if asc then quote(filtered.sortBy(t => (t.createdAt, t.id))(Ord(Ord.asc, Ord.asc)))
        else quote(filtered.sortBy(t => (t.createdAt, t.id))(Ord(Ord.desc, Ord.desc)))
      case SortField.DueDate =>
        if asc then quote(filtered.sortBy(t => (t.dueDate, t.id))(Ord(Ord.ascNullsLast, Ord.asc)))
        else quote(filtered.sortBy(t => (t.dueDate, t.id))(Ord(Ord.descNullsLast, Ord.desc)))
      case SortField.Priority =>
        if asc then quote(filtered.sortBy(t => (t.priority, t.id))(Ord(Ord.asc, Ord.asc)))
        else quote(filtered.sortBy(t => (t.priority, t.id))(Ord(Ord.desc, Ord.desc)))
      case SortField.Title =>
        if asc then quote(filtered.sortBy(t => (t.title, t.id))(Ord(Ord.asc, Ord.asc)))
        else quote(filtered.sortBy(t => (t.title, t.id))(Ord(Ord.desc, Ord.desc)))

    val offset = query.page.offset
    val size   = query.page.size

    val items = run(quote(sorted.drop(lift(offset)).take(lift(size))))
    val total = run(quote(filtered.size))

    (items <*> total).orDie.map((rows, count) => Page(rows.map(_.toDomain), count, query.page))

  def find(owner: UserId, id: TodoId): UIO[Option[Todo]] =
    run(todos.filter(t => t.id == lift(id.value) && t.ownerId == lift(owner.value))).orDie
      .map(_.headOption.map(_.toDomain))

  def insert(owner: UserId, draft: TodoDraft, now: Instant): UIO[Todo] =
    val row = TodoRow(
      id = 0L,
      ownerId = owner.value,
      title = draft.title.value,
      description = draft.description,
      completed = draft.completed,
      priority = draft.priority.ordinal,
      dueDate = draft.dueDate,
      tags = draft.tags.map(_.value),
      createdAt = now,
      updatedAt = now
    )
    run(todos.insertValue(lift(row)).returningGenerated(_.id)).orDie
      .map(id => row.copy(id = id).toDomain)

  def update(owner: UserId, id: TodoId, draft: TodoDraft, now: Instant): UIO[Option[Todo]] =
    val title    = draft.title.value
    val priority = draft.priority.ordinal
    val tags     = draft.tags.map(_.value)
    run(
      todos
        .filter(t => t.id == lift(id.value) && t.ownerId == lift(owner.value))
        .update(
          _.title       -> lift(title),
          _.description -> lift(draft.description),
          _.completed   -> lift(draft.completed),
          _.priority    -> lift(priority),
          _.dueDate     -> lift(draft.dueDate),
          _.tags        -> lift(tags),
          _.updatedAt   -> lift(now)
        )
    ).orDie.flatMap(updated => if updated == 0 then ZIO.none else find(owner, id))

  def delete(owner: UserId, id: TodoId): UIO[Boolean] =
    run(todos.filter(t => t.id == lift(id.value) && t.ownerId == lift(owner.value)).delete).orDie
      .map(_ > 0)

  def deleteAll(owner: UserId): UIO[Long] =
    run(todos.filter(_.ownerId == lift(owner.value)).delete).orDie

  def stats(owner: UserId, today: LocalDate): UIO[TodoStats] =
    val total     = run(todos.filter(_.ownerId == lift(owner.value)).size)
    val completed = run(todos.filter(t => t.ownerId == lift(owner.value) && t.completed).size)
    val overdue = run(
      todos
        .filter(t =>
          t.ownerId == lift(owner.value) && !t.completed &&
            sql"${t.dueDate} < ${lift(today)}".asCondition
        )
        .size
    )
    (total <*> completed <*> overdue).orDie.map(TodoStats.apply)

  def tags(owner: UserId): UIO[List[TodoTag]] =
    run(todos.filter(_.ownerId == lift(owner.value)).concatMap(_.tags).distinct).orDie
      .map(_.sorted.map(TodoTag.unsafe))

  /** Escapes LIKE wildcards so a search for "100%" matches literally. */
  private def escapeLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
