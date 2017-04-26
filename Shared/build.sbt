libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % Test

libraryDependencies += "org.scalaz" %% "scalaz-concurrent" % "7.2.11"

libraryDependencies += "com.thoughtworks.tryt" %% "tryt" % "2.0.0-SNAPSHOT" % Test

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

scalacOptions += "-Ypartial-unification"