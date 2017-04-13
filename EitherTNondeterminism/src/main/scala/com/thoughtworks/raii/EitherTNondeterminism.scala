package com.thoughtworks.raii

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz.{-\/, @@, Applicative, Apply, EitherT, Monoid, Nondeterminism, Reducer, Semigroup, \/, \/-}
import scalaz.syntax.all._

object EitherTNondeterminism {

  implicit def eitherTParallelApplictive[F[_], L](
      implicit F0: Applicative[Lambda[R => F[R] @@ Parallel]],
      semigroup: Semigroup[L]): Applicative[Lambda[R => EitherT[F, L, R] @@ Parallel]] =
    new Applicative[Lambda[R => EitherT[F, L, R] @@ Parallel]] {
      override def point[R](r: => R): EitherT[F, L, R] @@ Parallel = {
        val fa: F[L \/ R] @@ Parallel = F0.point(\/-(r))
        Parallel(new EitherT(Parallel.unwrap(fa)))
      }

      override def ap[A, B](fa: => EitherT[F, L, A] @@ Parallel)(
          f: => EitherT[F, L, A => B] @@ Parallel): EitherT[F, L, B] @@ Parallel = {
        Parallel(
          new EitherT(Parallel.unwrap(F0.apply2(Parallel(Parallel.unwrap(fa).run), Parallel(Parallel.unwrap(f).run)) {
            (eitherA: L \/ A, eitherF: L \/ (A => B)) =>
              eitherA match {
                case \/-(a) =>
                  eitherF match {
                    case \/-(f) => \/-(f(a))
                    case left @ -\/(_) => left
                  }
                case leftA @ -\/(la) =>
                  eitherF match {
                    case \/-(_) => leftA
                    case leftF @ -\/(lf) =>
                      -\/(la |+| lf)
                  }
              }
          })))
      }
    }

  implicit def eitherTNondeterminism[F[_], L](implicit F0: Nondeterminism[F]): Nondeterminism[EitherT[F, L, ?]] =
    new EitherTNondeterminism[F, L] {
      val F = F0
    }
}

private[raii] trait EitherTNondeterminism[F[_], L] extends Nondeterminism[EitherT[F, L, ?]] {
  implicit protected def F: Nondeterminism[F]

  override def chooseAny[A](head: EitherT[F, L, A],
                            tail: Seq[EitherT[F, L, A]]): EitherT[F, L, (A, Seq[EitherT[F, L, A]])] =
    new EitherT(F.map(F.chooseAny(head.run, tail map (_.run))) {
      case (a, residuals) =>
        a.map((_, residuals.map(new EitherT(_))))
    })

  override def bind[A, B](fa: EitherT[F, L, A])(f: (A) => EitherT[F, L, B]): EitherT[F, L, B] =
    fa flatMap f

  override def point[A](a: => A): EitherT[F, L, A] = EitherT.eitherTMonad[F, L].point(a)

  override def reduceUnordered[A, M](fs: Seq[EitherT[F, L, A]])(implicit R: Reducer[A, M]): EitherT[F, L, M] = {
    import R.monoid
    val AE = Apply[L \/ ?]
    val RR: Reducer[L \/ A, L \/ M] =
      Reducer[L \/ A, L \/ M](
        _.map(R.unit),
        c => AE.apply2(c, _)(R.cons(_, _)),
        m => AE.apply2(m, _)(R.snoc(_, _))
      )(Monoid.liftMonoid[L \/ ?, M])
    new EitherT(F.reduceUnordered(fs.map(_.run))(RR))
  }
}
