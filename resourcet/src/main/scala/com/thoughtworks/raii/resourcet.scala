package com.thoughtworks.raii

import com.thoughtworks.raii.resourcet.ResourceT

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz.{\/, _}
import scalaz.syntax.all._

object resourcet {

  private[resourcet] val ResourceFactoryTExtractor: ResourceFactoryTExtractor = new ResourceFactoryTExtractor {
    override type ResourceT[F[_], A] = F[Releaseable[F, A]]

    override def apply[F[_], A](run: F[Releaseable[F, A]]): ResourceT[F, A] = run

    override private[raii] def unwrap[F[_], A](resourceFactoryT: ResourceT[F, A]): F[Releaseable[F, A]] =
      resourceFactoryT
  }

  type ResourceT[F[_], A] = ResourceFactoryTExtractor.ResourceT[F, A]

  trait Releaseable[F[_], +A] {
    def value: A

    /** Releases all the native resources [[ResourceT.unwrap]]d during creating this [[Releaseable]].
      *
      * @note After [[release]], [[value]] should not be used if:
      *  - [[value]] is a scoped native resource,
      *    e.g. a [[com.thoughtworks.raii.sde.raii#scoped scoped]] `AutoCloseable`,
      *  - or, [[value]] internally uses some scoped native resources.
      */
    def release(): F[Unit]
  }

  object Releaseable {
    @inline
    def delay[F[_]: Applicative, A](a: => A): Releaseable[F, A] = new Releaseable[F, A] {
      override val value: A = a

      override def release(): F[Unit] = Applicative[F].point(())
    }
  }

  private[raii] trait ResourceFactoryTExtractor {
    type ResourceT[F[_], A]

    def apply[F[_], A](run: F[Releaseable[F, A]]): ResourceT[F, A]

    private[raii] def unwrap[F[_], A](resourceFactoryT: ResourceT[F, A]): F[Releaseable[F, A]]

    final def unapply[F[_], A](resourceFactoryT: ResourceT[F, A]): Some[F[Releaseable[F, A]]] =
      Some(unwrap(resourceFactoryT))
  }

  object ResourceT extends ResourceFactoryTInstances0 {

    def apply[F[_], A](run: F[Releaseable[F, A]]): ResourceT[F, A] = ResourceFactoryTExtractor.apply(run)

    private[raii] def unwrap[F[_], A](resourceFactoryT: ResourceT[F, A]): F[Releaseable[F, A]] =
      ResourceFactoryTExtractor.unwrap(resourceFactoryT)

    def unapply[F[_], A](resourceFactoryT: ResourceT[F, A]): Some[F[Releaseable[F, A]]] =
      ResourceFactoryTExtractor.unapply(resourceFactoryT)

    private[raii] final def using[F[_], A, B](resourceFactoryT: ResourceT[F, A], f: A => F[B])(
        implicit monad: Bind[F]): F[B] = {
      unwrap(resourceFactoryT).flatMap { fa =>
        f(fa.value).flatMap { a: B =>
          fa.release().map { _ =>
            a
          }
        }
      }
    }

    /**
      * Return a [Future] which contains content of [Releaseable] and [Releaseable] will be closed,
      * NOTE: the content of [Releaseable] must be JVM resource cause the content will not be closed.
      */
    final def run[F[_], A](resourceFactoryT: ResourceT[F, A])(implicit monad: Bind[F]): F[A] = {
      unwrap(resourceFactoryT).flatMap { resource: Releaseable[F, A] =>
        resource.release().map { _ =>
          resource.value
        }
      }
    }

    private[raii] final def foreach[F[_], A](resourceFactoryT: ResourceT[F, A],
                                             f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
      unwrap(resourceFactoryT)
        .flatMap { fa =>
          f(fa.value)
          fa.release()
        }
        .sequence_[Id.Id, Unit]
    }

    private[raii] def catchError[F[_]: MonadError[?[_], S], S, A](fa: F[A]): F[S \/ A] = {
      fa.map(_.right[S]).handleError(_.left[A].point[F])
    }

