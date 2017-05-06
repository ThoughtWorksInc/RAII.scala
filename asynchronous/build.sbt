libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % Test

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

scalacOptions += "-Ypartial-unification"

scalacOptions += "-Xexperimental" // Enable SAM types on Scala 2.11

libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.2"