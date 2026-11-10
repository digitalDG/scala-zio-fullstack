package com.example.todo.todo

import com.example.todo.db.TodoRepository
import com.example.todo.domain.*
import zio.*

import java.time.LocalDate

/** Todo use cases. Every operation is scoped to an owner, so one user can never read or change
  * another user's todos: a todo that belongs to someone else is reported as not found.
  */
trait TodoService:
  def list(owner: UserId, query: TodoQuery): UIO[Page[Todo]]
  def get(owner: UserId, id: TodoId): IO[AppError.NotFound, Todo]
  def create(owner: UserId, draft: TodoDraft): UIO[Todo]
  def update(owner: UserId, id: TodoId, draft: TodoDraft): IO[AppError.NotFound, Todo]
  def setCompleted(owner: UserId, id: TodoId, completed: Boolean): IO[AppError.NotFound, Todo]
  def delete(owner: UserId, id: TodoId): IO[AppError.NotFound, Unit]
  def stats(owner: UserId, today: LocalDate): UIO[TodoStats]
  def tags(owner: UserId): UIO[List[TodoTag]]

object TodoService:
  final class Live(repo: TodoRepository) extends TodoService:
    def list(owner: UserId, query: TodoQuery): UIO[Page[Todo]] =
      repo.list(owner, query)

    def get(owner: UserId, id: TodoId): IO[AppError.NotFound, Todo] =
      repo.find(owner, id).someOrFail(AppError.todoNotFound(id))

    def create(owner: UserId, draft: TodoDraft): UIO[Todo] =
      Clock.instant.flatMap(now => repo.insert(owner, draft, now))

    def update(owner: UserId, id: TodoId, draft: TodoDraft): IO[AppError.NotFound, Todo] =
      Clock.instant
        .flatMap(now => repo.update(owner, id, draft, now))
        .someOrFail(AppError.todoNotFound(id))

    def setCompleted(owner: UserId, id: TodoId, completed: Boolean): IO[AppError.NotFound, Todo] =
      get(owner, id).flatMap { todo =>
        if todo.completed == completed then ZIO.succeed(todo)
        else update(owner, id, todo.toDraft.copy(completed = completed))
      }

    def delete(owner: UserId, id: TodoId): IO[AppError.NotFound, Unit] =
      ZIO.unlessZIO(repo.delete(owner, id))(ZIO.fail(AppError.todoNotFound(id))).unit

    def stats(owner: UserId, today: LocalDate): UIO[TodoStats] =
      repo.stats(owner, today)

    def tags(owner: UserId): UIO[List[TodoTag]] =
      repo.tags(owner)

  val live: URLayer[TodoRepository, TodoService] = ZLayer.derive[Live]
