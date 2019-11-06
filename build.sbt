resolvers += Resolver.sonatypeRepo("snapshots")
name := "rpc-model-r2"



libraryDependencies in ThisBuild += "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0"
version in ThisBuild := "0.1"
scalaVersion in ThisBuild := "2.13.1"



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

ThisBuild / scalacOptions ++= Seq(
  "-feature",
  "-unchecked",
  "-deprecation",
  "-language:higherKinds",
  "-Xsource:2.13",
  "-explaintypes",
  "-Wdead-code",
  "-Wextra-implicit",
  "-Wnumeric-widen",
  "-Woctal-literal",
  "-Wvalue-discard",
  "-Wunused:_",
  "-Xlint:_"
)

val izumi = "0.9.10"


lazy val core = (project in file("rpc-model"))
  .settings(
    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full),
    libraryDependencies += "io.7mind.izumi" %% "fundamentals-bio" % izumi,
    libraryDependencies += "io.7mind.izumi" %% "fundamentals-functional" % izumi,
    libraryDependencies += "io.7mind.izumi" %% "fundamentals-platform" % izumi,
  )

lazy val util = (project in file("rpc-undertow-ahc"))
  .dependsOn(core)
  .settings(
    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full),
    libraryDependencies += "dev.zio" %% "zio" % "1.0.0-RC15",
    libraryDependencies += "org.asynchttpclient" % "async-http-client" % "2.10.4",
    libraryDependencies ++= Seq(
      "io.undertow" % "undertow-core" % "2.0.1.Final",
    ),
  )