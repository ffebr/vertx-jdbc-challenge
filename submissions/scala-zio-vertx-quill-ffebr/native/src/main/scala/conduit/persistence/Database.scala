package conduit.persistence

import conduit.AppConfig
import conduit.PostgresDatabaseUrl
import conduit.domain.AppError
import io.vertx.core.Vertx
import io.vertx.pgclient.{PgConnectOptions, PgException}
import io.vertx.sqlclient.{Pool, PoolOptions}
import zio.{Scope, Task, ZEnvironment, ZIO}
import zio.quill.{PostgresZioVertxContext, SnakeCase}
import zio.quill.context.vertx.VertxPool

final class Database private (pool: Pool) {
  val context = new PostgresZioVertxContext(SnakeCase)

  def execute[A](effect: ZIO[Pool, Throwable, A]): Task[A] =
    effect.provideEnvironment(ZEnvironment(pool)).mapError {
      case error: PgException if error.getSqlState == "23505" => AppError.Validation("Value already exists")
      case error                                              => error
    }
}
object Database {
  val backendName                                                            = "native Vert.x"
  def make(config: AppConfig, vertx: Vertx): ZIO[Scope, Throwable, Database] =
    ZIO.fromEither(PostgresDatabaseUrl.parse(config.databaseUrl))
      .mapError(message => new IllegalArgumentException(message))
      .flatMap { databaseUrl =>
        VertxPool.fromVertx(
          vertx,
          PgConnectOptions.fromUri(databaseUrl.nativeUri).setCachePreparedStatements(true),
          new PoolOptions().setMaxSize(16)
        ).build.map(environment => new Database(environment.get[Pool]))
      }
}
