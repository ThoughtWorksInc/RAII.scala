package com.thoughtworks.raii

import com.thoughtworks.raii.transformers.ResourceFactoryT

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz.{\/, _}
import scalaz.syntax.all._

object transformers {

  private[transformers] val ResourceFactoryTExtractor: ResourceFactoryTExtractor = new ResourceFactoryTExtractor {
    override type ResourceFactoryT[F[_], A] = F[ResourceT[F, A]]

    override def apply[F[_], A](run: F[ResourceT[F, A]]): ResourceFactoryT[F, A] = run

    override private[raii] def unwrap[F[_], A](resourceFactoryT: ResourceFactoryT[F, A]): F[ResourceT[F, A]] =
      resourceFactoryT
  }

  type ResourceFactoryT[F[_], A] = ResourceFactoryTExtractor.ResourceFactoryT[F, A]

  trait ResourceT[F[_], +A] {
    def value: A

    /** Releases all the native resources [[ResourceFactoryT.unwrap]]d during creating this [[ResourceT]].
      *
      * @note After [[release]], [[value]] should not be used if:
      *  - [[value]] is a scoped native resource,
      *    e.g. a [[com.thoughtworks.raii.sde.raii#scoped scoped]] `AutoCloseable`,
      *  - or, [[value]] internally uses some scoped native resources.
      */
    def release(): F[Unit]
  }

  object ResourceT {
    @inline
    def delay[F[_]: Applicative, A](a: => A): ResourceT[F, A] = new ResourceT[F, A] {
      override val value: A = a

      override def release(): F[Unit] = Applicative[F].point(())
    }
  }

  private[raii] trait ResourceFactoryTExtractor {
    type ResourceFactoryT[F[_], A]

    def apply[F[_], A](run: F[ResourceT[F, A]]): ResourceFactoryT[F, A]

    private[raii] def unwrap[F[_], A](resourceFactoryT: ResourceFactoryT[F, A]): F[ResourceT[F, A]]

    final def unapply[F[_], A](resourceFactoryT: ResourceFactoryT[F, A]): Some[F[ResourceT[F, A]]] =
      Some(unwrap(resourceFactoryT))
  }

  object ResourceFactoryT extends ResourceFactoryTInstances0 {

    def apply[F[_], A](run: F[ResourceT[F, A]]): ResourceFactoryT[F, A] = ResourceFactoryTExtractor.apply(run)

    private[raii] def unwrap[F[_], A](resourceFactoryT: ResourceFactoryT[F, A]): F[ResourceT[F, A]] =
      ResourceFactoryTExtractor.unwrap(resourceFactoryT)

    def unapply[F[_], A](resourceFactoryT: ResourceFactoryT[F, A]): Some[F[ResourceT[F, A]]] =
      ResourceFactoryTExtractor.unapply(resourceFactoryT)

