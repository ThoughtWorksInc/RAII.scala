package com.thoughtworks

import java.io.{File, FileInputStream}

import org.scalatest.{AsyncFreeSpec, FreeSpec, Matchers}
import com.thoughtworks.ResourceT._

import scalaz.syntax.all._
import scalaz._
import scala.collection.mutable
import scala.concurrent.Await
import scalaz.Id

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class ResourceTSpec extends FreeSpec with Matchers {

  object Exceptions {
    case class Boom() extends RuntimeException

    case class CanNotCloseResourceTwice() extends RuntimeException

    case class CanNotOpenResourceTwice() extends RuntimeException

    case class CanNotGenerateDataBecauseResourceIsNotOpen() extends RuntimeException
  }

  import Exceptions._

  class FakeResource(resourceName: String) extends AutoCloseable {
    val events: mutable.Buffer[String] = mutable.Buffer.empty[String]

    var isOpened: Boolean = false

    events += s"open $resourceName"
    isOpened = true

    override def close(): Unit = {
      if (isOpened) isOpened = false
      else throw CanNotCloseResourceTwice()
      events += s"close $resourceName"
    }

    def generateData: Option[Double] =
      if (isOpened) Some(math.random) else throw CanNotGenerateDataBecauseResourceIsNotOpen()
  }

  class ThrowExceptionOnCloseFakeResource(resourceName: String) extends FakeResource(resourceName: String) {
    override def close(): Unit = throw Boom()
  }

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

  "must open and close" in {

    val r = new FakeResource("r")
    r.isOpened should be(true)
    val mr = managed(r)
    r.isOpened should be(true)
    for (r <- mr) {
      r.isOpened should be(true)
      r.events += "using r"
    }
    r.isOpened should be(false)
    r.events should be(mutable.Buffer("open r", "using r", "close r"))
  }

  "must close when error occur" ignore {

    val r = new FakeResource("r")
    r.isOpened should be(true)
    val mr = managed(r)
    r.isOpened should be(true)

    try {
      for (r <- mr) {
        r.isOpened should be(true)
        r.events += "error is coming"
        throw Boom()
      }
    } catch {
      case _: Boom =>
    }

    r.isOpened should be(false)
    r.events should be(mutable.Buffer("open r", "error is coming", "close r"))
  }

  "must throw IllegalStateException when close throw exception" ignore { //or something else but raw exception
    val r = new ThrowExceptionOnCloseFakeResource("r")
    r.isOpened should be(true)
    val mr = managed(r)
    r.isOpened should be(true)

    //    recoverToSucceededIf[Boom]{
    //      for ( r <- mr) {
    //        r.isOpened should be(true)
    //      }
    //    }

    intercept[IllegalStateException] {
      for (r <- mr) {
        r.events += "error is coming"
        r.isOpened should be(true)
      }
    }

    r.isOpened should be(true)

    r.events should be(mutable.Buffer("open r", "error is coming", "close r"))
  }

  "must throw Exception when close throw exception and another error occur" in {
    val r = new ThrowExceptionOnCloseFakeResource("r")
    r.isOpened should be(true)
    val mr = managed(r)
    r.isOpened should be(true)

    intercept[Boom] {
      for (r <- mr) {
        r.isOpened should be(true)
        r.events += "error is coming"
        throw Boom()
      }
    }

    r.isOpened should be(true)
    r.events should be(mutable.Buffer("open r", "error is coming"))
  }

  "must extract value" ignore {
    val r = new FakeResource("r")
    r.isOpened should be(true)
    val mr = managed(r)
    r.isOpened should be(true)
    for (r <- mr) yield {
      r.isOpened should be(true)
      val data = r.generateData
      data.isDefined should be(true)
    }
    r.isOpened should be(false)
    r.events should be(mutable.Buffer("open r", "close r"))
  }

  "both of resource must open and close" in {

    val r1 = new FakeResource("r1")
    val r2 = new FakeResource("r2")
    r1.isOpened should be(true)
    r2.isOpened should be(true)

    val mr1 = managed(r1)
    val mr2 = managed(r2)

    r1.isOpened should be(true)
    r2.isOpened should be(true)

    for (r1 <- mr1; r2 <- mr2) {
      r1.isOpened should be(true)
      r1.events += "using r1"
      r2.isOpened should be(true)
      r2.events += "using r2"
    }

    r1.isOpened should be(false)
    r2.isOpened should be(false)
    r1.events should be(mutable.Buffer("open r1", "using r1", "close r1"))
    r2.events should be(mutable.Buffer("open r2", "using r2", "close r2"))
  }

  "must not close twice" ignore {
    val r = new FakeResource("r")
    r.isOpened should be(true)
    val mr = managed(r)
    r.isOpened should be(true)

    for (r1 <- mr; r2 <- mr) {
      r1.isOpened should be(true)
      r1.events += "using r1"
      r2.isOpened should be(true)
      r2.events += "using r2"
    }
    r.isOpened should be(false)
    r.events should be(mutable.Buffer("open r", "using r1", "using r1", "close r"))
  }

//  "must extract for yield" in {
//
//    val r1 = new FakeResource("r1")
//    val r2 = new FakeResource("r2")
//    r1.isOpened should be(true)
//    r2.isOpened should be(true)
//
//    val mr1 = managed(r1)
//    val mr2 = managed(r2)
//
//    r1.isOpened should be(true)
//    r2.isOpened should be(true)
//
//    val areBothDefined: Boolean = for (r1 <- mr1; r2 <- mr2)
//      yield r1.generateData.isDefined & r2.generateData.isDefined
//
//    areBothDefined should be(true)
//
//    r1.isOpened should be(false)
//    r2.isOpened should be(false)
//  }

//  "must return capture all exceptions" in {
//    val r = new ThrowExceptionOnCloseFakeResource()
//
//    val m: ResourceT[Id.Id, ThrowExceptionOnCloseFakeResource] = managed(r)
//
//    ???
//  }
//
//  "mustNestCaptureAllExceptions" in {
//    ???
//  }
//
//  "mustNestCaptureAllExceptions_and" in {
//    ???
//  }

//  "must support vals in for" in {
//    val r1 = new FakeResource("r1")
//    val r2 = new FakeResource("r2")
//    r1.isOpened should be(true)
//    r2.isOpened should be(true)
//
//    val mr1 = managed(r1)
//    val mr2 = managed(r2)
//
//    r1.isOpened should be(true)
//    r2.isOpened should be(true)
//
//    val areBothDefined: Boolean = for {
//      r1 <- mr1
//      dataOfR1: Option[Double] = r1.generateData
//      r2 <- mr2
//      dataOfR2: Option[Double] = r2.generateData
//    } yield dataOfR1.isDefined & dataOfR2.isDefined
//
//    areBothDefined should be(true)
//
//    r1.isOpened should be(false)
//    r2.isOpened should be(false)
//  }

//
//  "mustAcquireFor" in {
//    ???
//  }
//
//  "mustCloseOnException" in {
//    ???
//  }
//
  "must close on return" in {
    val r = new FakeResource("r")
    r.isOpened should be(true)
    val mr = managed(r)
    r.isOpened should be(true)
    mr foreach { r =>
      r.isOpened should be(true)
      r.events += "using r"
      new FakeResource("whatever")
    }
    r.isOpened should be(false)
    r.events should be(mutable.Buffer("open r", "using r", "close r"))
  }

  // "mustAcquireAndGet"  "mustReturnFirstExceptionInAcquireAndGet" "mustJoinSequence" mustCreateTraversable mustCreateTraversableForExpression
  // mustCreateTraversableMultiLevelForExpression mustErrorOnTraversal mustAllowApplyUsage

  "must could be shared" in {
    val r1 = new FakeResource("r1")
    val mr1 = managed(r1)

    val r2 = new FakeResource("r2")
    val mr2 = managed(r2)

    for (r1 <- mr1) {
      for (r2 <- mr2) {
        r1.events += "using r1"
        r2.events += "using r2"
        val areBothDefined = r1.generateData.isDefined & r2.generateData.isDefined
        areBothDefined should be(true)
      }
    }
    r1.events should be(mutable.Buffer("open r1", "using r1", "close r1"))
    r2.events should be(mutable.Buffer("open r2", "using r2", "close r2"))
  }

  //mustBeSuccessFuture mustBeFailedFuture mustBeSuccessTry mustBeFailedTry

}
