package com.thoughtworks.raii

import com.thoughtworks.raii.ownership._
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
import shapeless.<:!<

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object asynchronous {

  /** @template */
  private[raii] type RAIIFuture[+A] = ResourceT[Future, A]

  private[raii] trait OpacityTypes {
    type Do[+A]

    def fromTryT[A](run: TryT[ResourceT[Future, `+?`], A]): Do[A]

    private[raii] def toTryT[F[_], A](doA: Do[A]): TryT[RAIIFuture, A]

    //TODO: BindRec
    implicit def doMonadErrorInstances: MonadError[Do, Throwable]

    implicit def doParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[Lambda[A => Do[A] @@ Parallel]]
  }

  private[asynchronous] val opacityTypes: OpacityTypes = new OpacityTypes {
    override type Do[+A] = TryT[RAIIFuture, A]

    override def fromTryT[A](run: TryT[RAIIFuture, A]): TryT[RAIIFuture, A] = run

    override private[raii] def toTryT[F[_], A](doa: TryT[RAIIFuture, A]): TryT[RAIIFuture, A] = doa

    override def doMonadErrorInstances: MonadError[TryT[RAIIFuture, ?], Throwable] = {
      TryT.tryTMonadError[RAIIFuture](ResourceT.resourceTMonad[Future](Future.futureInstance))
    }

    override def doParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]) = {
      TryT.tryTParallelApplicative[RAIIFuture](
        ResourceT.resourceTParallelApplicative[Future](Future.futureParallelApplicativeInstance),
        throwableSemigroup)
    }
  }

  /** @template */
  type Do[+A] = opacityTypes.Do[A]

  // DoFunctions is a workaround for type alias `Covariant`,
  // because the abstract type cannot defined in object.
  private[raii] trait DoFunctions {
    type Covariant[A] >: Do[_ <: A] <: Do[_ <: A]
  }

  object Do extends DoFunctions {

    implicit def doMonadErrorInstances: MonadError[Do, Throwable] = opacityTypes.doMonadErrorInstances
    implicit def doParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[Lambda[A => Do[A] @@ Parallel]] =
      opacityTypes.doParallelApplicative

    def apply[A](run: Future[Releasable[Future, Try[A]]]): Do[A] = {
      opacityTypes.fromTryT(TryT[RAIIFuture, A](ResourceT(run)))
    }

    private def unwrap[F[_], A](doA: Do[A]): Future[Releasable[Future, Try[A]]] = {
      val ResourceT(future) = TryT.unwrap(opacityTypes.toTryT(doA))
      future
    }

    def unapply[F[_], A](doA: Do[A]): Some[Future[Releasable[Future, Try[A]]]] = {
      Some(unwrap(doA))
    }

    def scoped[A <: AutoCloseable](task: Task[A]): Do[Scoped[A]] = {
      Do(
        task.get.map { either =>
          new Releasable[Future, Try[Scoped[A]]] {
            override def value: Try[this.type Owned A] = `try`.fromDisjunction(either).map(this.own)

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

    def scoped[A <: AutoCloseable](future: Future[A]): Do[Scoped[A]] = {
      scoped(new Task(future.map(\/-(_))))
    }

    def scoped[A <: AutoCloseable](a: => A): Do[Scoped[A]] = {
      scoped(Task.delay(a))
    }

    def delay[A](task: Task[A]): Do[A] = {
      Do(
        task.get.map { either =>
          Releasable.now[Future, Try[A]](`try`.fromDisjunction(either))
        }
      )
    }

    def delay[A](future: Future[A]): Do[A] = {
      delay(new Task(future.map(\/-(_))))
    }

    def delay[A](continuation: ContT[Trampoline, Unit, A]): Do[A] = {
      delay(
        new Task(
          Future.Async { continue: ((Throwable \/ A) => Trampoline[Unit]) =>
            continuation { a: A =>
              continue(\/-(a))
            }.run
          }
        )
      )
    }

    def delay[A](a: => A): Do[A] = {
      delay(Task.delay(a))
    }

    def now[A](a: A): Do[A] = {
      delay(Task.now(a))
    }

    def jump()(implicit executorService: ExecutionContext): Do[Unit] = {
      delay(Future.async { handler: (Unit => Unit) =>
        executorService.execute { () =>
          handler(())
        }
      })
    }

    /**
      * Returns a `Task` of `A`, which will open `A` and release all resources during opening `A`.
      *
      * @note `A` itself must not be a is scoped resources,
      *      though `A` may depends on some scoped resources during opening `A`.
      * @example {{{
      *   val doInputStream: Do[Scoped[InputStream]] = ???
      *   doInputStream.map { input: InputStream =>
      *     doSomethingWith(input)
      *   }.run.unsafeAsync....
      * }}}
      */
    def run[A](doA: Do[A])(implicit notScoped: A <:!< Scoped[_]): Task[A] = {
      val future: Future[Throwable \/ A] =
        ResourceT.run(ResourceT(Do.unwrap(doA))).map(`try`.toDisjunction)
      new Task(future)
    }

    def releaseFlatMap[A, B](doA: Do[A])(f: A => Do[B]): Do[B] = {
      val resourceA = ResourceT(Do.unwrap(doA))
      val resourceB = ResourceT.releaseFlatMap[Future, Try[A], Try[B]](resourceA) {
        case Failure(e) =>
          ResourceT(Future.now(Releasable.now(Failure(e))))
        case Success(a) =>
          ResourceT(Do.unwrap(f(a)))
      }
      val ResourceT(future) = resourceB
      Do(future)
    }

    def releaseMap[A, B](doA: Do[A])(f: A => B): Do[B] = {
      val resourceA = ResourceT(Do.unwrap(doA))
      val resourceB = ResourceT.releaseMap(resourceA)(_.map(f))
      val ResourceT(future) = resourceB
      Do(future)
    }

    def shared[A](doA: Do[A]): Do[A] = {
      val sharedFuture: RAIIFuture[Try[A]] = TryT.unwrap(opacityTypes.toTryT(doA)).shared
      opacityTypes.fromTryT(TryT(sharedFuture))
    }
  }

}
