package com.thoughtworks.raii

import java.io.{File, FileInputStream}

import org.scalatest._
import com.thoughtworks.raii.RAII._
import com.thoughtworks.raii.RAIISpec.Exceptions.{CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import com.thoughtworks.raii.RAIISpec.FakeResource

import scalaz.syntax.all._
import scalaz._
import scala.collection.mutable
import scala.concurrent.{Await, Promise}
import scalaz.Id.Id

object RAIISpec {

  object Exceptions {

    case class Boom() extends RuntimeException

    case class CanNotCloseResourceTwice() extends RuntimeException

    case class CanNotOpenResourceTwice() extends RuntimeException

    case class CanNotGenerateDataBecauseResourceIsNotOpen() extends RuntimeException

  }

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

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class RAIISpec extends AsyncFreeSpec with Matchers with Inside {

  import RAIISpec._

  import Exceptions._

  "must open and close" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    for (r0 <- mr0) {
      allOpenedResources("r0") should be(r0)
    }
    allOpenedResources.keys shouldNot contain("r0")
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

    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResources, "mr0"))
    val mr1 = managed(new FakeResource(allOpenedResources, "mr1"))
    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")

    for (r0 <- mr0; r1 <- mr1) {
      allOpenedResources("mr0") should be(r0)
      allOpenedResources("mr1") should be(r1)
    }

    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")
  }

  "both of resources must open and close in complicated usage" in {

    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResources, "mr0"))
    val mr1 = managed(new FakeResource(allOpenedResources, "mr1"))
    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")

    for (r0 <- mr0; x = 0;
         r1 <- mr1; y = 1) {
      allOpenedResources("mr0") should be(r0)
      allOpenedResources("mr1") should be(r1)
    }

    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")

  }
  "must open and close twice" in {

    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = RAIISpec.createIdGenerator()
    val mr = managed(new FakeResource(allOpenedResources, idGenerator))
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

    for (r0 <- mr; r1 <- mr) {
      allOpenedResources("0") should be(r0)
      allOpenedResources("1") should be(r1)

    }
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

  }

  "must could be shared" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = RAIISpec.createIdGenerator()
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
}
