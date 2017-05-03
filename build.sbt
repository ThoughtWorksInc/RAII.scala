crossScalaVersions := Seq("2.11.11", "2.12.2")

lazy val ResourceFactoryT = crossProject.crossType(CrossType.Pure).dependsOn(ownership)

lazy val ResourceFactoryTJVM = ResourceFactoryT.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val ResourceFactoryTJS = ResourceFactoryT.js.addSbtFiles(file("../build.sbt.shared"))

lazy val Shared =
  project.dependsOn(ResourceFactoryTJVM, ResourceFactoryTJVM % "test->test")

lazy val Do = project.dependsOn(Shared, ResourceFactoryTJVM)

lazy val ownership = crossProject.crossType(CrossType.Pure)

lazy val ownershipJVM = ownership.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val ownershipJS = ownership.js.addSbtFiles(file("../build.sbt.shared"))

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inProjects(ResourceFactoryTJVM, Shared, Do)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental"
  )

organization in ThisBuild := "com.thoughtworks.raii"

publishArtifact := false
