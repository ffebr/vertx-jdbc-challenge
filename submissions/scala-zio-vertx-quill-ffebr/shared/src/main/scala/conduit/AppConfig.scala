package conduit

import zio.{Task, ZIO}

final case class AppConfig(databaseUrl: String, jwtSecret: String, host: String, port: Int)
object AppConfig {
  def load: Task[AppConfig] = ZIO.attempt {
    def required(name: String): String = sys.env.get(name).filter(_.nonEmpty)
      .getOrElse(throw new IllegalArgumentException(s"$name is required"))
    val port = sys.env.getOrElse("PORT", "3000").toInt
    require(port >= 1 && port <= 65535, "PORT must be between 1 and 65535")
    AppConfig(required("DATABASE_URL"), required("JWT_SECRET"), sys.env.getOrElse("HOST", "127.0.0.1"), port)
  }
}
