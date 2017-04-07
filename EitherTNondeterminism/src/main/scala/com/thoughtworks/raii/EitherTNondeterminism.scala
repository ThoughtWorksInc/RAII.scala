package com.thoughtworks.raii

import scala.language.higherKinds
import scalaz.{Apply, DisjunctionT, EitherT, Monoid, Nondeterminism, Reducer, \/}

object EitherTNondeterminism {
  implicit def eitherTNondeterminism[F[_], L](implicit F0: Nondeterminism[F]): Nondeterminism[EitherT[F, L, ?]] =
    new EitherTNondeterminism[F, L] {
      val F = Nondeterminism[F]
    }
}

private[raii] trait EitherTNondeterminism[F[_], L] extends Nondeterminism[EitherT[F, L, ?]] {
  implicit protected def F: Nondeterminism[F]

  override def chooseAny[A](head: DisjunctionT[F, L, A],
                            tail: Seq[DisjunctionT[F, L, A]]): DisjunctionT[F, L, (A, Seq[DisjunctionT[F, L, A]])] =
    new EitherT(F.map(F.chooseAny(head.run, tail map (_.run))) {
      case (a, residuals) =>
        a.map((_, residuals.map(new EitherT(_))))
    })

  override def bind[A, B](fa: DisjunctionT[F, L, A])(f: (A) => DisjunctionT[F, L, B]): DisjunctionT[F, L, B] =
    fa flatMap f

  override def point[A](a: => A): DisjunctionT[F, L, A] = EitherT.eitherTMonad[F, L].point(a)

  override def reduceUnordered[A, M](fs: Seq[DisjunctionT[F, L, A]])(
      implicit R: Reducer[A, M]): DisjunctionT[F, L, M] = {
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
