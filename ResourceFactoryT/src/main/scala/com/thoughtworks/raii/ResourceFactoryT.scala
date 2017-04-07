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

  def acquire(): F[ReleasableT[F, A]]

  private[raii] final def using[B](f: A => F[B])(implicit monad: Bind[F]): F[B] = {
    acquire().flatMap { fa =>
      f(fa.value).flatMap { a: B =>
        fa.release().map { _ =>
          a
        }
      }
    }
  }

  def run(implicit monad: Bind[F]): F[A] = {
    acquire().flatMap { fa =>
      fa.release().map { _ =>
        fa.value
      }
    }
  }

  private[raii] final def foreach(f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
    this
      .acquire()
      .flatMap { fa =>
        f(fa.value)
        fa.release()
      }
      .sequence_[Id.Id, Unit]
  }

}

private[raii] trait LowPriorityResourceFactoryTInstances3 { this: ResourceFactoryT.type =>

  implicit def resourceFactoryTApplicative[F[_]: Applicative]: Applicative[ResourceFactoryT[F, ?]] =
    new ResourceFactoryTApplicative[F] {
      override private[raii] def typeClass = implicitly
    }

}

private[raii] trait LowPriorityResourceFactoryTInstances2 extends LowPriorityResourceFactoryTInstances3 {
  this: ResourceFactoryT.type =>

  implicit def resourceFactoryTMonad[F[_]: Monad]: Monad[ResourceFactoryT[F, ?]] = new ResourceFactoryTMonad[F] {
    private[raii] override def typeClass = implicitly
  }

}

private[raii] trait LowPriorityResourceFactoryTInstances1 extends LowPriorityResourceFactoryTInstances2 {
  this: ResourceFactoryT.type =>

  implicit def resourceFactoryTNondeterminism[F[_], L](implicit F0: Nondeterminism[F]): Nondeterminism[ResourceFactoryT[F, ?]] =
    new ResourceFactoryTNondeterminism[F] {
      private[raii] override def typeClass = implicitly
    }
}

object ResourceFactoryT extends LowPriorityResourceFactoryTInstances1 {

  def managed[F[_]: Applicative, A <: AutoCloseable](autoCloseable: => A): ResourceFactoryT[F, A] = { () =>
    Applicative[F].point {
      val a = autoCloseable
      new ReleasableT[F, A] {
        override def value: A = a

        override def release(): F[Unit] = Applicative[F].point(a.close())
      }
    }
  }

  trait ReleasableT[F[_], +A] {
    def value: A

    def release(): F[Unit]
  }

  // implicit conversion of SAM type for Scala 2.10 and 2.11
  implicit final class FunctionResourceFactoryT[F[_], A](val underlying: () => F[ReleasableT[F, A]])
      extends AnyVal
      with ResourceFactoryT[F, A] {
    override def acquire(): F[ReleasableT[F, A]] = underlying()
  }

  def apply[F[_]: Applicative, A](a: A): ResourceFactoryT[F, A] = { () =>
    Applicative[F].point(new ReleasableT[F, A] {
      override def value: A = a

      override def release(): F[Unit] = Applicative[F].point(())
    })
  }

  def liftM[F[_]: Applicative, A](fa: F[A]): ResourceFactoryT[F, A] = { () =>
    fa.map { a =>
      new ReleasableT[F, A] {
        override def value: A = a

        override def release(): F[Unit] = Applicative[F].point(())
      }
    }
  }

  def bind[F[_]: Bind, A, B](fa: ResourceFactoryT[F, A])(f: A => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = {
    () =>
      for {
        releasableA <- fa.acquire()
        releasableB <- f(releasableA.value).acquire()
      } yield {
        new ReleasableT[F, B] {
          override def value: B = releasableB.value

          override def release(): F[Unit] = {
            releasableB.release() >> releasableA.release()
          }
        }
      }
  }

  def ap[F[_]: Applicative, A, B](fa: => ResourceFactoryT[F, A])(
      f: => ResourceFactoryT[F, (A) => B]): ResourceFactoryT[F, B] = { () =>
    Applicative[F].apply2(fa.acquire(), f.acquire()) { (releasableA, releasableF) =>
      val b = releasableF.value(releasableA.value)
      new ReleasableT[F, B] {
        override def value: B = b

        override def release(): F[Unit] = {
          Applicative[F].apply2(releasableA.release(), releasableF.release()) { (_: Unit, _: Unit) =>
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

  private[raii] trait ResourceFactoryTMonad[F[_]]
      extends ResourceFactoryTApplicative[F]
      with Monad[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Monad[F]

    override def bind[A, B](fa: ResourceFactoryT[F, A])(f: (A) => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = {
      ResourceFactoryT.bind(fa)(f)
    }

  }

  private[raii] trait ResourceFactoryTNondeterminism[F[_]]
      extends ResourceFactoryTMonad[F]
      with Nondeterminism[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](
        head: ResourceFactoryT[F, A],
        tail: Seq[ResourceFactoryT[F, A]]): ResourceFactoryT[F, (A, Seq[ResourceFactoryT[F, A]])] = { () =>
      typeClass.chooseAny(head.acquire(), tail.map(_.acquire())).map {
        case (fa, residuals) =>
          new ReleasableT[F, (A, Seq[ResourceFactoryT[F, A]])] {
            override def value: (A, Seq[ResourceFactoryT[F, A]]) =
              (fa.value, residuals.map { residual: F[ReleasableT[F, A]] =>
                { () =>
                  residual
                }: ResourceFactoryT[F, A]
              })

            override def release(): F[Unit] = fa.release()
          }

      }

    }
  }

  implicit val resourceFactoryTMonadTrans = new MonadTrans[ResourceFactoryT] {

    override def liftM[G[_]: Monad, A](a: G[A]): ResourceFactoryT[G, A] = ResourceFactoryT.liftM(a)

    override implicit def apply[G[_]: Monad]: Monad[ResourceFactoryT[G, ?]] = resourceFactoryTMonad
  }

}
