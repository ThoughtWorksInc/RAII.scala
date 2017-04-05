package com.thoughtworks

import java.io.{File, FileInputStream}

import com.thoughtworks.Exceptions.{CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import org.scalatest._
import com.thoughtworks.ResourceT._
import com.thoughtworks.ResourceTSpec.FakeResource

import scalaz.syntax.all._
import scalaz._
import scala.collection.mutable
import scala.concurrent.{Await, Promise}
import scalaz.Id.Id
import scalaz.concurrent.{Future, Task}

object ResourceTSpec {

  def createIdGenerator(): () => String = {
    var nextId = 0;
    { () =>
      val id = nextId
      nextId += 1
      id.toString
    }
  }

  final class FakeResource(allOpenedResources: mutable.HashMap[String, FakeResource], idGenerator: () => String)
      extends {
    val id = idGenerator()
  } with AutoCloseable {

    def this(allOpenedResources: mutable.HashMap[String, FakeResource], constantId: String) = {
      this(allOpenedResources, { () =>
        constantId
      })
    }

    if (allOpenedResources.contains(id)) {
      throw CanNotOpenResourceTwice()
    }
    allOpenedResources(id) = this

    override def close(): Unit = {
      val removed = allOpenedResources.remove(id)

      //noinspection OptionEqualsSome
      if (removed != Some(this)) {
        throw CanNotCloseResourceTwice()
      }
    }
  }

}

object Exceptions {
  case class Boom() extends RuntimeException

  case class CanNotCloseResourceTwice() extends RuntimeException

  case class CanNotOpenResourceTwice() extends RuntimeException

  case class CanNotGenerateDataBecauseResourceIsNotOpen() extends RuntimeException
}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class ResourceTSpec extends AsyncFreeSpec with Matchers with Inside {
  import ResourceTSpec._

  import Exceptions._

  "must open and close" in {
    val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResouces, "r0"))
    allOpenedResouces.keys shouldNot contain("r0")
    for (r0 <- mr0) {
      allOpenedResouces("r0") should be(r0)
    }
    allOpenedResouces.keys shouldNot contain("r0")
  }

  "when working with scalaz's Future, it must asynchronously open and close" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed[Future, FakeResource](new FakeResource(allOpenedResouces, "r0"))
    allOpenedResouces.keys shouldNot contain("r0")
    val asynchronousResource: Future[Unit] = mr0.using { r0 =>
      Future.delay {
        events += "using r0"
        allOpenedResouces("r0") should be(r0)
      }
    }

    val p = Promise[Assertion]
    asynchronousResource.unsafePerformAsync { _ =>
      p.success {
        allOpenedResouces.keys shouldNot contain("r0")
        events should be(Seq("using r0"))
      }
    }
    p.future
  }

  "when working with scalaz's EitherT" - {

    "must asynchronously open and close when an exception occurs" ignore {
      import scalaz.concurrent.Task._
      val events = mutable.Buffer.empty[String]
      val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managed[Future, FakeResource](new FakeResource(allOpenedResouces, "r0"))
      allOpenedResouces.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = new Task(mr0.using { r0 =>
        Future.delay {
          events += "using r0"
          -\/(new Boom: Throwable)
        }
      })

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { either =>
        p.success {
          inside(either) {
            case -\/(e) =>
              e should be(a[Boom])
          }
          allOpenedResouces.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }
  }
  "when working with scalaz's Task" - {
    "must asynchronously open and close" in {
      val events = mutable.Buffer.empty[String]
      val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managed[Task, FakeResource](new FakeResource(allOpenedResouces, "r0"))
      allOpenedResouces.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = mr0.using { r0 =>
        Task.delay {
          events += "using r0"
          allOpenedResouces("r0") should be(r0)
        }
      }

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResouces.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }

    "must asynchronously open and close when an exception occurs" ignore {
      import scalaz.concurrent.Task._
      val events = mutable.Buffer.empty[String]
      val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managed[Task, FakeResource](new FakeResource(allOpenedResouces, "r0"))
      allOpenedResouces.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = mr0.using { r0 =>
        events += "using r0"
        (new Boom: Throwable).raiseError
      }

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResouces.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }

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

      def generateData() = Some(math.random)

    }

    val mr = managed(new MyResource)

    for (r <- mr) yield {
      val data = r.generateData
      data.isDefined should be(true)
    }
    events should be(mutable.Buffer("open 0", "close 0"))
  }

  "both of resources must open and close" in {

    val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResouces, "mr0"))
    val mr1 = managed(new FakeResource(allOpenedResouces, "mr1"))
    allOpenedResouces.keys shouldNot contain("mr0")
    allOpenedResouces.keys shouldNot contain("mr1")

    for (r0 <- mr0; r1 <- mr1) {
      allOpenedResouces("mr0") should be(r0)
      allOpenedResouces("mr1") should be(r1)
    }

    allOpenedResouces.keys shouldNot contain("mr0")
    allOpenedResouces.keys shouldNot contain("mr1")
  }

  "both of resources must open and close in complicated usage" in {

    val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResouces, "mr0"))
    val mr1 = managed(new FakeResource(allOpenedResouces, "mr1"))
    allOpenedResouces.keys shouldNot contain("mr0")
    allOpenedResouces.keys shouldNot contain("mr1")

    for (r0 <- mr0; x = 0;
         r1 <- mr1; y = 1) {
      allOpenedResouces("mr0") should be(r0)
      allOpenedResouces("mr1") should be(r1)
    }

    allOpenedResouces.keys shouldNot contain("mr0")
    allOpenedResouces.keys shouldNot contain("mr1")

  }
  "must open and close twice" in {

    val allOpenedResouces = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = ResourceTSpec.createIdGenerator()
    val mr = managed(new FakeResource(allOpenedResouces, idGenerator))
    allOpenedResouces.keys shouldNot contain("0")
    allOpenedResouces.keys shouldNot contain("1")

    for (r0 <- mr; r1 <- mr) {
      allOpenedResouces("0") should be(r0)
      allOpenedResouces("1") should be(r1)

    }
    allOpenedResouces.keys shouldNot contain("0")
    allOpenedResouces.keys shouldNot contain("1")

  }

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
//    val r0 = new FakeResource("r0")
//    val r1 = new FakeResource("r1")
//    r0.isOpened should be(true)
//    r1.isOpened should be(true)
//
//    val mr0 = managed(r0)
//    val mr1 = managed(r1)
//
//    r0.isOpened should be(true)
//    r1.isOpened should be(true)
//
//    val areBothDefined: Boolean = for {
//      r0 <- mr0
//      dataOfr0: Option[Double] = r0.generateData
//      r1 <- mr1
//      dataOfr1: Option[Double] = r1.generateData
//    } yield dataOfr0.isDefined & dataOfr1.isDefined
//
//    areBothDefined should be(true)
//
//    r0.isOpened should be(false)
//    r1.isOpened should be(false)
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

  // "mustAcquireAndGet"  "mustReturnFirstExceptionInAcquireAndGet" "mustJoinSequence" mustCreateTraversable mustCreateTraversableForExpression
  // mustCreateTraversableMultiLevelForExpression mustErrorOnTraversal mustAllowApplyUsage

  "must could be shared" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = ResourceTSpec.createIdGenerator()
    val mr = managed(new FakeResource(allOpenedResources, idGenerator))
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

    for (r0 <- mr) {
      allOpenedResources("0") should be(r0)
      allOpenedResources.keys shouldNot contain("1")
      for (r1 <- mr) {
        allOpenedResources("0") should be(r0)
        allOpenedResources("1") should be(r1)
      }
      allOpenedResources.keys shouldNot contain("1")
    }
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")
  }

  //mustBeSuccessFuture mustBeFailedFuture mustBeSuccessTry mustBeFailedTry

  "reference count test without shared" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    intercept[CanNotOpenResourceTwice] {
      for (r0 <- mr0; r1 <- mr0) {
        allOpenedResources("r0") should be(r0)
      }
    }
    allOpenedResources.keys should contain("r0")
  }

  "reference count test without shared -- async" ignore {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr: ResourceT[Future, FakeResource] =
      managed[Future, FakeResource](new FakeResource(allOpenedResources, "0"))
    allOpenedResources.keys shouldNot contain("0")

    recoverToSucceededIf[CanNotOpenResourceTwice] {
      val usingResource = mr.flatMap { r1 =>
        mr.map { r2 =>
          allOpenedResources.keys should contain("0")
          events += "using 0"
        }
      }

      val asynchronousResource: Future[Unit] = usingResource.using { _ =>
        Future.now(())
      }

      val p = Promise[Assertion]

      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResources.keys shouldNot contain("0")
          events should be(Seq("using 0"))
        }
      }
      p.future
    }

  }

  "reference count test with shared -- async" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr: ResourceT[Future, FakeResource] =
      managed[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared
    allOpenedResources.keys shouldNot contain("0")

    val usingResource: ResourceT[Future, mutable.Buffer[String]] = mr.flatMap { r1 =>
      mr.map { r2 =>
        allOpenedResources.keys should contain("0")
        events += "using 0"
      }
    }

    val asynchronousResource: Future[Unit] = usingResource.using { _ =>
      Future.now(())
    }

    val p = Promise[Assertion]

    asynchronousResource.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("0")
        events should be(Seq("using 0"))
      }
    }
    p.future
  }

}
