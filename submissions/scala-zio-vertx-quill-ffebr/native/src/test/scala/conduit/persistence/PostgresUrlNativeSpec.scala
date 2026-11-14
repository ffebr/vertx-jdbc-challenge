package conduit.persistence

import conduit.PostgresDatabaseUrl
import io.vertx.pgclient.{PgConnectOptions, SslMode}
import zio.test._

object PostgresUrlNativeSpec extends ZIOSpecDefault {
  private def options(url: String): PgConnectOptions =
    PgConnectOptions.fromUri(PostgresDatabaseUrl.parse(url).fold(
      message => throw new IllegalArgumentException(message),
      _.nativeUri
    ))

  override def spec: Spec[TestEnvironment, Any] = suite("native PostgreSQL URL credentials")(
    test("passes plus, reserved characters, and Unicode credentials through Vert.x URI parsing") {
      val url    = "postgres://us%3Aer:p%2B%40%3A%26%E2%9C%93+tail@db.example:5444/conduit?sslmode=require"
      val parsed = options(url)
      assertTrue(
        parsed.getUser == "us:er",
        parsed.getPassword == "p+@:&✓+tail",
        parsed.getHost == "db.example",
        parsed.getPort == 5444,
        parsed.getDatabase == "conduit",
        parsed.getSslMode == SslMode.REQUIRE
      )
    },
    test("passes credentials and query options through Vert.x for IPv6 URLs") {
      val url    = "postgresql://reader%40west:p%3A%2B%26%E9%9B%AA@[2001:db8::1]:6543/app?sslmode=disable"
      val parsed = options(url)
      assertTrue(
        parsed.getUser == "reader@west",
        parsed.getPassword == "p:+&雪",
        parsed.getHost == "2001:db8::1",
        parsed.getPort == 6543,
        parsed.getDatabase == "app",
        parsed.getSslMode == SslMode.DISABLE
      )
    }
  )
}
