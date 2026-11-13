ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version      := "1.0.0"
ThisBuild / organization := "io.github.ffebr.challenge"

val zioVersion     = "2.1.20"
val vertxVersion   = "4.5.36"
val tapirVersion   = "1.11.44"
val stage          = taskKey[File]("Stage a standalone application and its runtime dependencies")
val submissionRoot = file(".").getCanonicalFile
// Compare the original JDBC executor with the native executor on the same Quill
// source revision and namespace, rather than mixing unrelated Quill releases.
val quillSource = sys.props.get("conduit.quill.source").map(path => file(path).toURI)
  .getOrElse(uri("https://github.com/ffebr/zio-quill-vertx.git#4dcd91128fda7d79e0614b1cb6abe2ca35258d6c"))

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

lazy val native = project.in(file("native")).dependsOn(ProjectRef(quillSource, "quill-vertx-zio"))
  .settings(common).settings(name := "conduit-native")

lazy val root = project.in(file(".")).aggregate(jdbc, native).settings(
  name           := "conduit-quill-comparison",
  publish / skip := true
)
