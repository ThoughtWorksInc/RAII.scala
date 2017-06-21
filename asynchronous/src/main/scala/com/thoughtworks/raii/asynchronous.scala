package com.thoughtworks.raii

import com.thoughtworks.raii.covariant.{ResourceT, Releasable}
import com.thoughtworks.tryt.covariant.TryT

import scala.concurrent.ExecutionContext
import scalaz.{-\/, @@, Applicative, ContT, Monad, MonadError, Semigroup, \/, \/-}
import scalaz.concurrent.{Future, Task}
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel
import scalaz.std.`try`
import ResourceT._
import TryT._
import com.thoughtworks.raii.shared._

/** The namespace that contains [[Do]].
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object asynchronous {

  /** @template */
  private type RAIIFuture[+Value] = ResourceT[Future, Value]

  private[asynchronous] trait OpacityTypes {
    type Do[+Value]

    private[asynchronous] def fromTryT[Value](run: TryT[ResourceT[Future, `+?`], Value]): Do[Value]

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
      TryT.tryTMonadError[RAIIFuture](ResourceT.resourceTMonad[Future](Future.futureInstance))
    }

    override private[asynchronous] def doParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]) = {
      TryT.tryTParallelApplicative[RAIIFuture](
        ResourceT.resourceTParallelApplicative[Future](Future.futureParallelApplicativeInstance),
        throwableSemigroup)
    }
  }

  /** An universal monadic data type built-in many useful monad transformers.
    *
    * Features of `Do`:
    *  - exception handling
    *  - automatic resource management
    *  - reference counting
    *  - asynchronous programming
    *  - parallel computing
    *
    * @note This `Do` type is an [[https://www.reddit.com/r/scala/comments/5qbdgq/value_types_without_anyval/dcxze9q/ opacity alias]] to `Future[Releasable[Future, Try[Value]]]`.
    * @see [[Do$ Do]] companion object for all type classes and helper functions for this `Do` type.
    * @template
    */
  type Do[+Value] = opacityTypes.Do[Value]

  type ParallelDo[Value] = Do[Value] @@ Parallel

  /** @define now Converts a strict value to a `Do` whose [[covariant.Releasable.release release]] operation is no-op.
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

    implicit def doMonadErrorInstances: MonadError[Do, Throwable] = opacityTypes.doMonadErrorInstances
    implicit def doParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]): Applicative[ParallelDo] =
      opacityTypes.doParallelApplicative

    def apply[Value](future: Future[Releasable[Future, Try[Value]]]): Do[Value] = {
      opacityTypes.fromTryT(TryT[RAIIFuture, Value](ResourceT(future)))
    }

    private def unwrap[Value](doValue: Do[Value]): Future[Releasable[Future, Try[Value]]] = {
      val ResourceT(future) = TryT.unwrap(opacityTypes.toTryT(doValue))
      future
    }

    /** Returns the underlying [[scalaz.concurrent.Future]] that creates a [[covariant.Releasable]] `Value`. */
    def unapply[Value](doValue: Do[Value]): Some[Future[Releasable[Future, Try[Value]]]] = {
      Some(unwrap(doValue))
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](task: Task[Value]): Do[Value] = {
      Do(
        task.get.map { either =>
          new Releasable[Future, Try[Value]] {
            override def value: Try[Value] = `try`.fromDisjunction(either)

            override def release(): Future[Unit] = {
              either match {
                case \/-(closeable) =>
                  Future.delay(closeable.close())
                case -\/(_) =>
                  Future.now(())
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
    def scoped[Value <: AutoCloseable](future: Future[Value]): Do[Value] = {
      scoped(new Task(future.map(\/-(_))))
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](continuation: ContT[Trampoline, Unit, Value]): Do[Value] = {
      scoped(
        new Task(
          Future.Async { continue: ((Throwable \/ Value) => Trampoline[Unit]) =>
            continuation { value: Value =>
              continue(\/-(value))
            }.run
          }
        )
      )
    }

    /** $scoped
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def scoped[Value <: AutoCloseable](value: => Value): Do[Value] = {
      scoped(Task.delay(value))
    }

    /** $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def delay[Value](task: Task[Value]): Do[Value] = {
      Do(
        task.get.map { either =>
          Releasable.now[Future, Try[Value]](`try`.fromDisjunction(either))
        }
      )
    }

    /** $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def delay[Value](future: Future[Value]): Do[Value] = {
      delay(new Task(future.map(\/-(_))))
    }

    /** $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def delay[Value](continuation: ContT[Trampoline, Unit, Value]): Do[Value] = {
      delay(
        new Task(
          Future.Async { continue: ((Throwable \/ Value) => Trampoline[Unit]) =>
            continuation { value: Value =>
              continue(\/-(value))
            }.run
          }
        )
      )
    }

    /** $delay
      * $nonstrict
      * $seenow
      * $seescoped
      */
    def delay[Value](value: => Value): Do[Value] = {
      delay(Task.delay(value))
    }

    /** $now
      * $seedelay
      * $seescoped
      */
    def now[Value](value: Value): Do[Value] = {
      delay(Task.now(value))
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
      delay(Future.async { handler: (Unit => Unit) =>
        executorContext.execute { () =>
          handler(())
        }
      })
    }

    /**
      * Returns a `Task` of `Value`, which will open `Value` and release all resources during opening `Value`.
      *
      * @note `Value` itself must not be a [[scoped]] resources,
      *       though `Value` may depends on some [[scoped]] resources during opening `Value`.
      */
    def run[Value](doValue: Do[Value]): Task[Value] = {
      val future: Future[Throwable \/ Value] =
        ResourceT.run(ResourceT(Do.unwrap(doValue))).map(`try`.toDisjunction)
      new Task(future)
    }

    /** Returns a `Do` of `B` based on a `Do` of `Value` and a function that creates a `Do` of `B`.
      *
      * @note `releaseFlatMap` is similar to `flatMap` in [[doMonadErrorInstances]],
      *       except `releaseFlatMap` will release `Value` right after `B` is created.
      */
    def releaseFlatMap[Value, B](doValue: Do[Value])(f: Value => Do[B]): Do[B] = {
      val resourceA = ResourceT(Do.unwrap(doValue))
      val resourceB = ResourceT.releaseFlatMap[Future, Try[Value], Try[B]](resourceA) {
        case Failure(e) =>
          ResourceT(Future.now(Releasable.now(Failure(e))))
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
      * The underlying `Value` will be [[covariant.Releasable.release release]]d once
      * at the time all user released the wrapper `Do`.
      */
    def shared[Value](doValue: Do[Value]): Do[Value] = {
      val sharedFuture: RAIIFuture[Try[Value]] = TryT.unwrap(opacityTypes.toTryT(doValue)).shared
      opacityTypes.fromTryT(TryT(sharedFuture))
    }
  }

}
