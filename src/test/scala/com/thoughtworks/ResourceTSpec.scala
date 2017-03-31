package com.thoughtworks

import org.scalatest.{AsyncFreeSpec, FreeSpec, Matchers}

import scala.collection.mutable

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class ResourceTSpec extends FreeSpec with Matchers {
  "complicated id" in {

    import scalaz.syntax.all._
    import scalaz._
    import ResourceT._

    val events = mutable.Buffer.empty[String]

    var seed = 0
    def nextId() = {
      val result = seed
      seed += 1
      result
    }
    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"open $id"

      override def close(): Unit = {
        events += s"close $id"
      }
    }

    val resource = managed(new MyResource)

    for (r1 <- ToFunctorOps[ResourceT[Id.Id, ?], MyResource](resource)(ResourceT.apply[Id.Id]);
         x = 0;
         r2 <- resource;
         y = 1) {
      events += "using r1 and r2"
    }

    events should be(mutable.Buffer("open 0", "open 1", "using r1 and r2", "close 1", "close 0"))

  }

  "id" in {

    import scalaz.syntax.all._
    import scalaz._
    import ResourceT._

    val events = mutable.Buffer.empty[String]

    var seed = 0
    def nextId() = {
      val result = seed
      seed += 1
      result
    }
    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"open $id"

      override def close(): Unit = {
        events += s"close $id"
      }
    }

    val resource = managed(new MyResource)

    for (r1 <- resource; r2 <- resource) {
      events += "using r1 and r2"
    }

    events should be(mutable.Buffer("open 0", "open 1", "using r1 and r2", "close 1", "close 0"))

  }

}
