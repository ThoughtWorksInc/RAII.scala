package com.thoughtworks.raii

import java.util.concurrent.ExecutorService

import com.thoughtworks.raii
import com.thoughtworks.raii.ownership._
import com.thoughtworks.raii.ownership.implicits._
import com.thoughtworks.raii.transformers.{ResourceFactoryT, ResourceT}
import com.thoughtworks.tryt.TryT

import scala.concurrent.ExecutionContext
import scalaz.{-\/, @@, Applicative, ContT, Monad, MonadError, Semigroup, \/, \/-}
import scalaz.concurrent.{Future, Task}
import scala.language.higherKinds
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}
import scalaz.Free.Trampoline
import scalaz.Tags.Parallel
import scalaz.std.`try`.fromDisjunction
import scalaz.std.`try`.toDisjunction

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object future {

  /** @template */
  private[raii] type RAIIFuture[A] = ResourceFactoryT[Future, A]

  private[raii] trait DoExtractor {
    type Do[A]

    def apply[A](run: TryT[RAIIFuture, A]): Do[A]

    private[raii] def unwrap[F[_], A](doA: Do[A]): TryT[RAIIFuture, A]

    final def unapply[A](doA: Do[A]): Some[TryT[RAIIFuture, A]] =
      Some(unwrap(doA))

    //TODO: BindRec
    implicit def doMonadErrorInstances: MonadError[Do, Throwable]

    implicit def doParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[Lambda[A => Do[A] @@ Parallel]]
  }

  private[future] val DoExtractor: DoExtractor = new DoExtractor {
    override type Do[A] = TryT[RAIIFuture, A]

    override def apply[A](run: TryT[RAIIFuture, A]): TryT[RAIIFuture, A] = run

    override private[raii] def unwrap[F[_], A](doa: TryT[RAIIFuture, A]): TryT[RAIIFuture, A] = doa

    override def doMonadErrorInstances: MonadError[TryT[RAIIFuture, ?], Throwable] =
      TryT.tryTMonadError[RAIIFuture](ResourceFactoryT.resourceFactoryTMonad[Future](Future.futureInstance))

    override def doParallelApplicative(implicit throwableSemigroup: Semigroup[Throwable]) = {
      import com.thoughtworks.raii.transformers.ResourceFactoryT.resourceFactoryTParallelApplicative
      import TryT.tryTParallelApplicative
      import Future.futureParallelApplicativeInstance
      implicitly
    }
  }

  type Do[A] = DoExtractor.Do[A]

  // DoFunctions is a workaround for type alias `Covariant`,
  // because the abstract type cannot defined in object.
  private[raii] trait DoFunctions {
    type Covariant[A] >: Do[_ <: A] <: Do[_ <: A]
  }

  object Do extends DoFunctions {

    implicit def doMonadErrorInstances: MonadError[Do, Throwable] = DoExtractor.doMonadErrorInstances
    implicit def doParallelApplicative(
        implicit throwableSemigroup: Semigroup[Throwable]): Applicative[Lambda[A => Do[A] @@ Parallel]] =
      DoExtractor.doParallelApplicative

    /** @template */
    type AsyncReleasable[A] = ResourceT[Future, A]

    def apply[A](run: Future[AsyncReleasable[Try[A]]]): Do[A] = {
      DoExtractor(TryT[RAIIFuture, A](ResourceFactoryT(run)))
    }

    private[raii] def unwrap[F[_], A](doA: Do[A]): Future[AsyncReleasable[Try[A]]] = {
      ResourceFactoryT.unwrap(TryT.unwrap(DoExtractor.unwrap(doA)))
    }

    def unapply[F[_], A](doA: Do[A]): Some[Future[AsyncReleasable[Try[A]]]] = {
      Some(unwrap(doA))
    }

    /** @template */
    //private[raii] type Do[A] = TryT[RAIIFuture, A]

    def scoped[A <: AutoCloseable](task: Task[A]): Do[Scoped[A]] = {
      Do(
        task.get.map { either =>
          new ResourceT[Future, Try[Scoped[A]]] {
            override def value: Try[this.type Owned A] = fromDisjunction(either).map(this.own)

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
          new ResourceT[Future, Try[A]] {
            override def value: Try[A] = fromDisjunction(either)

            override def release(): Future[Unit] = Future.now(())
          }
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
      * Return a `Task` which contains the value of A,
      * NOTE: If a is scoped resources, you should use `map` before `run`,
      * because [[ResourceFactoryT.run]] will invoke [[ResourceT.release]].
      * @example {{{
      *   val doInputStream: Do[InputStream] = ???
      *   doInputStream.map { input: InputStream =>
      *     doSomethingWith(input)
      *   }.run.unsafeAsync....
      * }}}
      */
    def run[A](doA: Do[A]): Task[A] = {
      val future: Future[Throwable \/ A] =
        ResourceFactoryT.run(ResourceFactoryT.apply(Do.unapply(doA).get)).map(toDisjunction)
      new Task(future)
    }
  }

}
