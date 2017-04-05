package com.thoughtworks

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.language.higherKinds
import scalaz.Free.Trampoline
import scalaz.Leibniz.===
import scalaz.concurrent.Future
import scalaz.concurrent.Future.ParallelFuture
import scalaz.{Id, _}
import scalaz.std.iterable._
import scalaz.syntax.all._

trait ResourceT[F[_], A] extends Any {
  import ResourceT._
  def open(): F[CloseableT[F, A]]

  private[thoughtworks] final def using[B](f: A => F[B])(implicit monad: Bind[F]): F[B] = {
    open().flatMap { fa =>
      f(fa.value).flatMap { a: B =>
        fa.close().map { _ =>
          a
        }
      }
    }
  }

  def run(implicit monad: Bind[F]): F[A] = {
    open().flatMap { fa =>
      fa.close().map { _ =>
        fa.value
      }
    }
  }

  private[thoughtworks] final def foreach(f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
    this
      .open()
      .flatMap { fa =>
        f(fa.value)
        fa.close()
      }
      .sequence_[Id.Id, Unit]
  }

  final def shared(implicit constraint: ResourceT[F, A] === ResourceT[Future, A]): ResourceT[Future, A] = {
    new SharedResource(constraint(this))
  }

}

trait LowPririoryInstances { this: ResourceT.type =>

  implicit def resourceMonad[F[_]: Monad]: Monad[ResourceT[F, ?]] = new Monad[ResourceT[F, ?]] {
    override def bind[A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
      ResourceT.bind(fa)(f)
    }

    override def point[A](a: => A): ResourceT[F, A] = ResourceT(a)
  }

}

object ResourceT extends LowPririoryInstances {

  private[ResourceT] object SharedResource {

    sealed trait State[A]
    final case class Closed[A]() extends State[A]
    final case class Opening[A](handlers: Queue[CloseableT[Future, A] => Trampoline[Unit]]) extends State[A]
    final case class Open[A](data: CloseableT[Future, A], count: Int) extends State[A]
  }

  import com.thoughtworks.ResourceT.SharedResource._

  private[ResourceT] final class SharedResource[A](underlying: ResourceT[Future, A])
      extends AtomicReference[State[A]](Closed())
      with ResourceT[Future, A]
      with CloseableT[Future, A] {
    import SharedResource._
    private def sharedCloseable = this
    override def value: A = state.get().asInstanceOf[Open[A]].data.value

    override def close(): Future[Unit] = Future.Suspend { () =>
      @tailrec
      def retry(): Future[Unit] = {
        state.get() match {
          case oldState @ Open(data, count) =>
            if (count == 1) {
              if (state.compareAndSet(oldState, Closed())) {
                data.close()
              } else {
                retry()
              }
            } else {
              if (state.compareAndSet(oldState, oldState.copy(count = count - 1))) {
                Future.now(())
              } else {
                retry()
              }
            }
          case Opening(_) | Closed() =>
            throw new IllegalStateException("Cannot close more than once")

        }
      }
      retry()
    }

    private def state = this

    @tailrec
    private def complete(data: CloseableT[Future, A]): Trampoline[Unit] = {
      state.get() match {
        case oldState @ Opening(handlers) =>
          val newState = Open(data, handlers.length)
          if (state.compareAndSet(oldState, newState)) {
            handlers.traverse_ { f: (CloseableT[Future, A] => Trampoline[Unit]) =>
              f(sharedCloseable)
            }
          } else {
            complete(data)
          }
        case Open(_, _) | Closed() =>
          throw new IllegalStateException("Cannot trigger handler more than once")
      }
    }

    @tailrec
    private def open(handler: CloseableT[Future, A] => Trampoline[Unit]): Unit = {
      state.get() match {
        case oldState @ Closed() =>
          if (state.compareAndSet(oldState, Opening(Queue(handler)))) {
            underlying.open().unsafePerformListen(complete)
          } else {
            open(handler)
          }
        case oldState @ Opening(handlers: Queue[CloseableT[Future, A] => Trampoline[Unit]]) =>
          if (state.compareAndSet(oldState, Opening(handlers.enqueue(handler)))) {
            ()
          } else {
            open(handler)
          }
        case oldState @ Open(data, count) =>
          if (state.compareAndSet(oldState, oldState.copy(count = count + 1))) {
            handler(sharedCloseable).run
          } else {
            open(handler)
          }
      }

    }

    override def open(): Future[CloseableT[Future, A]] = {
      Future.Async(open)
    }
  }

  def managed[F[_]: Applicative, A <: AutoCloseable](autoCloseable: => A): ResourceT[F, A] = { () =>
    Applicative[F].point {
      val a = autoCloseable
      new CloseableT[F, A] {
        override def value: A = a
        override def close(): F[Unit] = Applicative[F].point(a.close())
      }
    }
  }

  trait CloseableT[F[_], +A] {
    def value: A
    def close(): F[Unit]
  }

  // implicit conversion of SAM type for Scala 2.10 and 2.11
  implicit final class FunctionResourceT[F[_], A](val underlying: () => F[CloseableT[F, A]])
      extends AnyVal
      with ResourceT[F, A] {
    override def open(): F[CloseableT[F, A]] = underlying()
  }

  def apply[F[_]: Applicative, A](a: A): ResourceT[F, A] = { () =>
    Applicative[F].point(new CloseableT[F, A] {
      override def value: A = a

      override def close(): F[Unit] = Applicative[F].point(())
    })
  }

  def liftM[F[_]: Applicative, A](fa: F[A]): ResourceT[F, A] = { () =>
    fa.map { a =>
      new CloseableT[F, A] {
        override def value: A = a

        override def close(): F[Unit] = Applicative[F].point(())
      }
    }
  }

  def bind[F[_]: Bind, A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = { () =>
    for {
      closeableA <- fa.open()
      closeableB <- f(closeableA.value).open()
    } yield {
      new CloseableT[F, B] {
        override def value: B = closeableB.value

        override def close(): F[Unit] = {
          closeableB.close() >> closeableA.close()
        }
      }
    }
  }

  def ap[F[_]: Applicative, A, B](fa: => ResourceT[F, A])(f: => ResourceT[F, (A) => B]): ResourceT[F, B] = { () =>
    Applicative[F].apply2(fa.open(), f.open()) { (closeableA, closeableF) =>
      val b = closeableF.value(closeableA.value)
      new CloseableT[F, B] {
        override def value: B = b

        override def close(): F[Unit] = {
          Applicative[F].apply2(closeableA.close(), closeableF.close()) { (_: Unit, _: Unit) =>
            ()
          }
        }
      }
    }

  }

  implicit def resourceApplicative[F[_]: Applicative]: Applicative[ResourceT[F, ?]] =
    new Applicative[ResourceT[F, ?]] {
      override def point[A](a: => A): ResourceT[F, A] = ResourceT(a)

      override def ap[A, B](fa: => ResourceT[F, A])(f: => ResourceT[F, (A) => B]): ResourceT[F, B] = {
        ResourceT.ap(fa)(f)
      }
    }

  implicit val resourceMonadTrans = new MonadTrans[ResourceT] {

    override def liftM[G[_]: Monad, A](a: G[A]): ResourceT[G, A] = ResourceT.liftM(a)

    override implicit def apply[G[_]: Monad]: Monad[ResourceT[G, ?]] = resourceMonad
  }
}
