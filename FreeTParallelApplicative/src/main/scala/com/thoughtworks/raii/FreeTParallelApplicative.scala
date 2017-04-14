package com.thoughtworks.raii

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz._

object FreeTParallelApplicative {

  implicit def freeTParallelApplicative[S[_], F[_]](
      implicit FS: Functor[S],
      BF: BindRec[F],
      AF: Applicative[F],
      parallelApplicativeF: Applicative[Lambda[X => F[X] @@ Parallel]]
  ): Applicative[Lambda[X => FreeT[S, F, X] @@ Parallel]] =
    new Applicative[Lambda[X => FreeT[S, F, X] @@ Parallel]] {
      override def point[A](a: => A): FreeT[S, F, A] @@ Parallel = {
        Parallel(FreeT.suspend(Parallel.unwrap(parallelApplicativeF.point(\/.left[A, S[FreeT[S, F, A]]](a)))))
      }

      override def ap[A, B](fa: => FreeT[S, F, A] @@ Parallel)(
          ff: => FreeT[S, F, A => B] @@ Parallel): FreeT[S, F, B] @@ Parallel = {
        type T[X] = FreeT[S, F, X]
        def rawAp(ta: T[A], tf: T[A => B]): T[B] = {
          val tail: F[T[B]] @@ Parallel =
            parallelApplicativeF.apply2(Parallel[F[A \/ S[T[A]]]](ta.resume),
                                        Parallel[F[(A => B) \/ S[T[A => B]]]](tf.resume)) { (stepA, stepF) =>
              stepA match {
                case -\/(a) =>
                  stepF match {
                    case -\/(f) =>
                      FreeT.point(f(a))
                    case rightF @ \/-(_) =>
                      val nextTF: T[A => B] = FreeT.suspend(Applicative[F].point(rightF))
                      nextTF.map(_(a))(Functor[S], Applicative[F])
                  }
                case rightA @ \/-(suspendA) =>
                  stepF match {
                    case -\/(f) =>
                      val nextTA: T[A] = FreeT.suspend(Applicative[F].point(rightA))
                      nextTA.map(f)(Functor[S], Applicative[F])
                    case rightF @ \/-(suspendF) =>
                      val stb0: S[T[B]] = FS.map(suspendA) { nextTa: T[A] =>
                        val stb1: S[T[B]] = FS.map(suspendF) { nextTf: T[A => B] =>
                          rawAp(nextTa, nextTf)
                        }
                        FreeT.suspend(Applicative[F].point(\/-(stb1))): T[B]
                      }
                      FreeT.suspend(Applicative[F].point(\/-(stb0))): T[B]
                  }
              }
            }
          FreeT.freeTMonad[S, F].join(FreeT.liftM(Parallel.unwrap(tail))(BindRec[F]))
        }
        Parallel(rawAp(Parallel.unwrap(fa), Parallel.unwrap(ff)))
      }

    }
}
