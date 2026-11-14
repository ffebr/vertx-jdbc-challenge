ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version      := "1.0.0"
ThisBuild / organization := "io.github.ffebr.challenge"

val zioVersion     = "2.1.20"
val vertxVersion   = "4.5.36"
val tapirVersion   = "1.11.44"
val stage          = taskKey[File]("Stage a standalone application and its runtime dependencies")
val submissionRoot = file(".").getCanonicalFile

val publishedQuillVersion = sys.props.get("conduit.quill.published.version").map { version =>
  require(
    version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?") && !version.endsWith("SNAPSHOT"),
    "conduit.quill.published.version must be an immutable release version"
  )
  version
}
val quillPackageResolver =
  "GitHub Packages" at "https://maven.pkg.github.com/ffebr/zio-quill-vertx"
val quillPackageCredentials =
  publishedQuillVersion.toSeq.map { _ =>
    val username = sys.env.getOrElse(
      "GITHUB_PACKAGES_USER",
      sys.error("GITHUB_PACKAGES_USER is required in published mode")
    )
    val token = sys.env.getOrElse(
      "GITHUB_PACKAGES_TOKEN",
      sys.error("GITHUB_PACKAGES_TOKEN is required in published mode")
    )
    require(username.nonEmpty && token.nonEmpty, "GitHub Packages credentials must not be empty")
    Credentials("GitHub Package Registry", "maven.pkg.github.com", username, token)
  }
ThisBuild / resolvers ++= publishedQuillVersion.toSeq.map(_ => quillPackageResolver)
ThisBuild / credentials ++= quillPackageCredentials
// Compare the original JDBC executor with the native executor on the same Quill
// source revision and namespace, rather than mixing unrelated Quill releases.
val quillSource = sys.props.get("conduit.quill.source").map(path => file(path).toURI)
  .getOrElse(uri("https://github.com/ffebr/zio-quill-vertx.git#5a29bb669109b81694a763ade3e6221f861ef702"))

lazy val common = Seq(
  Compile / unmanagedSourceDirectories += submissionRoot / "shared" / "src" / "main" / "scala",
  Compile / unmanagedResourceDirectories += submissionRoot / "shared" / "src" / "main" / "resources",
  Test / unmanagedSourceDirectories += submissionRoot / "shared" / "src" / "test" / "scala",
  Compile / mainClass := Some("conduit.Main"),
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-encoding", "UTF-8", "-release:17"),
  libraryDependencies ++= Seq(
    "dev.zio"                     %% "zio"                    % zioVersion,
    "dev.zio"                     %% "zio-streams"            % zioVersion,
    "dev.zio"                     %% "zio-json"               % "0.7.44",
    "com.softwaremill.sttp.tapir" %% "tapir-vertx-server-zio" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-json-zio"         % tapirVersion,
    "io.vertx"                     % "vertx-web"              % vertxVersion,
    "com.auth0"                    % "java-jwt"               % "4.5.0",
    "at.favre.lib"                 % "bcrypt"                 % "0.10.2",
    "ch.qos.logback"               % "logback-classic"        % "1.5.18",
    "dev.zio"                     %% "zio-test"               % zioVersion % Test,
    "dev.zio"                     %% "zio-test-sbt"           % zioVersion % Test
  ),
  dependencyOverrides ++= Seq(
    "dev.zio" %% "zio"               % zioVersion,
    "dev.zio" %% "zio-streams"       % zioVersion,
    "io.vertx" % "vertx-core"        % vertxVersion,
    "io.vertx" % "vertx-web"         % vertxVersion,
    "io.vertx" % "vertx-web-common"  % vertxVersion,
    "io.vertx" % "vertx-auth-common" % vertxVersion
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  Test / fork := true,
  stage       := {
    val directory = target.value / "stage"
    val libraries = (Runtime / fullClasspathAsJars).value.map(_.data)
    IO.delete(directory)
    IO.createDirectory(directory / "lib")
    IO.copy(libraries.map(jar => jar -> directory / "lib" / jar.getName))
    streams.value.log.info(s"Staged ${libraries.size} runtime jars in $directory")
    directory
  }
)

lazy val jdbc = project.in(file("jdbc")).dependsOn(ProjectRef(quillSource, "quill-jdbc-zio")).settings(common).settings(
  name := "conduit-jdbc",
  libraryDependencies ++= Seq(
    "com.zaxxer"     % "HikariCP"   % "7.1.0",
    "org.postgresql" % "postgresql" % "42.7.14"
  )
)

lazy val native = {
  val base = Project("native", file("native")).settings(common).settings(
    name := "conduit-native",
    libraryDependencies ++= publishedQuillVersion.toSeq.map { version =>
      "io.github.ffebr" %% "quill-vertx-zio" % version
    }
  )
  publishedQuillVersion match {
    case Some(_) => base
    case None    => base.dependsOn(ProjectRef(quillSource, "quill-vertx-zio"))
  }
}

lazy val root = project.in(file(".")).aggregate(jdbc, native).settings(
  name           := "conduit-quill-comparison",
  publish / skip := true
)
