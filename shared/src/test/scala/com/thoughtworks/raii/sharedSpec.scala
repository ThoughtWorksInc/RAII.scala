package com.thoughtworks.raii

import com.thoughtworks.future._
import com.thoughtworks.continuation._
import com.thoughtworks.raii.sharedSpec.Exceptions.{Boom, CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import com.thoughtworks.raii.sharedSpec._
import com.thoughtworks.raii.shared.SharedOps
import com.thoughtworks.raii.covariant._
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

  def raiiFutureMonad: Monad[RAIIContinuation] = covariantResourceTMonad[UnitContinuation](continuationMonad)

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

  class FutureAsyncFakeResource(allOpenedResources: mutable.HashMap[String, FutureDelayFakeResource],
                                allCallBack: mutable.HashMap[String, Releasable[UnitContinuation, String] => Unit],
                                idGenerator: () => String)
      extends FutureDelayFakeResource(allOpenedResources, idGenerator) {

    override def apply(): UnitContinuation[Releasable[UnitContinuation, String]] = {
      Continuation.async { f =>
        if (allOpenedResources.contains(id)) {
          throw CanNotOpenResourceTwice()
        }
        allOpenedResources(id) = this
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
    asynchronousResource.onComplete { _ =>
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
      asynchronousResource.onComplete {
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

    import com.thoughtworks.raii.covariant.covariantResourceTMonad

    val usingResource: ResourceT[UnitContinuation, mutable.Buffer[String]] = sharedResource.flatMap { r1 =>
      sharedResource.map { r2 =>
        r1 should be(r2)
        allOpenedResources.keys should contain("0")
        events += "using 0"
      }
    }

    val asynchronousResource: UnitContinuation[mutable.Buffer[String]] = usingResource.run

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

    val pf: RAIIFuture[String] = TryT[ResourceT[UnitContinuation, `+?`], String](
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(sharedResource) { Success(_) })

    val parallelPf = Parallel(pf)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].map(parallelPf) { a =>
        events += "using a"
        a
      }

    val result: TryT[ResourceT[UnitContinuation, `+?`], String] = Parallel.unwrap(parallelResult)

    val resourceFactoryFutureString: ResourceT[UnitContinuation, Try[String]] =
      TryT.unwrap[ResourceT[UnitContinuation, `+?`], String](result)

    val future: UnitContinuation[Try[String]] = resourceFactoryFutureString.run

    val p = Promise[Assertion]

    ContinuationOps(future).onComplete { _ =>
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
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(sharedResource) { Success(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(sharedResource) { Success(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.raii.covariant.covariantResourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply2(parallelPf1, parallelPf2) {
        (a: String, b: String) =>
          events += "using a & b"
          a + b
      }

    val Parallel(TryT(raiiFuture)) = parallelResult
    val future: UnitContinuation[Try[String]] = raiiFuture.run

    val p = Promise[Assertion]

    ContinuationOps(future).onComplete { either =>
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
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(sharedResource) { Try(_) }

    val trypf1 = TryT(pf1)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(TryT.tryTMonadError[ResourceT[UnitContinuation, `+?`]].raiseError[String](Boom()))

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.raii.covariant.covariantResourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply2(parallelPf1, parallelPf2) {
        (a: String, b: String) =>
          events += "using a & b"
          a + b
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = raiiFuture.run

    val p = Promise[Assertion]

    ContinuationOps(future).onComplete { either =>
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
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource0) { Try(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource1) { Try(_) }

    val pf3: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.raii.covariant.covariantResourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = raiiFuture.run

    val p = Promise[Assertion]

    ContinuationOps(future).onComplete { either =>
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

    val allCallBack = mutable.HashMap.empty[String, Releasable[UnitContinuation, String] => Unit]

    val resource0: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "0").apply())
    val resource1: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureAsyncFakeResource(allOpenedResources, allCallBack, () => "1").apply()).shared

    val pf1: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource0) { Try(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource1) { Try(_) }

    val pf3: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.raii.covariant.covariantResourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = raiiFuture.run

    val p = Promise[Assertion]

    ContinuationOps(future).onComplete {
      case Success(value) =>
        val _ = p.success {
          value should be("011")
          events should be(Seq("using a & b & c"))

        }
      case scala.util.Failure(e) =>
        val _ = p.failure(e)
    }

    for ((id, continue) <- allCallBack) {
      val releasable = new Releasable[UnitContinuation, String] {
        override def value: String = id
        override def release: UnitContinuation[Unit] = Continuation.delay {
          val removed = allOpenedResources.remove(id)

          removed match {
            case Some(_) =>
            case None    => throw new CanNotCloseResourceTwice
          }

        }
      }
      continue(releasable)
    }

    p.future
  }

  "reference count test -- shared and not shared --apply3 -- no exception --with FutureFakeResource -- --async acquire and release" in {
    val events = mutable.Buffer.empty[String]
    val allOpenedResources = mutable.HashMap.empty[String, FutureDelayFakeResource]

    val allAcquireCallBack = mutable.HashMap.empty[String, Releasable[UnitContinuation, String] => Unit]

    val resource0: ResourceT[UnitContinuation, String] =
      ResourceT.apply(new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "0").apply())
    val resource1: ResourceT[UnitContinuation, String] =
      ResourceT
        .apply(new FutureAsyncFakeResource(allOpenedResources, allAcquireCallBack, () => "1").apply())
        .shared

    val pf1: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource0) { Try(_) }

    val pf2: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource1) { Try(_) }

    val pf3: ResourceT[UnitContinuation, Try[String]] =
      covariantResourceTMonad[UnitContinuation](continuationMonad).map(resource1) { Try(_) }

    val trypf1 = TryT(pf1)
    val trypf2 = TryT(pf2)
    val trypf3 = TryT(pf3)

    val parallelPf1 = Parallel(trypf1)
    val parallelPf2 = Parallel(trypf2)
    val parallelPf3 = Parallel(trypf3)

    import com.thoughtworks.tryt.covariant.TryT.tryTParallelApplicative
    import com.thoughtworks.raii.covariant.covariantResourceTParallelApplicative

    val parallelResult: RAIIFuture[String] @@ Parallel =
      tryTParallelApplicative[ResourceT[UnitContinuation, `+?`]].apply3(parallelPf1, parallelPf2, parallelPf3) {
        (a: String, b: String, c: String) =>
          events += "using a & b & c"
          a + b + c
      }

    val result: RAIIFuture[String] = Parallel.unwrap(parallelResult)
    val raiiFuture: RAIIContinuation[Try[String]] = TryT.unapply(result).get
    val future: UnitContinuation[Try[String]] = raiiFuture.run

    val p = Promise[Assertion]

    ContinuationOps(future).onComplete {
      case Success(value) =>
        val _ = p.success {
          value should be("011")
          events should be(Seq("using a & b & c"))
        }
      case scala.util.Failure(e) =>
        val _ = p.failure(e)
    }

    val allReleaseCallBack = mutable.HashMap.empty[String, Unit => Unit]

    for ((id, continue) <- allAcquireCallBack) {
      val releasable = new Releasable[UnitContinuation, String] {
        override def value: String = id

        override def release: UnitContinuation[Unit] = {
          Continuation.async { f =>
            allReleaseCallBack(id) = f
          }
        }
      }
      continue(releasable)
    }

    while (allReleaseCallBack.keySet.nonEmpty) {
      for ((id, continue) <- allReleaseCallBack) {
        continue {
          val removed = allOpenedResources.remove(id)
          allReleaseCallBack.remove(id)

          removed match {
            case Some(_) =>
            case None    => throw new CanNotCloseResourceTwice
          }
        }
      }
    }

    p.future
  }
}
