package conduit.persistence

import conduit.domain._
import java.time.Instant
import java.util.Locale
import zio.quill._
import zio.Task

/**
 * The query definitions in this repository deliberately use Quill's common
 * query DSL.
 */
final class QuillContentRepository(database: Database) extends ContentRepository {
  private val ctx = database.context
  import ctx._

  private case class ArticleRow(
    id: Long,
    slug: String,
    authorId: Long,
    title: String,
    description: String,
    body: String,
    createdAt: Instant,
    updatedAt: Instant
  )
  private case class ArticleSummaryRow(
    id: Long,
    slug: String,
    authorId: Long,
    title: String,
    description: String,
    createdAt: Instant,
    updatedAt: Instant
  )
  private case class TagRow(id: Int, name: String)
  private case class ArticleTagRow(articleId: Long, tagId: Int)
  private case class FollowRow(followerId: Long, followeeId: Long)
  private case class FavoriteRow(userId: Long, articleId: Long)
  private case class CommentRow(
    id: Long,
    articleId: Long,
    authorId: Long,
    body: String,
    createdAt: Instant,
    updatedAt: Instant
  )
  private val articles      = quote(querySchema[ArticleRow]("articles"))
  private val tagsTable     = quote(querySchema[TagRow]("tags"))
  private val articleTags   = quote(querySchema[ArticleTagRow]("article_tags"))
  private val follows       = quote(querySchema[FollowRow]("follows"))
  private val favorites     = quote(querySchema[FavoriteRow]("favorites"))
  private val commentsTable = quote(querySchema[CommentRow]("comments"))
  private val users         = quote(querySchema[UserRecord]("users"))

