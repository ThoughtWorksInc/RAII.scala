libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test

libraryDependencies += "com.thoughtworks.tryt" %% "covariant" % "2.0.3" % Test

libraryDependencies += "com.thoughtworks.future" %% "continuation" % "2.0.0-M1"

libraryDependencies += "com.thoughtworks.future" %% "future" % "2.0.0-M1" % Test

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

scalacOptions += "-Ypartial-unification"
