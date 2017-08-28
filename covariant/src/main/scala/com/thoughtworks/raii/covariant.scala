package com.thoughtworks.raii

import com.thoughtworks.raii.covariant.{Resource, ResourceT, opacityTypes}

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz._
import scalaz.syntax.all._

private[raii] sealed abstract class CovariantResourceTInstances3 {

  /** @group Type classes */
  implicit def covariantResourceTApplicative[F[+ _]: Applicative]: Applicative[ResourceT[F, ?]] =
    new CovariantResourceTApplicative[F] {
      override private[raii] def typeClass = implicitly
    }
}

private[raii] sealed abstract class CovariantResourceTInstances2 extends CovariantResourceTInstances3 {

  /** @group Type classes */
  implicit def covariantResourceTMonad[F[+ _]: Monad]: Monad[ResourceT[F, ?]] = new CovariantResourceTMonad[F] {
    private[raii] override def typeClass = implicitly
  }
}

private[raii] sealed abstract class CovariantResourceTInstances1 extends CovariantResourceTInstances2 {

  /** @group Type classes */
  implicit def covariantResourceTNondeterminism[F[+ _]](
      implicit F0: Nondeterminism[F]): Nondeterminism[ResourceT[F, ?]] =
    new CovariantResourceTNondeterminism[F] {
      private[raii] override def typeClass = implicitly
    }
}

private[raii] sealed abstract class CovariantResourceTInstances0 extends CovariantResourceTInstances1 {

  /** @group Type classes */
  implicit def covariantResourceTMonadError[F[+ _], S](implicit F0: MonadError[F, S]): MonadError[ResourceT[F, ?], S] =
    new CovariantResourceTMonadError[F, S] {
      private[raii] override def typeClass = implicitly
    }
}

private[raii] trait CovariantResourceTPoint[F[+ _]] extends Applicative[ResourceT[F, ?]] {
  private[raii] implicit def typeClass: Applicative[F]

  override def point[A](a: => A): ResourceT[F, A] = covariant.ResourceT.delay(a)
}

import com.thoughtworks.raii.covariant.opacityTypes.unwrap

private[raii] trait CovariantResourceTApplicative[F[+ _]]
    extends Applicative[ResourceT[F, ?]]
    with CovariantResourceTPoint[F] {

  override def ap[A, B](fa: => ResourceT[F, A])(f: => ResourceT[F, (A) => B]): ResourceT[F, B] = {
    opacityTypes.apply(
      Applicative[F].apply2(unwrap(fa), unwrap(f)) { (releasableA, releasableF) =>
        val releaseA = releasableA.release
        Resource[F, B](
          value = releasableF.value(releasableA.value),
          release = Applicative[F].apply2(releaseA, releasableF.release) { (_: Unit, _: Unit) =>
            ()
          }
        )
      }
    )
  }
}

private[raii] trait CovariantResourceTParallelApplicative[F[+ _]]
    extends Applicative[Lambda[A => ResourceT[F, A] @@ Parallel]] {
  private[raii] implicit def typeClass: Applicative[Lambda[A => F[A] @@ Parallel]]

  override def map[A, B](pfa: ResourceT[F, A] @@ Parallel)(f: (A) => B): ResourceT[F, B] @@ Parallel = {
    val Parallel(ResourceT(fa)) = pfa
    val Parallel(fb) = typeClass.map(Parallel(fa)) { releasableA: Resource[F, A] =>
      val releasableB: Resource[F, B] = new Resource[F, B] {
        override val value: B = f(releasableA.value)
        override val release = releasableA.release
      }
      releasableB
    }
    Parallel(ResourceT(fb))
  }

  override def point[A](a: => A): ResourceT[F, A] @@ Parallel = {

    Parallel({
      val fa: F[Resource[F, A]] = Parallel.unwrap[F[Resource[F, A]]](
        typeClass.point(
          Resource[F, A](
            value = a,
            release = Parallel.unwrap(covariant.callByNameUnitCache.point[Lambda[A => F[A] @@ Parallel]])
          )
        ))
      opacityTypes.apply(fa)
    }: ResourceT[F, A])
  }

  override def ap[A, B](fa: => ResourceT[F, A] @@ Parallel)(
      f: => ResourceT[F, A => B] @@ Parallel): ResourceT[F, B] @@ Parallel = {
    Parallel {
      opacityTypes.apply(
        Parallel.unwrap[F[Resource[F, B]]](
          typeClass.apply2(
            Parallel(unwrap(Parallel.unwrap(fa))),
            Parallel(unwrap(Parallel.unwrap(f)))
          ) { (resourceA, resourceF) =>
            val valueB = resourceF.value(resourceA.value)
            val releaseA = resourceA.release
            val releaseF = resourceF.release
            new Resource[F, B] {
              override val value: B = valueB

              override val release: F[Unit] = {
                Parallel.unwrap[F[Unit]](typeClass.apply2(Parallel(releaseA), Parallel(releaseF)) {
                  (_: Unit, _: Unit) =>
                    ()
                })
              }
            }
          }
        )
      )
    }
  }
}

