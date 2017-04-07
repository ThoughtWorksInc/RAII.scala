package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.language.higherKinds
import scalaz.Free.Trampoline
import scalaz.Leibniz.===
import scalaz.{Id, _}
import scalaz.std.iterable._
import scalaz.syntax.all._

trait ResourceFactoryT[F[_], A] extends Any {

  import ResourceFactoryT._

  def open(): F[CloseableT[F, A]]

  private[raii] final def using[B](f: A => F[B])(implicit monad: Bind[F]): F[B] = {
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

  private[raii] final def foreach(f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
    this
      .open()
      .flatMap { fa =>
        f(fa.value)
        fa.close()
      }
      .sequence_[Id.Id, Unit]
  }

}

private[raii] trait LowPriorityResourceFactoryTInstances3 { this: ResourceFactoryT.type =>

  implicit def raiiNondeterminism[F[_], L](implicit F0: Nondeterminism[F]): Nondeterminism[ResourceFactoryT[F, ?]] =
    new ResourceFactoryTNondeterminism[F] {
      private[raii] override def typeClass = implicitly
    }

}

private[raii] trait LowPriorityResourceFactoryTInstances2 extends LowPriorityResourceFactoryTInstances3 { this: ResourceFactoryT.type =>

  implicit def raiiMonad[F[_]: Monad]: Monad[ResourceFactoryT[F, ?]] = new ResourceFactoryTMonad[F] {
    private[raii] override def typeClass = implicitly
  }

}

private[raii] trait LowPriorityResourceFactoryTInstances1 extends LowPriorityResourceFactoryTInstances2 { this: ResourceFactoryT.type =>

  implicit def raiiApplicative[F[_]: Applicative]: Applicative[ResourceFactoryT[F, ?]] = new ResourceFactoryTApplicative[F] {
    override private[raii] def typeClass = implicitly
  }
}

object ResourceFactoryT extends LowPriorityResourceFactoryTInstances1 {

  def managed[F[_]: Applicative, A <: AutoCloseable](autoCloseable: => A): ResourceFactoryT[F, A] = { () =>
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
  implicit final class FunctionResourceFactoryT[F[_], A](val underlying: () => F[CloseableT[F, A]])
      extends AnyVal
      with ResourceFactoryT[F, A] {
    override def open(): F[CloseableT[F, A]] = underlying()
  }

  def apply[F[_]: Applicative, A](a: A): ResourceFactoryT[F, A] = { () =>
    Applicative[F].point(new CloseableT[F, A] {
      override def value: A = a

      override def close(): F[Unit] = Applicative[F].point(())
    })
  }

  def liftM[F[_]: Applicative, A](fa: F[A]): ResourceFactoryT[F, A] = { () =>
    fa.map { a =>
      new CloseableT[F, A] {
        override def value: A = a

        override def close(): F[Unit] = Applicative[F].point(())
      }
    }
  }

  def bind[F[_]: Bind, A, B](fa: ResourceFactoryT[F, A])(f: A => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = { () =>
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

  def ap[F[_]: Applicative, A, B](fa: => ResourceFactoryT[F, A])(f: => ResourceFactoryT[F, (A) => B]): ResourceFactoryT[F, B] = { () =>
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

  private[raii] trait ResourceFactoryTApplicative[F[_]] extends Applicative[ResourceFactoryT[F, ?]] {
    private[raii] implicit def typeClass: Applicative[F]

    override def point[A](a: => A): ResourceFactoryT[F, A] = {
      ResourceFactoryT[F, A](a)
    }

    override def ap[A, B](fa: => ResourceFactoryT[F, A])(f: => ResourceFactoryT[F, (A) => B]): ResourceFactoryT[F, B] = {
      ResourceFactoryT.ap(fa)(f)
    }
  }

  private[raii] trait ResourceFactoryTMonad[F[_]] extends ResourceFactoryTApplicative[F] with Monad[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Monad[F]

    override def bind[A, B](fa: ResourceFactoryT[F, A])(f: (A) => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = {
      ResourceFactoryT.bind(fa)(f)
    }

  }

  private[raii] trait ResourceFactoryTNondeterminism[F[_]] extends ResourceFactoryTMonad[F] with Nondeterminism[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](head: ResourceFactoryT[F, A], tail: Seq[ResourceFactoryT[F, A]]): ResourceFactoryT[F, (A, Seq[ResourceFactoryT[F, A]])] = { () =>
      typeClass.chooseAny(head.open(), tail.map(_.open())).map {
        case (fa, residuals) =>
          new CloseableT[F, (A, Seq[ResourceFactoryT[F, A]])] {
            override def value: (A, Seq[ResourceFactoryT[F, A]]) =
              (fa.value, residuals.map { residual: F[CloseableT[F, A]] =>
                { () =>
                  residual
                }: ResourceFactoryT[F, A]
              })

            override def close(): F[Unit] = fa.close()
          }

      }

    }
  }

  implicit val raiiMonadTrans = new MonadTrans[ResourceFactoryT] {

    override def liftM[G[_]: Monad, A](a: G[A]): ResourceFactoryT[G, A] = ResourceFactoryT.liftM(a)

    override implicit def apply[G[_]: Monad]: Monad[ResourceFactoryT[G, ?]] = raiiMonad
  }

}
