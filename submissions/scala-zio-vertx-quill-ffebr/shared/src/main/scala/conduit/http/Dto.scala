package conduit.http

import conduit.domain._
import zio.json._

/**
 * Wire types are deliberately separate from persistence/domain types: in
 * particular, UserRecord and Article's internal id can never accidentally
 * become part of a response.
 */
final case class ErrorBody(body: List[String])
final case class ErrorsEnvelope(errors: ErrorBody)
final case class UserDto(
  email: String,
  token: String,
  username: String,
  @jsonExplicitNull bio: Option[String],
  @jsonExplicitNull image: Option[String]
)
final case class ProfileDto(
  username: String,
  @jsonExplicitNull bio: Option[String],
  @jsonExplicitNull image: Option[String],
  following: Boolean
)
final case class ArticleDto(
  slug: String,
  title: String,
  description: String,
  body: Option[String],
  tagList: List[String],
  createdAt: String,
  updatedAt: String,
  favorited: Boolean,
  favoritesCount: Long,
  author: ProfileDto
)
final case class ArticleListDto(
  slug: String,
  title: String,
  description: String,
  tagList: List[String],
  createdAt: String,
  updatedAt: String,
  favorited: Boolean,
  favoritesCount: Long,
  author: ProfileDto
)
final case class CommentDto(id: Long, createdAt: String, updatedAt: String, body: String, author: ProfileDto)
final case class UserEnvelope(user: UserDto)
final case class ProfileEnvelope(profile: ProfileDto)
final case class ArticleEnvelope(article: ArticleDto)
final case class ArticleListEnvelope(articles: List[ArticleListDto], articlesCount: Long)
final case class CommentsEnvelope(comments: List[CommentDto])
final case class TagsEnvelope(tags: List[String])

final case class RegisterRequest(username: String, email: String, password: String)
final case class LoginRequest(email: String, password: String)
final case class CreateArticleRequest(
  title: String,
  description: String,
  body: String,
  tagList: Option[List[String]] = None
)
final case class UpdateArticleRequest(
  title: Option[String] = None,
  description: Option[String] = None,
  body: Option[String] = None
)
final case class CreateCommentRequest(body: String)
final case class UserRegisterWrapper(user: RegisterRequest)
final case class UserLoginWrapper(user: LoginRequest)
final case class UserUpdateRequest(
  email: Option[String] = None,
  username: Option[String] = None,
  password: Option[String] = None,
  bio: Option[String] = None,
  image: Option[String] = None
)
final case class UserUpdateWrapper(user: UserUpdateRequest)
final case class ArticleCreateWrapper(article: CreateArticleRequest)
final case class ArticleUpdateWrapper(article: UpdateArticleRequest)
final case class CommentCreateWrapper(comment: CreateCommentRequest)
final case class CommentEnvelope(comment: CommentDto)

object Dto {
  implicit val errorBodyCodec: JsonCodec[ErrorBody]                       = DeriveJsonCodec.gen
  implicit val errorsCodec: JsonCodec[ErrorsEnvelope]                     = DeriveJsonCodec.gen
  implicit val userCodec: JsonCodec[UserDto]                              = DeriveJsonCodec.gen
  implicit val profileCodec: JsonCodec[ProfileDto]                        = DeriveJsonCodec.gen
  implicit val articleCodec: JsonCodec[ArticleDto]                        = DeriveJsonCodec.gen
  implicit val articleListCodec: JsonCodec[ArticleListDto]                = DeriveJsonCodec.gen
  implicit val commentCodec: JsonCodec[CommentDto]                        = DeriveJsonCodec.gen
  implicit val userEnvelopeCodec: JsonCodec[UserEnvelope]                 = DeriveJsonCodec.gen
  implicit val profileEnvelopeCodec: JsonCodec[ProfileEnvelope]           = DeriveJsonCodec.gen
  implicit val articleEnvelopeCodec: JsonCodec[ArticleEnvelope]           = DeriveJsonCodec.gen
  implicit val articleListEnvelopeCodec: JsonCodec[ArticleListEnvelope]   = DeriveJsonCodec.gen
  implicit val commentsEnvelopeCodec: JsonCodec[CommentsEnvelope]         = DeriveJsonCodec.gen
  implicit val tagsEnvelopeCodec: JsonCodec[TagsEnvelope]                 = DeriveJsonCodec.gen
  implicit val registerCodec: JsonCodec[RegisterRequest]                  = DeriveJsonCodec.gen
  implicit val loginCodec: JsonCodec[LoginRequest]                        = DeriveJsonCodec.gen
  implicit val createArticleCodec: JsonCodec[CreateArticleRequest]        = DeriveJsonCodec.gen
  implicit val updateArticleCodec: JsonCodec[UpdateArticleRequest]        = DeriveJsonCodec.gen
  implicit val createCommentCodec: JsonCodec[CreateCommentRequest]        = DeriveJsonCodec.gen
  implicit val registerWrapperCodec: JsonCodec[UserRegisterWrapper]       = DeriveJsonCodec.gen
  implicit val loginWrapperCodec: JsonCodec[UserLoginWrapper]             = DeriveJsonCodec.gen
  implicit val userUpdateCodec: JsonCodec[UserUpdateRequest]              = DeriveJsonCodec.gen
  implicit val userUpdateWrapperCodec: JsonCodec[UserUpdateWrapper]       = DeriveJsonCodec.gen
  implicit val articleCreateWrapperCodec: JsonCodec[ArticleCreateWrapper] = DeriveJsonCodec.gen
  implicit val articleUpdateWrapperCodec: JsonCodec[ArticleUpdateWrapper] = DeriveJsonCodec.gen
  implicit val commentCreateWrapperCodec: JsonCodec[CommentCreateWrapper] = DeriveJsonCodec.gen
  implicit val commentEnvelopeCodec: JsonCodec[CommentEnvelope]           = DeriveJsonCodec.gen

  private val instantFormat                       = new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter
  def timestamp(value: java.time.Instant): String =
    instantFormat.format(value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
  def profile(value: Profile): ProfileDto = ProfileDto(value.username, value.bio, value.image, value.following)
  def article(value: Article): ArticleDto = ArticleDto(
    value.slug,
    value.title,
    value.description,
    value.body,
    value.tagList,
    timestamp(value.createdAt),
    timestamp(value.updatedAt),
    value.favorited,
    value.favoritesCount,
    profile(value.author)
  )
  def articleList(value: Article): ArticleListDto = ArticleListDto(
    value.slug,
    value.title,
    value.description,
    value.tagList,
    timestamp(value.createdAt),
    timestamp(value.updatedAt),
    value.favorited,
    value.favoritesCount,
    profile(value.author)
  )
  def comment(value: Comment): CommentDto =
    CommentDto(value.id, timestamp(value.createdAt), timestamp(value.updatedAt), value.body, profile(value.author))
  def user(value: UserSession): UserDto              = UserDto(value.email, value.token, value.username, value.bio, value.image)
  def register(value: RegisterRequest): RegisterUser = RegisterUser(value.username, value.email, value.password)
  def login(value: LoginRequest): LoginUser          = LoginUser(value.email, value.password)
  def update(value: UserUpdateRequest): UpdateUser   =
    UpdateUser(value.email, value.username, value.password, value.bio, value.image)
  def createArticle(value: CreateArticleRequest): CreateArticle =
    CreateArticle(value.title, value.description, value.body, value.tagList.getOrElse(Nil))
  def updateArticle(value: UpdateArticleRequest): UpdateArticle =
    UpdateArticle(value.title, value.description, value.body)
}
