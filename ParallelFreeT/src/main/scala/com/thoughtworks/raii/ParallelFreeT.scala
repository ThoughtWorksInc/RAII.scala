package com.thoughtworks.raii

import scalaz.{FreeT => _, _}
import scala.language.higherKinds
import annotation.tailrec
import FreeT._

import scalaz.Tags.Parallel

// case class MyFreeT[S[_], M[_], A](run : M[A\/S[A]])

object FreeT extends FreeTInstances {

  /** Suspend the computation with the given suspension. */
  private final case class Suspend[S[_], M[_], A](a: M[A \/ S[A]]) extends FreeT[S, M, A]

  /** Call a subroutine and continue with the given function. */
  private final case class Gosub[S[_], M[_], A0, B](a0: FreeT[S, M, A0], f0: A0 => FreeT[S, M, B])
      extends FreeT[S, M, B] {
    type A = A0
    def a: FreeT[S, M, A] = a0
    def f: A => FreeT[S, M, B] = f0
  }

  private final case class ParallelAp[S[_], M[_], A0, B](a: FreeT[S, M, A0], f: FreeT[S, M, A0 => B])
      extends FreeT[S, M, B] {
    type A = A0
    private[raii] final def toM(implicit M0: BindRec[M], M: Applicative[M]): M[FreeT[S, M, A]] =

    override def foldMap(
        g: ~>[S, M])(implicit M0: BindRec[M], M1: Applicative[M], M2: Applicative[λ[α => M[α] @@ Parallel]]): M[B] = {
      val mi: M[A] = a.foldMap(g)
      val mk: M[A => B] = f.foldMap(g)
      val pma: M[B] @@ Parallel = M2.ap(Parallel(mi))(Parallel(mk))
      Parallel.unwrap[M[B]](pma)
    }
  }

  /** Return the given value in the free monad. */
  def point[S[_], M[_], A](value: A)(implicit M: Applicative[M]): FreeT[S, M, A] = Suspend(M.point(-\/(value)))

  def suspend[S[_], M[_], A](a: M[A \/ S[FreeT[S, M, A]]])(implicit M: Applicative[M]): FreeT[S, M, A] =
    liftM(a).flatMap({
      case -\/(a) => point(a)
      case \/-(s) => roll(s)
    })

  def tailrecM[S[_], M[_]: Applicative, A, B](a: A)(f: A => FreeT[S, M, A \/ B]): FreeT[S, M, B] =
    f(a).flatMap {
      case -\/(a0) => tailrecM(a0)(f)
      case \/-(b) => point[S, M, B](b)
    }

  def liftM[S[_], M[_], A](value: M[A])(implicit M: Functor[M]): FreeT[S, M, A] =
    Suspend(M.map(value)(\/.left))

  /** A version of `liftM` that infers the nested type constructor. */
  def liftMU[S[_], MA](value: MA)(implicit M: Unapply[Functor, MA]): FreeT[S, M.M, M.A] =
    liftM[S, M.M, M.A](M(value))(M.TC)

  /** Suspends a value within a functor in a single step. Monadic unit for a higher-order monad. */
  def liftF[S[_], M[_], A](value: S[A])(implicit M: Applicative[M]): FreeT[S, M, A] =
    Suspend(M.point(\/-(value)))

  def roll[S[_], M[_], A](value: S[FreeT[S, M, A]])(implicit M: Applicative[M]): FreeT[S, M, A] =
    liftF[S, M, FreeT[S, M, A]](value).flatMap(identity)

}

sealed abstract class FreeT[S[_], M[_], A] {
  final def map[B](f: A => B)(implicit M: Applicative[M]): FreeT[S, M, B] =
    flatMap(a => point(f(a)))

  /** Binds the given continuation to the result of this computation. */
  final def flatMap[B](f: A => FreeT[S, M, B]): FreeT[S, M, B] =
    Gosub(this, f)

  /**
    * Changes the underlying `Monad` for this `FreeT`, ie.
    * turning this `FreeT[S, M, A]` into a `FreeT[S, N, A]`.
    */
  def hoist[N[_]](mn: M ~> N): FreeT[S, N, A] =
    step match {
      case e @ Gosub(_, _) =>
        Gosub(e.a.hoist(mn), e.f.andThen(_.hoist(mn)))
      case Suspend(m) =>
        Suspend(mn(m))
      case ParallelAp(a, f) =>
        ParallelAp(a.hoist(mn), f.hoist(mn))
    }

  @deprecated("Alias for `hoist`", "7.3")
  def hoistN[N[_]](mn: M ~> N): FreeT[S, N, A] = hoist(mn)

