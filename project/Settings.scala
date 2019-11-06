import sbt._
import sbt.Keys._

object Settings {
  val s212 = "2.12.10"
  val s213 = "2.13.1"

  implicit class ProjectEx(project: Project) {
    def shared(): Project ={
      project.settings(
        addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full),
        crossScalaVersions := Seq(s213, s212),
        scalaVersion := s213,
        scalacOptions ++= { (isSnapshot.value, scalaVersion.value) match {
          case (_, "2.12.10") => Seq(
            "-Xsource:2.13",
            "-Ypartial-unification",
            "-Yno-adapted-args",
            "-Xlint:adapted-args",
            "-Xlint:by-name-right-associative",
            "-Xlint:constant",
            "-Xlint:delayedinit-select",
            "-Xlint:doc-detached",
            "-Xlint:inaccessible",
            "-Xlint:infer-any",
            "-Xlint:missing-interpolator",
            "-Xlint:nullary-override",
            "-Xlint:nullary-unit",
            "-Xlint:option-implicit",
            "-Xlint:package-object-classes",
            "-Xlint:poly-implicit-overload",
            "-Xlint:private-shadow",
            "-Xlint:stars-align",
            "-Xlint:type-parameter-shadow",
            "-Xlint:unsound-match",
            "-opt-warnings:_",
            "-Ywarn-extra-implicit",
            "-Ywarn-unused:_",
            "-Ywarn-adapted-args",
            "-Ywarn-dead-code",
            "-Ywarn-inaccessible",
            "-Ywarn-infer-any",
            "-Ywarn-nullary-override",
            "-Ywarn-nullary-unit",
            "-Ywarn-numeric-widen",
            "-Ywarn-unused-import",
            "-Ywarn-value-discard"
          )
          case (_, "2.13.1") => Seq(
            "-Xlint:_,-eta-sam",
            "-Wdead-code",
            "-Wextra-implicit",
            "-Wnumeric-widen",
            "-Woctal-literal",
            "-Wunused:_",
            "-Wvalue-discard"
          )
          case (_, _) => Seq.empty
        } }
      )
    }

  }
}
