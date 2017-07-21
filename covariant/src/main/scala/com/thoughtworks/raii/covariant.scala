package com.thoughtworks.raii

import com.thoughtworks.raii.covariant.ResourceT

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz.{\/, _}
import scalaz.syntax.all._

/** The namespace that contains the covariant [[ResourceT]].
  *
  * Usage:
  * {{{
  * import com.thoughtworks.raii.covariant._
  * }}}
  */
object covariant {

  /** The type-level [[http://en.cppreference.com/w/cpp/language/pimpl Pimpl]]
    * in order to prevent the Scala compiler seeing the actual type of [[ResourceT]]
    *
    * @note For internal usage only.
    */
  val opacityTypes: OpacityTypes = new OpacityTypes {
    override type ResourceT[F[+ _], +A] = F[Releasable[F, A]]

    override def apply[F[+ _], A](run: F[Releasable[F, A]]): ResourceT[F, A] = run

    override def unwrap[F[+ _], A](resourceT: ResourceT[F, A]): F[Releasable[F, A]] =
      resourceT
  }

  /** The data structure that provides automatic resource management.
    *
    * @example `ResourceT` can be used as a monad transformer for [[scalaz.Name]]
    *
    *          {{{
    *          import scalaz.Name
    *          import com.thoughtworks.raii.covariant.ResourceT, ResourceT._
    *          type RAII[A] = ResourceT[Name, A]
    *          }}}
    *
    *          Given a resource that creates temporary files
    *
    *          {{{
    *          import java.io.File
    *          val resource: RAII[File] = ResourceT(Name(new Releasable[Name, File] {
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
    *          val (tmpFile1, tmpFile2) = ResourceT.run(usingResouce).value
    *          tmpFile1 shouldNot exist
    *          tmpFile2 shouldNot exist
    *          }}}
    *
    * @note This `ResourceT` type is an opacity alias to `F[Releasable[F, A]]`.
    *       All type classes and helper functions for this `ResourceT` type are defined in the companion object [[ResourceT$ ResourceT]]
    * @template
    */
  type ResourceT[F[+ _], +A] = opacityTypes.ResourceT[F, A]

  import opacityTypes._

  /** A container of a [[value]] and a function to [[release]] the `value`.
    * @tparam A the type of [[value]]
    * @tparam F the monadic type of [[release]]
    */
  trait Releasable[F[+ _], +A] {
    def value: A

    /** Releases [[value]] and all resource dependencies during creating [[value]].
      *
      * @note After [[release]], [[value]] should not be used if:
      *       - [[value]] is a scoped native resource,
      *         e.g. this [[Releasable]] is created from [[com.thoughtworks.raii.asynchronous.Do.scoped[Value<:AutoCloseable](value:=>Value)* scoped]],
      *       - or, [[value]] internally references some scoped native resources.
      */
    def release: F[Unit]
  }

  private[raii] object Releasable {
    @inline
    def now[F[+ _]: Applicative, A](value0: A): Releasable[F, A] = {
      new Releasable[F, A] {
        override val value: A = value0
        override val release: F[Unit] = Applicative[F].point(())
      }
    }
  }

  private[raii] trait OpacityTypes {
    type ResourceT[F[+ _], +A]

    private[raii] def apply[F[+ _], A](run: F[Releasable[F, A]]): ResourceT[F, A]

    private[raii] def unwrap[F[+ _], A](resourceT: ResourceT[F, A]): F[Releasable[F, A]]

  }

  /** The companion object of [[ResourceT]] that contains converters and type classes.
    *
    * @note There are some implicit method that provides [[scalaz.Monad]]s as monad transformers of `F`.
    *       Those monads running will collect all resources,
    *       which will be open and release altogether when [[run]] is called.
    */
  object ResourceT extends ResourceFactoryTInstances0 {

    /** @group Converters */
    def apply[F[+ _], A](run: F[Releasable[F, A]]): ResourceT[F, A] = opacityTypes.apply(run)

    /** @group Converters */
    def unapply[F[+ _], A](resourceT: ResourceT[F, A]): Some[F[Releasable[F, A]]] =
      Some(unwrap(resourceT))

    private[raii] final def using[F[+ _], A, B](resourceT: ResourceT[F, A], f: A => F[B])(
        implicit monad: Bind[F]): F[B] = {
      unwrap(resourceT).flatMap { fa =>
        f(fa.value).flatMap { a: B =>
          fa.release.map { _ =>
            a
          }
        }
      }
    }

