crossScalaVersions := Seq("2.11.11", "2.12.2")

lazy val resourcet = crossProject.crossType(CrossType.Pure).dependsOn(ownership % Test)

lazy val resourcetJVM = resourcet.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val resourcetJS = resourcet.js.addSbtFiles(file("../build.sbt.shared"))

lazy val shared =
  project.dependsOn(resourcetJVM, resourcetJVM % "test->test")

lazy val asynchronous = project.dependsOn(shared, resourcetJVM, ownershipJVM)

lazy val ownership = crossProject.crossType(CrossType.Pure)

lazy val ownershipJVM = ownership.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val ownershipJS = ownership.js.addSbtFiles(file("../build.sbt.shared"))

lazy val unidoc = project
  .enablePlugins(StandaloneUnidoc, TravisUnidocTitle)
  .settings(
    UnidocKeys.unidocProjectFilter in ScalaUnidoc in UnidocKeys.unidoc := {
      inDependencies(asynchronous, transitive = true, includeRoot = true)
    },
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
    scalacOptions += "-Xexperimental"
  )

organization in ThisBuild := "com.thoughtworks.raii"

publishArtifact := false
