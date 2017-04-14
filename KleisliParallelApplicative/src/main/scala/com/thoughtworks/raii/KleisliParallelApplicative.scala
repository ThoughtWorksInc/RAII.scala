package com.thoughtworks.raii
import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz.{@@, Applicative, Kleisli}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object KleisliParallelApplicative {

  implicit def kleisliParallelApplicative[F[_], Context](implicit F0: Applicative[Lambda[x => F[x] @@ Parallel]])
    : Applicative[Lambda[x => Kleisli[F, Context, x] @@ Parallel]] = {
    type T[A] = Kleisli[F, Context, A]
    type P[A] = T[A] @@ Parallel
    new Applicative[P] {
      override def point[A](a: => A): P[A] = {
        Parallel(Kleisli { _: Context =>
          Parallel.unwrap(F0.point(a))
        })
      }

      override def ap[A, B](fa: => P[A])(f: => P[(A) => B]): P[B] = {
        Parallel(Kleisli { context: Context =>
          Parallel.unwrap(F0.ap(Parallel(Parallel.unwrap(fa).run(context)))(Parallel(Parallel.unwrap(f).run(context))))
        })
      }
    }
  }

}
