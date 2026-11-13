package conduit.persistence

import conduit.domain.{Profile, UserPatch, UserRecord, UserRepository}
import zio.Task
import zio.quill._

final class QuillUserRepository(database: Database) extends UserRepository {
  private val ctx = database.context
  import ctx._

  private case class FollowPair(followerId: Long, followeeId: Long)

  override def byId(id: Long): Task[Option[UserRecord]] =
    database.execute(ctx.run(quote(querySchema[UserRecord]("users").filter(_.id == lift(id)))).map(_.headOption))

  override def byEmail(email: String): Task[Option[UserRecord]] =
    database.execute(ctx.run(quote(querySchema[UserRecord]("users").filter(_.email == lift(email)))).map(_.headOption))

  override def byUsername(username: String): Task[Option[UserRecord]] =
    database.execute(
      ctx.run(quote(querySchema[UserRecord]("users").filter(_.username == lift(username)))).map(_.headOption)
    )

  override def create(username: String, email: String, passwordHash: String): Task[UserRecord] = {
    val insert = quote(querySchema[UserRecord]("users").insert(
      _.username     -> lift(username),
      _.email        -> lift(email),
      _.passwordHash -> lift(passwordHash)
    ).returningGenerated(_.id))

    database.execute(ctx.transaction {
      for {
        id  <- ctx.run(insert)
        row <- ctx.run(quote(querySchema[UserRecord]("users").filter(_.id == lift(id)))).map(_.head)
      } yield row
    })
  }

  override def update(id: Long, patch: UserPatch): Task[Option[UserRecord]] = {
    val update = quote(querySchema[UserRecord]("users").filter(_.id == lift(id)).update(
      u => u.email        -> lift(patch.email).getOrElse(u.email),
      u => u.username     -> lift(patch.username).getOrElse(u.username),
      u => u.passwordHash -> lift(patch.passwordHash).getOrElse(u.passwordHash),
      u => u.bio          -> lift(patch.bio).orElse(u.bio),
      u => u.image        -> lift(patch.image).orElse(u.image)
    ))

    database.execute(ctx.transaction {
      for {
        _   <- ctx.run(update)
        row <- ctx.run(quote(querySchema[UserRecord]("users").filter(_.id == lift(id)))).map(_.headOption)
      } yield row
    })
  }

  override def profile(username: String, viewerId: Long): Task[Option[Profile]] = {
    val result = quote(querySchema[UserRecord]("users").filter(_.username == lift(username)).map { user =>
      val following = querySchema[FollowPair]("follows").filter(f =>
        f.followerId == lift(viewerId) && f.followeeId == user.id
      ).nonEmpty
      Profile(user.username, user.bio, user.image, following)
    })
    database.execute(ctx.run(result).map(_.headOption))
  }

  override def setFollowing(followerId: Long, followeeId: Long, following: Boolean): Task[Unit] = {
    if (following) {
      val insert =
        quote(querySchema[FollowPair]("follows").insertValue(lift(FollowPair(followerId, followeeId))).onConflictIgnore)
      database.execute(ctx.run(insert).unit)
    } else {
      val delete = quote(querySchema[FollowPair]("follows").filter(f =>
        f.followerId == lift(followerId) && f.followeeId == lift(followeeId)
      ).delete)
      database.execute(ctx.run(delete).unit)
    }
  }
}
