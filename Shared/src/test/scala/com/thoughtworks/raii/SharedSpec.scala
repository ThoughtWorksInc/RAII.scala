package com.thoughtworks.raii

import com.thoughtworks.raii.ResourceFactoryTSpec.Exceptions.{Boom, CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import com.thoughtworks.raii.ResourceFactoryTSpec._
import com.thoughtworks.raii.Shared.SharedOps
import com.thoughtworks.raii.transformers.{ResourceFactoryT, ResourceT}
import org.scalatest.{Assertion, AsyncFreeSpec, Inside, Matchers}
import com.thoughtworks.raii.transformers.ResourceFactoryT.{resourceFactoryTMonad, using}
import com.thoughtworks.tryt.TryT.{tryTBindRec, tryTFunctor, tryTParallelApplicative}
import com.thoughtworks.tryt.{TryT, TryTParallelApplicative}

import scala.util.{Success, Try}
import scala.language.higherKinds
import scala.collection.mutable
import scala.concurrent.Promise
import scalaz.Tags.Parallel
import scalaz.concurrent.{Future, Task}
import scalaz.syntax.all._
import scalaz.{-\/, @@, \/, _}
import Future._
import scala.util.control.NoStackTrace

/**
  * Created by 张志豪 on 2017/4/6.
  */
class SharedSpec extends AsyncFreeSpec with Matchers with Inside {

  def raiiFutureMonad: Monad[RAIIFuture] = ResourceFactoryT.resourceFactoryTMonad[Future]

  type RAIIFuture[A] = ResourceFactoryT[Future, A]

  type RAIITask[A] = TryT[RAIIFuture, A]

  /** An exception that contains multiple Throwables. */
  final case class MultipleException(throwableSet: Set[Throwable])
      extends Exception("Multiple exceptions found")
      with NoStackTrace {
    override def toString: String = throwableSet.toString()
  }

  implicit def throwableSemigroup = new Semigroup[Throwable] {
    override def append(f1: Throwable, f2: => Throwable): Throwable =
      f1 match {
        case MultipleException(exceptionSet1) =>
          f2 match {
            case MultipleException(exceptionSet2) => MultipleException(exceptionSet1 ++ exceptionSet2)
            case _: Throwable => MultipleException(exceptionSet1 + f2)
          }
        case _: Throwable =>
          f2 match {
            case MultipleException(exceptionSet2) => MultipleException(exceptionSet2 + f1)
            case _: Throwable => MultipleException(Set(f1, f2))
          }
      }
  }

  class FutureAsyncFakeResource(allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource],
                                allCallBack: mutable.HashMap[String, ResourceT[Future, String] => Unit],
                                idGenerator: () => String)
      extends FutureDelayFakeResource(allOpenedResources, idGenerator) {

    override def apply(): Future[ResourceT[Future, String]] = {
      if (allOpenedResources.contains(id)) {
        throw CanNotOpenResourceTwice()
      }
      allOpenedResources(id) = this

      Future.async { f => //: (ResourceT[Future, String] => Unit)
        allCallBack(id) = f
      }
    }
  }

  class FutureDelayFakeResource(allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource],
                                idGenerator: () => String)
      extends AutoCloseable {
    val id: String = idGenerator()

    def this(allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource], constantId: String) = {
      this(allOpenedResources, { () =>
        constantId
      })
    }

    def appendThisToAllOpenedResources(): Unit = {
      allOpenedResources(id) = this
    }

    def apply(): Future[ResourceT[Future, String]] = {
      appendThisToAllOpenedResources()
      Future.delay(
        new ResourceT[Future, String] {
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
      )
    }

    override def close(): Unit = ()
  }

  "when working with scalaz's Future, it must asynchronously acquire and release" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managedT[Future, FakeResource](new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    val asynchronousResource: Future[Unit] = using[Future, FakeResource, Unit](mr0, r0 => {
      Future.delay {
        events += "using r0"
        allOpenedResources("r0") should be(r0)
      }
    })

    val p = Promise[Assertion]
    asynchronousResource.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("r0")
        events should be(Seq("using r0"))
      }
    }
    p.future
  }

  "when working with scalaz's Task" - {
    "must asynchronously acquire and release" in {
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managedT[Task, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")

      val asynchronousResource: Task[Unit] = using[Task, FakeResource, Unit](mr0, r0 => {
        Task.delay {
          events += "using r0"
          allOpenedResources("r0") should be(r0)
        }
      })

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

  "reference count test with shared -- async" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr: ResourceFactoryT[Future, FakeResource] =
      managedT[Future, FakeResource](new FakeResource(allOpenedResources, "0")).shared
    allOpenedResources.keys shouldNot contain("0")

    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTMonad

    //def resourceMonad = resourceFactoryTMonad[Future](Future.futureInstance)
    //mr.flatMap(???)

    val usingResource: ResourceFactoryT[Future, mutable.Buffer[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).bind(mr) { r1 =>
        resourceFactoryTMonad[Future](Future.futureInstance).map(mr) { r2 =>
          allOpenedResources.keys should contain("0")
          events += "using 0"
        }
      }

//    val usingResource: ResourceFactoryT[Future, mutable.Buffer[String]] = mr.flatMap { r1 =>
//      mr.map { r2 =>
//        allOpenedResources.keys should contain("0")
//        events += "using 0"
//      }
//    }

    val asynchronousResource: Future[Unit] = using(usingResource, (a: mutable.Buffer[String]) => Future.now(()))

    val p = Promise[Assertion]

    asynchronousResource.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("0")
        events should be(Seq("using 0"))
      }
    }
    p.future
  }

  "reference count test with shared -- async -- TryT" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceFactoryT[Future, String] =
      ResourceFactoryT(new FutureDelayFakeResource(allOpenedResources, "0").apply()).shared

    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTMonad

    val pf: RAIITask[String] = TryT[ResourceFactoryT[Future, ?], String](
      resourceFactoryTMonad[Future](Future.futureInstance).map(sharedResource) { Success(_) }
      //sharedResource.map(Success(_))
    )

    val parallelPf = Parallel(pf)

    import com.thoughtworks.tryt.TryT.tryTParallelApplicative
    import scalaz.concurrent.Future.futureParallelApplicativeInstance
    import scalaz.concurrent.Future.futureInstance
    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTParallelApplicative

    val parallelResult: RAIITask[String] @@ Parallel =
      tryTParallelApplicative[ResourceFactoryT[Future, ?]].map(parallelPf) { a =>
        events += "using a"
        a
      }

    val result: TryT[ResourceFactoryT[Future, ?], String] = Parallel.unwrap(parallelResult)

    val resourceFactoryFutureString: ResourceFactoryT[Future, Try[String]] =
      TryT.unwrap[ResourceFactoryT[Future, ?], String](result)

    val future: Future[Try[String]] = ResourceFactoryT.run(resourceFactoryFutureString)

    val p = Promise[Assertion]

    future.unsafePerformAsync { _ =>
      p.success {
        allOpenedResources.keys shouldNot contain("0")
        events should be(Seq("using a"))
      }
    }
    p.future
  }

  "reference count test with shared  --apply2 -- no exception --with FutureDelayFakeResource" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceFactoryT[Future, String] =
      ResourceFactoryT.apply(new FutureDelayFakeResource(allOpenedResources, "0").apply()).shared

    val pf1: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(sharedResource) { Try(_) }

    val pf2: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(sharedResource) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)

    import com.thoughtworks.tryt.TryT.tryTParallelApplicative
    import scalaz.concurrent.Future.futureParallelApplicativeInstance
    import scalaz.concurrent.Future.futureInstance
    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTParallelApplicative

    val parallelResult: RAIITask[String] @@ Parallel =
      tryTParallelApplicative[ResourceFactoryT[Future, ?]].apply2(parallelPf1, parallelPf2) { (a: String, b: String) =>
        events += "using a & b"
        a + b
      }

    val result: RAIITask[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIFuture[Try[String]] = TryT.unapply(result).get
    val future: Future[Try[String]] = ResourceFactoryT.run(raiiFuture)

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case Success(value) =>
          p.success {
            value should be("00")
            events should be(Seq("using a & b"))
          }
      }
    }
    p.future
  }

  "reference count test with shared --apply2 -- raise exception --with FutureDelayFakeResource" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceFactoryT[Future, String] =
      ResourceFactoryT.apply(new FutureDelayFakeResource(allOpenedResources, "0").apply()).shared

    val pf1: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(sharedResource) { Try(_) }

    val trypf1 = TryT(pf1)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(TryT.tryTMonadError[ResourceFactoryT[Future, ?]].raiseError[String](Boom()))

    import com.thoughtworks.tryt.TryT.tryTParallelApplicative
    import scalaz.concurrent.Future.futureParallelApplicativeInstance
    import scalaz.concurrent.Future.futureInstance
    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTParallelApplicative

    val parallelResult: RAIITask[String] @@ Parallel =
      tryTParallelApplicative[ResourceFactoryT[Future, ?]].apply2(parallelPf1, parallelPf2) { (a: String, b: String) =>
        events += "using a & b"
        a + b
      }

    val result: RAIITask[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIFuture[Try[String]] = TryT.unapply(result).get
    val future: Future[Try[String]] = ResourceFactoryT.run(raiiFuture)

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case scala.util.Failure(e) =>
          p.success {
            e should be(a[Boom])
            events should be(Seq())
          }
      }
    }
    p.future
  }

  "reference count test -- shared and not shared -- apply3 -- no exception --with FutureDelayFakeResource" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val resource0: ResourceFactoryT[Future, String] =
      ResourceFactoryT.apply(new FutureDelayFakeResource(allOpenedResources, "0").apply())
    val resource1: ResourceFactoryT[Future, String] =
      ResourceFactoryT.apply(new FutureDelayFakeResource(allOpenedResources, "1").apply()).shared

    val pf1: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource0) { Try(_) }

    val pf2: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource1) { Try(_) }

    val pf3: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.TryT.tryTParallelApplicative
    import scalaz.concurrent.Future.futureParallelApplicativeInstance
    import scalaz.concurrent.Future.futureInstance
    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTParallelApplicative

    val parallelResult: RAIITask[String] @@ Parallel =
      tryTParallelApplicative[ResourceFactoryT[Future, ?]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIITask[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIFuture[Try[String]] = TryT.unapply(result).get
    val future: Future[Try[String]] = ResourceFactoryT.run(raiiFuture)

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case Success(value) =>
          p.success {
            value should be("011")
            events should be(Seq("using a & b & c"))
          }
      }
    }
    p.future
  }

  "reference count test -- shared and not shared --apply3 -- no exception --with FutureFakeResource --async acquire" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val allCallBack = mutable.HashMap.empty[String, ResourceT[Future, String] => Unit]

    val resource0: ResourceFactoryT[Future, String] =
      ResourceFactoryT.apply(new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "0").apply())
    val resource1: ResourceFactoryT[Future, String] =
      ResourceFactoryT.apply(new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "1").apply()).shared

    val pf1: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource0) { Try(_) }

    val pf2: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource1) { Try(_) }

    val pf3: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.TryT.tryTParallelApplicative
    import scalaz.concurrent.Future.futureParallelApplicativeInstance
    import scalaz.concurrent.Future.futureInstance
    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTParallelApplicative

    val parallelResult: RAIITask[String] @@ Parallel =
      tryTParallelApplicative[ResourceFactoryT[Future, ?]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIITask[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIFuture[Try[String]] = TryT.unapply(result).get
    val future: Future[Try[String]] = ResourceFactoryT.run(raiiFuture)

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case Success(value) =>
          p.success {
            value should be("011")
            events should be(Seq("using a & b & c"))
          }
      }
    }

    allCallBack.foreach { callBackTuple =>
      callBackTuple._2 {
        new ResourceT[Future, String] {
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

  "reference count test -- shared and not shared --apply3 -- no exception --with FutureFakeResource -- --async acquire and release" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val allAcquireCallBack = mutable.HashMap.empty[String, ResourceT[Future, String] => Unit]

    val resource0: ResourceFactoryT[Future, String] =
      ResourceFactoryT.apply(new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "0").apply())
    val resource1: ResourceFactoryT[Future, String] =
      ResourceFactoryT
        .apply(new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "1").apply())
        .shared

    val pf1: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource0) { Try(_) }

    val pf2: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource1) { Try(_) }

    val pf3: ResourceFactoryT[Future, Try[String]] =
      resourceFactoryTMonad[Future](Future.futureInstance).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.TryT.tryTParallelApplicative
    import scalaz.concurrent.Future.futureParallelApplicativeInstance
    import scalaz.concurrent.Future.futureInstance
    import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTParallelApplicative

    val parallelResult: RAIITask[String] @@ Parallel =
      tryTParallelApplicative[ResourceFactoryT[Future, ?]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIITask[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIFuture[Try[String]] = TryT.unapply(result).get
    val future: Future[Try[String]] = ResourceFactoryT.run(raiiFuture)

    val p = Promise[Assertion]

    future.unsafePerformAsync { either =>
      inside(either) {
        case Success(value) =>
          p.success {
            value should be("011")
            events should be(Seq("using a & b & c"))
          }
      }
    }

    val allReleaseCallBack = mutable.HashMap.empty[String, Unit => Unit]

    allAcquireCallBack.foreach { callBackTuple =>
      callBackTuple._2 {
        new ResourceT[Future, String] {
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
