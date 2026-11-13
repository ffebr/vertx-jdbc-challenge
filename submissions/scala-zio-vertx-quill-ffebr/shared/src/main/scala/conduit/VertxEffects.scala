package conduit

import io.vertx.core.Future
import zio.{Task, ZIO}

object VertxEffects {
  def fromFuture[A](future: => Future[A]): Task[A] = ZIO.async { complete =>
    try future.onComplete(result =>
        if (result.succeeded()) complete(ZIO.succeed(result.result()))
        else complete(ZIO.fail(result.cause()))
      )
    catch { case scala.util.control.NonFatal(error) => complete(ZIO.fail(error)) }
    ()
  }
}
