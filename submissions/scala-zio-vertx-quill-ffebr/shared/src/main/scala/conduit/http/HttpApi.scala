package conduit.http

import conduit.domain._
import conduit.service.{AuthService, ConduitService}
import Dto._
import io.vertx.ext.web.Router
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.tapir.json.zio._
import sttp.tapir.generic.auto._
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.interceptor.exception.ExceptionHandler
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.server.vertx.zio.{VertxZioServerInterpreter, VertxZioServerOptions}
import sttp.tapir.ztapir._
import zio.{Cause, IO, RIO, Runtime, ZIO}

/**
 * The HTTP boundary owns all wire conversion and never exposes persistence
 * records.
 */
final class HttpApi(service: ConduitService, auth: AuthService) {
  private type Err  = (StatusCode, ErrorsEnvelope)
  private type F[A] = RIO[Any, A]
  private val jsonErrors                                  = statusCode.and(jsonBody[ErrorsEnvelope])
  private def error(e: AppError): Err                     = StatusCode(e.status) -> ErrorsEnvelope(ErrorBody(List(e.publicMessage)))
  private def attempt[A](io: IO[AppError, A]): IO[Err, A] =
    io.tapError {
      case AppError.Internal(cause) => ZIO.logErrorCause("Internal application error", Cause.fail(cause))
      case _                        => ZIO.unit
    }.mapError(error)
  private def status(code: Int): StatusCode = StatusCode(code)

  private val optionalHeader   = header[Option[String]]("Authorization")
  private val requiredHeader   = header[Option[String]]("Authorization")
  private def optionalSecurity = endpoint.securityIn(optionalHeader).errorOut(jsonErrors)
  private def requiredSecurity = endpoint.securityIn(requiredHeader).errorOut(jsonErrors)

  private val register = endpoint.post.in("api" / "users").in(jsonBody[UserRegisterWrapper])
    .errorOut(jsonErrors).out(statusCode.and(jsonBody[UserEnvelope]))
    .zServerLogic(req =>
      attempt(service.register(Dto.register(req.user))).map(v => status(201) -> UserEnvelope(Dto.user(v)))
    )

  private val login = endpoint.post.in("api" / "users" / "login").in(jsonBody[UserLoginWrapper])
    .errorOut(jsonErrors).out(jsonBody[UserEnvelope])
    .zServerLogic(req => attempt(service.login(Dto.login(req.user))).map(v => UserEnvelope(Dto.user(v))))

  private val currentUser = requiredSecurity.get.in("api" / "user").out(jsonBody[UserEnvelope])
    .zServerSecurityLogic(header => attempt(auth.required(header)))
    .serverLogic(principal => _ => attempt(service.currentUser(principal)).map(v => UserEnvelope(Dto.user(v))))

  private val updateUser =
    requiredSecurity.put.in("api" / "user").in(jsonBody[UserUpdateWrapper]).out(jsonBody[UserEnvelope])
      .zServerSecurityLogic(header => attempt(auth.required(header)))
      .serverLogic(principal =>
        req => attempt(service.updateUser(principal, Dto.update(req.user)).map(v => UserEnvelope(Dto.user(v))))
      )

  private val getProfile =
    optionalSecurity.get.in("api" / "profiles" / path[String]("username")).out(jsonBody[ProfileEnvelope])
      .zServerSecurityLogic(header => attempt(auth.optional(header)))
      .serverLogic(principal =>
        username => attempt(service.profile(principal, username)).map(p => ProfileEnvelope(Dto.profile(p)))
      )

