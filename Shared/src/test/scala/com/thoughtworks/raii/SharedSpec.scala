package com.thoughtworks.raii

import com.thoughtworks.raii.ResourceFactoryT._
import com.thoughtworks.raii.ResourceFactoryTSpec.Exceptions.{Boom, CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import com.thoughtworks.raii.ResourceFactoryTSpec._
import com.thoughtworks.raii.Shared.SharedOps
import org.scalatest.{Assertion, AsyncFreeSpec, Inside, Matchers}

import scala.collection.mutable
import scala.concurrent.Promise
import scalaz.concurrent.{Future, Task}
import scalaz.syntax.all._
import scalaz.{-\/, EitherT, \/, _}

/**
  * Created by 张志豪 on 2017/4/6.
  */
class SharedSpec extends AsyncFreeSpec with Matchers with Inside {

  type PowerFuture[A] = EitherT[ResourceFactoryT[Future, ?], Throwable, A]

  class FutureAsyncFakeResource(allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource],
                                allCallBack: mutable.HashMap[String, ReleasableT[Future, String] => Unit],
                                idGenerator: () => String)
      extends FutureDelayFakeResource(allOpenedResources, idGenerator) {

    override def acquire(): Future[ReleasableT[Future, String]] = {
      if (allOpenedResources.contains(id)) {
        throw CanNotOpenResourceTwice()
      }
      allOpenedResources(id) = this

      Future.async { f => //: (ReleasableT[Future, String] => Unit)
        allCallBack(id) = f
      }
    }
  }

  class FutureDelayFakeResource(allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource],
                                idGenerator: () => String)
      extends {
    val id: String = idGenerator()
  } with ResourceFactoryT[Future, String] {

    def this(allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource], constantId: String) = {
      this(allOpenedResources, { () =>
        constantId
      })
    }

    override def acquire(): Future[ReleasableT[Future, String]] = {

      if (allOpenedResources.contains(id)) {
        throw CanNotOpenResourceTwice()
      }
      allOpenedResources(id) = this

      Future.delay {
        new ReleasableT[Future, String] {
          override def value: String = id

          override def release(): Future[Unit] = {
            val removed = allOpenedResources.remove(id)

            removed match {
              case Some(_) =>
              case None => throw CanNotCloseResourceTwice()
            }

            Future.now(())
          }
        }
      }
    }
  }

  "when working with scalaz's Future, it must asynchronously acquire and release" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managedT[Future, FakeResource](new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    val asynchronousResource: Future[Unit] = mr0.using { r0 =>
      Future.delay {
        events += "using r0"
        allOpenedResources("r0") should be(r0)
      }
    }

    val p = Promise[Assertion]
    asynchronousResource.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("r0")
        events should be(Seq("using r0"))
      }
    }
    p.future
  }

  "when working with scalaz's EitherT" - {

    "must asynchronously acquire and release when an exception occurs" ignore {
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managedT[Future, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = new Task(mr0.using { r0 =>
        Future.delay {
          events += "using r0"
          allOpenedResources("r0") should be(r0)
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
          allOpenedResources.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }
  }
  "when working with scalaz's Task" - {
    "must asynchronously acquire and release" in {
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managedT[Task, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = mr0.using { r0 =>
        Task.delay {
          events += "using r0"
          allOpenedResources("r0") should be(r0)
        }
      }

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResources.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }

    "must asynchronously acquire and release when an exception occurs" ignore {
      import scalaz.concurrent.Task._
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managedT[Task, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")
      val asynchronousResource: Task[Unit] = mr0.using { r0 =>
        events += "using r0"
        allOpenedResources("r0") should be(r0)
        (new Boom: Throwable).raiseError
      }

      val p = Promise[Assertion]
      asynchronousResource.unsafePerformAsync { _ =>
        p.success {
          allOpenedResources.keys shouldNot contain("r0")
          events should be(Seq("using r0"))
        }
      }
      p.future
    }

  }

  "reference count test without shared -- async" ignore {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr: ResourceFactoryT[Future, FakeResource] =
      managedT[Future, FakeResource](new FakeResource(allOpenedResources, "0"))
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
    val mr: ResourceFactoryT[Future, FakeResource] =
      managedT[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared
    allOpenedResources.keys shouldNot contain("0")

    val usingResource: ResourceFactoryT[Future, mutable.Buffer[String]] = mr.flatMap { r1 =>
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

  "reference count test with shared -- async -- eitherT" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceFactoryT[Future, String] =
      new FutureDelayFakeResource(allOpenedResources, "0").shared

    val pf: PowerFuture[String] = EitherT[ResourceFactoryT[Future, ?], Throwable, String](sharedResource.map(\/.right))

    import com.thoughtworks.raii.EitherTNondeterminism._

    val result: PowerFuture[String] =
      eitherTNondeterminism[ResourceFactoryT[Future, ?], Throwable](Nondeterminism[ResourceFactoryT[Future, ?]])
        .map(pf) { a =>
          events += "using a"
          a
        }

    val future: Future[Throwable \/ String] = result.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("0")
        events should be(Seq("using a"))
      }
    }
    p.future
  }

  "reference count test with shared -- async -- raise exception" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]

    val sharedResource: ResourceFactoryT[Future, FakeResource] =
      managedT[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared

    val mappedResource: ResourceFactoryT[Future, Throwable \/ FakeResource] = sharedResource.map(\/.right)

    val mr = new EitherT[ResourceFactoryT[Future, ?], Throwable, FakeResource](mappedResource)

    val usingResource = mr.flatMap { r1: FakeResource =>
      events += "using 0"
      allOpenedResources("0") should be(r1)

      EitherT.eitherTMonadError[ResourceFactoryT[Future, ?], Throwable].raiseError[Assertion](new Boom)
    }

    val future: Future[Throwable \/ Assertion] = usingResource.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case -\/(e) =>
          p.success {
            e should be(a[Boom])
            allOpenedResources.keys shouldNot contain("0")
            events should be(Seq("using 0"))
          }
      }
    }
    p.future
  }

  "reference count test with shared -- Nondeterminism: mapBoth -- no exception --with FutureFakeResource" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceFactoryT[Future, String] =
      new FutureDelayFakeResource(allOpenedResources, "0").shared

    val pf1: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](sharedResource.map(\/.right))
    val pf2: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](sharedResource.map(\/.right))

    import com.thoughtworks.raii.EitherTNondeterminism._

    val result: PowerFuture[String] = eitherTNondeterminism[ResourceFactoryT[Future, ?], Throwable](
      Nondeterminism[ResourceFactoryT[Future, ?]]).mapBoth(pf1, pf2) { (a: String, b: String) =>
      events += "using a & b"
      a + b
    }

    val future: Future[Throwable \/ String] = result.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case \/-(value) =>
          p.success {
            value should be("00")
            events should be(Seq("using a & b"))
          }
      }
    }
    p.future
  }

  "reference count test with shared -- Nondeterminism: mapBoth -- raise exception --with FutureFakeResource" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceFactoryT[Future, String] =
      new FutureDelayFakeResource(allOpenedResources, "0").shared

    val pf1: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](sharedResource.map(\/.right))
    val pf2: PowerFuture[String] =
      EitherT.eitherTMonadError[ResourceFactoryT[Future, ?], Throwable].raiseError[String](new Boom)

    import com.thoughtworks.raii.EitherTNondeterminism._

    val result: PowerFuture[String] = eitherTNondeterminism[ResourceFactoryT[Future, ?], Throwable](
      Nondeterminism[ResourceFactoryT[Future, ?]]).mapBoth(pf1, pf2) { (a: String, b: String) =>
      events += "using a & b"
      a + b
    }

    val future: Future[Throwable \/ String] = result.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case -\/(e) =>
          p.success {
            e should be(a[Boom])
            events should be(Seq())
          }
      }
    }
    p.future
  }

  "reference count test -- shared and not shared -- Nondeterminism: nmap3 -- no exception --with FutureFakeResource" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val resource0: ResourceFactoryT[Future, String] = new FutureDelayFakeResource(allOpenedResources, "0")
    val resource1: ResourceFactoryT[Future, String] = new FutureDelayFakeResource(allOpenedResources, "1").shared

    val pf0: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource0.map(\/.right))
    val pf1: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource1.map(\/.right))
    val pf2: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource1.map(\/.right))

    import com.thoughtworks.raii.EitherTNondeterminism._

    val result: PowerFuture[String] = eitherTNondeterminism[ResourceFactoryT[Future, ?], Throwable](
      Nondeterminism[ResourceFactoryT[Future, ?]]).nmap3(pf0, pf1, pf2) { (a: String, b: String, c: String) =>
      events += "using a & b & c"
      a + b + c
    }

    val future: Future[Throwable \/ String] = result.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case \/-(value) =>
          p.success {
            value should be("011")
            events should be(Seq("using a & b & c"))
          }
      }
    }
    p.future
  }

  "reference count test -- shared and not shared -- Nondeterminism: nmap3 -- no exception --with FutureFakeResource --async acquire" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val allCallBack = mutable.HashMap.empty[String, ReleasableT[Future, String] => Unit]

    val resource0: ResourceFactoryT[Future, String] =
      new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "0")
    val resource1: ResourceFactoryT[Future, String] =
      new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "1").shared

    val pf0: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource0.map(\/.right))
    val pf1: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource1.map(\/.right))
    val pf2: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource1.map(\/.right))

    import com.thoughtworks.raii.EitherTNondeterminism._

    val result: PowerFuture[String] = eitherTNondeterminism[ResourceFactoryT[Future, ?], Throwable](
      Nondeterminism[ResourceFactoryT[Future, ?]]).nmap3(pf0, pf1, pf2) { (a: String, b: String, c: String) =>
      events += "using a & b & c"
      a + b + c
    }

    val future: Future[Throwable \/ String] = result.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case \/-(value) =>
          p.success {
            value should be("011")
            events should be(Seq("using a & b & c"))
          }
      }
    }

    allCallBack.foreach { callBackTuple =>
      callBackTuple._2 {
        new ReleasableT[Future, String] {
          override def value: String = callBackTuple._1

          override def release(): Future[Unit] = {
            val removed = allOpenedResources.remove(callBackTuple._1)

            removed match {
              case Some(_) =>
              case None => throw new CanNotCloseResourceTwice
            }

            Future.now(())
          }
        }
      }
    }

    p.future
  }

  "reference count test -- shared and not shared -- Nondeterminism: nmap3 -- no exception --with FutureFakeResource --async acquire and release" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val allAcquireCallBack = mutable.HashMap.empty[String, ReleasableT[Future, String] => Unit]

    val resource0: ResourceFactoryT[Future, String] =
      new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "0")
    val resource1: ResourceFactoryT[Future, String] =
      new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "1").shared

    val pf0: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource0.map(\/.right))
    val pf1: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource1.map(\/.right))
    val pf2: PowerFuture[String] =
      EitherT[ResourceFactoryT[Future, ?], Throwable, String](resource1.map(\/.right))

    import com.thoughtworks.raii.EitherTNondeterminism._

    val result: PowerFuture[String] = eitherTNondeterminism[ResourceFactoryT[Future, ?], Throwable](
      Nondeterminism[ResourceFactoryT[Future, ?]]).nmap3(pf0, pf1, pf2) { (a: String, b: String, c: String) =>
      events += "using a & b & c"
      a + b + c
    }

    val future: Future[Throwable \/ String] = result.run.run

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case \/-(value) =>
          p.success {
            value should be("011")
            events should be(Seq("using a & b & c"))
          }
      }
    }

    val allReleaseCallBack = mutable.HashMap.empty[String, Unit => Unit]

    allAcquireCallBack.foreach { callBackTuple =>
      callBackTuple._2 {
        new ReleasableT[Future, String] {
          override def value: String = callBackTuple._1

          override def release(): Future[Unit] = {
            Future.async { f =>
              allReleaseCallBack(callBackTuple._1) = f
            }
          }
        }
      }
    }

    while (allReleaseCallBack.keySet.nonEmpty) {
      allReleaseCallBack.foreach { callBackTuple =>
        callBackTuple._2 {
          val removed = allOpenedResources.remove(callBackTuple._1)
          allReleaseCallBack.remove(callBackTuple._1)

          removed match {
            case Some(_) =>
            case None => throw new CanNotCloseResourceTwice
          }
        }
      }
    }

    p.future
  }
}
