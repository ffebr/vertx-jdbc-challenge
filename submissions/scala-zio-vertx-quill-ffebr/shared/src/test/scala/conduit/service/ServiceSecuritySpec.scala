package conduit.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import conduit.domain._
import zio.{Task, ZIO}
import zio.test._

import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

object ServiceSecuritySpec extends ZIOSpecDefault {
  private val secret = "unit-test-secret-with-enough-entropy"
  private val user   = UserRecord(41L, "tester", "tester@example.test", "unused", None, None, Instant.EPOCH)

  private final class Users extends UserRepository {
    val reads                                             = new AtomicInteger(0)
    override def byId(id: Long): Task[Option[UserRecord]] = ZIO.succeed {
      reads.incrementAndGet()
      Option.when(id == user.id)(user)
    }
    override def byEmail(email: String): Task[Option[UserRecord]]                                 = ZIO.succeed(None)
    override def byUsername(username: String): Task[Option[UserRecord]]                           = ZIO.succeed(None)
    override def create(username: String, email: String, passwordHash: String): Task[UserRecord]  = ZIO.succeed(user)
    override def update(id: Long, patch: UserPatch): Task[Option[UserRecord]]                     = ZIO.succeed(Some(user))
    override def profile(username: String, viewerId: Long): Task[Option[Profile]]                 = ZIO.succeed(None)
    override def setFollowing(followerId: Long, followeeId: Long, following: Boolean): Task[Unit] = ZIO.unit
  }

  private def token(algorithm: Algorithm, subject: String, issuedAt: Long, expiresAt: Long): String =
    JWT.create().withSubject(subject).withIssuedAt(Date.from(Instant.ofEpochSecond(issuedAt)))
      .withExpiresAt(Date.from(Instant.ofEpochSecond(expiresAt))).sign(algorithm)

  override def spec: Spec[TestEnvironment, Any] = suite("service security and validation")(
    test("issued JWT has only the required claims, exact subject, and a 30 day lifetime") {
      val users = new Users
      for {
        issued   <- new AuthService(users, secret).issue(user.id)
        decoded  <- ZIO.attempt(JWT.require(Algorithm.HMAC256(secret)).build().verify(issued))
        claims    = decoded.getClaims
        issuedAt  = decoded.getClaim("iat").asLong().longValue()
        expiresAt = decoded.getClaim("exp").asLong().longValue()
      } yield assertTrue(
        decoded.getSubject == "41",
        claims.keySet().asScala.toSet == Set("sub", "iat", "exp"),
        expiresAt - issuedAt == 30L * 24L * 60L * 60L
      )
    },
    test("rejects wrong algorithm, wrong signature, expired token, and malformed subject") {
      val auth    = new AuthService(new Users, secret)
      val now     = Instant.now().getEpochSecond
      val invalid = List(
        token(Algorithm.HMAC384(secret), "41", now, now + 60),
        token(Algorithm.HMAC256("different-secret"), "41", now, now + 60),
        token(Algorithm.HMAC256(secret), "41", now - 120, now - 60),
        token(Algorithm.HMAC256(secret), "41x", now, now + 60),
        JWT.create().withSubject("41").withClaim("iat", 1.5).withClaim("exp", java.lang.Long.valueOf(now + 60L))
          .sign(Algorithm.HMAC256(secret))
      )
      ZIO.foreach(invalid)(value => auth.required(Some(s"Token $value")).either).map(results =>
        assertTrue(results.forall(_.left.exists(_.status == 401)))
      )
    },
    test("consults the repository on every successful verification") {
      val users = new Users
      val auth  = new AuthService(users, secret)
      for {
        issued <- auth.issue(user.id)
        first  <- auth.required(Some(s"Token $issued"))
        second <- auth.required(Some(s"Token $issued"))
      } yield assertTrue(first.id == user.id, second.id == user.id, users.reads.get() == 2)
    },
    test("optional auth distinguishes an absent header from present invalid credentials") {
      val users = new Users
      val auth  = new AuthService(users, secret)
      for {
        absent  <- auth.optional(None)
        invalid <- auth.optional(Some("Bearer anything")).either
      } yield assertTrue(absent.isEmpty, invalid.left.exists(_.status == 401), users.reads.get() == 0)
    },
    test("pagination accepts documented bounds and rejects malformed or out of range values") {
      val accepted = List(
        Page.fromParameters(None, None) == Right(Page(20, 0)),
        Page.fromParameters(Some("1"), Some("0")) == Right(Page(1, 0)),
        Page.fromParameters(Some("100"), Some("2147483647")) == Right(Page(100, Int.MaxValue))
      )
      val rejected = List("0", "101", "-1", "1.5", "", "2147483648").map { raw =>
        Page.fromParameters(Some(raw), None).isLeft
      } ++ List("-1", "+1", "x", "2147483648").map(raw => Page.fromParameters(None, Some(raw)).isLeft)
      assertTrue(accepted.forall(identity), rejected.forall(identity))
    },
    test("bcrypt rejects passwords over 72 UTF-8 bytes and accepts exactly 72") {
      val passwords    = new PasswordHasher
      val tooLong      = "é" * 37 // 74 UTF-8 bytes, despite only 37 characters
      val exactlyLimit = "é" * 36 // 72 UTF-8 bytes
      for {
        rejected         <- passwords.hash(tooLong).either
        hash             <- passwords.hash(exactlyLimit)
        matches          <- passwords.verify(exactlyLimit, hash)
        oversizedMatches <- passwords.verify(tooLong, hash)
      } yield assertTrue(rejected.left.exists(_.status == 422), matches, !oversizedMatches)
    }
  )
}
