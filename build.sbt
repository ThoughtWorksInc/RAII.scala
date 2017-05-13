import scala.util.matching.Regex.{Groups, Match}

crossScalaVersions := Seq("2.11.11", "2.12.2")

lazy val covariant = crossProject.crossType(CrossType.Pure)

lazy val covariantJVM = covariant.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val covariantJS = covariant.js.addSbtFiles(file("../build.sbt.shared"))

val CovariantRegex = """extends ResourceFactoryTInstances0|covariant|\+\s*([A_])\b""".r

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
              case Match("covariant") => "invariant"
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

lazy val shared =
  project.dependsOn(covariantJVM)

lazy val asynchronous = project.dependsOn(shared, covariantJVM)

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inDependencies(asynchronous, transitive = true, includeRoot = true) || inProjects(invariantJVM)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental"
  )

organization in ThisBuild := "com.thoughtworks.raii"

publishArtifact := false
