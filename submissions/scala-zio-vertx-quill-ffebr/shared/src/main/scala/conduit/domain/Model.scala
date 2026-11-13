package conduit.domain

import java.time.Instant

final case class UserRecord(
  id: Long,
  username: String,
  email: String,
  passwordHash: String,
  bio: Option[String],
  image: Option[String],
  createdAt: Instant
)
final case class Principal(user: UserRecord, token: String) { def id: Long = user.id }
final case class UserSession(email: String, token: String, username: String, bio: Option[String], image: Option[String])
final case class Profile(username: String, bio: Option[String], image: Option[String], following: Boolean)
final case class Article(
  id: Long,
  slug: String,
  title: String,
  description: String,
  body: Option[String],
  tagList: List[String],
  createdAt: Instant,
  updatedAt: Instant,
  favorited: Boolean,
  favoritesCount: Long,
  author: Profile
)
final case class ArticlePage(articles: List[Article], articlesCount: Long)
final case class ArticleIdentity(id: Long, authorId: Long)
final case class Comment(id: Long, createdAt: Instant, updatedAt: Instant, body: String, author: Profile)

final case class RegisterUser(username: String, email: String, password: String)
final case class LoginUser(email: String, password: String)
final case class UpdateUser(
  email: Option[String] = None,
  username: Option[String] = None,
  password: Option[String] = None,
  bio: Option[String] = None,
  image: Option[String] = None
)
final case class UserPatch(
  email: Option[String],
  username: Option[String],
  passwordHash: Option[String],
  bio: Option[String],
  image: Option[String]
)
final case class CreateArticle(title: String, description: String, body: String, tagList: List[String])
final case class UpdateArticle(
  title: Option[String] = None,
  description: Option[String] = None,
  body: Option[String] = None
)

sealed trait ArticleFilter
object ArticleFilter {
  case object All                           extends ArticleFilter
  final case class Tag(value: String)       extends ArticleFilter
  final case class Author(value: String)    extends ArticleFilter
  final case class Favorited(value: String) extends ArticleFilter

  def fromParameters(tag: Option[String], author: Option[String], favorited: Option[String]): ArticleFilter =
    tag.filter(_.nonEmpty).map(Tag).orElse(author.filter(_.nonEmpty).map(Author))
      .orElse(favorited.filter(_.nonEmpty).map(Favorited)).getOrElse(All)
}

final case class Page(limit: Int, offset: Int)
object Page {
  def fromParameters(limit: Option[String], offset: Option[String]): Either[AppError, Page] = {
    def integer(value: Option[String], default: Int): Either[AppError, Int] = value match {
      case None                                                               => Right(default)
      case Some(raw) if raw.nonEmpty && raw.forall(c => c >= '0' && c <= '9') =>
        raw.toIntOption.toRight(AppError.Validation("Pagination value is out of range"))
      case _ => Left(AppError.Validation("Pagination values must be non-negative decimal integers"))
    }
    for {
      size  <- integer(limit, 20)
      start <- integer(offset, 0)
      _     <- Either.cond(size >= 1 && size <= 100, (), AppError.Validation("Limit must be between 1 and 100"))
    } yield Page(size, start)
  }
}
