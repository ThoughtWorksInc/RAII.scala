crossScalaVersions := Seq("2.11.9", "2.12.1")

lazy val RAII = crossProject.crossType(CrossType.Pure)

lazy val RAIIJVM = RAII.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val RAIIJS = RAII.js.addSbtFiles(file("../build.sbt.shared"))

lazy val Shared = project.dependsOn(RAIIJVM)

lazy val EitherTNondeterminism = crossProject.crossType(CrossType.Pure)

lazy val EitherTNondeterminismJVM = EitherTNondeterminism.jvm.addSbtFiles(file("../build.sbt.shared"))

lazy val EitherTNondeterminismJS = EitherTNondeterminism.js.addSbtFiles(file("../build.sbt.shared"))
