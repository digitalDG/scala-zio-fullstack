package com.example.todo

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.example.todo.config.*
import com.example.todo.db.*
import com.example.todo.domain.*
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.time.{Instant, LocalDate}
import scala.util.Try

/** Runs the real SQL against a throwaway Postgres in Docker, so the Quill queries, migrations and
  * constraints are all tested for real. Skipped when Docker isn't available.
  */
object PostgresRepositorySpec extends ZIOSpecDefault:

  private val dockerAvailable =
    Try(DockerClientFactory.instance().isDockerAvailable).getOrElse(false)

  private val postgres: TaskLayer[AppConfig] = ZLayer.scoped {
    ZIO
      .acquireRelease(ZIO.attemptBlocking {
        val container = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        container.start()
        container
      })(container => ZIO.attemptBlocking(container.stop()).orDie)
      .map(c =>
        AppConfig(
          HttpConfig(),
          DbConfig(c.jdbcUrl, c.username, Config.Secret(c.password), maxPoolSize = 4),
          SessionConfig(),
          LoginConfig(),
          DemoConfig()
        )
      )
  }

  private val migrated: RLayer[javax.sql.DataSource, Unit] =
    Migrations.layer >>> ZLayer(ZIO.serviceWithZIO[Migrations](_.migrate))

  private val now   = Instant.parse("2026-01-15T12:00:00Z")
  private val today = LocalDate.of(2026, 1, 15)

  private def newUser(name: String) =
    NewUser(Username.unsafe(name), Email.unsafe(s"$name@example.com"), PasswordHash("hash"), now)

  private def draft(
    title: String,
    priority: Priority = Priority.Medium,
    tags: List[String] = Nil,
    due: Option[LocalDate] = None,
    completed: Boolean = false
  ) = TodoDraft(Title.unsafe(title), None, priority, due, tags.map(TodoTag.unsafe), completed)

  def spec = suite("Postgres repositories")(
    test("users are created and found; duplicates are conflicts") {
      for
        repo  <- ZIO.service[UserRepository]
        alice <- repo.create(newUser("pg_alice"))
        found <- repo.findByUsername(Username.unsafe("pg_alice"))
        dupe  <- repo.create(newUser("pg_alice")).either
        email <- repo
          .create(newUser("pg_alice").copy(username = Username.unsafe("pg_alice2")))
          .either
      yield assertTrue(
        found.contains(alice),
        dupe == Left(AppError.Conflict("That username is already taken")),
        email == Left(AppError.Conflict("That email is already registered"))
      )
    },
    test("todos round-trip every field, including tags and dates") {
      for
        users <- ZIO.service[UserRepository]
        repo  <- ZIO.service[TodoRepository]
        owner <- users.create(newUser("pg_bob"))
        draftValue = draft("Round trip", Priority.High, List("a", "b"), Some(today))
          .copy(description = Some("details"))
        saved <- repo.insert(owner.id, draftValue, now)
        found <- repo.find(owner.id, saved.id)
      yield assertTrue(found.contains(saved), saved.toDraft == draftValue)
    },
    test("filters, search, sorting and paging run as SQL") {
      for
        users <- ZIO.service[UserRepository]
        repo  <- ZIO.service[TodoRepository]
        owner <- users.create(newUser("pg_carol"))
        _ <- repo.insert(
          owner.id,
          draft("Buy milk", Priority.Low, List("home"), Some(today.plusDays(2))),
          now
        )
        _ <- repo.insert(owner.id, draft("Ship 100% done", Priority.High, List("work")), now)
        _ <- repo.insert(
          owner.id,
          draft("Review PR", Priority.Medium, List("work"), Some(today)),
          now
        )
        _ <- repo.insert(
          owner.id,
          draft("Pay rent", Priority.High, List("home"), completed = true),
          now
        )
        byTag   <- repo.list(owner.id, TodoQuery(TodoFilter(tag = Some(TodoTag.unsafe("work")))))
        open    <- repo.list(owner.id, TodoQuery(TodoFilter(completed = Some(false))))
        literal <- repo.list(owner.id, TodoQuery(TodoFilter(search = Some("100%"))))
        byPriority <- repo.list(
          owner.id,
          TodoQuery(sort = SortField.Priority, order = SortOrder.Desc)
        )
        byDue <- repo.list(owner.id, TodoQuery(sort = SortField.DueDate, order = SortOrder.Asc))
        paged <- repo.list(
          owner.id,
          TodoQuery(sort = SortField.Title, order = SortOrder.Asc, page = PageRequest(2, 3))
        )
      yield assertTrue(
        byTag.items.map(_.title.value).toSet == Set("Ship 100% done", "Review PR"),
        open.total == 3L,
        literal.items.map(_.title.value) == List("Ship 100% done"),
        byPriority.items.head.priority == Priority.High,
        byDue.items.map(_.title.value).take(2) == List("Review PR", "Buy milk"),
        paged.items.map(_.title.value) == List("Ship 100% done"),
        paged.total == 4L
      )
    },
    test("update, delete, stats and tags are scoped to the owner") {
      for
        users <- ZIO.service[UserRepository]
        repo  <- ZIO.service[TodoRepository]
        owner <- users.create(newUser("pg_dave"))
        other <- users.create(newUser("pg_eve"))
        late <- repo.insert(
          owner.id,
          draft("Late", tags = List("x"), due = Some(today.minusDays(1))),
          now
        )
        _      <- repo.insert(owner.id, draft("Done", tags = List("y", "x"), completed = true), now)
        hijack <- repo.update(other.id, late.id, draft("hijacked"), now)
        edited <- repo.update(
          owner.id,
          late.id,
          late.toDraft.copy(title = Title.unsafe("Later")),
          now.plusSeconds(60)
        )
        stats   <- repo.stats(owner.id, today)
        tags    <- repo.tags(owner.id)
        notMine <- repo.delete(other.id, late.id)
        mine    <- repo.delete(owner.id, late.id)
      yield assertTrue(
        hijack.isEmpty,
        edited.map(_.title.value).contains("Later"),
        edited.map(_.updatedAt).contains(now.plusSeconds(60)),
        stats == TodoStats(total = 2, completed = 1, overdue = 1),
        tags.map(_.value) == List("x", "y"),
        !notMine,
        mine
      )
    },
    test("a password hash can be replaced, and one user's todos deleted in bulk") {
      for
        users  <- ZIO.service[UserRepository]
        repo   <- ZIO.service[TodoRepository]
        owner  <- users.create(newUser("pg_gina"))
        other  <- users.create(newUser("pg_hal"))
        _      <- users.setPasswordHash(owner.id, PasswordHash("new-hash"))
        reread <- users.findById(owner.id)
        _      <- repo.insert(owner.id, draft("one"), now)
        _      <- repo.insert(owner.id, draft("two"), now)
        _      <- repo.insert(other.id, draft("not mine"), now)
        gone   <- repo.deleteAll(owner.id)
        mine   <- repo.list(owner.id, TodoQuery())
        theirs <- repo.list(other.id, TodoQuery())
      yield assertTrue(
        reread.map(_.passwordHash.value).contains("new-hash"),
        gone == 2L,
        mine.total == 0L,
        theirs.total == 1L
      )
    },
    test("sessions are found until they expire, and can be revoked") {
      for
        users    <- ZIO.service[UserRepository]
        sessions <- ZIO.service[SessionRepository]
        owner    <- users.create(newUser("pg_frank"))
        _        <- sessions.create("hash-a", owner.id, now, now.plusSeconds(60))
        _        <- sessions.create("hash-b", owner.id, now, now.plusSeconds(3600))
        _        <- sessions.create("hash-c", owner.id, now, now.plusSeconds(3600))
        found    <- sessions.findUser("hash-a", now)
        expired  <- sessions.findUser("hash-a", now.plusSeconds(61))
        unknown  <- sessions.findUser("nope", now)
        purged   <- sessions.deleteExpired(now.plusSeconds(61))
        _        <- sessions.delete("hash-b")
        gone     <- sessions.findUser("hash-b", now)
        left     <- sessions.deleteAllForUser(owner.id)
      yield assertTrue(
        found.contains(AuthUser(owner.id, owner.username)),
        expired.isEmpty,
        unknown.isEmpty,
        purged == 1L,
        gone.isEmpty,
        left == 1L
      )
    }
  ).provideShared(
    postgres,
    Database.dataSource,
    Database.quill,
    migrated,
    UserRepository.postgres,
    TodoRepository.postgres,
    SessionRepository.postgres
  ) @@ TestAspect.sequential @@ (if dockerAvailable then TestAspect.identity else TestAspect.ignore)
