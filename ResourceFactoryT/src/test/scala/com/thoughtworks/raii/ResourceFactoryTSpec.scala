package com.thoughtworks.raii

import java.io.{File, FileInputStream}

import org.scalatest._
import com.thoughtworks.raii.ResourceFactoryTSpec.Exceptions.{CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import com.thoughtworks.raii.ResourceFactoryTSpec.FakeResource
import com.thoughtworks.raii.transformers.{ResourceFactoryT, ResourceT}
import com.thoughtworks.raii.transformers._

import scalaz.syntax.all._
import scalaz._
import scala.collection.mutable
import scala.concurrent.{Await, Promise}
import scala.language.higherKinds
import scalaz.effect.IO
import scalaz.effect.IOInstances

private[raii] object ResourceFactoryTSpec {

  def managed[A <: AutoCloseable](autoCloseable: => A): ResourceFactoryT[IO, A] = managedT[IO, A](autoCloseable)

  def managedT[F[_]: Applicative, A <: AutoCloseable](autoCloseable: => A): ResourceFactoryT[F, A] = {
    ResourceFactoryT(
      Applicative[F].point {
        new ResourceT[F, A] {
          override val value: A = autoCloseable

          override def release(): F[Unit] = Applicative[F].point(value.close())
        }
      }
    )
  }

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
final class ResourceFactoryTSpec extends AsyncFreeSpec with Matchers with Inside {

  import ResourceFactoryTSpec._

  import Exceptions._

  "must acquire and release" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")

    val ioResource: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr0).map { r0 =>
      allOpenedResources("r0") should be(a[FakeResource])
      r0
    }
    ResourceFactoryT.run(ResourceFactoryT(ioResource)).unsafePerformIO()

    allOpenedResources.keys shouldNot contain("r0")
  }

  "must release when error occur" ignore {

    val events = mutable.Buffer.empty[String]

    var seed = 0

    def nextId() = {
      val result = seed
      seed += 1
      result
    }

    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"acquire $id"

      override def close(): Unit = {
        events += s"release $id"
      }
    }

    val mr = managed(new MyResource)

    try {
      val ioResource: IO[ResourceT[IO, MyResource]] = ResourceFactoryT.unwrap(mr).map { r =>
        events += "error is coming"
        throw Boom()
        r
      }
      ResourceFactoryT.run(ResourceFactoryT(ioResource)).unsafePerformIO()
    } catch {
      case _: Boom =>
    }
    events should be(mutable.Buffer("acquire 0", "error is coming", "release 0"))
  }

  "must throw IllegalStateException when release throw exception" ignore {

    val events = mutable.Buffer.empty[String]

    var seed = 0

    def nextId() = {
      val result = seed
      seed += 1
      result
    }

    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"acquire $id"

      override def close(): Unit = {
        events += s"release $id"
        throw Boom()
      }
    }

    val mr = managed(new MyResource)

    intercept[IllegalStateException] {
      val ioResource: IO[ResourceT[IO, MyResource]] = ResourceFactoryT.unwrap(mr).map { r =>
        events += "error is coming"
        r
      }
      ResourceFactoryT.run(ResourceFactoryT(ioResource)).unsafePerformIO()
    }

    events should be(mutable.Buffer("acquire r", "error is coming", "release r"))
  }

  "must throw Exception when release throw exception and another error occur" ignore {
    val events = mutable.Buffer.empty[String]

    var seed = 0

    def nextId() = {
      val result = seed
      seed += 1
      result
    }

    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"acquire $id"

      override def close(): Unit = {
        events += s"release $id"
        throw Boom()
      }
    }

    val mr = managed(new MyResource)

    intercept[Boom] {
      val ioResource: IO[ResourceT[IO, MyResource]] = ResourceFactoryT.unwrap(mr).map { r =>
        events += "error is coming"
        throw Boom()
        r
      }
      ResourceFactoryT.run(ResourceFactoryT(ioResource)).unsafePerformIO()
    }

    events should be(mutable.Buffer("acquire 0", "error is coming", "release 0"))
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
      events += s"acquire $id"

      override def close(): Unit = {
        events += s"release $id"
      }

      def generateData() = Some(math.random)

    }

    val mr = managed(new MyResource)

    val ioResource: IO[ResourceT[IO, MyResource]] = ResourceFactoryT.unwrap(mr).map { r =>
      r
    }
    ResourceFactoryT.run(ResourceFactoryT(ioResource)).unsafePerformIO()

    events should be(mutable.Buffer("acquire 0", "release 0"))
  }

  "both of resources must acquire and release" in {

    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResources, "mr0"))
    val mr1 = managed(new FakeResource(allOpenedResources, "mr1"))
    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")

    val ioResource0: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr0).map { r0 =>
      allOpenedResources("mr0") should be(a[FakeResource])
      val ioResource1: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr1).map { r1 =>
        allOpenedResources("mr1") should be(a[FakeResource])
        r1
      }
      ResourceFactoryT.run(ResourceFactoryT(ioResource1)).unsafePerformIO()
      r0
    }
    ResourceFactoryT.run(ResourceFactoryT(ioResource0)).unsafePerformIO()

    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")
  }

  "must acquire and release twice" in {

    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = ResourceFactoryTSpec.createIdGenerator()
    val mr = managed(new FakeResource(allOpenedResources, idGenerator))
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

    val ioResource0: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr).map { r0 =>
      allOpenedResources("0") should be(a[FakeResource])
      val ioResource1: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr).map { r1 =>
        allOpenedResources("1") should be(a[FakeResource])
        r1
      }
      ResourceFactoryT.run(ResourceFactoryT(ioResource1)).unsafePerformIO()
      r0
    }
    ResourceFactoryT.run(ResourceFactoryT(ioResource0)).unsafePerformIO()

    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

  }

  "must could be shared" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = ResourceFactoryTSpec.createIdGenerator()
    val mr = managed(new FakeResource(allOpenedResources, idGenerator))
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

    val ioResource0: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr).map { r0 =>
      allOpenedResources("0") should be(a[FakeResource])
      val ioResource1: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr).map { r1 =>
        allOpenedResources("0") should be(a[FakeResource])
        allOpenedResources("1") should be(a[FakeResource])
        r1
      }
      ResourceFactoryT.run(ResourceFactoryT(ioResource1)).unsafePerformIO()
      allOpenedResources.keys shouldNot contain("1")
      r0
    }
    ResourceFactoryT.run(ResourceFactoryT(ioResource0)).unsafePerformIO()

    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")
  }

  "reference count test without shared" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managed(new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    intercept[CanNotOpenResourceTwice] {

      val ioResource0: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr0).map { r0 =>
        val ioResource1: IO[ResourceT[IO, FakeResource]] = ResourceFactoryT.unwrap(mr0).map { r1 =>
          r1
        }
        ResourceFactoryT.run(ResourceFactoryT(ioResource1)).unsafePerformIO()
        r0
      }
      ResourceFactoryT.run(ResourceFactoryT(ioResource0)).unsafePerformIO()
    }
    allOpenedResources.keys should contain("r0")
  }
}
