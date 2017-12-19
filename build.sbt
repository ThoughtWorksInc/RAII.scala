import scala.util.matching.Regex.{Groups, Match}

crossScalaVersions := Seq("2.11.11", "2.12.3")

lazy val covariant = crossProject.crossType(CrossType.Pure)

lazy val covariantJVM = covariant.jvm
  .dependsOn(ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(file("future.scala"), "continuationJVM"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val covariantJS = covariant.js
  .dependsOn(ProjectRef(file("future.scala"), "futureJS"), ProjectRef(file("future.scala"), "continuationJS"))
  .addSbtFiles(file("../build.sbt.shared"))

val CovariantRegex = """extends ResourceFactoryTInstances0|[Cc]ovariant|\+\s*([A_])\b""".r

lazy val invariant = crossProject
  .crossType(CrossType.Pure)
  .settings(
    for (configuration <- Seq(Compile, Test)) yield {
      sourceGenerators in configuration += Def.task {
        for {
          covariantFile <- (unmanagedSources in configuration in covariantJVM).value
          covariantDirectory <- (unmanagedSourceDirectories in configuration in covariantJVM).value
          relativeFile <- covariantFile.relativeTo(covariantDirectory)
        } yield {
          val covariantSource = IO.read(covariantFile, scala.io.Codec.UTF8.charSet)

          val doubleSource = CovariantRegex.replaceAllIn(
            covariantSource,
            (_: Match) match {
              case Match("extends ResourceFactoryTInstances0") =>
                "extends ResourceFactoryTInstances0 with ResourceFactoryTInvariantInstances"
              case Match("covariant")         => "invariant"
              case Match("Covariant")         => "Invariant"
              case Groups(name @ ("A" | "_")) => name
            }
          )

          val outputFile = (sourceManaged in configuration).value / relativeFile.getPath
          IO.write(outputFile, doubleSource, scala.io.Codec.UTF8.charSet)
          outputFile
        }
      }.taskValue
    }
  )

lazy val invariantJVM = invariant.jvm
  .dependsOn(ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(file("future.scala"), "continuationJVM"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val invariantJS = invariant.js
  .dependsOn(ProjectRef(file("future.scala"), "futureJS"), ProjectRef(file("future.scala"), "continuationJS"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val shared = crossProject.crossType(CrossType.Pure).dependsOn(covariant)

lazy val sharedJVM = shared.jvm
  .dependsOn(ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(file("future.scala"), "continuationJVM"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val sharedJS = shared.js
  .dependsOn(ProjectRef(file("future.scala"), "futureJS"), ProjectRef(file("future.scala"), "continuationJS"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val asynchronous = crossProject.crossType(CrossType.Pure).dependsOn(shared, covariant)

lazy val asynchronousJVM = asynchronous.jvm
  .dependsOn(ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(file("future.scala"), "continuationJVM"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val asynchronousJS = asynchronous.js
  .dependsOn(ProjectRef(file("future.scala"), "futureJS"), ProjectRef(file("future.scala"), "continuationJS"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val AsynchronousPool = crossProject.crossType(CrossType.Pure).dependsOn(asynchronous)

lazy val asynchronouspoolJVM = AsynchronousPool.jvm
  .dependsOn(ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(file("future.scala"), "continuationJVM"))
  .addSbtFiles(file("../build.sbt.shared")) dependsOn (ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(
  file("future.scala"),
  "continuationJVM"))

lazy val asynchronouspoolJS = AsynchronousPool.js
  .dependsOn(ProjectRef(file("future.scala"), "futureJS"), ProjectRef(file("future.scala"), "continuationJS"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val AsynchronousSemaphore = crossProject.crossType(CrossType.Pure).dependsOn(asynchronous)

lazy val AsynchronousSemaphoreJVM = AsynchronousSemaphore.jvm
  .dependsOn(ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(file("future.scala"), "continuationJVM"))
  .addSbtFiles(file("../build.sbt.shared")) dependsOn (ProjectRef(file("future.scala"), "futureJVM"), ProjectRef(
  file("future.scala"),
  "continuationJVM"))

lazy val AsynchronousSemaphoreJS = AsynchronousSemaphore.js
  .dependsOn(ProjectRef(file("future.scala"), "futureJS"), ProjectRef(file("future.scala"), "continuationJS"))
  .addSbtFiles(file("../build.sbt.shared"))

lazy val remote = project.dependsOn(`asynchronousJVM`)

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inDependencies(AsynchronousSemaphoreJVM, transitive = true, includeRoot = true) ||
      inProjects(asynchronouspoolJVM, invariantJVM)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental",
    scalacOptions += "-Ypartial-unification"
  )

organization in ThisBuild := "com.thoughtworks.raii"

publishArtifact := false
