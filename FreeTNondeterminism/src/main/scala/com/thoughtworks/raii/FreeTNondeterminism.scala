package com.thoughtworks.raii

import scala.language.higherKinds
import scalaz.{Apply, FreeT, Monoid, Nondeterminism, Reducer, \/}

object FreeTNondeterminism {
  implicit def freeTNondeterminism[S[_], F[_]](implicit F0: Nondeterminism[F]): Nondeterminism[FreeT[S, F, ?]] =
    new FreeTNondeterminism[S, F] {
      val F = Nondeterminism[F]
    }
}

private[raii] trait FreeTNondeterminism[S[_], F[_]] extends Nondeterminism[FreeT[S, F, ?]] {
  implicit protected def F: Nondeterminism[F]
//
//  override def chooseAny[A](head: FreeT[F, L, A], tail: Seq[FreeT[F, L, A]]): FreeT[F, L, (A, Seq[FreeT[F, L, A]])] =
//    new FreeT(F.map(F.chooseAny(head.run, tail map (_.run))) {
//      case (a, residuals) =>
//        a.map((_, residuals.map(new FreeT(_))))
//    })
//
//  override def bind[A, B](fa: FreeT[F, L, A])(f: (A) => FreeT[F, L, B]): FreeT[F, L, B] =
//    fa flatMap f
//
//  override def point[A](a: => A): FreeT[F, L, A] = FreeT.freeTMonad[F, L].point(a)
//
//  override def reduceUnordered[A, M](fs: Seq[FreeT[F, L, A]])(implicit R: Reducer[A, M]): FreeT[F, L, M] = {
//    import R.monoid
//    val AE = Apply[L \/ ?]
//    val RR: Reducer[L \/ A, L \/ M] =
//      Reducer[L \/ A, L \/ M](
//        _.map(R.unit),
//        c => AE.apply2(c, _)(R.cons(_, _)),
//        m => AE.apply2(m, _)(R.snoc(_, _))
//      )(Monoid.liftMonoid[L \/ ?, M])
//    new FreeT(F.reduceUnordered(fs.map(_.run))(RR))
//  }
  override def chooseAny[A](head: FreeT[S, F, A], tail: Seq[FreeT[S, F, A]]): FreeT[S, F, (A, Seq[FreeT[S, F, A]])] =
    ???

  override def point[A](a: => A): FreeT[S, F, A] = ???

  override def bind[A, B](fa: FreeT[S, F, A])(f: (A) => FreeT[S, F, B]): FreeT[S, F, B] = ???
}