  @deprecated("Alias for `hoist`", "7.3")
  def hoistM[N[_]](mn: M ~> N): FreeT[S, N, A] = hoist(mn)

  /** Change the base functor `S` for a `FreeT` action. */
  def interpret[T[_]](st: S ~> T)(implicit M: Functor[M]): FreeT[T, M, A] =
    step match {
      case e @ Gosub(_, _) =>
        Gosub(e.a.interpret(st), e.f.andThen(_.interpret(st)))
      case Suspend(m) =>
        Suspend(M.map(m)(_.map(s => st(s))))
      case ParallelAp(a, f) =>
        ParallelAp(a.interpret(st), f.interpret(st))
    }

  @deprecated("Alias for `interpret`", "7.3")
  def interpretS[T[_]](st: S ~> T)(implicit M: Functor[M]): FreeT[T, M, A] = interpret(st)

  @deprecated("Alias for `interpret`", "7.3")
  def interpretT[T[_]](st: S ~> T)(implicit M: Functor[M]): FreeT[T, M, A] = interpret(st)

  /**
    * Runs to completion, mapping the suspension with the given transformation
    * at each step and accumulating into the monad `M`.
    */
  def foldMap(
      f: S ~> M)(implicit M0: BindRec[M], M1: Applicative[M], M2: Applicative[λ[α => M[α] @@ Parallel]]): M[A] = {
    @tailrec
    def go(ft: FreeT[S, M, A]): M[FreeT[S, M, A] \/ A] =
      ft match {
        case Suspend(ma) =>
          M0.bind(ma) {
            case -\/(a) => M1.point(\/-(a))
            case \/-(sa) => M0.map(f(sa))(\/.right)
          }
        case g @ Gosub(_, _) =>
          g.a match {
            case Suspend(mx) =>
              M0.bind(mx) {
                case -\/(x) => M1.point(-\/(g.f(x)))
                case \/-(sx) => M0.map(f(sx))(g.f andThen \/.left)
              }
            case g0 @ Gosub(_, _) => go(g0.a.flatMap(g0.f(_).flatMap(g.f)))
            case p @ ParallelAp(i, k) =>
              M0.map(p.foldMap(f))(g.f andThen \/.left)
          }
        case p @ ParallelAp(i, k) =>
          M0.map(p.foldMap(f))(\/.right)
      }

    M0.tailrecM(go)(this)
  }

  /**
    * Perform recursive binds on `M` until first suspension is reached.
    */
  private[raii] final def toM(implicit M0: BindRec[M], M: Applicative[M]): M[FreeT[S, M, A]] =
    M0.tailrecM[FreeT[S, M, A], FreeT[S, M, A]](_.step match {
      case Suspend(m) =>
        M.map(m) {
          case -\/(a) => \/-(point(a))
          case \/-(s) => \/-(liftF(s))
        }
      case g1 @ Gosub(_, _) =>
        g1.a match {
          case Suspend(m) =>
            M.map(m) {
              case -\/(a) => -\/(g1.f(a))
              case \/-(s) => \/-(liftF[S, M, g1.A](s).flatMap(g1.f))
            }
          case g0 @ Gosub(_, _) =>
            sys.error("Unreachable code: `Gosub` returned from `step` has `Suspend` on the left")
        }
    })(this)

  @tailrec
  private def step: FreeT[S, M, A] =
    this match {
      case g @ Gosub(_, _) =>
        g.a match {
          case g0 @ Gosub(_, _) => g0.a.flatMap(a => g0.f(a).flatMap(g.f)).step
          case _ => g
        }
      case x => x
    }
}

sealed abstract class FreeTInstances6 {
  implicit def freeTMonadTell[S[_], M[_], E](implicit M1: MonadTell[M, E]): MonadTell[FreeT[S, M, ?], E] =
    new MonadTell[FreeT[S, M, ?], E] with FreeTMonad[S, M] {
      override def M = implicitly
      override def writer[A](w: E, v: A) =
        FreeT.liftM(M1.writer(w, v))
    }
}

sealed abstract class FreeTInstances5 extends FreeTInstances6 {
  implicit def freeTMonadReader[S[_], M[_], E](implicit M1: MonadReader[M, E]): MonadReader[FreeT[S, M, ?], E] =
    new MonadReader[FreeT[S, M, ?], E] with FreeTMonad[S, M] {
      override def M = implicitly
      override def ask =
        FreeT.liftM(M1.ask)
      override def local[A](f: E => E)(fa: FreeT[S, M, A]) =
        fa.hoist(λ[M ~> M](M1.local(f)(_)))
    }
}

