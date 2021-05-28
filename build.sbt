import scala.util.matching.Regex.{Groups, Match}

crossScalaVersions := Seq("2.11.12", "2.12.14")

lazy val covariant = crossProject.crossType(CrossType.Pure)

lazy val covariantJVM = covariant.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val covariantJS = covariant.js.addSbtFiles(file("../build.sbt.shared"))

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

lazy val invariantJVM = invariant.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val invariantJS = invariant.js.addSbtFiles(file("../build.sbt.shared"))

lazy val shared = crossProject.crossType(CrossType.Pure).dependsOn(covariant)

lazy val sharedJVM = shared.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val sharedJS = shared.js.addSbtFiles(file("../build.sbt.shared"))

lazy val asynchronous = crossProject.crossType(CrossType.Pure).dependsOn(shared, covariant)

lazy val asynchronousJVM = asynchronous.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val asynchronousJS = asynchronous.js.addSbtFiles(file("../build.sbt.shared"))

lazy val AsynchronousPool = crossProject.crossType(CrossType.Pure).dependsOn(asynchronous)

lazy val asynchronouspoolJVM = AsynchronousPool.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val asynchronouspoolJS = AsynchronousPool.js.addSbtFiles(file("../build.sbt.shared"))

lazy val AsynchronousSemaphore = crossProject.crossType(CrossType.Pure).dependsOn(asynchronous)

lazy val AsynchronousSemaphoreJVM = AsynchronousSemaphore.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val AsynchronousSemaphoreJS = AsynchronousSemaphore.js.addSbtFiles(file("../build.sbt.shared"))

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inDependencies(AsynchronousSemaphoreJVM, transitive = true, includeRoot = true) ||
      inProjects(asynchronouspoolJVM, invariantJVM)
    },
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
    scalacOptions += "-Xexperimental",
    scalacOptions += "-Ypartial-unification"
  )

organization in ThisBuild := "com.thoughtworks.raii"

publishArtifact := false
