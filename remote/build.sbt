libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.4"
libraryDependencies += "com.typesafe.akka" %% "akka-remote" % "2.5.4"

libraryDependencies += "com.thoughtworks.each" %% "each" % "3.3.1"

scalacOptions += "-Ypartial-unification"

enablePlugins(Example)

exampleSuperTypes ~= { oldExampleSuperTypes =>
  import oldExampleSuperTypes._
  updated(indexOf("_root_.org.scalatest.FreeSpec"), "_root_.org.scalatest.AsyncFreeSpec")
}
