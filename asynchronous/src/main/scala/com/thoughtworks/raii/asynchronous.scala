package com.thoughtworks.raii

import com.thoughtworks.raii.covariant._
import com.thoughtworks.tryt.covariant.TryT

import scala.concurrent.ExecutionContext
import scalaz.{-\/, @@, Applicative, ContT, Monad, MonadError, Semigroup, Trampoline, \/, \/-}
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel
import scalaz.std.`try`
import ResourceT._
import TryT._
import com.thoughtworks.future._
import com.thoughtworks.continuation._
import com.thoughtworks.raii.asynchronous.Do.scoped
import com.thoughtworks.raii.shared._

import scalaz.syntax.all._

/** The namespace that contains [[Do]].
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object asynchronous {

  private def fromContinuation[Value](future: UnitContinuation[Releasable[UnitContinuation, Try[Value]]]): Do[Value] = {
    opacityTypes.fromTryT(TryT[RAIIContinuation, Value](ResourceT(future)))
  }

  private def toContinuation[Value](doValue: Do[Value]): UnitContinuation[Releasable[UnitContinuation, Try[Value]]] = {
    val ResourceT(future) = TryT.unwrap(opacityTypes.toTryT(doValue))
    future
  }

  /** @template */
  private type RAIIContinuation[+Value] = ResourceT[UnitContinuation, Value]

  private[asynchronous] trait OpacityTypes {
    type Do[+Value]

    private[asynchronous] def fromTryT[Value](run: TryT[RAIIContinuation, Value]): Do[Value]

    private[asynchronous] def toTryT[Value](doValue: Do[Value]): TryT[RAIIContinuation, Value]

    //TODO: BindRec
    implicit private[asynchronous] def asynchronousDoMonadErrorInstances: MonadError[Do, Throwable]

    implicit private[asynchronous] def doParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[Lambda[Value => Do[Value] @@ Parallel]]
  }

  /** The type-level [[http://en.cppreference.com/w/cpp/language/pimpl Pimpl]] in order to prevent the Scala compiler seeing the actual type of [[Do]]
    *
    * @note For internal usage only.
    */
  val opacityTypes: OpacityTypes = new OpacityTypes {
    override type Do[+Value] = TryT[RAIIContinuation, Value]

    override private[asynchronous] def fromTryT[Value](
        run: TryT[RAIIContinuation, Value]): TryT[RAIIContinuation, Value] = run

    override private[asynchronous] def toTryT[Value](
        doa: TryT[RAIIContinuation, Value]): TryT[RAIIContinuation, Value] = doa

    override private[asynchronous] def asynchronousDoMonadErrorInstances
      : MonadError[TryT[RAIIContinuation, ?], Throwable] = {
      TryT.tryTMonadError[RAIIContinuation](covariantResourceTMonad[UnitContinuation](continuationMonad))
    }

    override private[asynchronous] def doParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]) = {
      TryT.tryTParallelApplicative[RAIIContinuation](
        covariantResourceTParallelApplicative[UnitContinuation](continuationParallelApplicative),
        throwableSemigroup)
    }
  }

  /** An universal monadic data type that consists of many useful monad transformers.
    *
    * == Features of `Do` ==
    *  - [[com.thoughtworks.tryt.covariant.TryT exception handling]]
    *  - [[com.thoughtworks.raii.covariant.ResourceT automatic resource management]]
    *  - [[Do$.shared reference counting]]
    *  - [[com.thoughtworks.future.continuation.UnitContinuation asynchronous programming]]
    *  - [[ParallelDo parallel computing]]
    *
    * @note This `Do` type is an [[https://www.reddit.com/r/scala/comments/5qbdgq/value_types_without_anyval/dcxze9q/ opacity alias]] to `UnitContinuation[Releasable[UnitContinuation, Try[Value]]]`.
    * @see [[Do$ Do]] companion object for all type classes and helper functions for this `Do` type.
    * @template
    */
  type Do[+Value] = opacityTypes.Do[Value]

  /** A [[Do]] tagged as [[scalaz.Tags.Parallel Parallel]].
    *
    * @example `ParallelDo` and [[Do]] can be converted to each other via [[scalaz.Tags.Parallel]].
    *
    *          Given a [[Do]],
    *
    *          {{{
    *          import com.thoughtworks.raii.asynchronous.{Do, ParallelDo}
    *          import java.net._
    *          import java.io._
    *          val originalDoInput: Do[InputStream] = Do.scoped(new URL("http://thoughtworks.com/").openStream())
    *          }}}
    *
    *          when converting it to `ParallelDo` and converting it back,
    *
    *          {{{
    *          import scalaz.Tags.Parallel
    *          val parallelDoInput: ParallelDo[InputStream] = Parallel(originalDoInput)
    *          val Parallel(doInput) = parallelDoInput
    *          }}}
    *
    *          then the [[Do]] should be still the original instance.
    *
    *          {{{
    *          doInput should be(originalDoInput)
    *          }}}
    *
    * @see [[ParallelDo.doParallelApplicative]] for the [[scalaz.Applicative Applicative]] type class for parallel computing.
    *
    * @template
    */
  type ParallelDo[Value] = Do[Value] @@ Parallel

  /** Returns an [[scalaz.Applicative Applicative]] type class for parallel computing.
    *
    * @note This type class requires a [[scalaz.Semigroup Semigroup]] to combine multiple `Throwable`s into one,
    *       in the case of multiple tasks report errors in parallel.
    */
  implicit def asynchronousDoParallelApplicative(
      implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelDo] =
    opacityTypes.doParallelApplicative

  /** @group Type classes */
  implicit def asynchronousDoMonadErrorInstances: MonadError[Do, Throwable] =
    opacityTypes.asynchronousDoMonadErrorInstances

  /** The companion object of [[Do]]
    * @define now Converts a strict value to a `Do` whose [[covariant.Releasable.release release]] operation is no-op.
    *
    * @define seenow @see [[now]] for strict garbage collected `Do`
    *
    * @define delay Returns a non-strict `Do` whose [[covariant.Releasable.release release]] operation is no-op.
    *
    * @define seedelay @see [[delay]] for non-strict garbage collected `Do`
    *
    * @define scoped Returns a non-strict `Do` whose [[covariant.Releasable.release release]] operation is [[java.lang.AutoCloseable.close]].
    *
    * @define seescoped @see [[scoped]] for auto-closeable `Do`
    *
    * @define nonstrict Since the `Do` is non-strict,
    *                   `Value` will be recreated each time it is sequenced into a larger `Do`.
    *
    * @define garbageCollected `Value` must be a garbage-collected type that does not hold native resource.
    */
  object Do {

    def apply[Value](tryT: TryT[ResourceT[UnitContinuation, `+?`], Value]): Do[Value] = {
      opacityTypes.fromTryT(tryT)
    }

    def unapply[Value](doValue: Do[Value]): Some[TryT[ResourceT[UnitContinuation, `+?`], Value]] = {
      Some(opacityTypes.toTryT(doValue))
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](future: Future[Value]): Do[Value] = {
      val Future(TryT(continuation)) = future
      fromContinuation(
        continuation.map { either =>
          new Releasable[UnitContinuation, Try[Value]] {
            override def value: Try[Value] = either

            override def release(): UnitContinuation[Unit] = {
              either match {
                case Success(closeable) =>
                  Continuation.delay(closeable.close())
                case Failure(_) =>
                  Continuation.now(())
              }
            }
          }
        }
      )
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](future: UnitContinuation[Value],
                                       dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Do[Value] = {
      scoped(Future(TryT(future.map(Success(_)))))
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](continuation: ContT[Trampoline, Unit, Value]): Do[Value] = {
      scoped(
        Future(TryT(Continuation(ContT { (continue: Try[Value] => Trampoline[Unit]) =>
          continuation { value: Value =>
            continue(Success(value))
          }
        })))
      )
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](value: => Value): Do[Value] = {
      scoped(Future.delay(value))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seescoped
      * $seedelay
      */
    def garbageCollected[Value](future: Future[Value]): Do[Value] = {
      val Future(TryT(continuation)) = future
      fromContinuation(
        continuation.map { either =>
          Releasable.now[UnitContinuation, Try[Value]](either)
        }
      )
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seescoped
      * $seedelay
      */
    def garbageCollected[Value](continuation: UnitContinuation[Value],
                                dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Do[Value] = {
      garbageCollected(Future(TryT(continuation.map(Success(_)))))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seescoped
      * $seedelay
      */
    def garbageCollected[Value](contT: ContT[Trampoline, Unit, Value]): Do[Value] = {
      garbageCollected(Future(TryT(Continuation(ContT { continue: (Try[Value] => Trampoline[Unit]) =>
        contT.run { value: Value =>
          continue(Success(value))
        }
      }))))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seescoped
      */
    def delay[Value](value: => Value): Do[Value] = {
      garbageCollected(Future.delay(value))
    }

    /** $now
      * $garbageCollected
      * $seedelay
      * $seescoped
      */
    def now[Value](value: Value): Do[Value] = {
      garbageCollected(Future.now(value))
    }

    /** Returns a `Do` that runs in `executorContext`.
      *
      * @note This method is usually been used for changing the current thread.
      *
      *       {{{
      *       import java.util.concurrent._
      *       import scala.concurrent._
      *       import scalaz.syntax.all._
      *       import com.thoughtworks.raii.asynchronous._
      *
      *       implicit def executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
      *
      *       val mainThread = Thread.currentThread
      *
      *       val doAssertion = for {
      *         _ <- Do.delay(())
      *         threadBeforeJump = Thread.currentThread
      *         _ = threadBeforeJump should be(mainThread)
      *         _ <- Do.execute(())
      *         threadAfterJump = Thread.currentThread
      *       } yield {
      *         threadAfterJump shouldNot be(mainThread)
      *       }
      *       doAssertion.run
      *       }}}
      *
      * $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def execute[Value](value: => Value)(implicit executorContext: ExecutionContext): Do[Value] = {
      garbageCollected(Future.execute(value))
    }

  }

  implicit final class AsynchronousDoOps[Value](asynchronousDo: Do[Value]) {

    /**
      * Returns a `Future` of `Value`, which will open `Value` and release all resources during opening `Value`.
      *
      * @note `Value` itself must not be a [[scoped]] resources,
      *       though `Value` may depends on some [[scoped]] resources during opening `Value`.
      */
    def run: Future[Value] = {
      Future(TryT(ResourceT(toContinuation(asynchronousDo)).run))
    }

    /** Returns a `Do` of `B` based on a `Do` of `Value` and a function that creates a `Do` of `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveFlatMap` is similar to `flatMap` in [[asynchronousDoMonadErrorInstances]],
      *       except `intransitiveFlatMap` will release `Value` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveFlatMap[B](f: Value => Do[B]): Do[B] = {
      val resourceA = ResourceT(toContinuation(asynchronousDo))
      val resourceB = resourceA.intransitiveFlatMap[Try[B]] {
        case Failure(e) =>
          ResourceT(Continuation.now(Releasable.now(Failure(e))))
        case Success(value) =>
          ResourceT(toContinuation(f(value)))
      }
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }

    /** Returns a `Do` of `B` based on a `Do` of `Value` and a function that creates `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveMap` is similar to `map` in [[asynchronousDoMonadErrorInstances]],
      *       except `intransitiveMap` will release `Value` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveMap[B](f: Value => B): Do[B] = {
      val resourceA = ResourceT(toContinuation(asynchronousDo))
      val resourceB = resourceA.intransitiveMap(_.map(f))
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }

    /** Converts `asynchronousDo` to a reference counted wrapper.
      *
      * When the wrapper `Do` is used by multiple larger `Do` at the same time,
      * only one `Value` instance is created.
      * The underlying `Value` will be [[covariant.Releasable.release release]]d only once,
      * when all users [[covariant.Releasable.release release]] the wrapper `Do`.
      */
    def shared: Do[Value] = {
      val sharedFuture: RAIIContinuation[Try[Value]] = TryT.unwrap(opacityTypes.toTryT(asynchronousDo)).shared
      opacityTypes.fromTryT(TryT(sharedFuture))
    }
  }

}
