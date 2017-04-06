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

trait RAII[F[_], A] extends Any {

  import RAII._

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

private[raii] trait LowPriorityRAIIInstances3 { this: RAII.type =>

  implicit def raiiNondeterminism[F[_], L](implicit F0: Nondeterminism[F]): Nondeterminism[RAII[F, ?]] =
    new RAIINondeterminism[F] {
      private[raii] override def typeClass = implicitly
    }

}

private[raii] trait LowPriorityRAIIInstances2 extends LowPriorityRAIIInstances3 { this: RAII.type =>

  implicit def raiiMonad[F[_]: Monad]: Monad[RAII[F, ?]] = new RAIIMonad[F] {
    private[raii] override def typeClass = implicitly
  }

}

private[raii] trait LowPriorityRAIIInstances1 extends LowPriorityRAIIInstances2 { this: RAII.type =>

  implicit def raiiApplicative[F[_]: Applicative]: Applicative[RAII[F, ?]] = new RAIIApplicative[F] {
    override private[raii] def typeClass = implicitly
  }
}

object RAII extends LowPriorityRAIIInstances1 {

  def managed[F[_]: Applicative, A <: AutoCloseable](autoCloseable: => A): RAII[F, A] = { () =>
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
  implicit final class FunctionRAII[F[_], A](val underlying: () => F[CloseableT[F, A]])
      extends AnyVal
      with RAII[F, A] {
    override def open(): F[CloseableT[F, A]] = underlying()
  }

  def apply[F[_]: Applicative, A](a: A): RAII[F, A] = { () =>
    Applicative[F].point(new CloseableT[F, A] {
      override def value: A = a

      override def close(): F[Unit] = Applicative[F].point(())
    })
  }

  def liftM[F[_]: Applicative, A](fa: F[A]): RAII[F, A] = { () =>
    fa.map { a =>
      new CloseableT[F, A] {
        override def value: A = a

        override def close(): F[Unit] = Applicative[F].point(())
      }
    }
  }

  def bind[F[_]: Bind, A, B](fa: RAII[F, A])(f: A => RAII[F, B]): RAII[F, B] = { () =>
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

  def ap[F[_]: Applicative, A, B](fa: => RAII[F, A])(f: => RAII[F, (A) => B]): RAII[F, B] = { () =>
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

  private[raii] trait RAIIApplicative[F[_]] extends Applicative[RAII[F, ?]] {
    private[raii] implicit def typeClass: Applicative[F]

    override def point[A](a: => A): RAII[F, A] = {
      RAII[F, A](a)
    }

    override def ap[A, B](fa: => RAII[F, A])(f: => RAII[F, (A) => B]): RAII[F, B] = {
      RAII.ap(fa)(f)
    }
  }

  private[raii] trait RAIIMonad[F[_]] extends RAIIApplicative[F] with Monad[RAII[F, ?]] {
    private[raii] implicit override def typeClass: Monad[F]

    override def bind[A, B](fa: RAII[F, A])(f: (A) => RAII[F, B]): RAII[F, B] = {
      RAII.bind(fa)(f)
    }

  }

  private[raii] trait RAIINondeterminism[F[_]] extends RAIIMonad[F] with Nondeterminism[RAII[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](head: RAII[F, A], tail: Seq[RAII[F, A]]): RAII[F, (A, Seq[RAII[F, A]])] = { () =>
      typeClass.chooseAny(head.open(), tail.map(_.open())).map {
        case (fa, residuals) =>
          new CloseableT[F, (A, Seq[RAII[F, A]])] {
            override def value: (A, Seq[RAII[F, A]]) =
              (fa.value, residuals.map { residual: F[CloseableT[F, A]] =>
                { () =>
                  residual
                }: RAII[F, A]
              })

            override def close(): F[Unit] = fa.close()
          }

      }

    }
  }

  implicit val raiiMonadTrans = new MonadTrans[RAII] {

    override def liftM[G[_]: Monad, A](a: G[A]): RAII[G, A] = RAII.liftM(a)

    override implicit def apply[G[_]: Monad]: Monad[RAII[G, ?]] = raiiMonad
  }

}