    /** Returns a `F` that performs the following process:
      *
      *  - Creating a [[Releasable]] for `A`
      *  - Closing the [[Releasable]]
      *  - Returning `A`
      */
    final def run[F[+ _], A](resourceT: ResourceT[F, A])(implicit monad: Bind[F]): F[A] = {
      unwrap(resourceT).flatMap { resource: Releasable[F, A] =>
        val value = resource.value
        resource.release.map { _ =>
          value
        }
      }
    }

    /** Returns a resource of `B` based on a resource of `A` and a function that creates `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `nonTransitiveMap` is to `map` in [[resourceTMonad]],
      *       except `nonTransitiveMap` will release `A` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def nonTransitiveMap[F[+ _]: Monad, A, B](fa: ResourceT[F, A])(f: A => B): ResourceT[F, B] = {
      opacityTypes.apply(
        unwrap(fa).flatMap { releasableA =>
          val b = f(releasableA.value)
          releasableA.release.map { _ =>
            new Releasable[F, B] {
              override val value: B = b

              override val release: F[Unit] = {
                ().point[F]
              }
            }
          }
        }
      )
    }

    /** Returns a resource of `B` based on a resource of `A` and a function that creates resource of `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `nonTransitiveFlatMap` is similar to `flatMap` in [[resourceTMonad]],
      *       except `nonTransitiveFlatMap` will release `A` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def nonTransitiveFlatMap[F[+ _]: Bind, A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
      opacityTypes.apply(
        for {
          releasableA <- unwrap(fa)
          releasableB <- unwrap(f(releasableA.value))
          _ <- releasableA.release
        } yield releasableB
      )
    }
    private[raii] final def foreach[F[+ _], A](resourceT: ResourceT[F, A],
                                               f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
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

    /** @group Type classes */
    implicit def resourceTParallelApplicative[F[+ _]](
        implicit F0: Applicative[
          Lambda[A => @@[F[A], Parallel]]
        ]
    ): Applicative[Lambda[A => ResourceT[F, A] @@ Parallel]] = {
      new ResourceFactoryTParallelApplicative[F] {
        override private[raii] implicit def typeClass = F0
      }
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances3 {

    /** @group Type classes */
    implicit def resourceTApplicative[F[+ _]: Applicative]: Applicative[ResourceT[F, ?]] =
      new ResourceFactoryTApplicative[F] {
        override private[raii] def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances2 extends ResourceFactoryTInstances3 {

    /** @group Type classes */
    implicit def resourceTMonad[F[+ _]: Monad]: Monad[ResourceT[F, ?]] = new ResourceFactoryTMonad[F] {
      private[raii] override def typeClass = implicitly
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances1 extends ResourceFactoryTInstances2 {

    /** @group Type classes */
    implicit def resourceTNondeterminism[F[+ _]](implicit F0: Nondeterminism[F]): Nondeterminism[ResourceT[F, ?]] =
      new ResourceFactoryTNondeterminism[F] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances0 extends ResourceFactoryTInstances1 {

    /** @group Type classes */
    implicit def resourceTMonadError[F[+ _], S](implicit F0: MonadError[F, S]): MonadError[ResourceT[F, ?], S] =
      new ResourceFactoryTMonadError[F, S] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] trait ResourceFactoryTPoint[F[+ _]] extends Applicative[ResourceT[F, ?]] {
    private[raii] implicit def typeClass: Applicative[F]

    override def point[A](a: => A): ResourceT[F, A] =
      opacityTypes.apply(Applicative[F].point(Releasable.now(a)))
  }

  import com.thoughtworks.raii.covariant.opacityTypes.unwrap

  private[raii] trait ResourceFactoryTApplicative[F[+ _]]
      extends Applicative[ResourceT[F, ?]]
      with ResourceFactoryTPoint[F] {

    override def ap[A, B](fa: => ResourceT[F, A])(f: => ResourceT[F, (A) => B]): ResourceT[F, B] = {
      opacityTypes.apply(
        Applicative[F].apply2(unwrap(fa), unwrap(f)) { (releasableA, releasableF) =>
          val releaseA = releasableA.release
          new Releasable[F, B] {
            override val value: B = releasableF.value(releasableA.value)

            override val release: F[Unit] = {
              Applicative[F].apply2(releaseA, releasableF.release) { (_: Unit, _: Unit) =>
                ()
              }
            }
          }
        }
      )
    }
  }

  private[raii] trait ResourceFactoryTParallelApplicative[F[+ _]]
      extends Applicative[Lambda[A => ResourceT[F, A] @@ Parallel]] {
    private[raii] implicit def typeClass: Applicative[Lambda[A => F[A] @@ Parallel]]

    override def map[A, B](pfa: ResourceT[F, A] @@ Parallel)(f: (A) => B): ResourceT[F, B] @@ Parallel = {
      val Parallel(ResourceT(fa)) = pfa
      val Parallel(fb) = typeClass.map(Parallel(fa)) { releasableA: Releasable[F, A] =>
        val releasableB: Releasable[F, B] = new Releasable[F, B] {
          override val value: B = f(releasableA.value)
          override val release = releasableA.release
        }
        releasableB
      }
      Parallel(ResourceT(fb))
    }

    override def point[A](a: => A): ResourceT[F, A] @@ Parallel = {

      Parallel({
        val fa: F[Releasable[F, A]] = Parallel.unwrap[F[Releasable[F, A]]](
          typeClass.point(
            new Releasable[F, A] {
              override val value: A = a

              override val release: F[Unit] = Parallel.unwrap(typeClass.point(()))
            }
          ))
        opacityTypes.apply(fa)
      }: ResourceT[F, A])
    }

    override def ap[A, B](fa: => ResourceT[F, A] @@ Parallel)(
        f: => ResourceT[F, A => B] @@ Parallel): ResourceT[F, B] @@ Parallel = {
      Parallel {
        opacityTypes.apply(
          Parallel.unwrap[F[Releasable[F, B]]](
            typeClass.apply2(
              Parallel(unwrap(Parallel.unwrap(fa))),
              Parallel(unwrap(Parallel.unwrap(f)))
            ) { (resourceA, resourceF) =>
              val valueB = resourceF.value(resourceA.value)
              val releaseA = resourceA.release
              val releaseF = resourceF.release
              new Releasable[F, B] {
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

  private[raii] trait ResourceFactoryTMonad[F[+ _]] extends ResourceFactoryTApplicative[F] with Monad[ResourceT[F, ?]] {
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
          new Releasable[F, B] {
            override def value: B = b

            override val release: F[Unit] = {
              releaseB >> releaseA
            }
          }
        }
      )
    }

  }

  private[raii] trait ResourceFactoryTMonadError[F[+ _], S]
      extends MonadError[ResourceT[F, ?], S]
      with ResourceFactoryTPoint[F] {
    private[raii] implicit def typeClass: MonadError[F, S]

    override def raiseError[A](e: S): ResourceT[F, A] =
      opacityTypes.apply(typeClass.raiseError[Releasable[F, A]](e))

    override def handleError[A](fa: ResourceT[F, A])(f: (S) => ResourceT[F, A]): ResourceT[F, A] = {
      opacityTypes.apply(
        unwrap(fa).handleError { s =>
          unwrap(f(s))
        }
      )
    }

    import com.thoughtworks.raii.covariant.ResourceT.catchError

    override def bind[A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
      opacityTypes.apply(
        catchError(unwrap(fa)).flatMap {
          case \/-(releasableA) =>
            catchError(unwrap(f(releasableA.value))).flatMap[Releasable[F, B]] {
              case \/-(releasableB) =>
                val wrappedReleasableB: Releasable[F, B] = new Releasable[F, B] {
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
                  typeClass.raiseError[Releasable[F, B]](s)
                }
            }
          case either @ -\/(s) =>
            typeClass.raiseError[Releasable[F, B]](s)
        }
      )
    }
  }

  private[raii] trait ResourceFactoryTNondeterminism[F[+ _]]
      extends ResourceFactoryTMonad[F]
      with Nondeterminism[ResourceT[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](head: ResourceT[F, A],
                              tail: Seq[ResourceT[F, A]]): ResourceT[F, (A, Seq[ResourceT[F, A]])] = {
      opacityTypes.apply(
        typeClass.chooseAny(unwrap(head), tail.map(unwrap)).map {
          case (fa, residuals) =>
            new Releasable[F, (A, Seq[ResourceT[F, A]])] {
              override val value: (A, Seq[ResourceT[F, A]]) =
                (fa.value, residuals.map { residual: F[Releasable[F, A]] =>
                  opacityTypes.apply[F, A](residual)
                })

              override val release: F[Unit] = fa.release
            }

        }
      )

    }
  }
}
