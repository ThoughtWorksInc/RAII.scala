libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % Test

libraryDependencies += "com.thoughtworks.tryt" %% "covariant" % "2.0.3" % Test

libraryDependencies += "com.thoughtworks.future" %% "continuation" % "1.0.0-M2"

libraryDependencies += "com.thoughtworks.future" %% "future" % "1.0.0-M2" % Test

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

scalacOptions += "-Ypartial-unification"
