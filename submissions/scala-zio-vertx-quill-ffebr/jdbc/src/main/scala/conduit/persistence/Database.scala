package conduit.persistence

import conduit.AppConfig
import conduit.PostgresDatabaseUrl
import conduit.domain.AppError
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.vertx.core.Vertx
import javax.sql.DataSource
import java.sql.SQLException
import zio.{Scope, Task, ZEnvironment, ZIO}
import zio.quill.{PostgresZioJdbcContext, SnakeCase}

final class Database private (dataSource: DataSource) {
  val context = new PostgresZioJdbcContext(SnakeCase)

  def execute[A](effect: ZIO[DataSource, Throwable, A]): Task[A] =
    effect.provideEnvironment(ZEnvironment(dataSource)).mapError {
      case error: SQLException if error.getSQLState == "23505" => AppError.Validation("Value already exists")
      case error                                               => error
    }
}
object Database {
  val backendName                                                            = "JDBC"
  def make(config: AppConfig, vertx: Vertx): ZIO[Scope, Throwable, Database] =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val databaseUrl = PostgresDatabaseUrl.parse(config.databaseUrl)
        .fold(message => throw new IllegalArgumentException(message), identity)
      val pool = new HikariConfig()
      pool.setJdbcUrl(databaseUrl.jdbcUrl)
      databaseUrl.username.foreach(pool.setUsername)
      databaseUrl.password.foreach(pool.setPassword)
      pool.setMaximumPoolSize(16)
      pool.setMinimumIdle(2)
      pool.setPoolName("conduit-jdbc")
      new HikariDataSource(pool)
    })(pool => ZIO.attemptBlocking(pool.close()).orDie).map(new Database(_))
}