  private val follow =
    requiredSecurity.post.in("api" / "profiles" / path[String]("username") / "follow").out(jsonBody[ProfileEnvelope])
      .zServerSecurityLogic(header => attempt(auth.required(header)))
      .serverLogic(principal =>
        username => attempt(service.follow(principal, username, true)).map(p => ProfileEnvelope(Dto.profile(p)))
      )
  private val unfollow =
    requiredSecurity.delete.in("api" / "profiles" / path[String]("username") / "follow").out(jsonBody[ProfileEnvelope])
      .zServerSecurityLogic(header => attempt(auth.required(header)))
      .serverLogic(principal =>
        username => attempt(service.follow(principal, username, false)).map(p => ProfileEnvelope(Dto.profile(p)))
      )

  private def page(limit: Option[String], offset: Option[String]): IO[Err, Page] =
    Page.fromParameters(limit, offset).fold(e => ZIO.fail(error(e)), ZIO.succeed(_))
  private val listArticles = optionalSecurity.get.in("api" / "articles")
    .in(query[Option[String]]("tag").and(query[Option[String]]("author")).and(query[Option[String]]("favorited"))
      .and(query[Option[String]]("limit")).and(query[Option[String]]("offset")))
    .out(jsonBody[ArticleListEnvelope]).zServerSecurityLogic(header => attempt(auth.optional(header)))
    .serverLogic(viewer => { case (tag, author, favorited, limit, offset) =>
      page(limit, offset).flatMap(p =>
        attempt(service.listArticles(viewer, ArticleFilter.fromParameters(tag, author, favorited), p))
      )
        .map(a => ArticleListEnvelope(a.articles.map(Dto.articleList), a.articlesCount))
    })

  private val feed = requiredSecurity.get.in("api" / "articles" / "feed")
    .in(query[Option[String]]("limit").and(query[Option[String]]("offset"))).out(jsonBody[ArticleListEnvelope])
    .zServerSecurityLogic(header => attempt(auth.required(header)))
    .serverLogic(principal => { case (limit, offset) =>
      page(limit, offset).flatMap(p => attempt(service.feed(principal, p)))
        .map(a => ArticleListEnvelope(a.articles.map(Dto.articleList), a.articlesCount))
    })

  private val getArticle =
    optionalSecurity.get.in("api" / "articles" / path[String]("slug")).out(jsonBody[ArticleEnvelope])
      .zServerSecurityLogic(header => attempt(auth.optional(header)))
      .serverLogic(viewer =>
        slug => attempt(service.getArticle(viewer, slug)).map(a => ArticleEnvelope(Dto.article(a)))
      )

  private val createArticle = requiredSecurity.post.in("api" / "articles").in(jsonBody[ArticleCreateWrapper])
    .out(statusCode.and(jsonBody[ArticleEnvelope])).zServerSecurityLogic(header => attempt(auth.required(header)))
    .serverLogic(principal =>
      req =>
        attempt(service.createArticle(principal, Dto.createArticle(req.article)))
          .map(a => status(201) -> ArticleEnvelope(Dto.article(a)))
    )

  private val updateArticle =
    requiredSecurity.put.in("api" / "articles" / path[String]("slug")).in(jsonBody[ArticleUpdateWrapper])
      .out(jsonBody[ArticleEnvelope]).zServerSecurityLogic(header => attempt(auth.required(header)))
      .serverLogic(principal => { case (slug, req) =>
        attempt(service.updateArticle(principal, slug, Dto.updateArticle(req.article)))
          .map(a => ArticleEnvelope(Dto.article(a)))
      })

  private val deleteArticle = requiredSecurity.delete.in("api" / "articles" / path[String]("slug")).out(statusCode)
    .zServerSecurityLogic(header => attempt(auth.required(header)))
    .serverLogic(principal => slug => attempt(service.deleteArticle(principal, slug)).as(StatusCode.NoContent))