    private[raii] final def using[F[_], A, B](resourceFactoryT: ResourceFactoryT[F, A], f: A => F[B])(
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
      * Return a [Future] which contains content of [ResourceT] and [ResourceT] will be closed,
      * NOTE: the content of [ResourceT] must be JVM resource cause the content will not be closed.
      */
    final def run[F[_], A](resourceFactoryT: ResourceFactoryT[F, A])(implicit monad: Bind[F]): F[A] = {
      unwrap(resourceFactoryT).flatMap { resource: ResourceT[F, A] =>
        resource.release().map { _ =>
          resource.value
        }
      }
    }

    private[raii] final def foreach[F[_], A](resourceFactoryT: ResourceFactoryT[F, A],
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

    implicit val resourceFactoryTMonadTrans = new MonadTrans[ResourceFactoryT] {

      override def liftM[F[_]: Monad, A](fa: F[A]): ResourceFactoryT[F, A] =
        ResourceFactoryTExtractor.apply(fa.map(ResourceT.delay(_)))

      override def apply[F[_]: Monad]: Monad[ResourceFactoryT[F, ?]] = resourceFactoryTMonad
    }

    implicit def resourceFactoryTParallelApplicative[F[_]](
        implicit F0: Applicative[Lambda[R => F[R] @@ Parallel]]
    ): Applicative[Lambda[R => ResourceFactoryT[F, R] @@ Parallel]] = {
      new ResourceFactoryTParallelApplicative[F] {
        override private[raii] implicit def typeClass = F0
      }
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances3 {

    implicit def resourceFactoryTApplicative[F[_]: Applicative]: Applicative[ResourceFactoryT[F, ?]] =
      new ResourceFactoryTApplicative[F] {
        override private[raii] def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances2 extends ResourceFactoryTInstances3 {

    implicit def resourceFactoryTMonad[F[_]: Monad]: Monad[ResourceFactoryT[F, ?]] = new ResourceFactoryTMonad[F] {
      private[raii] override def typeClass = implicitly
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances1 extends ResourceFactoryTInstances2 {

    implicit def resourceFactoryTNondeterminism[F[_]](
        implicit F0: Nondeterminism[F]): Nondeterminism[ResourceFactoryT[F, ?]] =
      new ResourceFactoryTNondeterminism[F] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances0 extends ResourceFactoryTInstances1 {

    implicit def resourceFactoryTMonadError[F[_], S](
        implicit F0: MonadError[F, S]): MonadError[ResourceFactoryT[F, ?], S] =
      new ResourceFactoryTMonadError[F, S] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] trait ResourceFactoryTPoint[F[_]] extends Applicative[ResourceFactoryT[F, ?]] {
    private[raii] implicit def typeClass: Applicative[F]

    override def point[A](a: => A): ResourceFactoryT[F, A] =
      ResourceFactoryTExtractor.apply(Applicative[F].point(ResourceT.delay(a)))
  }

  import com.thoughtworks.raii.transformers.ResourceFactoryTExtractor.unwrap

  private[raii] trait ResourceFactoryTApplicative[F[_]]
      extends Applicative[ResourceFactoryT[F, ?]]
      with ResourceFactoryTPoint[F] {

    override def ap[A, B](fa: => ResourceFactoryT[F, A])(f: => ResourceFactoryT[F, (A) => B]): ResourceFactoryT[F, B] = {
      ResourceFactoryTExtractor.apply(
        Applicative[F].apply2(unwrap(fa), unwrap(f)) { (releasableA, releasableF) =>
          new ResourceT[F, B] {
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
      extends Applicative[Lambda[R => ResourceFactoryT[F, R] @@ Parallel]] {
    private[raii] implicit def typeClass: Applicative[Lambda[R => F[R] @@ Parallel]]

    override def point[A](a: => A): ResourceFactoryT[F, A] @@ Parallel = {

      Parallel({
        val fa: F[ResourceT[F, A]] = Parallel.unwrap[F[ResourceT[F, A]]](
          typeClass.point(
            new ResourceT[F, A] {
              override def value: A = a

              override def release(): F[Unit] = Parallel.unwrap(typeClass.point(()))
            }
          ))
        ResourceFactoryTExtractor.apply(fa)
      }: ResourceFactoryT[F, A])
    }

    override def ap[A, B](fa: => ResourceFactoryT[F, A] @@ Parallel)(
        f: => ResourceFactoryT[F, A => B] @@ Parallel): ResourceFactoryT[F, B] @@ Parallel = {
      Parallel {
        ResourceFactoryTExtractor.apply(
          Parallel.unwrap[F[ResourceT[F, B]]](
            typeClass.apply2(
              Parallel(unwrap(Parallel.unwrap(fa))),
              Parallel(unwrap(Parallel.unwrap(f)))
            ) { (resourceA, resourceF) =>
              new ResourceT[F, B] {
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
      with Monad[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Monad[F]

    override def bind[A, B](fa: ResourceFactoryT[F, A])(f: (A) => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = {
      ResourceFactoryTExtractor.apply(
        for {
          releasableA <- unwrap(fa)
          releasableB <- unwrap(f(releasableA.value))
        } yield {
          new ResourceT[F, B] {
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
      extends MonadError[ResourceFactoryT[F, ?], S]
      with ResourceFactoryTPoint[F] {
    private[raii] implicit def typeClass: MonadError[F, S]

    override def raiseError[A](e: S): ResourceFactoryT[F, A] =
      ResourceFactoryTExtractor.apply(typeClass.raiseError[ResourceT[F, A]](e))

    override def handleError[A](fa: ResourceFactoryT[F, A])(f: (S) => ResourceFactoryT[F, A]): ResourceFactoryT[F, A] = {
      ResourceFactoryTExtractor.apply(
        unwrap(fa).handleError { s =>
          unwrap(f(s))
        }
      )
    }

    import com.thoughtworks.raii.transformers.ResourceFactoryT.catchError

    override def bind[A, B](fa: ResourceFactoryT[F, A])(f: A => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = {
      ResourceFactoryTExtractor.apply(
        catchError(unwrap(fa)).flatMap {
          case \/-(releasableA) =>
            catchError(unwrap(f(releasableA.value))).flatMap[ResourceT[F, B]] {
              case \/-(releasableB) =>
                new ResourceT[F, B] {
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
                  typeClass.raiseError[ResourceT[F, B]](s)
                }
            }
          case either @ -\/(s) =>
            typeClass.raiseError[ResourceT[F, B]](s)
        }
      )
    }
  }

  private[raii] trait ResourceFactoryTNondeterminism[F[_]]
      extends ResourceFactoryTMonad[F]
      with Nondeterminism[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](
        head: ResourceFactoryT[F, A],
        tail: Seq[ResourceFactoryT[F, A]]): ResourceFactoryT[F, (A, Seq[ResourceFactoryT[F, A]])] = {
      ResourceFactoryTExtractor.apply(
        typeClass.chooseAny(unwrap(head), tail.map(unwrap)).map {
          case (fa, residuals) =>
            new ResourceT[F, (A, Seq[ResourceFactoryT[F, A]])] {
              override val value: (A, Seq[ResourceFactoryT[F, A]]) =
                (fa.value, residuals.map { residual: F[ResourceT[F, A]] =>
                  { ResourceFactoryTExtractor.apply(residual) }: ResourceFactoryT[F, A]
                })

              override def release(): F[Unit] = fa.release()
            }

        }
      )

    }
  }
}
