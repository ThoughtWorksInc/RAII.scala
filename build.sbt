libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.2.10"

libraryDependencies += "org.scalaz" %% "scalaz-concurrent" % "7.2.10"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % Test

crossScalaVersions := Seq("2.11.8", "2.12.1")
