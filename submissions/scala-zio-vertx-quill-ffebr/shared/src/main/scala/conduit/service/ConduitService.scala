package conduit.service

import conduit.domain._
import zio.IO

final class ConduitService(
  users: UserRepository,
  content: ContentRepository,
  auth: AuthService,
  passwords: PasswordHasher
) {
  private def db[A](effect: zio.Task[A]): IO[AppError, A] = effect.mapError {
    case error: AppError => error
    case error           => AppError.Internal(error)
  }

  private def required(value: String, name: String): IO[AppError, String] =
    if (value == null || value.isEmpty) zio.ZIO.fail(AppError.Validation(s"$name must be a non-empty string"))
    else zio.ZIO.succeed(value)

  private def session(user: UserRecord, token: String): UserSession =
    UserSession(user.email, token, user.username, user.bio, user.image)

  def register(input: RegisterUser): IO[AppError, UserSession] = for {
    username <- required(input.username, "Username")
    email    <- required(input.email, "Email")
    password <- required(input.password, "Password")
    hash     <- passwords.hash(password)
    user     <- db(users.create(username, email, hash))
    token    <- auth.issue(user.id)
  } yield session(user, token)

  def login(input: LoginUser): IO[AppError, UserSession] = for {
    email     <- required(input.email, "Email")
    password  <- required(input.password, "Password")
    candidate <- db(users.byEmail(email))
    user      <- candidate match {
              case Some(found) => passwords.verify(password, found.passwordHash).flatMap(ok =>
                  if (ok) zio.ZIO.succeed(found) else zio.ZIO.fail(AppError.Unauthorized("Invalid email or password"))
                )
              case None => zio.ZIO.fail(AppError.Unauthorized("Invalid email or password"))
            }
    token <- auth.issue(user.id)
  } yield session(user, token)

  def currentUser(principal: Principal): IO[AppError, UserSession] =
    zio.ZIO.succeed(session(principal.user, principal.token))

  def updateUser(principal: Principal, input: UpdateUser): IO[AppError, UserSession] = for {
    email <-
      input.email.fold[IO[AppError, Option[String]]](zio.ZIO.succeed(None))(v => required(v, "Email").map(Some(_)))
    username <- input.username.fold[IO[AppError, Option[String]]](zio.ZIO.succeed(None))(v =>
                  required(v, "Username").map(Some(_))
                )
    password <- input.password.fold[IO[AppError, Option[String]]](zio.ZIO.succeed(None))(v =>
                  required(v, "Password").map(Some(_))
                )
    hash    <- password.fold[IO[AppError, Option[String]]](zio.ZIO.succeed(None))(v => passwords.hash(v).map(Some(_)))
    updated <- db(users.update(principal.id, UserPatch(email, username, hash, input.bio, input.image)))
    user    <- updated.fold[IO[AppError, UserRecord]](zio.ZIO.fail(AppError.Unauthorized()))(zio.ZIO.succeed(_))
  } yield session(user, principal.token)

  def profile(viewer: Option[Principal], username: String): IO[AppError, Profile] =
    db(users.profile(
      username,
      viewer.fold(0L)(_.id)
    )).flatMap(_.fold[IO[AppError, Profile]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))

  def follow(principal: Principal, username: String, following: Boolean): IO[AppError, Profile] = for {
    target <- db(users.byUsername(username)).flatMap(_.fold[IO[
                AppError,
                UserRecord
              ]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
    _      <- db(users.setFollowing(principal.id, target.id, following))
    result <- db(users.profile(
                username,
                principal.id
              )).flatMap(_.fold[IO[AppError, Profile]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
  } yield result

  def listArticles(viewer: Option[Principal], filter: ArticleFilter, page: Page): IO[AppError, ArticlePage] =
    db(content.list(viewer.fold(0L)(_.id), filter, page))

  def feed(principal: Principal, page: Page): IO[AppError, ArticlePage] = db(content.feed(principal.id, page))

  def getArticle(viewer: Option[Principal], slug: String): IO[AppError, Article] =
    db(content.articleBySlug(
      slug,
      viewer.fold(0L)(_.id)
    )).flatMap(_.fold[IO[AppError, Article]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))

  def createArticle(principal: Principal, input: CreateArticle): IO[AppError, Article] = for {
    title       <- required(input.title, "Title")
    description <- required(input.description, "Description")
    body        <- required(input.body, "Body")
    tags        <- zio.ZIO.foreach(input.tagList)(required(_, "Tag"))
    id          <- db(content.createArticle(
            principal.id,
            input.copy(title = title, description = description, body = body, tagList = tags)
          ))
    article <- db(content.articleById(id, principal.id)).flatMap(_.fold[IO[AppError, Article]](
                 zio.ZIO.fail(AppError.Internal(new IllegalStateException("Created article was not found")))
               )(zio.ZIO.succeed(_)))
  } yield article

  private def ownedArticle(principal: Principal, slug: String): IO[AppError, ArticleIdentity] =
    db(content.articleIdentity(slug)).flatMap {
      case None                                                => zio.ZIO.fail(AppError.NotFound())
      case Some(identity) if identity.authorId != principal.id => zio.ZIO.fail(AppError.Forbidden())
      case Some(identity)                                      => zio.ZIO.succeed(identity)
    }

  def updateArticle(principal: Principal, slug: String, input: UpdateArticle): IO[AppError, Article] = for {
    identity <- ownedArticle(principal, slug)
    title    <-
      input.title.fold[IO[AppError, Option[String]]](zio.ZIO.succeed(None))(v => required(v, "Title").map(Some(_)))
    description <- input.description.fold[IO[AppError, Option[String]]](zio.ZIO.succeed(None))(v =>
                     required(v, "Description").map(Some(_))
                   )
    body    <- input.body.fold[IO[AppError, Option[String]]](zio.ZIO.succeed(None))(v => required(v, "Body").map(Some(_)))
    _       <- db(content.updateArticle(identity.id, UpdateArticle(title, description, body)))
    article <- db(content.articleById(
                 identity.id,
                 principal.id
               )).flatMap(_.fold[IO[AppError, Article]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
  } yield article

  def deleteArticle(principal: Principal, slug: String): IO[AppError, Unit] =
    ownedArticle(principal, slug).flatMap(identity => db(content.deleteArticle(identity.id)))

  def favorite(principal: Principal, slug: String, favorite: Boolean): IO[AppError, Article] = for {
    identity <- db(content.articleIdentity(slug)).flatMap(_.fold[IO[
                  AppError,
                  ArticleIdentity
                ]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
    _       <- db(content.setFavorite(principal.id, identity.id, favorite))
    article <- db(content.articleById(
                 identity.id,
                 principal.id
               )).flatMap(_.fold[IO[AppError, Article]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
  } yield article

  def comments(viewer: Option[Principal], slug: String): IO[AppError, List[Comment]] = for {
    identity <- db(content.articleIdentity(slug)).flatMap(_.fold[IO[
                  AppError,
                  ArticleIdentity
                ]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
    result <- db(content.comments(identity.id, viewer.fold(0L)(_.id)))
  } yield result

  def createComment(principal: Principal, slug: String, body: String): IO[AppError, Comment] = for {
    validBody <- required(body, "Comment body")
    identity  <- db(content.articleIdentity(slug)).flatMap(_.fold[IO[
                  AppError,
                  ArticleIdentity
                ]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
    comment <- db(content.createComment(identity.id, principal.id, validBody))
  } yield comment

  def deleteComment(principal: Principal, slug: String, id: String): IO[AppError, Unit] = {
    val commentId = Option(id).flatMap(_.toLongOption).filter(_ > 0)
    commentId match {
      case None        => zio.ZIO.fail(AppError.NotFound())
      case Some(value) => for {
          identity <- db(content.articleIdentity(slug)).flatMap(_.fold[IO[
                        AppError,
                        ArticleIdentity
                      ]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_)))
          author        <- db(content.commentAuthor(identity.id, value))
          commentAuthor <- author.fold[IO[AppError, Long]](zio.ZIO.fail(AppError.NotFound()))(zio.ZIO.succeed(_))
          _             <- if (commentAuthor == principal.id) zio.ZIO.unit else zio.ZIO.fail(AppError.Forbidden())
          _             <- db(content.deleteComment(value))
        } yield ()
    }
  }

  def tags: IO[AppError, List[String]] = db(content.tags)
}
