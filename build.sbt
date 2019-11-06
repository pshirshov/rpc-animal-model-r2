import Settings._

resolvers += Resolver.sonatypeRepo("snapshots")
name := "rpc-model-r2"



libraryDependencies in ThisBuild ++= Seq(
  "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.2",
)
version in ThisBuild := "0.1"



val circeVersion = "0.12.1"

ThisBuild / libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-literal",
).map(_ % circeVersion) ++ Seq(
  "io.circe" %% "circe-derivation" % "0.12.0-M7"
)

ThisBuild / libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % "test"

val izumi = "0.9.10"

lazy val core = (project in file("rpc-model"))
  .shared()
  .settings(
    libraryDependencies += "io.7mind.izumi" %% "fundamentals-bio" % izumi,
    libraryDependencies += "io.7mind.izumi" %% "fundamentals-functional" % izumi,
    libraryDependencies += "io.7mind.izumi" %% "fundamentals-platform" % izumi,
  )

lazy val util = (project in file("rpc-undertow-ahc"))
  .shared()
  .dependsOn(core)
  .settings(
    libraryDependencies += "dev.zio" %% "zio" % "1.0.0-RC15",
    libraryDependencies += "org.asynchttpclient" % "async-http-client" % "2.10.4",
    libraryDependencies ++= Seq(
      "io.undertow" % "undertow-core" % "2.0.1.Final",
    ),
  )

lazy val root = (project in file(".")).shared().aggregate(core, util)