package com.thoughtworks.raii

import com.thoughtworks.future.Future
import Future._
import com.thoughtworks.future.continuation.{Continuation, UnitContinuation}
import com.thoughtworks.future.continuation.Continuation._
import com.thoughtworks.raii.sharedSpec.Exceptions.{Boom, CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import com.thoughtworks.raii.sharedSpec._
import com.thoughtworks.raii.shared.SharedOps
import com.thoughtworks.raii.covariant.{Releasable, ResourceT}
import org.scalatest.{Assertion, AsyncFreeSpec, Inside, Matchers}
import com.thoughtworks.raii.covariant.ResourceT._
import com.thoughtworks.raii.scalatest.ContinuationToScalaFuture
import com.thoughtworks.tryt.covariant.TryT.{tryTBindRec, tryTFunctor, tryTParallelApplicative}
import com.thoughtworks.tryt.covariant.{TryT, TryTParallelApplicative}

import scala.util.{Success, Try}
import scala.language.higherKinds
import scala.collection.mutable
import scala.concurrent.Promise
import scalaz.Tags.Parallel
import scalaz.syntax.all._
import scalaz.{-\/, @@, \/, _}
import scala.util.control.NoStackTrace
import scalaz.Free.Trampoline

/**
  * Created by 张志豪 on 2017/4/6.
  */
object sharedSpec {

  def managedT[F[+ _]: Applicative, Resource <: AutoCloseable](autoCloseable: => Resource): ResourceT[F, Resource] = {
    ResourceT(
      Applicative[F].point {
        new Releasable[F, Resource] {
          override val value: Resource = autoCloseable

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

  def raiiFutureMonad: Monad[RAIIContinuation] =
    ResourceT.resourceTMonad[UnitContinuation](Continuation.continuationMonad)

  type RAIIContinuation[+A] = ResourceT[UnitContinuation, A]

  type RAIIFuture[+A] = TryT[RAIIContinuation, A]

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
            case _: Throwable                     => MultipleException(exceptionSet1 + f2)
          }
        case _: Throwable =>
          f2 match {
            case MultipleException(exceptionSet2) => MultipleException(exceptionSet2 + f1)
            case _: Throwable                     => MultipleException(Set(f1, f2))
          }
      }
  }

  class FutureAsyncFakeResource(
      allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource],
      allCallBack: mutable.HashMap[String, Releasable[UnitContinuation, String] => Trampoline[Unit]],
      idGenerator: () => String)
      extends FutureDelayFakeResource(allOpenedResources, idGenerator) {

    override def apply(): UnitContinuation[Releasable[UnitContinuation, String]] = {

      Continuation.apply { f => //: (Releasable[UnitContinuation, String] => Unit)
        if (allOpenedResources.contains(id)) {
          throw CanNotOpenResourceTwice()
        }
        allOpenedResources(id) = this
        allCallBack(id) = f
        Trampoline.done(())
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

    def apply(): UnitContinuation[Releasable[UnitContinuation, String]] = {
      appendThisToAllOpenedResources()
      Continuation.delay(
        new Releasable[UnitContinuation, String] {
          override def value: String = id

          override def release: UnitContinuation[Unit] = Continuation.delay {
            val removed = allOpenedResources.remove(id)
            removed match {
              case Some(_) =>
              case None    => throw CanNotCloseResourceTwice()
            }

          }
        }
      )
    }

    override def close(): Unit = ()
  }

}

class sharedSpec extends AsyncFreeSpec with Matchers with Inside with ContinuationToScalaFuture {
  import sharedSpec._
  "when working with scalaz's UnitContinuation, it must asynchronously acquire and release" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = managedT[UnitContinuation, FakeResource](new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    val asynchronousResource: UnitContinuation[Assertion] =
      using[UnitContinuation, FakeResource, Assertion](mr0, r0 => {
        Continuation.delay {
          events += "using r0"
          allOpenedResources("r0") should be(r0)
        }
      })

    val p = Promise[Assertion]
    Continuation.onComplete(asynchronousResource) { _ =>
      val _ = p.success {
        allOpenedResources.keys shouldNot contain("r0")
        events should be(Seq("using r0"))
      }
    }

    p.future
  }

  "when working with Future" - {
    "must asynchronously acquire and release" in {
      val events = mutable.Buffer.empty[String]
      val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
      val mr0 = managedT[Future, FakeResource](new FakeResource(allOpenedResources, "r0"))
      allOpenedResources.keys shouldNot contain("r0")

      val asynchronousResource: Future[Assertion] = using[Future, FakeResource, Assertion](mr0, r0 => {
        Future.delay {
          events += "using r0"
          allOpenedResources("r0") should be(r0)
        }
      })

      val p = Promise[Assertion]
      Future.onComplete(asynchronousResource) {
        case scala.util.Failure(e) =>
          val _ = p.failure(e)
        case Success(_) =>
          val _ = p.success {
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

    val sharedResource: ResourceT[UnitContinuation, FakeResource] =
      managedT[UnitContinuation, FakeResource](new FakeResource(allOpenedResources, "0")).shared

    allOpenedResources.keys shouldNot contain("0")

    import com.thoughtworks.raii.covariant.ResourceT.resourceTMonad

    val usingResource: ResourceT[UnitContinuation, mutable.Buffer[String]] = sharedResource.flatMap { r1 =>
      sharedResource.map { r2 =>
        r1 should be(r2)
        allOpenedResources.keys should contain("0")
        events += "using 0"
      }
    }

    val asynchronousResource: UnitContinuation[mutable.Buffer[String]] = ResourceT.run(usingResource)

    allOpenedResources.keys shouldNot contain("0")

    asynchronousResource.map { _ =>
      allOpenedResources.keys shouldNot contain("0")
      events should be(Seq("using 0"))
    }

  }

  "reference count test with shared -- async -- TryT" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceT[UnitContinuation, String] =
      ResourceT(new FutureDelayFakeResource(allOpenedResources, "0").apply()).shared

    import com.thoughtworks.raii.covariant.ResourceT.resourceTMonad

    val pf: RAIIFuture[String] = TryT[ResourceT[UnitContinuation, `+?`], String](
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(sharedResource) { Success(_) }
    )

    val parallelPf = Parallel(pf)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationMonad
    import com.thoughtworks.raii.covariant.ResourceT.resourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].map(parallelPf) { a =>
        events += "using a"
        a
      }

    val result: TryT[ResourceT[UnitContinuation, `+?`], String] = Parallel.unwrap(parallelResult)

    val resourceFactoryFutureString: ResourceT[UnitContinuation, Try[String]] =
      TryT.unwrap[ResourceT[UnitContinuation, `+?`], String](result)

    val future: UnitContinuation[Try[String]] = ResourceT.run(resourceFactoryFutureString)

    val p = Promise[Assertion]

    Continuation.onComplete(future) { _ =>
      val _ = p.success {
        allOpenedResources.keys shouldNot contain("0")
        events should be(Seq("using a"))
      }
    }
    p.future
  }

  "reference count test with shared  --apply2 -- no exception --with FutureDelayFakeResource" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val sharedResource: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureDelayFakeResource(allOpenedResources, "0").apply()).shared

    val pf1: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(sharedResource) { Success(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(sharedResource) { Success(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationMonad
    import com.thoughtworks.raii.covariant.ResourceT.resourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply2(parallelPf1, parallelPf2) {
        (a: String, b: String) =>
          events += "using a & b"
          a + b
      }

    val Parallel(TryT(raiiFuture)) = parallelResult
    val future: UnitContinuation[Try[String]] = ResourceT.run(raiiFuture)

    val p = Promise[Assertion]

    Continuation.onComplete(future) { either =>
      inside(either) {
        case Success(value) =>
          val _ = p.success {
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

    val sharedResource: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureDelayFakeResource(allOpenedResources, "0").apply()).shared

    val pf1: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(sharedResource) { Try(_) }

    val trypf1 = TryT(pf1)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(TryT.tryTMonadError[ResourceT[UnitContinuation, `+?`]].raiseError[String](Boom()))

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationMonad
    import com.thoughtworks.raii.covariant.ResourceT.resourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply2(parallelPf1, parallelPf2) {
        (a: String, b: String) =>
          events += "using a & b"
          a + b
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = ResourceT.run(raiiFuture)

    val p = Promise[Assertion]

    Continuation.onComplete(future) { either =>
      inside(either) {
        case scala.util.Failure(e) =>
          val _ = p.success {
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

    val resource0: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureDelayFakeResource(allOpenedResources, "0").apply())
    val resource1: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureDelayFakeResource(allOpenedResources, "1").apply()).shared

    val pf1: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource0) { Try(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource1) { Try(_) }

    val pf3: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationMonad
    import com.thoughtworks.raii.covariant.ResourceT.resourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = ResourceT.run(raiiFuture)

    val p = Promise[Assertion]

    Continuation.onComplete(future) { either =>
      inside(either) {
        case Success(value) =>
          val _ = p.success {
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

    val allCallBack = mutable.HashMap.empty[String, Releasable[UnitContinuation, String] => Trampoline[Unit]]

    val resource0: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "0").apply())
    val resource1: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "1").apply()).shared

    val pf1: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource0) { Try(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource1) { Try(_) }

    val pf3: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationMonad
    import com.thoughtworks.raii.covariant.ResourceT.resourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = ResourceT.run(raiiFuture)

    val p = Promise[Assertion]

    Continuation.onComplete(future) {
      case Success(value) =>
        val _ = p.success {
          value should be("011")
          events should be(Seq("using a & b & c"))

        }
      case scala.util.Failure(e) =>
        val _ = p.failure(e)
    }

    allCallBack.foreach { callBackTuple =>
      val releasable = new Releasable[UnitContinuation, String] {
        override def value: String = callBackTuple._1

        override def release: UnitContinuation[Unit] = Continuation.delay {
          val removed = allOpenedResources.remove(callBackTuple._1)

          removed match {
            case Some(_) =>
            case None    => throw new CanNotCloseResourceTwice
          }

        }
      }

      callBackTuple._2(releasable).run
    }

    p.future
  }

  "reference count test -- shared and not shared --apply3 -- no exception --with FutureFakeResource -- --async acquire and release" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val allAcquireCallBack = mutable.HashMap.empty[String, Releasable[UnitContinuation, String] => Trampoline[Unit]]

    val resource0: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "0").apply())
    val resource1: ResourceT[UnitContinuation, String] =
      ResourceT
        .apply(new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "1").apply())
        .shared

    val pf1: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource0) { Try(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource1) { Try(_) }

    val pf3: ResourceT[UnitContinuation, Try[String]] =
      resourceTMonad[UnitContinuation](Continuation.continuationMonad).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationParallelApplicative
    import com.thoughtworks.future.continuation.Continuation.continuationMonad
    import com.thoughtworks.raii.covariant.ResourceT.resourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = ResourceT.run(raiiFuture)

    val p = Promise[Assertion]

    Continuation.onComplete(future) {
      case Success(value) =>
        val _ = p.success {
          value should be("011")
          events should be(Seq("using a & b & c"))
        }
      case scala.util.Failure(e) =>
        val _ = p.failure(e)
    }

    val allReleaseCallBack = mutable.HashMap.empty[String, Unit => Trampoline[Unit]]

    allAcquireCallBack.foreach { callBackTuple =>
      val releasable = new Releasable[UnitContinuation, String] {
        override def value: String = callBackTuple._1

        override def release: UnitContinuation[Unit] = {
          Continuation { f =>
            allReleaseCallBack(callBackTuple._1) = f
            Trampoline.done(())
          }
        }
      }
      callBackTuple._2(releasable).run
    }

    while (allReleaseCallBack.keySet.nonEmpty) {
      allReleaseCallBack.foreach { callBackTuple =>
        callBackTuple._2 {
          val removed = allOpenedResources.remove(callBackTuple._1)
          allReleaseCallBack.remove(callBackTuple._1)

          removed match {
            case Some(_) =>
            case None    => throw new CanNotCloseResourceTwice
          }
        }.run
      }
    }

    p.future
  }
}