sealed abstract class FreeTInstances4 extends FreeTInstances5 {
  implicit def freeTMonadState[S[_], M[_], E](implicit M1: MonadState[M, E]): MonadState[FreeT[S, M, ?], E] =
    new MonadState[FreeT[S, M, ?], E] with FreeTMonad[S, M] {
      override def M = implicitly
      override def get =
        FreeT.liftM(M1.get)
      override def put(s: E) =
        FreeT.liftM(M1.put(s))
      override def init =
        FreeT.liftM(M1.init)
    }
}

sealed abstract class FreeTInstances3 extends FreeTInstances4 {
  implicit def freeTMonadError[S[_], M[_]: BindRec, E](implicit E: MonadError[M, E]): MonadError[FreeT[S, M, ?], E] =
    new MonadError[FreeT[S, M, ?], E] with FreeTMonad[S, M] {
      override def M = implicitly
      override def handleError[A](fa: FreeT[S, M, A])(f: E => FreeT[S, M, A]) =
        FreeT.liftM[S, M, FreeT[S, M, A]](E.handleError(fa.toM)(f.andThen(_.toM)))(M).flatMap(identity)
      override def raiseError[A](e: E) =
        FreeT.liftM(E.raiseError[A](e))(M)
    }
}

sealed abstract class FreeTInstances2 extends FreeTInstances3 {
  implicit def freeTBind[S[_], M[_]](implicit M0: Applicative[M]): Bind[FreeT[S, M, ?]] =
    new FreeTBind[S, M] {
      implicit def M: Applicative[M] = M0
    }

  implicit def freeTHoist[S[_]]: Hoist[FreeT[S, ?[_], ?]] =
    new Hoist[FreeT[S, ?[_], ?]] {
      def hoist[M[_]: Monad, N[_]](f: M ~> N) =
        λ[FreeT[S, M, ?] ~> FreeT[S, N, ?]](_ hoist f)
      def liftM[G[_]: Monad, A](a: G[A]) =
        FreeT.liftM(a)
      def apply[G[_]: Monad] =
        Monad[FreeT[S, G, ?]]
    }
}

sealed abstract class FreeTInstances1 extends FreeTInstances2 {

}

sealed abstract class FreeTInstances0 extends FreeTInstances1 {
  implicit def freeTMonad[S[_], M[_]](
      implicit M0: Applicative[M]): Monad[FreeT[S, M, ?]] with BindRec[FreeT[S, M, ?]] =
    new FreeTMonad[S, M] {
      def M = M0
    }

  implicit def freeTPlus[S[_], M[_]: Applicative: BindRec: Plus]: Plus[FreeT[S, M, ?]] =
    new FreeTPlus[S, M] {
      override def M = implicitly
      override def M1 = implicitly
      override def M2 = implicitly
    }
}

sealed abstract class FreeTInstances extends FreeTInstances0 {
  implicit def freeTMonadPlus[S[_], M[_]: ApplicativePlus: BindRec]: MonadPlus[FreeT[S, M, ?]] =
    new MonadPlus[FreeT[S, M, ?]] with FreeTPlus[S, M] with FreeTMonad[S, M] {
      override def M = implicitly
      override def M1 = implicitly
      override def M2 = implicitly

      override def empty[A] = FreeT.liftM[S, M, A](PlusEmpty[M].empty[A])(M)
    }
}

private trait FreeTBind[S[_], M[_]] extends Bind[FreeT[S, M, ?]] {
  implicit def M: Applicative[M]

  override final def map[A, B](fa: FreeT[S, M, A])(f: A => B): FreeT[S, M, B] = fa.map(f)
  def bind[A, B](fa: FreeT[S, M, A])(f: A => FreeT[S, M, B]): FreeT[S, M, B] = fa.flatMap(f)
}

private trait FreeTMonad[S[_], M[_]] extends Monad[FreeT[S, M, ?]] with BindRec[FreeT[S, M, ?]] with FreeTBind[S, M] {
  implicit def M: Applicative[M]

  override final def point[A](a: => A) =
    FreeT.point[S, M, A](a)
  override final def tailrecM[A, B](f: A => FreeT[S, M, A \/ B])(a: A) =
    FreeT.tailrecM(a)(f)
}

private trait FreeTPlus[S[_], M[_]] extends Plus[FreeT[S, M, ?]] {
  implicit def M: Applicative[M]
  implicit def M1: BindRec[M]
  def M2: Plus[M]
  override final def plus[A](a: FreeT[S, M, A], b: => FreeT[S, M, A]) =
    FreeT.liftM(M2.plus(a.toM, b.toM))(M).flatMap(identity)
}
