package conduit.http

import conduit.domain.{Article, Profile}
import conduit.http.Dto._
import zio.test._
import zio.json._

import java.time.Instant

object DtoSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] = suite("HTTP DTO wire format")(
    test("user and profile preserve nullable bio and image as explicit JSON nulls") {
      val userJson    = UserEnvelope(UserDto("a@example.test", "token", "author", None, None)).toJson
      val profileJson = ProfileEnvelope(ProfileDto("author", None, None, following = false)).toJson
      assertTrue(
        userJson.contains("\"bio\":null"),
        userJson.contains("\"image\":null"),
        !userJson.contains("passwordHash"),
        profileJson.contains("\"bio\":null"),
        profileJson.contains("\"image\":null")
      )
    },
    test("timestamps truncate to exactly three fractional digits in UTC") {
      val timestamp = Dto.timestamp(Instant.parse("2025-04-01T12:00:00.123987Z"))
      assertTrue(timestamp == "2025-04-01T12:00:00.123Z")
    },
    test("article list DTO omits body and internal article id") {
      val article = Article(
        id = 987654L,
        slug = "safe-article",
        title = "Safe article",
        description = "Description",
        body = Some("body text"),
        tagList = List("scala"),
        createdAt = Instant.parse("2025-04-01T12:00:00.123987Z"),
        updatedAt = Instant.parse("2025-04-01T12:00:01.999999Z"),
        favorited = false,
        favoritesCount = 4L,
        author = Profile("author", None, None, following = false)
      )
      val json = ArticleListEnvelope(List(Dto.articleList(article)), articlesCount = 1L).toJson
      assertTrue(
        json.contains("\"slug\":\"safe-article\""),
        json.contains("\"favoritesCount\":4"),
        json.contains("\"bio\":null"),
        !json.contains("body"),
        !json.contains("987654"),
        !json.contains("passwordHash")
      )
    }
  )
}
