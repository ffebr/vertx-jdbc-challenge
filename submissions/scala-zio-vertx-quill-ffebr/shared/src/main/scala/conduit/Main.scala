package conduit

import conduit.http.HttpApi
import conduit.persistence.{Database, QuillContentRepository, QuillUserRepository}
import conduit.service.{AuthService, ConduitService, PasswordHasher}
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import zio.{ZIO, ZIOAppDefault}

object Main extends ZIOAppDefault {
  def run = ZIO.scoped {
    for {
      config   <- AppConfig.load
      vertx    <- ZIO.acquireRelease(ZIO.attempt(Vertx.vertx()))(v => VertxEffects.fromFuture(v.close()).orDie)
      database <- Database.make(config, vertx)
      users     = new QuillUserRepository(database)
      content   = new QuillContentRepository(database)
      auth      = new AuthService(users, config.jwtSecret)
      service   = new ConduitService(users, content, auth, new PasswordHasher)
      runtime  <- ZIO.runtime[Any]
      router   <- ZIO.attempt {
                  val result = Router.router(vertx)
                  new HttpApi(service, auth).register(result)(runtime)
                  result
                }
      server <- ZIO.acquireRelease(
                  VertxEffects.fromFuture(vertx.createHttpServer(new HttpServerOptions().setIdleTimeout(75))
                    .requestHandler(router).listen(config.port, config.host))
                )(s => VertxEffects.fromFuture(s.close()).orDie)
      _ <- ZIO.logInfo(s"Conduit ${Database.backendName} listening on ${config.host}:${server.actualPort()}")
      _ <- ZIO.never
    } yield ()
  }
}
