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

    val mr = managed(new MyResource)
    for (r <- mr) {
      events += "using r"
    }
    events should be(mutable.Buffer("open 0", "using r", "close 0"))
  }

  "must close when error occur" ignore {

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

    val mr = managed(new MyResource)

    try {
      for (r <- mr) {
        events += "error is coming"
        throw Boom()
      }
    } catch {
      case _: Boom =>
    }
    events should be(mutable.Buffer("open 0", "error is coming", "close 0"))
  }

  "must throw IllegalStateException when close throw exception" ignore {

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
        throw Boom()
      }
    }

    val mr = managed(new MyResource)

    //    recoverToSucceededIf[Boom]{
    //      for ( r <- mr) {
    //        r.isOpened should be(true)
    //      }
    //    }

    intercept[IllegalStateException] {
      for (r <- mr) {
        events += "error is coming"
      }
    }

    events should be(mutable.Buffer("open r", "error is coming", "close r"))
  }

  "must throw Exception when close throw exception and another error occur" ignore {
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
        throw Boom()
      }
    }

    val mr = managed(new MyResource)

    intercept[Boom] {
      for (r <- mr) {
        events += "error is coming"
        throw Boom()
      }
    }

    events should be(mutable.Buffer("open 0", "error is coming", "close 0"))
  }

  "must extract value" ignore {

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

      def generateData() = Some(math.random())

    }

    val mr = managed(new MyResource)

    for (r <- mr) yield {
      val data = r.generateData
      data.isDefined should be(true)
    }
    events should be(mutable.Buffer("open 0", "close 0"))
  }

  "both of resource must open and close" in {

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

      def generateData() = Some(math.random())

    }

    val mr1 = managed(new MyResource)
    val mr2 = managed(new MyResource)

    for (r1 <- mr1; r2 <- mr2) {
      events += "using r1"
      events += "using r2"
    }

    events should be(mutable.Buffer("open 0", "open 1", "using r1", "using r2", "close 1", "close 0"))
  }

  "must not close twice" ignore {

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

      def generateData() = Some(math.random())

    }

    val mr = managed(new MyResource)

    for (r1 <- mr; r2 <- mr) {
      events += "using r1"
      events += "using r2"
    }
    events should be(mutable.Buffer("open 0", "using r1", "using r2", "close 0"))
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

      def generateData() = Some(math.random())

    }

    val mr = managed(new MyResource)

    mr foreach { r =>
      events += "using 0"
    }
    events should be(mutable.Buffer("open 0", "using 0", "close 0"))
  }

  // "mustAcquireAndGet"  "mustReturnFirstExceptionInAcquireAndGet" "mustJoinSequence" mustCreateTraversable mustCreateTraversableForExpression
  // mustCreateTraversableMultiLevelForExpression mustErrorOnTraversal mustAllowApplyUsage

  "must could be shared" in {

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

      def generateData() = Some(math.random())

    }

    val mr1 = managed(new MyResource)
    val mr2 = managed(new MyResource)

    for (r1 <- mr1) {
      for (r2 <- mr2) {
        events += "using 0"
        events += "using 1"
        val areBothDefined = r1.generateData().isDefined & r2.generateData().isDefined
        areBothDefined should be(true)
      }
    }
    events should be(mutable.Buffer("open 0", "open 1", "using 0", "using 1", "close 1", "close 0"))
  }

  //mustBeSuccessFuture mustBeFailedFuture mustBeSuccessTry mustBeFailedTry

}