    implicit val resourceFactoryTMonadTrans = new MonadTrans[ResourceT] {

      override def liftM[F[_]: Monad, A](fa: F[A]): ResourceT[F, A] =
        ResourceFactoryTExtractor.apply(fa.map(Releaseable.delay(_)))

      override def apply[F[_]: Monad]: Monad[ResourceT[F, ?]] = resourceFactoryTMonad
    }

    implicit def resourceFactoryTParallelApplicative[F[_]](
        implicit F0: Applicative[Lambda[R => F[R] @@ Parallel]]
    ): Applicative[Lambda[R => ResourceT[F, R] @@ Parallel]] = {
      new ResourceFactoryTParallelApplicative[F] {
        override private[raii] implicit def typeClass = F0
      }
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances3 {

    implicit def resourceFactoryTApplicative[F[_]: Applicative]: Applicative[ResourceT[F, ?]] =
      new ResourceFactoryTApplicative[F] {
        override private[raii] def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances2 extends ResourceFactoryTInstances3 {

    implicit def resourceFactoryTMonad[F[_]: Monad]: Monad[ResourceT[F, ?]] = new ResourceFactoryTMonad[F] {
      private[raii] override def typeClass = implicitly
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances1 extends ResourceFactoryTInstances2 {

    implicit def resourceFactoryTNondeterminism[F[_]](
        implicit F0: Nondeterminism[F]): Nondeterminism[ResourceT[F, ?]] =
      new ResourceFactoryTNondeterminism[F] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances0 extends ResourceFactoryTInstances1 {

    implicit def resourceFactoryTMonadError[F[_], S](
        implicit F0: MonadError[F, S]): MonadError[ResourceT[F, ?], S] =
      new ResourceFactoryTMonadError[F, S] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] trait ResourceFactoryTPoint[F[_]] extends Applicative[ResourceT[F, ?]] {
    private[raii] implicit def typeClass: Applicative[F]

    override def point[A](a: => A): ResourceT[F, A] =
      ResourceFactoryTExtractor.apply(Applicative[F].point(Releaseable.delay(a)))
  }

  import com.thoughtworks.raii.resourcet.ResourceFactoryTExtractor.unwrap

  private[raii] trait ResourceFactoryTApplicative[F[_]]
      extends Applicative[ResourceT[F, ?]]
      with ResourceFactoryTPoint[F] {

    override def ap[A, B](fa: => ResourceT[F, A])(f: => ResourceT[F, (A) => B]): ResourceT[F, B] = {
      ResourceFactoryTExtractor.apply(
        Applicative[F].apply2(unwrap(fa), unwrap(f)) { (releasableA, releasableF) =>
          new Releaseable[F, B] {
            override val value: B = releasableF.value(releasableA.value)

            override def release(): F[Unit] = {
              Applicative[F].apply2(releasableA.release(), releasableF.release()) { (_: Unit, _: Unit) =>
                ()
              }
            }
          }
        }
      )
    }
  }

  private[raii] trait ResourceFactoryTParallelApplicative[F[_]]
      extends Applicative[Lambda[R => ResourceT[F, R] @@ Parallel]] {
    private[raii] implicit def typeClass: Applicative[Lambda[R => F[R] @@ Parallel]]

    override def point[A](a: => A): ResourceT[F, A] @@ Parallel = {

      Parallel({
        val fa: F[Releaseable[F, A]] = Parallel.unwrap[F[Releaseable[F, A]]](
          typeClass.point(
            new Releaseable[F, A] {
              override def value: A = a

              override def release(): F[Unit] = Parallel.unwrap(typeClass.point(()))
            }
          ))
        ResourceFactoryTExtractor.apply(fa)
      }: ResourceT[F, A])
    }

    override def ap[A, B](fa: => ResourceT[F, A] @@ Parallel)(
        f: => ResourceT[F, A => B] @@ Parallel): ResourceT[F, B] @@ Parallel = {
      Parallel {
        ResourceFactoryTExtractor.apply(
          Parallel.unwrap[F[Releaseable[F, B]]](
            typeClass.apply2(
              Parallel(unwrap(Parallel.unwrap(fa))),
              Parallel(unwrap(Parallel.unwrap(f)))
            ) { (resourceA, resourceF) =>
              new Releaseable[F, B] {
                override val value: B = resourceF.value(resourceA.value)

                override def release(): F[Unit] = {
                  Parallel.unwrap[F[Unit]](
                    typeClass.apply2(Parallel(resourceA.release()), Parallel(resourceF.release())) {
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

  private[raii] trait ResourceFactoryTMonad[F[_]]
      extends ResourceFactoryTApplicative[F]
      with Monad[ResourceT[F, ?]] {
    private[raii] implicit override def typeClass: Monad[F]

    override def bind[A, B](fa: ResourceT[F, A])(f: (A) => ResourceT[F, B]): ResourceT[F, B] = {
      ResourceFactoryTExtractor.apply(
        for {
          releasableA <- unwrap(fa)
          releasableB <- unwrap(f(releasableA.value))
        } yield {
          new Releaseable[F, B] {
            override def value: B = releasableB.value

            override def release(): F[Unit] = {
              releasableB.release() >> releasableA.release()
            }
          }
        }
      )
    }

  }

  private[raii] trait ResourceFactoryTMonadError[F[_], S]
      extends MonadError[ResourceT[F, ?], S]
      with ResourceFactoryTPoint[F] {
    private[raii] implicit def typeClass: MonadError[F, S]

    override def raiseError[A](e: S): ResourceT[F, A] =
      ResourceFactoryTExtractor.apply(typeClass.raiseError[Releaseable[F, A]](e))

    override def handleError[A](fa: ResourceT[F, A])(f: (S) => ResourceT[F, A]): ResourceT[F, A] = {
      ResourceFactoryTExtractor.apply(
        unwrap(fa).handleError { s =>
          unwrap(f(s))
        }
      )
    }

    import com.thoughtworks.raii.resourcet.ResourceT.catchError

    override def bind[A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
      ResourceFactoryTExtractor.apply(
        catchError(unwrap(fa)).flatMap {
          case \/-(releasableA) =>
            catchError(unwrap(f(releasableA.value))).flatMap[Releaseable[F, B]] {
              case \/-(releasableB) =>
                new Releaseable[F, B] {
                  override def value: B = releasableB.value

                  override def release(): F[Unit] = {
                    catchError(releasableB.release()).flatMap {
                      case \/-(()) =>
                        releasableA.release()
                      case -\/(s) =>
                        releasableA.release().flatMap { _ =>
                          typeClass.raiseError[Unit](s)
                        }
                    }
                  }
                }.point[F]
              case -\/(s) =>
                releasableA.release().flatMap { _ =>
                  typeClass.raiseError[Releaseable[F, B]](s)
                }
            }
          case either @ -\/(s) =>
            typeClass.raiseError[Releaseable[F, B]](s)
        }
      )
    }
  }

  private[raii] trait ResourceFactoryTNondeterminism[F[_]]
      extends ResourceFactoryTMonad[F]
      with Nondeterminism[ResourceT[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](
        head: ResourceT[F, A],
        tail: Seq[ResourceT[F, A]]): ResourceT[F, (A, Seq[ResourceT[F, A]])] = {
      ResourceFactoryTExtractor.apply(
        typeClass.chooseAny(unwrap(head), tail.map(unwrap)).map {
          case (fa, residuals) =>
            new Releaseable[F, (A, Seq[ResourceT[F, A]])] {
              override val value: (A, Seq[ResourceT[F, A]]) =
                (fa.value, residuals.map { residual: F[Releaseable[F, A]] =>
                  { ResourceFactoryTExtractor.apply(residual) }: ResourceT[F, A]
                })

              override def release(): F[Unit] = fa.release()
            }

        }
      )

    }
  }
}
