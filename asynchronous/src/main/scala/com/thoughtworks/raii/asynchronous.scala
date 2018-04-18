package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.raii.covariant._
import com.thoughtworks.tryt.covariant.TryT

import scala.concurrent.ExecutionContext
import scalaz.{-\/, @@, Applicative, ContT, EphemeralStream, Monad, MonadError, Semigroup, Trampoline, \/, \/-}
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel
import scalaz.std.`try`
import ResourceT._
import TryT._
import com.thoughtworks.future._
import com.thoughtworks.continuation.{UnitContinuation, _}
import com.thoughtworks.raii.asynchronous.Do.scoped
import com.thoughtworks.raii.shared._

import scala.util.control.NonFatal
import scalaz.syntax.all._
import scalaz.std.anyVal._

/** The namespace that contains [[Do]].
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object asynchronous {

  trait DefaultCloseable extends MonadicCloseable[UnitContinuation] {
    def monadicClose = UnitContinuation.now(())
  }

  private def fromContinuation[A](future: UnitContinuation[Resource[UnitContinuation, Try[A]]]): Do[A] = {
    opacityTypes.fromTryT(TryT[RAIIContinuation, A](ResourceT(future)))
  }

  private def toContinuation[A](doValue: Do[A]): UnitContinuation[Resource[UnitContinuation, Try[A]]] = {
    val ResourceT(future) = TryT.unwrap(opacityTypes.toTryT(doValue))
    future
  }

  /** @template */
  private type RAIIContinuation[+A] = ResourceT[UnitContinuation, A]

  private[asynchronous] trait OpacityTypes {
    type Do[+A]

    private[asynchronous] def fromTryT[A](run: TryT[RAIIContinuation, A]): Do[A]

    private[asynchronous] def toTryT[A](doValue: Do[A]): TryT[RAIIContinuation, A]

    //TODO: BindRec
    implicit private[asynchronous] def asynchronousDoMonadErrorInstances: MonadError[Do, Throwable]

    implicit private[asynchronous] def doParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[Lambda[A => Do[A] @@ Parallel]]
  }

  /** The type-level [[http://en.cppreference.com/w/cpp/language/pimpl Pimpl]] in order to prevent the Scala compiler seeing the actual type of [[Do]]
    *
    * @note For internal usage only.
    */
  val opacityTypes: OpacityTypes = new OpacityTypes {
    override type Do[+A] = TryT[RAIIContinuation, A]

    override private[asynchronous] def fromTryT[A](run: TryT[RAIIContinuation, A]): TryT[RAIIContinuation, A] = run

    override private[asynchronous] def toTryT[A](doa: TryT[RAIIContinuation, A]): TryT[RAIIContinuation, A] = doa

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
    *  - [[asynchronous.AsynchronousDoOps.shared reference counting]]
    *  - [[com.thoughtworks.continuation.UnitContinuation asynchronous programming]]
    *  - [[ParallelDo parallel computing]]
    *
    * @note This `Do` type is an [[https://www.reddit.com/r/scala/comments/5qbdgq/value_types_without_anyval/dcxze9q/ opacity alias]] to `UnitContinuation[Resource[UnitContinuation, Try[A]]]`.
    * @see [[Do$ Do]] companion object for static helper functions for this `Do` type.
    @ @see [[AsynchronousDoOps]] for implicit methods for this `Do` type.
    * @see [[asynchronousDoMonadErrorInstances]] for the [[scalaz.MonadError MonadError]] the type class for this `Do` type.
    * @template
    */
  type Do[+A] = opacityTypes.Do[A]

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
    *          val originalDoInput: Do[InputStream] = Do.autoCloseable(new URL("http://thoughtworks.com/").openStream())
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
    * @see [[doParallelApplicative]] for the [[scalaz.Applicative Applicative]] type class for parallel computing.
    *
    * @template
    */
  type ParallelDo[A] = Do[A] @@ Parallel

  /** Returns an [[scalaz.Applicative Applicative]] type class for parallel computing.
    *
    * @note This type class requires a [[scalaz.Semigroup Semigroup]] to combine multiple `Throwable`s into one,
    *       in the case of multiple tasks report errors in parallel.
    * @group Type classes
    */
  implicit def asynchronousDoParallelApplicative(
      implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelDo] =
    opacityTypes.doParallelApplicative

  /** @group Type classes */
  implicit def asynchronousDoMonadErrorInstances: MonadError[Do, Throwable] =
    opacityTypes.asynchronousDoMonadErrorInstances

  /** The companion object of [[Do]]
    * @define now Converts a strict value to a `Do` whose [[covariant.Resource.release release]] operation is no-op.
    *
    * @define seenow @see [[now]] for strict garbage collected `Do`
    *
    * @define delay Returns a non-strict `Do` whose [[covariant.Resource.release release]] operation is no-op.
    *
    * @define seedelay @see [[delay]] for non-strict garbage collected `Do`
    *
    * @define autocloseable Returns a non-strict `Do` whose [[covariant.Resource.release release]] operation is [[java.lang.AutoCloseable.close]].
    *
    * @define releasable Returns a non-strict `Do` whose [[covariant.Resource.release release]] operation is asynchronous.
    *
    * @define seeautocloseable @see [[autoCloseable]] for auto-closeable `Do`
    *
    * @define seereleasable @see [[monadicCloseable]] for creating a `Do` whose [[covariant.Resource.release release]] operation is asynchronous.
    *
    * @define nonstrict Since the `Do` is non-strict,
    *                   `A` will be recreated each time it is sequenced into a larger `Do`.
    *
    * @define garbageCollected `A` must be a garbage-collected type that does not hold native resource.
    */
  object Do {
    def resource[A](resource: => Resource[UnitContinuation, A]): Do[A] = {
      val resourceContinuation: UnitContinuation[Resource[UnitContinuation, Try[A]]] = UnitContinuation.delay {
        try {
          val Resource(a, monadicClose) = resource
          Resource(Success(a), monadicClose)
        } catch {
          case NonFatal(e) =>
            Resource(Failure(e), UnitContinuation.now(()))
        }
      }
      Do(TryT(ResourceT(resourceContinuation)))
    }

    def apply[A](tryT: TryT[ResourceT[UnitContinuation, `+?`], A]): Do[A] = {
      opacityTypes.fromTryT(tryT)
    }

    def unapply[A](doValue: Do[A]): Some[TryT[ResourceT[UnitContinuation, `+?`], A]] = {
      Some(opacityTypes.toTryT(doValue))
    }

    /** $releasable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seeautocloseable
      */
    def monadicCloseable[A <: MonadicCloseable[UnitContinuation]](future: Future[A]): Do[A] = {
      val Future(TryT(continuation)) = future
      fromContinuation(
        continuation.map {
          case failure @ Failure(e) =>
            new Resource[UnitContinuation, Try[A]] {
              override val value: Try[A] = Failure(e)
              override def release: UnitContinuation[Unit] = {
                UnitContinuation.now(())
              }
            }
          case success @ Success(releasable) =>
            new Resource[UnitContinuation, Try[A]] {
              override val value = Success(releasable)
              override def release: UnitContinuation[Unit] = releasable.monadicClose
            }
        }
      )
    }

    @deprecated(message = "Use [[autoCloseable]] instead.", since = "3.0.0")
    def scoped[A <: AutoCloseable](future: Future[A]): Do[A] = {
      autoCloseable(future)
    }

    /** $autocloseable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seereleasable
      */
    def autoCloseable[A <: AutoCloseable](future: Future[A]): Do[A] = {
      val Future(TryT(continuation)) = future
      fromContinuation(
        continuation.map {
          case failure @ Failure(e) =>
            new Resource[UnitContinuation, Try[A]] {
              override val value: Try[A] = failure
              override val release: UnitContinuation[Unit] = {
                UnitContinuation.now(())
              }
            }
          case success @ Success(closeable) =>
            new Resource[UnitContinuation, Try[A]] {
              override val value: Try[A] = success
              override val release: UnitContinuation[Unit] = {
                Continuation.delay(closeable.close())
              }
            }
        }
      )
    }

    @deprecated(message = "Use [[autoCloseable]] instead.", since = "3.0.0")
    def scoped[A <: AutoCloseable](future: UnitContinuation[A],
                                   dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Do[A] = {
      autoCloseable(future)
    }

    /** $releasable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seeautocloseable
      */
    def monadicCloseable[A <: MonadicCloseable[UnitContinuation]](future: UnitContinuation[A],
                                                                  dummyImplicit: DummyImplicit =
                                                                    DummyImplicit.dummyImplicit): Do[A] = {
      monadicCloseable(Future(TryT(future.map(Success(_)))))
    }

    /** $autocloseable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seereleasable
      */
    def autoCloseable[A <: AutoCloseable](continuation: UnitContinuation[A],
                                          dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Do[A] = {
      autoCloseable(Future(TryT(continuation.map(Success(_)))))
    }

    @deprecated(message = "Use [[autoCloseable]] instead.", since = "3.0.0")
    def scoped[A <: AutoCloseable](contT: ContT[Trampoline, Unit, A]): Do[A] = {
      autoCloseable(
        Future(TryT(Continuation { (continue: Try[A] => Trampoline[Unit]) =>
          contT { value: A =>
            continue(Success(value))
          }
        }))
      )
    }

    @deprecated(message = "Use [[autoCloseable]] instead.", since = "3.0.0")
    def scoped[A <: AutoCloseable](value: => A): Do[A] = {
      autoCloseable(value)
    }

    /** $releasable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seeautocloseable
      */
    def monadicCloseable[A <: MonadicCloseable[UnitContinuation]](value: => A): Do[A] = {
      monadicCloseable(Future.delay(value))
    }

    /** $autocloseable
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def autoCloseable[A <: AutoCloseable](value: => A): Do[A] = {
      autoCloseable(Future.delay(value))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seeautocloseable
      * $seedelay
      */
    def garbageCollected[A](future: Future[A]): Do[A] = {
      val Future(TryT(continuation)) = future
      fromContinuation(
        continuation.map { either =>
          Resource.now[UnitContinuation, Try[A]](either)
        }
      )
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seeautocloseable
      * $seedelay
      */
    def garbageCollected[A](continuation: UnitContinuation[A],
                            dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Do[A] = {
      garbageCollected(Future(TryT(continuation.map(Success(_)))))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seeautocloseable
      * $seedelay
      */
    def garbageCollected[A](contT: ContT[Trampoline, Unit, A]): Do[A] = {
      garbageCollected(Future(TryT(Continuation { continue: (Try[A] => Trampoline[Unit]) =>
        contT.run { value: A =>
          continue(Success(value))
        }
      })))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seeautocloseable
      */
    def delay[A](value: => A): Do[A] = {
      Do(TryT(ResourceT.delay(Try(value))))
    }

    /** Returns a nested scope of `doA`.
      *
      * All resources created during building `A` will be released after `A` is built.
      *
      * @note `A` must be a garbage collected type, i.e. not a [[java.lang.AutoCloseable]] or a [[com.thoughtworks.raii.covariant.MonadicCloseable]]
      * @note This method has the same behavior as `Do.garbageCollected(doA.run)`.
      * @see [[garbageCollected]] for creating a garbage collected `Do`
      * @see [[AsynchronousDoOps.run]] for running a `Do` as a [[com.thoughtworks.future.Future ThoughtWorks Future]].
      */
    def nested[A](doA: Do[A]): Do[A] = {
      val Do(TryT(resourceT)) = doA
      Do(TryT(ResourceT.nested(resourceT)))
    }

    /** $now
      * $garbageCollected
      * $seedelay
      * $seeautocloseable
      */
    def now[A](value: A): Do[A] = {
      garbageCollected(Future.now(value))
    }

    def async[A](start: (Resource[UnitContinuation, Try[A]] => Unit) => Unit): Do[A] = {
      Do(TryT(ResourceT(UnitContinuation.async(start))))
    }

    def safeAsync[A](start: (Resource[UnitContinuation, Try[A]] => Trampoline[Unit]) => Trampoline[Unit]): Do[A] = {
      Do(TryT(ResourceT(UnitContinuation.safeAsync(start))))
    }

    def suspend[A](doValue: => Do[A]): Do[A] = {
      Do.safeAsync(doValue.safeOnComplete(_))
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
      * $seeautocloseable
      */
    def execute[A](value: => A)(implicit executorContext: ExecutionContext): Do[A] = {
      garbageCollected(Future.execute(value))
    }

  }

  implicit final class AsynchronousDoOps[A](asynchronousDo: Do[A]) {

    def onComplete(continue: Resource[UnitContinuation, Try[A]] => Unit) = {
      val Do(TryT(ResourceT(continuation))) = asynchronousDo
      continuation.onComplete(continue)
    }

    def safeOnComplete(continue: Resource[UnitContinuation, Try[A]] => Trampoline[Unit]) = {
      val Do(TryT(ResourceT(continuation))) = asynchronousDo
      continuation.safeOnComplete(continue)
    }

    def acquire: Future[Resource[UnitContinuation, A]] = {
      val Do(TryT(ResourceT(continuation))) = asynchronousDo
      Future(TryT(continuation.flatMap { resource =>
        resource.value match {
          case Success(a) =>
            UnitContinuation.now(Success(Resource(a, resource.release)))
          case Failure(e) =>
            resource.release.map { _: Unit =>
              Failure(e)
            }
        }
      }))
    }

    /**
      * Returns a `Future` of `A`, which will open `A` and release all resources during opening `A`.
      *
      * @note `A` itself must be [[Do.garbageCollected garbageCollected]](i.e. does not have clean up operation),
      *       though `A` may use some non-garbage-collected resources during opening `A`.
      */
    def run: Future[A] = {
      Future(TryT(ResourceT(toContinuation(asynchronousDo)).run))
    }

    /** Returns a `Do` of `B` based on a `Do` of `A` and a function that creates a `Do` of `B`,
      * when `B` will not reference to `A`,
      * or `A` is a garbage collected object without additional release action.
      *
      * @note `intransitiveFlatMap` is similar to `flatMap` in [[asynchronousDoMonadErrorInstances]].
      *
      *       Whereas `intransitiveFlatMap` will release `A` right after `B` is __created__,
      *       if there is a release action associated with `A`,
      *       `flatMap` will release `A` right after `B` is __released__.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveFlatMap[B](f: A => Do[B]): Do[B] = {
      val resourceA = ResourceT(toContinuation(asynchronousDo))
      val resourceB = resourceA.intransitiveFlatMap[Try[B]] {
        case Failure(e) =>
          ResourceT(Continuation.now(Resource.now(Failure(e))))
        case Success(value) =>
          try {
            ResourceT(toContinuation(f(value)))
          } catch {
            case NonFatal(e) =>
              ResourceT(Continuation.now(Resource.now(Failure(e))))
          }
      }
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }

    /** Returns a `Do` of `B` based on a `Do` of `A` and a function that creates `B`,
      * when `B` will not reference to `A`,
      * or `A` is a garbage collected object without additional release action.
      *
      * @note `intransitiveMap` is similar to `map` in [[asynchronousDoMonadErrorInstances]].
      *
      *       Whereas `intransitiveMap` will release `A` right after `B` is __created__
      *       if there is a release action associated with `A`,
      *       `map` will release `A` right after `B` is __released__.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveMap[B](f: A => B): Do[B] = {
      val resourceA = ResourceT(toContinuation(asynchronousDo))
      val resourceB = resourceA.intransitiveMap(_.map(f))
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }

    /** Converts `asynchronousDo` to a reference counted wrapper.
      *
      * When the wrapper `Do` is used by multiple larger `Do` at the same time,
      * only one `A` instance is created.
      * The underlying `A` will be [[covariant.Resource.release release]]d only once,
      * when all users [[covariant.Resource.release release]] the wrapper `Do`.
      */
    def shared: Do[A] = {
      val sharedFuture: RAIIContinuation[Try[A]] = TryT.unwrap(opacityTypes.toTryT(asynchronousDo)).shared
      opacityTypes.fromTryT(TryT(sharedFuture))
    }
  }

}
