package com.thoughtworks.raii

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz._

object FreeTNondeterminism {

  implicit def freeTParallelApplicative[S[_]: Functor, F[_]: BindRec: Applicative](
      implicit parallelApplicativeF: Applicative[Lambda[X => F[X] @@ Parallel]]
  ): Applicative[Lambda[X => FreeT[S, F, X] @@ Parallel]] =
    new Applicative[Lambda[X => FreeT[S, F, X] @@ Parallel]] {
      override def point[A](a: => A): FreeT[S, F, A] @@ Parallel = {
        Parallel(FreeT.suspend(Parallel.unwrap(parallelApplicativeF.point(\/.left[A, S[FreeT[S, F, A]]](a)))))
      }

      override def ap[A, B](fa: => FreeT[S, F, A] @@ Parallel)(
          ff: => FreeT[S, F, A => B] @@ Parallel): FreeT[S, F, B] @@ Parallel = {
        type T[X] = FreeT[S, F, X]
        type Continue = (T[A], T[A => B])
        val ftb: F[T[B]] = BindRec[F].tailrecM[Continue, T[B]] {
          case (ta: T[A], tf: T[A => B]) =>
            val tail: F[Continue \/ T[B]] @@ Parallel =
              parallelApplicativeF.apply2(Parallel(ta.resume), Parallel(tf.resume)) { (stepA, stepF) =>
                stepA match {
                  case -\/(a) =>
                    stepF match {
                      case -\/(f) =>
                        \/-(FreeT.point(f(a)))
                      case suspendF @ \/-(_) =>
                        @inline def nextTF: T[A => B] = FreeT.suspend(Applicative[F].point(suspendF))
                        \/-(nextTF.map(_(a))(Functor[S], Applicative[F]))
                    }
                  case suspendA @ \/-(_) =>
                    @inline def nextTA: T[A] = FreeT.suspend(Applicative[F].point(suspendA))
                    stepF match {
                      case -\/(f) =>
                        \/-(nextTA.map(f)(Functor[S], Applicative[F]))
                      case suspendF @ \/-(_) =>
                        @inline def nextTF: T[A => B] = FreeT.suspend(Applicative[F].point(suspendF))
                        -\/((nextTA, nextTF))
                    }
                }
              }
            Parallel.unwrap(tail)
        }((Parallel.unwrap(fa), Parallel.unwrap(ff)))

        Parallel(FreeT.freeTMonad[S, F].join(FreeT.liftM(ftb)(BindRec[F])))
      }

    }
}
