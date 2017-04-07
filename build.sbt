crossScalaVersions := Seq("2.11.8", "2.12.1")

lazy val ResourceFactoryT = crossProject.crossType(CrossType.Pure)

lazy val ResourceFactoryTJVM = ResourceFactoryT.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val ResourceFactoryTJS = ResourceFactoryT.js.addSbtFiles(file("../build.sbt.shared"))

lazy val Shared = project.dependsOn(ResourceFactoryTJVM, ResourceFactoryTJVM % "test->test")

lazy val EitherTNondeterminism = crossProject.crossType(CrossType.Pure)

lazy val EitherTNondeterminismJVM = EitherTNondeterminism.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val EitherTNondeterminismJS = EitherTNondeterminism.js.addSbtFiles(file("../build.sbt.shared"))

lazy val `package` = crossProject.crossType(CrossType.Pure).dependsOn(ResourceFactoryT)

lazy val packageJVM = `package`.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val packageJS = `package`.js.addSbtFiles(file("../build.sbt.shared"))

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inAnyProject -- inProjects(ResourceFactoryTJS, EitherTNondeterminismJS, packageJS)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")
  )
