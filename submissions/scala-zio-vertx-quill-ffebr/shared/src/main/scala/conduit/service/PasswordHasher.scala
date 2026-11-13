package conduit.service

import at.favre.lib.crypto.bcrypt.BCrypt
import conduit.domain.AppError
import zio.IO

/**
 * BCrypt password storage shared by registration, login, and password updates.
 */
final class PasswordHasher {
  private val bcrypt   = BCrypt.withDefaults()
  private val verifier = BCrypt.verifyer()

  private def validateLength(password: String): Either[AppError, Unit] = {
    if (password == null) Left(AppError.Validation("Password must be a non-empty string"))
    else if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      Left(AppError.Validation("Password must be at most 72 UTF-8 bytes"))
    else Right(())
  }

  def hash(password: String): IO[AppError, String] =
    validateLength(password) match {
      case Left(error) => zio.ZIO.fail(error)
      case Right(_)    => zio.ZIO.attemptBlocking(bcrypt.hashToString(10, password.toCharArray))
          .mapError(error => AppError.Internal(error))
    }

  def verify(password: String, hash: String): IO[AppError, Boolean] =
    if (password == null) zio.ZIO.fail(AppError.Validation("Password must be a non-empty string"))
    else if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) zio.ZIO.succeed(false)
    else zio.ZIO.attemptBlocking(verifier.verify(password.toCharArray, hash).verified)
      .mapError(error => AppError.Internal(error))
}
