package conduit.persistence

import conduit.AppConfig
import conduit.domain.AppError
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.vertx.core.Vertx
import javax.sql.DataSource
import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
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
      val uri = new URI(config.databaseUrl)
      require(Set("postgres", "postgresql")(uri.getScheme), "DATABASE_URL must use PostgreSQL")
      val credentials                   = Option(uri.getRawUserInfo).getOrElse("").split(":", 2)
      def decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
      val pool                          = new HikariConfig()
      val port                          = if (uri.getPort < 0) 5432 else uri.getPort
      val query                         = Option(uri.getRawQuery).fold("")("?" + _)
      pool.setJdbcUrl(s"jdbc:postgresql://${uri.getHost}:$port${uri.getRawPath}$query")
      if (credentials.headOption.exists(_.nonEmpty)) pool.setUsername(decode(credentials(0)))
      if (credentials.length > 1) pool.setPassword(decode(credentials(1)))
      pool.setMaximumPoolSize(16)
      pool.setMinimumIdle(2)
      pool.setPoolName("conduit-jdbc")
      new HikariDataSource(pool)
    })(pool => ZIO.attemptBlocking(pool.close()).orDie).map(new Database(_))
}
