package com.example.todo.db

import com.example.todo.config.AppConfig
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

object Database:
  /** A HikariCP connection pool, closed automatically when the layer's scope ends. */
  val dataSource: ZLayer[AppConfig, Throwable, DataSource] = ZLayer.scoped {
    for
      cfg <- ZIO.serviceWith[AppConfig](_.db)
      pool <- ZIO.fromAutoCloseable(ZIO.attemptBlocking {
        val hc = HikariConfig()
        hc.setJdbcUrl(cfg.url)
        hc.setUsername(cfg.user)
        hc.setPassword(String(cfg.password.value.toArray))
        hc.setMaximumPoolSize(cfg.maxPoolSize)
        hc.setPoolName("todo-pool")
        HikariDataSource(hc)
      })
    yield pool
  }

  val quill: ZLayer[DataSource, Nothing, Quill.Postgres[SnakeCase]] =
    Quill.Postgres.fromNamingStrategy(SnakeCase)

/** Applies the SQL files in `db/migration` that haven't run yet. */
final class Migrations(dataSource: DataSource):
  def migrate: Task[Unit] =
    for
      result <- ZIO.attemptBlocking(
        Flyway.configure().dataSource(dataSource).load().migrate()
      )
      _ <- ZIO.logInfo(
        s"Database schema at version ${result.targetSchemaVersion} " +
          s"(${result.migrationsExecuted} migrations applied)"
      )
    yield ()

object Migrations:
  val layer: URLayer[DataSource, Migrations] = ZLayer.derive[Migrations]

trait HealthCheck:
  def databaseUp: UIO[Boolean]

object HealthCheck:
  final class Live(dataSource: DataSource) extends HealthCheck:
    def databaseUp: UIO[Boolean] =
      ZIO
        .scoped(
          ZIO
            .fromAutoCloseable(ZIO.attemptBlocking(dataSource.getConnection))
            .flatMap(conn => ZIO.attemptBlocking(conn.isValid(2)))
        )
        .orElseSucceed(false)

  val live: URLayer[DataSource, HealthCheck] = ZLayer.derive[Live]