  private val favorite =
    requiredSecurity.post.in("api" / "articles" / path[String]("slug") / "favorite").out(jsonBody[ArticleEnvelope])
      .zServerSecurityLogic(header => attempt(auth.required(header)))
      .serverLogic(principal =>
        slug => attempt(service.favorite(principal, slug, true)).map(a => ArticleEnvelope(Dto.article(a)))
      )
  private val unfavorite =
    requiredSecurity.delete.in("api" / "articles" / path[String]("slug") / "favorite").out(jsonBody[ArticleEnvelope])
      .zServerSecurityLogic(header => attempt(auth.required(header)))
      .serverLogic(principal =>
        slug => attempt(service.favorite(principal, slug, false)).map(a => ArticleEnvelope(Dto.article(a)))
      )

  private val getComments =
    optionalSecurity.get.in("api" / "articles" / path[String]("slug") / "comments").out(jsonBody[CommentsEnvelope])
      .zServerSecurityLogic(header => attempt(auth.optional(header)))
      .serverLogic(viewer =>
        slug => attempt(service.comments(viewer, slug)).map(c => CommentsEnvelope(c.map(Dto.comment)))
      )
  private val createComment = requiredSecurity.post.in("api" / "articles" / path[String]("slug") / "comments")
    .in(jsonBody[CommentCreateWrapper]).out(statusCode.and(jsonBody[CommentEnvelope]))
    .zServerSecurityLogic(header => attempt(auth.required(header)))
    .serverLogic(principal => { case (slug, req) =>
      attempt(service.createComment(principal, slug, req.comment.body))
        .map(c => status(201) -> CommentEnvelope(Dto.comment(c)))
    })
  private val deleteComment =
    requiredSecurity.delete.in("api" / "articles" / path[String]("slug") / "comments" / path[String]("id"))
      .out(statusCode).zServerSecurityLogic(header => attempt(auth.required(header)))
      .serverLogic(principal => { case (slug, id) =>
        attempt(service.deleteComment(principal, slug, id)).as(StatusCode.NoContent)
      })

  private val tags = endpoint.get.in("api" / "tags").errorOut(jsonErrors).out(jsonBody[TagsEnvelope])
    .zServerLogic(_ => attempt(service.tags).map(TagsEnvelope))

  private val endpoints: List[ZServerEndpoint[Any, ZioStreams with WebSockets]] = List(
    register,
    login,
    currentUser,
    updateUser,
    getProfile,
    follow,
    unfollow,
    listArticles,
    feed,
    getArticle,
    createArticle,
    updateArticle,
    deleteArticle,
    favorite,
    unfavorite,
    getComments,
    createComment,
    deleteComment,
    tags
  )

  def register(router: Router)(implicit runtime: Runtime[Any]): Unit = {
    val decoder = DecodeFailureHandler.pure[F] { ctx =>
      DefaultDecodeFailureHandler.respond(ctx).map { _ =>
        ValuedEndpointOutput(
          statusCode.and(jsonBody[ErrorsEnvelope]),
          StatusCode.UnprocessableEntity -> ErrorsEnvelope(ErrorBody(List("Invalid request")))
        )
      }
    }
    val exceptions = ExceptionHandler[F] { ctx =>
      val output = ValuedEndpointOutput(
        statusCode.and(jsonBody[ErrorsEnvelope]),
        StatusCode.InternalServerError -> ErrorsEnvelope(ErrorBody(List("Internal server error")))
      )
      ZIO.logErrorCause("Unhandled HTTP endpoint exception", Cause.fail(ctx.e)).as(Some(output))
    }
    val options = VertxZioServerOptions.customiseInterceptors[Any]
      .decodeFailureHandler(decoder)
      .exceptionHandler(exceptions)
      .options
    val interpreter = VertxZioServerInterpreter[Any](options)
    endpoints.foreach(e => interpreter.route(e)(runtime)(router))
    // A final catch-all provides a JSON 404 for otherwise unmatched paths.
    router.route().handler(ctx => {
      if (!ctx.response().ended()) ctx.response().setStatusCode(404).putHeader("content-type", "application/json")
        .end("{\"errors\":{\"body\":[\"Resource not found\"]}}")
    })
  }
}
