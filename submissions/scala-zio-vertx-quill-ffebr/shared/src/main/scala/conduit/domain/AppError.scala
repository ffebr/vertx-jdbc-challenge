package conduit.domain

sealed abstract class AppError(val status: Int, val publicMessage: String, cause: Throwable = null)
    extends Exception(publicMessage, cause)

object AppError {
  final case class Validation(message: String)                               extends AppError(422, message)
  final case class Unauthorized(message: String = "Authentication required") extends AppError(401, message)
  final case class Forbidden(message: String = "Operation is not permitted") extends AppError(403, message)
  final case class NotFound(message: String = "Resource not found")          extends AppError(404, message)
  final case class Internal(error: Throwable)                                extends AppError(500, "Internal server error", error)
}