private[raii] trait CovariantResourceTMonad[F[+ _]]
    extends CovariantResourceTApplicative[F]
    with Monad[ResourceT[F, ?]] {
  private[raii] implicit override def typeClass: Monad[F]

  override def bind[A, B](fa: ResourceT[F, A])(f: (A) => ResourceT[F, B]): ResourceT[F, B] = {
    opacityTypes.apply(
      for {
        releasableA <- unwrap(fa)
        releasableB <- unwrap(f(releasableA.value))
      } yield {
        val b = releasableB.value
        val releaseB = releasableB.release
        val releaseA = releasableA.release
        new Resource[F, B] {
          override def value: B = b

          override val release: F[Unit] = {
            covariant.appendMonadicUnit(releaseB, releaseA)
          }
        }
      }
    )
  }

}

private[raii] trait CovariantResourceTMonadError[F[+ _], S]
    extends MonadError[ResourceT[F, ?], S]
    with CovariantResourceTPoint[F] {
  import covariant.catchError
  private[raii] implicit def typeClass: MonadError[F, S]

  override def raiseError[A](e: S): ResourceT[F, A] =
    opacityTypes.apply(typeClass.raiseError[Resource[F, A]](e))

  override def handleError[A](fa: ResourceT[F, A])(f: (S) => ResourceT[F, A]): ResourceT[F, A] = {
    opacityTypes.apply(
      unwrap(fa).handleError { s =>
        unwrap(f(s))
      }
    )
  }

  override def bind[A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
    opacityTypes.apply(
      catchError(unwrap(fa)).flatMap {
        case \/-(releasableA) =>
          catchError(unwrap(f(releasableA.value))).flatMap[Resource[F, B]] {
            case \/-(releasableB) =>
              val wrappedReleasableB: Resource[F, B] = new Resource[F, B] {
                override val value: B = releasableB.value

                override val release: F[Unit] = {
                  catchError(releasableB.release).flatMap {
                    case \/-(()) =>
                      releasableA.release
                    case -\/(s) =>
                      releasableA.release.flatMap { _ =>
                        typeClass.raiseError[Unit](s)
                      }
                  }
                }
              }
              wrappedReleasableB.point[F]
            case -\/(s) =>
              releasableA.release.flatMap { _ =>
                typeClass.raiseError[Resource[F, B]](s)
              }
          }
        case either @ -\/(s) =>
          typeClass.raiseError[Resource[F, B]](s)
      }
    )
  }
}

private[raii] trait CovariantResourceTNondeterminism[F[+ _]]
    extends CovariantResourceTMonad[F]
    with Nondeterminism[ResourceT[F, ?]] {
  private[raii] implicit override def typeClass: Nondeterminism[F]

  override def chooseAny[A](head: ResourceT[F, A],
                            tail: Seq[ResourceT[F, A]]): ResourceT[F, (A, Seq[ResourceT[F, A]])] = {
    opacityTypes.apply(
      typeClass.chooseAny(unwrap(head), tail.map(unwrap)).map {
        case (fa, residuals) =>
          new Resource[F, (A, Seq[ResourceT[F, A]])] {
            override val value: (A, Seq[ResourceT[F, A]]) =
              (fa.value, residuals.map { residual: F[Resource[F, A]] =>
                opacityTypes.apply[F, A](residual)
              })

            override val release: F[Unit] = fa.release
          }

      }
    )

  }
}

/** The namespace that contains the covariant [[ResourceT]].
  *
  * Usage:
  * {{{
  * import com.thoughtworks.raii.covariant._
  * }}}
  */
object covariant extends CovariantResourceTInstances0 {

  private[raii] final class CallByNameUnitCache(callByNameUnit: => Unit) {
    @inline
    def point[F[_]](implicit applicative: Applicative[F]): F[Unit] = {
      applicative.point(callByNameUnit)
    }
  }

  /** A cache of `=> Unit`.
    *
    * @note When using this cache to create two `UnitContinuation[UnitContinuation]`s,
    *       {{{
    *       import com.thoughtworks.continuation._
    *       val continuation1 = covariant.callByNameUnitCache.point[UnitContinuation]
    *       val continuation2 = covariant.callByNameUnitCache.point[UnitContinuation]
    *       }}}
    *       then the two continuations should equal to each other.
    *
    *       {{{
    *       continuation1 should be(continuation2)
    *       }}}
    */
  private[raii] val callByNameUnitCache = new CallByNameUnitCache(())

