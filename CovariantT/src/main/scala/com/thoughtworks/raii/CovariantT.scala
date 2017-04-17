package com.thoughtworks.raii
import scala.language.higherKinds
import scalaz.{Monad, MonadTrans}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object CovariantT {

  type CovariantT[F[_], +A] = F[_ <: A]

  trait CovariantTMonad[F[_]] extends Monad[CovariantT[F, ?]] {
    implicit def F: Monad[F]

    override def point[A](a: => A): CovariantT[F, A] = F.point(a)

    override def bind[A, B](fa: CovariantT[F, A])(f: A => CovariantT[F, B]) = {
      F.bind(fa) { a: A =>
        F.map(f(a))(identity[B])
      }
    }
  }

  implicit def covariantTMonadTrans = new MonadTrans[CovariantT] {

    override def liftM[G[_], A](a: G[A])(implicit evidence$1: Monad[G]): CovariantT[G, A] = a

    override implicit def apply[G[_]](implicit evidence$2: Monad[G]): Monad[CovariantT[G, ?]] = covariantTMonad[G]
  }

  implicit def covariantTMonad[F[_]: Monad]: Monad[CovariantT[F, ?]] = new CovariantTMonad[F] {

    override def F: Monad[F] = implicitly

  }

}
