package conduit.service

import conduit.domain.{AppError, Principal, UserRepository}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import zio.IO
import zio.json._
import zio.json.ast.Json

import java.time.Instant
import java.util.Date
import java.util.Base64
import java.nio.charset.StandardCharsets
import scala.util.Try

final class AuthService(users: UserRepository, jwtSecret: String) {
  private def internalUnlessTyped(error: Throwable): AppError = error match {
    case appError: AppError => appError
    case other              => AppError.Internal(other)
  }
  private val algorithm = Algorithm.HMAC256(jwtSecret)
  private val verifier  =
    JWT.require(algorithm).withClaimPresence("sub").withClaimPresence("iat").withClaimPresence("exp").build()
  private val lifetimeSeconds = 30L * 24L * 60L * 60L

  def issue(id: Long): IO[AppError, String] = zio.ZIO.attempt {
    val now = Instant.now().getEpochSecond
    JWT.create().withSubject(id.toString).withIssuedAt(Date.from(Instant.ofEpochSecond(now)))
      .withExpiresAt(Date.from(Instant.ofEpochSecond(now + lifetimeSeconds))).sign(algorithm)
  }.mapError(internalUnlessTyped)

  private def decode(header: String): Either[AppError, (Long, String)] = {
    val invalid = AppError.Unauthorized("Invalid authentication token")
    if (header == null || !header.startsWith("Token ") || header.length <= 6) Left(invalid)
    else {
      val token = header.substring(6)
      Try {
        val decoded = verifier.verify(token)
        val payload = new String(Base64.getUrlDecoder.decode(decoded.getPayload), StandardCharsets.UTF_8)
          .fromJson[Json].fold(message => throw new IllegalArgumentException(message), identity)
        val claims = payload match {
          case Json.Obj(fields) => fields.toMap
          case _                => throw new IllegalArgumentException("Invalid JWT payload")
        }
        val subject = claims.get("sub") match {
          case Some(Json.Str(value)) if value.matches("[0-9]+") => value
          case _                                                => throw new IllegalArgumentException("Invalid JWT subject")
        }
        def integerClaim(name: String): Long = claims.get(name) match {
          case Some(Json.Num(value)) => value.longValueExact()
          case _                     => throw new IllegalArgumentException(s"Invalid JWT $name claim")
        }
        val issuedAt  = integerClaim("iat")
        val expiresAt = integerClaim("exp")
        if (expiresAt <= Instant.now().getEpochSecond)
          throw new IllegalArgumentException("Invalid JWT claims")
        // Force parsing of sub as a bounded decimal before converting to the DB identifier.
        val id = subject.toLong
        if (issuedAt < 0L) throw new IllegalArgumentException("Invalid JWT issued-at claim")
        (id, token)
      }.toEither.left.map(_ => invalid)
    }
  }

  def optional(header: Option[String]): IO[AppError, Option[Principal]] = header match {
    case None        => zio.ZIO.succeed(None)
    case Some(value) => decode(value) match {
        case Left(error)        => zio.ZIO.fail(error)
        case Right((id, token)) => users.byId(id).mapError(internalUnlessTyped).flatMap {
            case Some(user) => zio.ZIO.succeed(Some(Principal(user, token)))
            case None       => zio.ZIO.fail(AppError.Unauthorized("Invalid authentication token"))
          }
      }
  }

  def required(header: Option[String]): IO[AppError, Principal] =
    optional(header).flatMap(_.fold[IO[AppError, Principal]](zio.ZIO.fail(AppError.Unauthorized()))(zio.ZIO.succeed(_)))
}
