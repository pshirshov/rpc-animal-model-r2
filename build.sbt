name := "rpc-model-r2"

version := "0.1"

scalaVersion := "2.13.0"

libraryDependencies in ThisBuild += "org.scalatest" %% "scalatest" % "3.0.8" % "test"

libraryDependencies in ThisBuild += "dev.zio" %% "zio" % "1.0.0-RC12-1"

libraryDependencies in ThisBuild += "io.7mind.izumi" %% "fundamentals-bio" % "0.9.5-M11"

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

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
