package com.thoughtworks.raii

import java.util.concurrent.ExecutorService

import com.thoughtworks.raii
import com.thoughtworks.raii.transformers.{ResourceFactoryT, ResourceT}
import com.thoughtworks.tryt.TryT

import scala.concurrent.ExecutionContext
import scalaz.{-\/, @@, Applicative, BindRec, Monad, MonadError, \/, \/-}
import scalaz.concurrent.{Future, Task}
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import scalaz.Tags.Parallel

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object future {

  private[future] val DoExtractor: DoExtractor = new DoExtractor {
    override type Do[A] = TryT[RAIIFuture, A]

    override def apply[A](run: TryT[RAIIFuture, A]): TryT[RAIIFuture, A] = run

    override private[raii] def unwrap[F[_], A](doa: TryT[RAIIFuture, A]): TryT[RAIIFuture, A] = doa

    override def doMonadInstances: BindRec[TryT[RAIIFuture, ?]] with MonadError[TryT[RAIIFuture, ?], Throwable] =
      ??? //implicitly

    override def doParallelApplicative: Applicative[TryT[RAIIFuture, ?]] @@ Parallel = ??? //implicitly

  }

  import scala.language.higherKinds

  type Do[A] = DoExtractor.Do[A]

  private[raii] trait DoExtractor {
    type Do[A]

    def apply[A](run: TryT[RAIIFuture, A]): Do[A]

    private[raii] def unwrap[F[_], A](doA: Do[A]): TryT[RAIIFuture, A]

    final def unapply[A](doA: Do[A]): Some[TryT[RAIIFuture, A]] =
      Some(unwrap(doA))

    implicit def doMonadInstances: BindRec[Do] with Monad[Do]
    implicit def doParallelApplicative: Applicative[Do] @@ Parallel
  }

  implicit def doMonadInstances: BindRec[Do] with Monad[Do] = DoExtractor.doMonadInstances
  implicit def doParallelApplicative: Applicative[Do] @@ Parallel = DoExtractor.doParallelApplicative

  // DoFunctions is a workaround for type alias `Covariant`,
  // because the abstract type cannot defined in object.
  private[raii] trait DoFunctions {
    type Covariant[A] >: Do[_ <: A] <: Do[_ <: A]
  }

  object Do extends DoFunctions {

    /** @template */
    type AsyncReleasable[A] = ResourceT[Future, A]

    def apply[A](run: Future[AsyncReleasable[Try[A]]]): Do[A] = {
      DoExtractor(TryT(ResourceFactoryT(run)))
    }

    private[raii] def unwrap[F[_], A](doA: Do[A]): Future[AsyncReleasable[Try[A]]] = {
      ResourceFactoryT.unwrap(TryT.unwrap(DoExtractor.unwrap(doA)))
    }

    def unapply[F[_], A](doA: Do[A]): Some[Future[AsyncReleasable[Try[A]]]] = {
      Some(unwrap(doA))
    }

    /** @template */
    private[raii] type RAIIFuture[A] = ResourceFactoryT[Future, A]

    /** @template */
    //private[raii] type Do[A] = TryT[RAIIFuture, A]

    def managed[A <: AutoCloseable](task: Task[A]): Do[A] = {
      Do(
        task.get.map { either =>
          new ResourceT[Future, Try[A]] {
            override def value: Try[A] = eitherToTry(either)

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

    def managed[A <: AutoCloseable](future: Future[A]): Do[A] = {
      managed(new Task(future.map(\/-(_))))
    }

    def managed[A <: AutoCloseable](a: => A): Do[A] = {
      managed(Task.delay(a))
    }

    def unmanaged[A](task: Task[A]): Do[A] = {
      Do(
        task.get.map { either =>
          new ResourceT[Future, Try[A]] {
            override def value: Try[A] = eitherToTry(either)

            override def release(): Future[Unit] = Future.now(())
          }
        }
      )
    }

    def unmanaged[A](future: Future[A]): Do[A] = {
      unmanaged(new Task(future.map(\/-(_))))
    }

    def delay[A](a: => A): Do[A] = {
      unmanaged(Task.delay(a))
    }

    def now[A](a: A): Do[A] = {
      unmanaged(Task.now(a))
    }

    def jump()(implicit executorService: ExecutionContext): Do[Unit] = {
      unmanaged(Future.async { handler: (Unit => Unit) =>
        executorService.execute { () =>
          handler(())
        }
      })
    }

//      def run[A](doa: Do[A]): Task[A] = {
//        //Future[Throwable \/ A]
//        new Task(doa.run.run)
//      }

    def eitherToTry[A](either: Throwable \/ A): Try[A] = either match {
      case -\/(e) => Failure(e)
      case \/-(value) => Success(value)
    }

  }

}
