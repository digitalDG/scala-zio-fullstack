package com.example.todo

import com.example.todo.db.{SessionRepository, TodoRepository, UserRepository}
import com.example.todo.domain.*
import zio.*

import java.time.{Instant, LocalDate}

/** Test doubles backed by a `Ref`. They implement the same traits as the Postgres repositories, so
  * swapping them in is a one-line change to the layer graph.
  */
final class InMemoryUserRepository(state: Ref[Map[UserId, User]], ids: Ref[Long])
    extends UserRepository:

  def create(user: NewUser): IO[AppError.Conflict, User] =
    for
      users <- state.get
      _ <- ZIO.when(users.values.exists(_.username == user.username))(
        ZIO.fail(AppError.Conflict("That username is already taken"))
      )
      _ <- ZIO.when(users.values.exists(_.email == user.email))(
        ZIO.fail(AppError.Conflict("That email is already registered"))
      )
      id <- ids.updateAndGet(_ + 1).map(UserId(_))
      created = User(id, user.username, user.email, user.passwordHash, user.createdAt)
      _ <- state.update(_ + (id -> created))
    yield created

  def findByUsername(username: Username): UIO[Option[User]] =
    state.get.map(_.values.find(_.username == username))

  def findById(id: UserId): UIO[Option[User]] = state.get.map(_.get(id))

object InMemoryUserRepository:
  val layer: ULayer[UserRepository] = ZLayer {
    for
      state <- Ref.make(Map.empty[UserId, User])
      ids   <- Ref.make(0L)
    yield InMemoryUserRepository(state, ids)
  }

final class InMemoryTodoRepository(state: Ref[Map[TodoId, Todo]], ids: Ref[Long])
    extends TodoRepository:

  private def owned(owner: UserId): UIO[List[Todo]] =
    state.get.map(_.values.filter(_.ownerId == owner).toList)

  def list(owner: UserId, query: TodoQuery): UIO[Page[Todo]] =
    owned(owner).map { todos =>
      val f = query.filter
      val matching = todos.filter(t =>
        f.completed.forall(_ == t.completed) &&
          f.priority.forall(_ == t.priority) &&
          f.tag.forall(t.tags.contains) &&
          f.search.forall(s => t.title.value.toLowerCase.contains(s.toLowerCase))
      )
      val sorted = query.sort match
        case SortField.CreatedAt => matching.sortBy(t => (t.createdAt, t.id.value))
        case SortField.DueDate =>
          matching.sortBy(t => (t.dueDate.getOrElse(LocalDate.MAX), t.id.value))
        case SortField.Priority => matching.sortBy(t => (t.priority.ordinal, t.id.value))
        case SortField.Title    => matching.sortBy(t => (t.title.value, t.id.value))
      val ordered = if query.order == SortOrder.Desc then sorted.reverse else sorted
      Page(
        ordered.slice(query.page.offset, query.page.offset + query.page.size),
        matching.size.toLong,
        query.page
      )
    }

  def find(owner: UserId, id: TodoId): UIO[Option[Todo]] =
    state.get.map(_.get(id).filter(_.ownerId == owner))

  def insert(owner: UserId, d: TodoDraft, now: Instant): UIO[Todo] =
    for
      id <- ids.updateAndGet(_ + 1).map(TodoId(_))
      todo = Todo(
        id,
        owner,
        d.title,
        d.description,
        d.completed,
        d.priority,
        d.dueDate,
        d.tags,
        now,
        now
      )
      _ <- state.update(_ + (id -> todo))
    yield todo

  def update(owner: UserId, id: TodoId, d: TodoDraft, now: Instant): UIO[Option[Todo]] =
    state.modify { todos =>
      todos.get(id).filter(_.ownerId == owner) match
        case Some(existing) =>
          val updated = existing.copy(
            title = d.title,
            description = d.description,
            completed = d.completed,
            priority = d.priority,
            dueDate = d.dueDate,
            tags = d.tags,
            updatedAt = now
          )
          (Some(updated), todos + (id -> updated))
        case None => (None, todos)
    }

  def delete(owner: UserId, id: TodoId): UIO[Boolean] =
    state.modify { todos =>
      if todos.get(id).exists(_.ownerId == owner) then (true, todos - id) else (false, todos)
    }

  def stats(owner: UserId, today: LocalDate): UIO[TodoStats] =
    owned(owner).map(todos =>
      TodoStats(
        todos.size.toLong,
        todos.count(_.completed).toLong,
        todos.count(_.isOverdue(today)).toLong
      )
    )

  def tags(owner: UserId): UIO[List[TodoTag]] =
    owned(owner).map(_.flatMap(_.tags).distinct.sortBy(_.value))

object InMemoryTodoRepository:
  val layer: ULayer[TodoRepository] = ZLayer {
    for
      state <- Ref.make(Map.empty[TodoId, Todo])
      ids   <- Ref.make(0L)
    yield InMemoryTodoRepository(state, ids)
  }

final class InMemorySessionRepository(
  state: Ref[Map[String, (UserId, Instant)]],
  users: UserRepository
) extends SessionRepository:

  def create(tokenHash: String, user: UserId, createdAt: Instant, expiresAt: Instant): UIO[Unit] =
    state.update(_ + (tokenHash -> (user, expiresAt)))

  def findUser(tokenHash: String, now: Instant): UIO[Option[AuthUser]] =
    state.get.map(_.get(tokenHash).filter((_, expires) => now.isBefore(expires))).flatMap {
      case Some((id, _)) => users.findById(id).map(_.map(u => AuthUser(u.id, u.username)))
      case None          => ZIO.none
    }

  def delete(tokenHash: String): UIO[Unit] = state.update(_ - tokenHash)

  def deleteAllForUser(user: UserId): UIO[Long] =
    state.modify { sessions =>
      val (removed, kept) = sessions.partition((_, s) => s._1 == user)
      (removed.size.toLong, kept)
    }

  def deleteExpired(now: Instant): UIO[Long] =
    state.modify { sessions =>
      val (expired, kept) = sessions.partition((_, s) => !now.isBefore(s._2))
      (expired.size.toLong, kept)
    }

  /** The stored keys, so tests can check that raw tokens are never stored. */
  def storedHashes: UIO[Set[String]] = state.get.map(_.keySet)

object InMemorySessionRepository:
  val layer: URLayer[UserRepository, InMemorySessionRepository] = ZLayer {
    for
      users <- ZIO.service[UserRepository]
      state <- Ref.make(Map.empty[String, (UserId, Instant)])
    yield InMemorySessionRepository(state, users)
  }

  val asRepository: URLayer[UserRepository, SessionRepository] = ZLayer {
    for
      users <- ZIO.service[UserRepository]
      state <- Ref.make(Map.empty[String, (UserId, Instant)])
    yield (InMemorySessionRepository(state, users): SessionRepository)
  }
