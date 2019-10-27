name := "rpc-model-r2"

version := "0.1"

scalaVersion := "2.13.0"

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")


libraryDependencies in ThisBuild += "io.7mind.izumi" %% "fundamentals-bio" % "0.9.8"
libraryDependencies in ThisBuild += "dev.zio" %% "zio" % "1.0.0-RC15"

libraryDependencies in ThisBuild += "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0"

libraryDependencies in ThisBuild += "org.asynchttpclient" % "async-http-client" % "2.10.4"

libraryDependencies in ThisBuild ++= Seq(
  "io.undertow" % "undertow-core"  % "2.0.1.Final",
)

val circeVersion = "0.12.1"

libraryDependencies in ThisBuild ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-literal",
).map(_ % circeVersion) ++ Seq(
  "io.circe" %% "circe-derivation" % "0.12.0-M7"
)

libraryDependencies in ThisBuild += "org.scalatest" %% "scalatest" % "3.0.8" % "test"

scalacOptions ++= Seq(
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
