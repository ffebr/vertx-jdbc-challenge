package conduit.domain

import zio.Task

trait UserRepository {
  def byId(id: Long): Task[Option[UserRecord]]
  def byEmail(email: String): Task[Option[UserRecord]]
  def byUsername(username: String): Task[Option[UserRecord]]
  def create(username: String, email: String, passwordHash: String): Task[UserRecord]
  def update(id: Long, patch: UserPatch): Task[Option[UserRecord]]
  def profile(username: String, viewerId: Long): Task[Option[Profile]]
  def setFollowing(followerId: Long, followeeId: Long, following: Boolean): Task[Unit]
}

trait ContentRepository {
  def tags: Task[List[String]]
  def list(viewerId: Long, filter: ArticleFilter, page: Page): Task[ArticlePage]
  def feed(viewerId: Long, page: Page): Task[ArticlePage]
  def articleBySlug(slug: String, viewerId: Long): Task[Option[Article]]
  def articleById(id: Long, viewerId: Long): Task[Option[Article]]
  def articleIdentity(slug: String): Task[Option[ArticleIdentity]]
  def createArticle(authorId: Long, command: CreateArticle): Task[Long]
  def updateArticle(id: Long, patch: UpdateArticle): Task[Unit]
  def deleteArticle(id: Long): Task[Unit]
  def setFavorite(userId: Long, articleId: Long, favorite: Boolean): Task[Unit]
  def comments(articleId: Long, viewerId: Long): Task[List[Comment]]
  def createComment(articleId: Long, authorId: Long, body: String): Task[Comment]
  def commentAuthor(articleId: Long, commentId: Long): Task[Option[Long]]
  def deleteComment(id: Long): Task[Unit]
}
