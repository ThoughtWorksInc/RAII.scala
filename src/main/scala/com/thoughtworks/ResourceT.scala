package com.thoughtworks

import scala.language.higherKinds
import scalaz.{Id, _}
import scalaz.syntax.all._

trait ResourceT[F[_], A] extends Any {
  import ResourceT._
  def open(): F[CloseableT[F, A]]
  final def foreach(f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
    open()
      .flatMap { fa =>
        f(fa.value)
        fa.close()
      }
      .sequence_[Id.Id, Unit]
  }
}

object ResourceT {

  def managed[F[_]: Applicative, A <: AutoCloseable](autoCloseable: => A): ResourceT[F, A] = { () =>
    Applicative[F].point {
      val a = autoCloseable
      new CloseableT[F, A] {
        override def value: A = a
        override def close(): F[Unit] = Applicative[F].point(a.close())
      }
    }
  }

  trait CloseableT[F[_], A] {
    def value: A
    def close(): F[Unit]
  }

  // For Scala 2.10 and 2.11
  implicit final class FunctionResourceT[F[_], A](val underlying: () => F[CloseableT[F, A]])
      extends AnyVal
      with ResourceT[F, A] {
    override def open(): F[CloseableT[F, A]] = underlying()
  }

  def apply[F[_]: Monad, A](a: => A): ResourceT[F, A] = { () =>
    Monad[F].point(new CloseableT[F, A] {
      override def value: A = a

      override def close(): F[Unit] = Monad[F].point(())
    })
  }

  def liftM[F[_]: Monad, A](fa: F[A]): ResourceT[F, A] = { () =>
    fa.map { a =>
      new CloseableT[F, A] {
        override def value: A = a

        override def close(): F[Unit] = Monad[F].point(())
      }
    }
  }

  def bind[F[_]: Monad, A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = { () =>
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

  implicit object ResourceTMonadTrans extends MonadTrans[ResourceT] {

    override def liftM[F[_]: Monad, A](fa: F[A]): ResourceT[F, A] = ResourceT.liftM(fa)

    override implicit def apply[F[_]: Monad]: Monad[ResourceT[F, ?]] = new Monad[ResourceT[F, ?]] {
      override def bind[A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
        ResourceT.bind(fa)(f)
      }

      override def point[A](a: => A): ResourceT[F, A] = ResourceT(a)
    }

  }
}
