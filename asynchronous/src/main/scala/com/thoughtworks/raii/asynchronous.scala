package com.thoughtworks.raii

import com.thoughtworks.raii.covariant.{Releasable, ResourceT}
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
import com.thoughtworks.future.Future
import Future._
import com.thoughtworks.future.continuation.{Continuation, UnitContinuation}, Continuation._
import com.thoughtworks.raii.shared._

import scalaz.syntax.all._

/** The namespace that contains [[Do]].
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object asynchronous {

  /** @template */
  private type RAIIFuture[+Value] = ResourceT[UnitContinuation, Value]

  private[asynchronous] trait OpacityTypes {
    type Do[+Value]

    private[asynchronous] def fromTryT[Value](run: TryT[ResourceT[UnitContinuation, `+?`], Value]): Do[Value]

    private[asynchronous] def toTryT[Value](doValue: Do[Value]): TryT[RAIIFuture, Value]

    //TODO: BindRec
    implicit private[asynchronous] def doMonadErrorInstances: MonadError[Do, Throwable]

    implicit private[asynchronous] def doParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[Lambda[Value => Do[Value] @@ Parallel]]
  }

  /** The type-level [[http://en.cppreference.com/w/cpp/language/pimpl Pimpl]] in order to prevent the Scala compiler seeing the actual type of [[Do]]
    *
    * @note For internal usage only.
    */
  val opacityTypes: OpacityTypes = new OpacityTypes {
    override type Do[+Value] = TryT[RAIIFuture, Value]

    override private[asynchronous] def fromTryT[Value](run: TryT[RAIIFuture, Value]): TryT[RAIIFuture, Value] = run

    override private[asynchronous] def toTryT[Value](doa: TryT[RAIIFuture, Value]): TryT[RAIIFuture, Value] = doa

    override private[asynchronous] def doMonadErrorInstances: MonadError[TryT[RAIIFuture, ?], Throwable] = {
      TryT.tryTMonadError[RAIIFuture](ResourceT.resourceTMonad[UnitContinuation](Continuation.continuationMonad))
    }

    override private[asynchronous] def doParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]) = {
      TryT.tryTParallelApplicative[RAIIFuture](
        ResourceT.resourceTParallelApplicative[UnitContinuation](Continuation.continuationParallelApplicative),
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

  /** The companion object of [[ParallelDo]] */
  object ParallelDo {

    /** Returns an [[scalaz.Applicative Applicative]] type class for parallel computing.
      *
      * @note This type class requires a [[scalaz.Semigroup Semigroup]] to combine multiple `Throwable`s into one,
      *       in the case of multiple tasks report errors in parallel.
      */
    implicit def doParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelDo] =
      opacityTypes.doParallelApplicative
  }

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
    * @define garbagecollected [[Value]] must be a garbage-collected type that does not hold native resource.
    */
  object Do {

    /** @group Type classes */
    implicit def doMonadErrorInstances: MonadError[Do, Throwable] = opacityTypes.doMonadErrorInstances

    /** @group Converters */
    def apply[Value](future: UnitContinuation[Releasable[UnitContinuation, Try[Value]]]): Do[Value] = {
      opacityTypes.fromTryT(TryT[RAIIFuture, Value](ResourceT(future)))
    }

    private def unwrap[Value](doValue: Do[Value]): UnitContinuation[Releasable[UnitContinuation, Try[Value]]] = {
      val ResourceT(future) = TryT.unwrap(opacityTypes.toTryT(doValue))
      future
    }

    /** Returns the underlying [[com.thoughtworks.future.continuation.UnitContinuation]] that creates a [[covariant.Releasable]] `Value`.
      *
      * @group Converters
      */
    def unapply[Value](doValue: Do[Value]): Some[UnitContinuation[Releasable[UnitContinuation, Try[Value]]]] = {
      Some(unwrap(doValue))
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](future: Future[Value]): Do[Value] = {
      val TryT(continuation) = Future.toTryT[Value](future)
      Do(
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
      scoped(Future.fromTryT(TryT(future.map(Success(_)))))
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](continuation: ContT[Trampoline, Unit, Value]): Do[Value] = {
      scoped(
        Future.fromTryT(
          TryT(
            Continuation.apply { (continue: Try[Value] => Trampoline[Unit]) =>
              continuation { value: Value =>
                continue(Success(value))
              }
            }
          ))
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
      * $seenow
      * $seescoped
      */
    def fromFuture[Value](future: Future[Value]): Do[Value] = {
      val TryT(continuation) = Future.toTryT(future)
      Do(
        continuation.map { either =>
          Releasable.now[UnitContinuation, Try[Value]](either)
        }
      )
    }

    /** $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def fromContinuation[Value](future: UnitContinuation[Value]): Do[Value] = {
      fromFuture(Future.fromTryT(TryT(future.map(Success(_)))))
    }

    /** $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def delay[Value](continuation: ContT[Trampoline, Unit, Value]): Do[Value] = {
      fromFuture(
        Future.fromTryT(
          TryT(Continuation.apply { continue: (Try[Value] => Trampoline[Unit]) =>
            continuation { value: Value =>
              continue(Success(value))
            }
          })
        )
      )
    }

    /** $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def delay[Value](value: => Value): Do[Value] = {
      fromFuture(Future.delay(value))
    }

    /** $now
      * $seedelay
      * $seescoped
      */
    def now[Value](value: Value): Do[Value] = {
      fromFuture(Future.now(value))
    }

    /** Returns a `Do` that runs in `executorContext`
      *
      * @note This method is usually been used for changing the current thread.
      *
      *       {{{
      *       import java.util.concurrent._
      *       import scala.concurrent._
      *       import scalaz.syntax.all._
      *       import com.thoughtworks.raii.asynchronous.Do, Do._
      *
      *       implicit def executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
      *
      *       val mainThread = Thread.currentThread
      *
      *       Do.run {
      *         for {
      *           _ <- Do.delay(())
      *           threadBeforeJump = Thread.currentThread
      *           _ = threadBeforeJump should be(mainThread)
      *           _ <- Do.jump()
      *           threadAfterJump = Thread.currentThread
      *         } yield {
      *           threadAfterJump shouldNot be(mainThread)
      *         }
      *       }
      *       }}}
      */
    def jump()(implicit executorContext: ExecutionContext): Do[Unit] = {
      fromContinuation(Continuation.apply { handler: (Unit => Trampoline[Unit]) =>
        Trampoline.delay {
          executorContext.execute { () =>
            handler(()).run
          }
        }
      })
    }

    /**
      * Returns a `Future` of `Value`, which will open `Value` and release all resources during opening `Value`.
      *
      * @note `Value` itself must not be a [[scoped]] resources,
      *       though `Value` may depends on some [[scoped]] resources during opening `Value`.
      */
    def run[Value](doValue: Do[Value]): Future[Value] = {
      Future.fromTryT(TryT(ResourceT.run(ResourceT(Do.unwrap(doValue)))))
    }

    /** Returns a `Do` of `B` based on a `Do` of `Value` and a function that creates a `Do` of `B`.
      *
      * @note `releaseFlatMap` is similar to `flatMap` in [[doMonadErrorInstances]],
      *       except `releaseFlatMap` will release `Value` right after `B` is created.
      */
    def releaseFlatMap[Value, B](doValue: Do[Value])(f: Value => Do[B]): Do[B] = {
      val resourceA = ResourceT(Do.unwrap(doValue))
      val resourceB = ResourceT.releaseFlatMap[UnitContinuation, Try[Value], Try[B]](resourceA) {
        case Failure(e) =>
          ResourceT(Continuation.now(Releasable.now(Failure(e))))
        case Success(value) =>
          ResourceT(Do.unwrap(f(value)))
      }
      val ResourceT(future) = resourceB
      Do(future)
    }

    /** Returns a `Do` of `B` based on a `Do` of `Value` and a function that creates `B`.
      *
      * @note `releaseMap` is similar to `map` in [[doMonadErrorInstances]],
      *       except `releaseMap` will release `Value` right after `B` is created.
      */
    def releaseMap[Value, B](doValue: Do[Value])(f: Value => B): Do[B] = {
      val resourceA = ResourceT(Do.unwrap(doValue))
      val resourceB = ResourceT.releaseMap(resourceA)(_.map(f))
      val ResourceT(future) = resourceB
      Do(future)
    }

    /** Converts `doValue` to a reference counted wrapper `Do`.
      *
      * When the wrapper `Do` is used by multiple larger `Do` at the same time,
      * only one `Value` instance is created.
      * The underlying `Value` will be [[covariant.Releasable.release release]]d only once,
      * when all users [[covariant.Releasable.release release]] the wrapper `Do`.
      */
    def shared[Value](doValue: Do[Value]): Do[Value] = {
      val sharedFuture: RAIIFuture[Try[Value]] = TryT.unwrap(opacityTypes.toTryT(doValue)).shared
      opacityTypes.fromTryT(TryT(sharedFuture))
    }
  }

}
