package conduit

import java.io.ByteArrayOutputStream
import java.net.{URI, URISyntaxException}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

/** Validated, consistently decoded view of a PostgreSQL connection URL. */
final case class PostgresDatabaseUrl(
  username: Option[String],
  password: Option[String],
  jdbcUrl: String,
  nativeUri: String
)

object PostgresDatabaseUrl {
  private def decodeComponent(raw: String): Either[String, String] = {
    val bytes = new ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
      raw.charAt(index) match {
        case '%' =>
          if (index + 2 >= raw.length) return Left("DATABASE_URL contains an incomplete percent escape")
          val high = Character.digit(raw.charAt(index + 1), 16)
          val low  = Character.digit(raw.charAt(index + 2), 16)
          if (high < 0 || low < 0) return Left("DATABASE_URL contains an invalid percent escape")
          bytes.write((high << 4) | low)
          index += 3
        case _ =>
          val codePoint = raw.codePointAt(index)
          val encoded   = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)
          bytes.write(encoded, 0, encoded.length)
          index += Character.charCount(codePoint)
      }
    }
    try {
      val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      Right(decoder.decode(ByteBuffer.wrap(bytes.toByteArray)).toString)
    } catch {
      case _: CharacterCodingException => Left("DATABASE_URL credentials are not valid UTF-8")
    }
  }

  private def encodeComponent(value: String): String = {
    val out = new StringBuilder
    value.getBytes(StandardCharsets.UTF_8).foreach { byte =>
      val unsigned   = byte & 0xff
      val unreserved = (unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z') ||
        (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '.' || unsigned == '_' || unsigned == '~'
      if (unreserved) out.append(unsigned.toChar)
      else out.append(
        '%'
      ).append("0123456789ABCDEF".charAt(unsigned >>> 4)).append("0123456789ABCDEF".charAt(unsigned & 0x0f))
    }
    out.toString
  }

  def parse(value: String): Either[String, PostgresDatabaseUrl] = {
    if (value == null || value.isEmpty) return Left("DATABASE_URL is required")
    val parsed =
      try Right(new URI(value))
      catch {
        // URI parser diagnostics can echo the full input, including credentials.
        case _: URISyntaxException => Left("DATABASE_URL is invalid")
      }
    parsed.flatMap { uri =>
      val scheme = Option(uri.getScheme).map(_.toLowerCase(java.util.Locale.ROOT))
      if (!scheme.exists(Set("postgres", "postgresql"))) Left("DATABASE_URL must use PostgreSQL")
      else if (uri.getRawAuthority == null || uri.getHost == null || uri.getHost.isEmpty)
        Left("DATABASE_URL must include a database host")
      else if (uri.getPort > 65535) Left("DATABASE_URL port must be between 1 and 65535")
      else if (uri.getPort == 0) Left("DATABASE_URL port must be between 1 and 65535")
      else if (uri.getRawFragment != null) Left("DATABASE_URL must not include a fragment")
      else {
        val rawUserInfo                                                   = Option(uri.getRawUserInfo)
        val credentials: Either[String, (Option[String], Option[String])] = rawUserInfo match {
          case None      => Right((None, None))
          case Some(raw) =>
            val separator   = raw.indexOf(':')
            val rawUsername = if (separator < 0) raw else raw.substring(0, separator)
            val rawPassword = if (separator < 0) None else Some(raw.substring(separator + 1))
            for {
              username <- decodeComponent(rawUsername)
              password <- rawPassword match {
                            case Some(part) => decodeComponent(part).map(Some(_))
                            case None       => Right(None)
                          }
            } yield (Some(username), password)
        }
        credentials.map { case (username, password) =>
          val authority       = uri.getRawAuthority
          val rawHostAndPort  = rawUserInfo.fold(authority)(_ => authority.substring(authority.indexOf('@') + 1))
          val nativeAuthority = (username, password) match {
            case (Some(user), Some(pass)) => s"${encodeComponent(user)}:${encodeComponent(pass)}@$rawHostAndPort"
            case (Some(user), None)       => s"${encodeComponent(user)}@$rawHostAndPort"
            case _                        => rawHostAndPort
          }
          val path      = Option(uri.getRawPath).getOrElse("")
          val query     = Option(uri.getRawQuery).fold("")(q => s"?$q")
          val nativeUri = s"${scheme.get}://$nativeAuthority$path$query"
          val jdbcUrl   = s"jdbc:postgresql://$rawHostAndPort$path$query"
          PostgresDatabaseUrl(username, password, jdbcUrl, nativeUri)
        }
      }
    }
  }
}
