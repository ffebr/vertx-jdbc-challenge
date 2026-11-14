package conduit

import zio.test._

object PostgresDatabaseUrlSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] = suite("PostgreSQL DATABASE_URL parsing")(
    test("decodes percent-escaped credential delimiters and Unicode without treating plus as space") {
      val result = PostgresDatabaseUrl.parse(
        "postgres://us%3Aer:p%3Aa%40b%26%E2%9C%93+ok@db.example:5433/conduit?sslmode=require&application_name=real%20world"
      )
      assertTrue(
        result == Right(PostgresDatabaseUrl(
          Some("us:er"),
          Some("p:a@b&✓+ok"),
          "jdbc:postgresql://db.example:5433/conduit?sslmode=require&application_name=real%20world",
          "postgres://us%3Aer:p%3Aa%40b%26%E2%9C%93%2Bok@db.example:5433/conduit?sslmode=require&application_name=real%20world"
        ))
      )
    },
    test("preserves IPv6 authority, explicit port, path encoding, and query options") {
      val result = PostgresDatabaseUrl.parse(
        "postgresql://reader:secret@[2001:db8::1]:6543/db%20name?sslmode=verify-full&options=-c%20search_path%3Dpublic"
      )
      assertTrue(result == Right(PostgresDatabaseUrl(
        Some("reader"),
        Some("secret"),
        "jdbc:postgresql://[2001:db8::1]:6543/db%20name?sslmode=verify-full&options=-c%20search_path%3Dpublic",
        "postgresql://reader:secret@[2001:db8::1]:6543/db%20name?sslmode=verify-full&options=-c%20search_path%3Dpublic"
      )))
    },
    test("supports absent credentials and PostgreSQL's default port") {
      val result = PostgresDatabaseUrl.parse("postgres://localhost/conduit?connect_timeout=5")
      assertTrue(result == Right(PostgresDatabaseUrl(
        None,
        None,
        "jdbc:postgresql://localhost/conduit?connect_timeout=5",
        "postgres://localhost/conduit?connect_timeout=5"
      )))
    },
    test("rejects non-PostgreSQL, malformed credentials, missing hosts, and invalid ports") {
      val invalid = List(
        "mysql://user:pass@localhost/db",
        "postgres://user:bad%Q0@localhost/db",
        "postgres://user:%FF@localhost/db",
        "postgres:///db",
        "postgres://localhost:0/db",
        "postgres://localhost:65536/db",
        "postgres://localhost/db#fragment"
      )
      assertTrue(invalid.forall(value => PostgresDatabaseUrl.parse(value).isLeft))
    },
    test("does not echo credentials in malformed URL errors") {
      val result = PostgresDatabaseUrl.parse("postgres://user:super-secret@host:bad-port/db")
      assertTrue(result.left.toOption.exists(message => !message.contains("super-secret")))
    }
  )
}