  private def slugify(value: String): String = {
    val asciiHyphens = value.trim.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT)
    asciiHyphens.replaceAll("^-+|-+$", "")
  }

  def tags: Task[List[String]] = database.execute(ctx.run(quote(tagsTable.sortBy(_.name).map(_.name))).map(_.toList))

  private def articleBase(viewerId: Long) = quote {
    articles.join(users).on((a, u) => a.authorId == u.id).map { case (a, u) =>
      val favoriteCount = favorites.filter(_.articleId == a.id).size
      val favorited     = favorites.filter(f => f.articleId == a.id && f.userId == lift(viewerId)).nonEmpty
      val following     = follows.filter(f => f.followerId == lift(viewerId) && f.followeeId == a.authorId).nonEmpty
      (a, u.username, u.bio, u.image, favoriteCount, favorited, following)
    }
  }

  private def articleSummaryBase(viewerId: Long) = quote {
    articles.join(users).on((a, u) => a.authorId == u.id).map { case (a, u) =>
      val summary       = ArticleSummaryRow(a.id, a.slug, a.authorId, a.title, a.description, a.createdAt, a.updatedAt)
      val favoriteCount = favorites.filter(_.articleId == a.id).size
      val favorited     = favorites.filter(f => f.articleId == a.id && f.userId == lift(viewerId)).nonEmpty
      val following     = follows.filter(f => f.followerId == lift(viewerId) && f.followeeId == a.authorId).nonEmpty
      (summary, u.username, u.bio, u.image, favoriteCount, favorited, following)
    }
  }

  private def loadTags(ids: List[Long]): Task[Map[Long, List[String]]] = {
    val tagQuery = quote {
      articleTags.join(tagsTable).on((at, t) => at.tagId == t.id)
        .filter { case (at, _) => liftQuery(ids).contains(at.articleId) }
        .sortBy { case (_, t) => t.name }
        .map { case (at, t) => (at.articleId, t.name) }
    }
    database.execute(ctx.run(tagQuery).map(_.toList)).map(_.groupMap(_._1)(_._2))
  }

  private type SummaryResult = (ArticleSummaryRow, String, Option[String], Option[String], Long, Boolean, Boolean)
  private def assembleSummaries(rows: List[SummaryResult]): Task[List[Article]] =
    if (rows.isEmpty) zio.ZIO.succeed(Nil)
    else loadTags(rows.map(_._1.id)).map { grouped =>
      rows.map { case (a, username, bio, image, count, favorited, following) =>
        Article(
          a.id,
          a.slug,
          a.title,
          a.description,
          None,
          grouped.getOrElse(a.id, Nil),
          a.createdAt,
          a.updatedAt,
          favorited,
          count,
          Profile(username, bio, image, following)
        )
      }
    }

  private def assemble(rows: List[(ArticleRow, String, Option[String], Option[String], Long, Boolean, Boolean)])
    : Task[List[Article]] = {
    if (rows.isEmpty) zio.ZIO.succeed(Nil)
    else loadTags(rows.map(_._1.id)).map { grouped =>
      rows.map { case (a, username, bio, image, count, favorited, following) =>
        Article(
          a.id,
          a.slug,
          a.title,
          a.description,
          Some(a.body),
          grouped.getOrElse(a.id, Nil),
          a.createdAt,
          a.updatedAt,
          favorited,
          count,
          Profile(username, bio, image, following)
        )
      }
    }
  }

  private def pageRows(viewerId: Long, filter: ArticleFilter, page: Page): Task[List[SummaryResult]] = {
    val base = articleSummaryBase(viewerId)
    val q    = filter match {
      case ArticleFilter.All       => quote(base.sortBy(_._1.id)(Ord.desc).drop(lift(page.offset)).take(lift(page.limit)))
      case ArticleFilter.Tag(name) =>
        quote(base.filter(r =>
          articleTags.filter(at =>
            at.articleId == r._1.id &&
              tagsTable.filter(t => t.id == at.tagId && t.name == lift(name)).nonEmpty
          ).nonEmpty
        )
          .sortBy(_._1.id)(Ord.desc).drop(lift(page.offset)).take(lift(page.limit)))
      case ArticleFilter.Author(name) =>
        quote(base.filter(r => users.filter(u => u.username == lift(name) && u.id == r._1.authorId).nonEmpty)
          .sortBy(_._1.id)(Ord.desc).drop(lift(page.offset)).take(lift(page.limit)))
      case ArticleFilter.Favorited(name) =>
        quote(base.filter(r =>
          favorites.filter(f =>
            f.articleId == r._1.id &&
              users.filter(u => u.username == lift(name) && u.id == f.userId).nonEmpty
          ).nonEmpty
        )
          .sortBy(_._1.id)(Ord.desc).drop(lift(page.offset)).take(lift(page.limit)))
    }
    database.execute(ctx.run(q).map(_.toList))
  }

  private def count(filter: ArticleFilter): Task[Long] = {
    val capped = filter match {
      case ArticleFilter.All       => quote(articles.map(_ => 1L).take(1000).nested.size)
      case ArticleFilter.Tag(name) => quote(
          articles.filter(a =>
            articleTags.filter(at =>
              at.articleId == a.id &&
                tagsTable.filter(t => t.id == at.tagId && t.name == lift(name)).nonEmpty
            ).nonEmpty
          ).map(_ => 1L).take(1000).nested.size
        )
      case ArticleFilter.Author(name) => quote(
          articles.filter(a => users.filter(u => u.username == lift(name) && u.id == a.authorId).nonEmpty).map(_ =>
            1L
          ).take(1000).nested.size
        )
      case ArticleFilter.Favorited(name) => quote(
          favorites.filter(f => users.filter(u => u.username == lift(name) && u.id == f.userId).nonEmpty).map(_ =>
            1L
          ).take(1000).nested.size
        )
    }
    database.execute(ctx.run(capped))
  }

  def list(viewerId: Long, filter: ArticleFilter, page: Page): Task[ArticlePage] = for {
    rows   <- pageRows(viewerId, filter, page)
    result <- assembleSummaries(rows)
    total  <- count(filter)
  } yield ArticlePage(result, total)

  def feed(viewerId: Long, page: Page): Task[ArticlePage] = {
    val query = quote(articleSummaryBase(viewerId).filter(r =>
      follows.filter(f => f.followerId == lift(viewerId) && f.followeeId == r._1.authorId).nonEmpty
    )
      .sortBy(_._1.id)(Ord.desc).drop(lift(page.offset)).take(lift(page.limit)))
    for {
      rows   <- database.execute(ctx.run(query).map(_.toList))
      result <- assembleSummaries(rows)
      total  <- database.execute(ctx.run(quote(articles.filter(a =>
                 follows.filter(f => f.followerId == lift(viewerId) && f.followeeId == a.authorId).nonEmpty
               ).map(_ => 1L).take(1000).nested.size)))
    } yield ArticlePage(result, total)
  }

  private def one(id: Long, slug: Option[String], viewerId: Long): Task[Option[Article]] = {
    val query = slug match {
      case Some(value) => quote(articleBase(viewerId).filter(r => r._1.slug == lift(value)).take(1))
      case None        => quote(articleBase(viewerId).filter(r => r._1.id == lift(id)).take(1))
    }
    database.execute(ctx.run(query).map(_.toList)).flatMap(rows => assemble(rows).map(_.headOption))
  }
  def articleBySlug(slug: String, viewerId: Long): Task[Option[Article]] = one(0L, Some(slug), viewerId)
  def articleById(id: Long, viewerId: Long): Task[Option[Article]]       = one(id, None, viewerId)

  def articleIdentity(slug: String): Task[Option[ArticleIdentity]] =
    database.execute(ctx.run(quote(articles.filter(_.slug == lift(slug)).map(a =>
      ArticleIdentity(a.id, a.authorId)
    ).take(1))).map(_.headOption))

  def createArticle(authorId: Long, command: CreateArticle): Task[Long] = {
    val titleSlug = slugify(command.title)
    val names     = command.tagList.distinct
    database.execute(ctx.transaction {
      for {
        id <-
          ctx.run(quote(infix"select nextval(pg_get_serial_sequence('articles', 'id'))".as[Query[Long]])).map(_.head)
        _ <- ctx.run(quote(articles.insert(
               _.id          -> lift(id),
               _.slug        -> lift(s"$titleSlug-$id"),
               _.authorId    -> lift(authorId),
               _.title       -> lift(command.title),
               _.description -> lift(command.description),
               _.body        -> lift(command.body)
             )))
        _ <- zio.ZIO.foreachDiscard(names) { name =>
               ctx.run(quote(tagsTable.insert(_.name -> lift(name)).onConflictIgnore))
             }
        tagIds <- if (names.isEmpty) zio.ZIO.succeed(List.empty[Int])
                  else ctx.run(quote(tagsTable.filter(t => liftQuery(names).contains(t.name)).map(_.id))).map(_.toList)
        _ <- zio.ZIO.foreachDiscard(tagIds) { tagId =>
               ctx.run(quote(articleTags.insertValue(lift(ArticleTagRow(id, tagId))).onConflictIgnore))
             }
      } yield id
    })
  }

  def updateArticle(id: Long, patch: UpdateArticle): Task[Unit] = {
    val update = quote(articles.filter(_.id == lift(id)).update(
      a => a.title       -> lift(patch.title).getOrElse(a.title),
      a => a.description -> lift(patch.description).getOrElse(a.description),
      a => a.body        -> lift(patch.body).getOrElse(a.body),
      a => a.slug        -> lift(patch.title.map(t => s"${slugify(t)}-$id")).getOrElse(a.slug),
      a => a.updatedAt   -> infix"now()".as[Instant]
    ))
    database.execute(ctx.run(update).unit)
  }

  def deleteArticle(id: Long): Task[Unit] =
    database.execute(ctx.run(quote(articles.filter(_.id == lift(id)).delete)).unit)

  def setFavorite(userId: Long, articleId: Long, favorite: Boolean): Task[Unit] = {
    if (favorite) database.execute(ctx.run(quote(favorites.insert(
      _.userId    -> lift(userId),
      _.articleId -> lift(articleId)
    ).onConflictIgnore)).unit)
    else database.execute(ctx.run(quote(favorites.filter(f =>
      f.userId == lift(userId) && f.articleId == lift(articleId)
    ).delete)).unit)
  }

  private def commentQuery(articleId: Long, viewerId: Long) =
    quote(commentsTable.join(users).on((c, u) => c.authorId == u.id)
      .filter(_._1.articleId == lift(articleId)).sortBy(x => (x._1.createdAt, x._1.id))(Ord.desc).take(100)
      .map { case (c, u) =>
        val following = follows.filter(f => f.followerId == lift(viewerId) && f.followeeId == c.authorId).nonEmpty
        (c, u.username, u.bio, u.image, following)
      })

  def comments(articleId: Long, viewerId: Long): Task[List[Comment]] =
    database.execute(ctx.run(commentQuery(articleId, viewerId)).map(_.toList.map {
      case (c, name, bio, image, following) =>
        Comment(c.id, c.createdAt, c.updatedAt, c.body, Profile(name, bio, image, following))
    }))

  def createComment(articleId: Long, authorId: Long, body: String): Task[Comment] = database.execute(ctx.transaction {
    val insert = quote(commentsTable.insert(
      _.articleId -> lift(articleId),
      _.authorId  -> lift(authorId),
      _.body      -> lift(body)
    ).returningGenerated(_.id))
    for {
      id  <- ctx.run(insert)
      row <- ctx.run(quote(commentsTable.join(users).on((c, u) => c.authorId == u.id)
               .filter(_._1.id == lift(id)).map { case (c, u) => (c, u.username, u.bio, u.image) })).map(_.head)
    } yield Comment(
      row._1.id,
      row._1.createdAt,
      row._1.updatedAt,
      row._1.body,
      Profile(row._2, row._3, row._4, following = false)
    )
  })

  def commentAuthor(articleId: Long, commentId: Long): Task[Option[Long]] =
    database.execute(ctx.run(quote(commentsTable.filter(c =>
      c.articleId == lift(articleId) && c.id == lift(commentId)
    ).map(_.authorId).take(1))).map(_.headOption))

  def deleteComment(id: Long): Task[Unit] =
    database.execute(ctx.run(quote(commentsTable.filter(_.id == lift(id)).delete)).unit)
}
