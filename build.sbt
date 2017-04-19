crossScalaVersions := Seq("2.11.11", "2.12.2")

lazy val CovariantT = crossProject.crossType(CrossType.Pure)

lazy val CovariantTJVM = CovariantT.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val CovariantTJS = CovariantT.js.addSbtFiles(file("../build.sbt.shared"))

lazy val ResourceFactoryT = crossProject.crossType(CrossType.Pure)

lazy val ResourceFactoryTJVM = ResourceFactoryT.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val ResourceFactoryTJS = ResourceFactoryT.js.addSbtFiles(file("../build.sbt.shared"))

lazy val Shared =
  project.dependsOn(EitherTNondeterminismJVM % Test, ResourceFactoryTJVM, ResourceFactoryTJVM % "test->test")

lazy val RAIITask = project.dependsOn(EitherTNondeterminismJVM, Shared, ResourceFactoryTJVM)

lazy val `sde-raii` =
  crossProject.crossType(CrossType.Pure).dependsOn(ResourceFactoryT, ResourceFactoryT % "test->test", `package`)

lazy val `sde-raiiJVM` = `sde-raii`.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val `sde-raiiJS` = `sde-raii`.js.addSbtFiles(file("../build.sbt.shared"))

lazy val EitherTNondeterminism = crossProject.crossType(CrossType.Pure)

lazy val EitherTNondeterminismJVM = EitherTNondeterminism.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val EitherTNondeterminismJS = EitherTNondeterminism.js.addSbtFiles(file("../build.sbt.shared"))

lazy val FreeTParallelApplicative = crossProject.crossType(CrossType.Pure)

lazy val FreeTParallelApplicativeJVM = FreeTParallelApplicative.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val FreeTParallelApplicativeJS = FreeTParallelApplicative.js.addSbtFiles(file("../build.sbt.shared"))

lazy val KleisliParallelApplicative = crossProject.crossType(CrossType.Pure)

lazy val KleisliParallelApplicativeJVM = KleisliParallelApplicative.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val KleisliParallelApplicativeJS = KleisliParallelApplicative.js.addSbtFiles(file("../build.sbt.shared"))

lazy val `package` = crossProject.crossType(CrossType.Pure).dependsOn(ResourceFactoryT)

lazy val packageJVM = `package`.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val packageJS = `package`.js.addSbtFiles(file("../build.sbt.shared"))

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inProjects(ResourceFactoryTJVM, packageJVM, Shared, `sde-raiiJVM`, RAIITask)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental"
  )

organization in ThisBuild := "com.thoughtworks.raii"

publishArtifact := false