  private[raii] def appendMonadicUnit[F[+ _]: Monad](f0: F[Unit], f1: F[Unit]): F[Unit] = {
    val noop = callByNameUnitCache.point[F]
    if (f0 == noop) {
      f1
    } else if (f1 == noop) {
      f0
    } else {
      f0 >> f1
    }
  }

  /** The type-level [[http://en.cppreference.com/w/cpp/language/pimpl Pimpl]]
    * in order to prevent the Scala compiler seeing the actual type of [[ResourceT]]
    *
    * @note For internal usage only.
    */
  val opacityTypes: OpacityTypes = new OpacityTypes {
    override type ResourceT[F[+ _], +A] = F[Resource[F, A]]

    override def apply[F[+ _], A](run: F[Resource[F, A]]): ResourceT[F, A] = run

    override def unwrap[F[+ _], A](resourceT: ResourceT[F, A]): F[Resource[F, A]] =
      resourceT
  }

  /** The data structure that provides automatic resource management.
    *
    * @example `ResourceT` can be used as a monad transformer for [[scalaz.Name]]
    *
    *          {{{
    *          import scalaz.Name
    *          import com.thoughtworks.raii.covariant._
    *          type RAII[A] = ResourceT[Name, A]
    *          }}}
    *
    *          Given a resource that creates temporary files
    *
    *          {{{
    *          import java.io.File
    *          val resource: RAII[File] = ResourceT(Name(new Resource[Name, File] {
    *            override val value: File = File.createTempFile("test", ".tmp");
    *            override val release: Name[Unit] = Name {
    *              val isDeleted = value.delete()
    *            }
    *          }))
    *          }}}
    *
    *          when using temporary file created by `resouce` in a  `for` / `yield` block,
    *          those temporary files should be available.
    *
    *          {{{
    *          import scalaz.syntax.all._
    *          val usingResouce = for {
    *            tmpFile1 <- resource
    *            tmpFile2 <- resource
    *          } yield {
    *            tmpFile1 shouldNot be(tmpFile2)
    *            tmpFile1 should exist
    *            tmpFile2 should exist
    *            (tmpFile1, tmpFile2)
    *          }
    *          }}}
    *
    *          and those files should have been deleted after the `for` / `yield` block.
    *
    *          {{{
    *          val (tmpFile1, tmpFile2) = usingResouce.run.value
    *          tmpFile1 shouldNot exist
    *          tmpFile2 shouldNot exist
    *          }}}
    *
    * @note This `ResourceT` type is an opacity alias to `F[Resource[F, A]]`.
    *       All type classes and helper functions for this `ResourceT` type are defined in the companion object [[ResourceT$ ResourceT]]
    * @template
    */
  type ResourceT[F[+ _], +A] = opacityTypes.ResourceT[F, A]

  import opacityTypes._

  /** A container of a [[value]] and a function to [[release]] the `value`.
    * @note This [[Resource]] will become a case class. Use [[Resource.apply]] instead of `new Resource[F, A] { ... }`.
    * @tparam A the type of [[value]]
    * @tparam F the monadic type of [[release]]
    */
  trait Resource[F[+ _], +A] {
    def value: A

    /** Releases [[value]] and all resource dependencies during creating [[value]].
      *
      * @note After [[release]], [[value]] should not be used if:
      *       - [[value]] is a scoped native resource,
      *         e.g. this [[Resource]] is created from [[com.thoughtworks.raii.asynchronous.Do.scoped[Value<:AutoCloseable](value:=>Value)* scoped]],
      *       - or, [[value]] internally references some scoped native resources.
      */
    def release: F[Unit]
  }

  @deprecated(message = "Use [[Resource]] instead.", since = "3.0.0")
  type Releasable[F[+ _], +A] = Resource[F, A]

  object Resource {

    def unapply[F[+ _], A](resource: Resource[F, A]): Option[(A, F[Unit])] = Some((resource.value, resource.release))

    def apply[F[+ _], A](value: A, release: F[Unit]): Resource[F, A] = {
      val value0 = value
      val release0 = release
      new Resource[F, A] {
        def value: A = value0

        def release: F[Unit] = release0

        override def toString: String = raw"""Resource($value, $release)"""
      }
    }

    @inline
    private[raii] def now[F[+ _]: Applicative, A](value: A): Resource[F, A] = {
      Resource[F, A](value, callByNameUnitCache.point[F])
    }
  }

  private[raii] trait OpacityTypes {
    type ResourceT[F[+ _], +A]

    private[raii] def apply[F[+ _], A](run: F[Resource[F, A]]): ResourceT[F, A]

    private[raii] def unwrap[F[+ _], A](resourceT: ResourceT[F, A]): F[Resource[F, A]]

  }

