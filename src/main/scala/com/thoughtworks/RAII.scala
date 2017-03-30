package com.thoughtworks
import scala.language.higherKinds
import scalaz.{Monad, MonadTrans}
import scalaz.syntax.all._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object RAII {

  trait Resource[F[_], A] {
    def retain(a: A): F[Unit]
    def release(a: A): F[Unit]
  }

  trait RAIIT[F[_], A] {
    def value: F[A]
    def retain(): F[Unit]
    def release(): F[Unit]
  } //(fa: F[A])(implicit val resource: Resource[F, A])

  /*

  val fa: RAIIT = ???

  fa.flatMap(_ => fa)

   */

  implicit object RAIITMonadTrans extends MonadTrans[RAIIT] {
    override def liftM[G[_], A](ga: G[A])(implicit monad: Monad[G]): RAIIT[G, A] = {

      new RAIIT[G, A] {
        override def value: G[A] = ga

        override def release(): G[Unit] = monad.point(())

        override def retain(): G[Unit] = ???
      }

    }

    override implicit def apply[G[_]](implicit monad: Monad[G]): Monad[RAIIT[G, ?]] = new Monad[RAIIT[G, ?]] {
      override def ap[A, B](fa: => RAIIT[G, A])(f: => RAIIT[G, (A) => B]): RAIIT[G, B] = super.ap(fa)(f)

      override def tuple2[A, B](fa: => RAIIT[G, A], fb: => RAIIT[G, B]): RAIIT[G, (A, B)] = super.tuple2(fa, fb)

      override def bind[A, B](fa: RAIIT[G, A])(f: (A) => RAIIT[G, B]): RAIIT[G, B] = {
        val t: G[(B, () => G[Unit])] = fa.value.flatMap { a =>
          val rb: RAIIT[G, B] = f(a)
          monad.tuple2(
            rb.value,
            monad.point(rb.release _)
          )
        }
        new RAIIT[G, B] {
          var count: Int = 0

          override val value: G[B] = {
            t.map(_._1)
          }

          override def release(): G[Unit] = {
            t.flatMap { pair =>
              val closeB = pair._2()
              closeB.flatMap { _: Unit =>
                fa.release()
              }
            }
          }

          override def retain(): G[Unit] = ???
        }

      }

      override def map[A, B](fa: RAIIT[G, A])(f: (A) => B): RAIIT[G, B] = super.map(fa)(f)

      override def point[A](a: => A): RAIIT[G, A] = {
        new RAIIT[G, A] {
          override def value: G[A] = monad.point(a)

          override def release(): G[Unit] = {
            monad.point(())
          }

          override def retain(): G[Unit] = ???
        }
      }

      final def managed[A](a: A)(implicit resource: Resource[G, A]): RAIIT[G, A] = {
        new RAIIT[G, A] {
          override def value: G[A] = monad.point(a)

          override def release(): G[Unit] = {
            resource.release(a)
          }

          override def retain(): G[Unit] = ???
        }
      }
    }
  }

}