  /** An object that may hold resources until it is closed.
    *
    * Similar to [[java.lang.AutoCloseable]] except the close operation is monadic.
    *
    * @tparam F
    */
  trait MonadicCloseable[F[+ _]] extends Any {
    def monadicClose: F[Unit]
  }

  /** The companion object of [[ResourceT]] that contains converters and type classes.
    *
    * @note There are some implicit method that provides [[scalaz.Monad]]s as monad transformers of `F`.
    *       Those monads running will collect all resources,
    *       which will be open and release altogether when [[ResourceT.run]] is called.
    */
  object ResourceT {

    def delay[F[+ _]: Applicative, A](a: => A): ResourceT[F, A] =
      ResourceT(Applicative[F].point(Resource.now(a)))

    def garbageCollected[F[+ _]: Applicative, A](fa: F[A]): ResourceT[F, A] = {
      ResourceT(fa.map(Resource.now[F, A](_)))
    }

    def nested[F[+ _]: Monad, A](fa: ResourceT[F, A]): ResourceT[F, A] = {
      garbageCollected(fa.run)
    }

    def apply[F[+ _], A](run: F[Resource[F, A]]): ResourceT[F, A] = opacityTypes.apply(run)

    def unapply[F[+ _], A](resourceT: ResourceT[F, A]): Some[F[Resource[F, A]]] =
      Some(unwrap(resourceT))

    def monadicCloseable[F[+ _]: Functor, A <: MonadicCloseable[F]](run: F[A]): ResourceT[F, A] = {
      val resource: F[Resource[F, A]] = run.map { a: A =>
        Resource(a, a.monadicClose)
      }
      ResourceT(resource)
    }

  }

  /** @group Type classes */
  implicit def covariantResourceTParallelApplicative[F[+ _]](
      implicit F0: Applicative[Lambda[A => F[A] @@ Parallel]]
  ): Applicative[Lambda[A => ResourceT[F, A] @@ Parallel]] = {
    new CovariantResourceTParallelApplicative[F] {
      override private[raii] implicit def typeClass = F0
    }
  }

  private[raii] final def using[F[+ _]: Bind, A, B](resourceT: ResourceT[F, A], f: A => F[B]): F[B] = {
    unwrap(resourceT).flatMap { fa =>
      f(fa.value).flatMap { a: B =>
        fa.release.map { _ =>
          a
        }
      }
    }
  }

  private[raii] final def foreach[F[+ _]: Bind: Foldable, A](resourceT: ResourceT[F, A], f: A => Unit): Unit = {
    unwrap(resourceT)
      .flatMap { fa =>
        f(fa.value)
        fa.release
      }
      .sequence_[Id.Id, Unit]
  }

  private[raii] def catchError[F[+ _]: MonadError[?[_], S], S, A](fa: F[A]): F[S \/ A] = {
    fa.map(_.right[S]).handleError(_.left[A].point[F])
  }

  implicit final class CovariantResourceTOps[F[+ _], A](resourceT: ResourceT[F, A]) {

    /** Returns a `F` that performs the following process:
      *
      *  - Creating a [[Resource]] for `A`
      *  - Closing the [[Resource]]
      *  - Returning `A`
      */
    def run(implicit monad: Bind[F]): F[A] = {
      unwrap(resourceT).flatMap { resource: Resource[F, A] =>
        val value = resource.value
        resource.release.map { _ =>
          value
        }
      }
    }

    /** Returns a resource of `B` based on a resource of `A` and a function that creates `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveMap` is to `map` in [[covariantResourceTMonad]],
      *       except `intransitiveMap` will release `A` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveMap[B](f: A => B)(implicit monad: Monad[F]): ResourceT[F, B] = {
      opacityTypes.apply(
        unwrap(resourceT).flatMap { releasableA =>
          val b = f(releasableA.value)
          releasableA.release.map { _ =>
            new Resource[F, B] {
              override val value: B = b

              override val release: F[Unit] = {
                callByNameUnitCache.point[F]
              }
            }
          }
        }
      )
    }

    /** Returns a resource of `B` based on a resource of `A` and a function that creates resource of `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveFlatMap` is similar to `flatMap` in [[covariantResourceTMonad]],
      *       except `intransitiveFlatMap` will release `A` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveFlatMap[B](f: A => ResourceT[F, B])(implicit bind: Bind[F]): ResourceT[F, B] = {
      opacityTypes.apply(
        for {
          releasableA <- unwrap(resourceT)
          releasableB <- unwrap(f(releasableA.value))
          _ <- releasableA.release
        } yield releasableB
      )
    }
  }
}
