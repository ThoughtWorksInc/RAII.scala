package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.continuation._

import scala.util.{Failure, Success, Try}
import com.thoughtworks.tryt.covariant._
import com.thoughtworks.raii.asynchronous._
import com.thoughtworks.raii.covariant._

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scalaz.Free.Trampoline
import scalaz.{Monoid, Trampoline}
import scalaz.syntax.all._

/** The namespace that contains the implementation of the asynchronous resource pool.
  *
  * @example Given a factory that creates resources,
  *
  *          {{{
  *          import scalaz.Tags.Parallel
  *          import scalaz._
  *          import scalaz.syntax.tag._
  *          import scalaz.syntax.all._
  *          import com.thoughtworks.future._
  *          import com.thoughtworks.raii.asynchronous._
  *          import com.thoughtworks.raii.AsynchronousPool
  *          import java.lang.AutoCloseable
  *
  *          trait MyResource extends AutoCloseable {
  *            def inUse(): Unit
  *          }
  *
  *          val myResourceStub0 = stub[MyResource]
  *          val myResourceStub1 = stub[MyResource]
  *          val myResourceStub2 = stub[MyResource]
  *
  *          val myResourceFactoryMock = mockFunction[MyResource]
  *          myResourceFactoryMock.expects().returns(myResourceStub0)
  *          myResourceFactoryMock.expects().returns(myResourceStub1)
  *          myResourceFactoryMock.expects().returns(myResourceStub2)
  *          }}}
  *
  *          then it can be converted to a resource pool, which holds some instances of `MyResource`.
  *
  *          {{{
  *          val myResourcePool: Do[Do[MyResource]] = AsynchronousPool.fixed(
  *            resourceFactory = Do.autoCloseable(myResourceFactoryMock()),
  *            poolSize = 3
  *          )
  *          }}}
  *
  *          When some clients are using the resource pool,
  *
  *          {{{
  *          def client(acquire: Do[MyResource], operationsPerClient: Int): Do[Unit] = {
  *            Do.nested[Unit](acquire.flatMap { myResource =>
  *              Do.execute {
  *                  myResource.inUse
  *                }
  *                .replicateM_(operationsPerClient)
  *            })
  *          }
  *          def allClients(acquire: Do[MyResource], numberOfClients: Int, operationsPerClient: Int): ParallelDo[Unit] = {
  *            implicit def keepLastException = new Semigroup[Throwable] {
  *              override def append(f1: Throwable, f2: => Throwable) = f2
  *            }
  *            Applicative[ParallelDo].replicateM_(numberOfClients, Parallel(client(acquire, operationsPerClient)))
  *          }
  *          def usingPool(numberOfClients: Int, operationsPerClient: Int) = {
  *            myResourcePool.flatMap { acquire =>
  *              allClients(acquire, numberOfClients, operationsPerClient).unwrap
  *            }
  *          }
  *          }}}
  *
  *          then the operations from these clients should be distributed on those `MyResource`s,
  *          and those `MyResource`s should be closed after being used.
  *
  *          {{{
  *          usingPool(numberOfClients = 10, operationsPerClient = 10).run.map { _: Unit =>
  *            ((myResourceStub0.inUse _): () => Unit).verify().repeated(30 to 40)
  *            ((myResourceStub0.close _): () => Unit).verify().once()
  *
  *            ((myResourceStub1.inUse _): () => Unit).verify().repeated(30 to 40)
  *            ((myResourceStub1.close _): () => Unit).verify().once()
  *
  *            ((myResourceStub2.inUse _): () => Unit).verify().repeated(30 to 40)
  *            ((myResourceStub2.close _): () => Unit).verify().once()
  *
  *            succeed
  *          }.toScalaFuture
  *          }}}
  */
object AsynchronousPool {
  private[AsynchronousPool] sealed trait State[A]
  private[AsynchronousPool] sealed trait DisableAcquire[A] extends State[A]

  private[AsynchronousPool] final case class Available[A](availableResources: List[A]) extends State[A]
  private[AsynchronousPool] final case class Queued[A](
      tasks: Queue[Resource[UnitContinuation, Try[A]] => Trampoline[Unit]])
      extends State[A]
  private[AsynchronousPool] final case class Flushing[A](
      tasks: Queue[Resource[UnitContinuation, Try[A]] => Trampoline[Unit]],
      shutDownHandler: Unit => Trampoline[Unit])
      extends DisableAcquire[A]
  private[AsynchronousPool] final case class ShuttingDown[A](numberOfShuttedDownResources: Int,
                                                             shutDownHandler: Unit => Trampoline[Unit])
      extends DisableAcquire[A]
  private[AsynchronousPool] final case class ShuttedDown[A]() extends DisableAcquire[A]

  final class ClosedPoolException(message: String = "This pool has been closed.", cause: Throwable = null)
      extends IllegalStateException(message, cause)

  /** Returns a resource pool from preloaded resoures. */
  def preloaded[A](preloadedResources: Seq[A]): Resource[UnitContinuation, Do[A]] = {
    val pool = new AsynchronousPool[A](preloadedResources)
    Resource(pool.acquire, pool.shutdown)
  }

  /** Returns a factory of fixed sized resource pool. */
  def fixed[A](resourceFactory: Do[A], poolSize: Int): Do[Do[A]] = {
    resourceFactory.replicateM(poolSize).flatMap { resources: List[A] =>
      Do.resource[Do[A]](AsynchronousPool.preloaded[A](resources))
    }
  }
}

import AsynchronousPool._
private final class AsynchronousPool[A](resources: Seq[A])
    extends AtomicReference[State[A]](Available(resources.toList))
    with ((Resource[UnitContinuation, Try[A]] => Trampoline[Unit]) => Trampoline[Unit]) {
  private val poolSize = resources.length
  @tailrec
  def apply(task: Resource[UnitContinuation, Try[A]] => Trampoline[Unit]): Trampoline[Unit] = {
    super.get() match {
      case oldState @ Available(head :: tail) =>
        if (super.compareAndSet(oldState, Available(tail))) {
          Trampoline.suspend {
            task(borrowed(head))
          }
        } else {
          apply(task)
        }
      case oldState @ Available(Nil) =>
        if (super.compareAndSet(oldState, Queued(Queue(task)))) {
          Trampoline.done(())
        } else {
          apply(task)
        }
      case oldState @ Queued(tasks) =>
        if (super.compareAndSet(oldState, Queued(tasks.enqueue(task)))) {
          Trampoline.done(())
        } else {
          apply(task)
        }
      case _: DisableAcquire[_] =>
        Trampoline.suspend {
          task(Resource.now(Failure(new ClosedPoolException)))
        }
    }
  }

  private def borrowed(head: A) = {
    Resource(Success(head), UnitContinuation(release(head)))
  }

  @tailrec
  private def release(a: A)(continue: Unit => Trampoline[Unit]): Trampoline[Unit] = {
    super.get() match {
      case oldState @ Queued(tasks) =>
        if (tasks.nonEmpty) {
          val (head, tail) = tasks.dequeue
          if (super.compareAndSet(oldState, Queued(tail))) {
            Trampoline.suspend {
              continue(()) >> head(borrowed(a))
            }
          } else {
            release(a)(continue)
          }
        } else {
          if (super.compareAndSet(oldState, Available(a :: Nil))) {
            Trampoline.suspend(continue(()))
          } else {
            release(a)(continue)
          }
        }
      case oldState @ Available(rest) =>
        if (super.compareAndSet(oldState, Available(a :: rest))) {
          Trampoline.suspend(continue(()))
        } else {
          release(a)(continue)
        }
      case oldState @ Flushing(tasks, shutDownHandler) =>
        if (tasks.nonEmpty) {
          val (head, tail) = tasks.dequeue
          if (super.compareAndSet(oldState, Flushing(tail, shutDownHandler))) {
            Trampoline.suspend {
              continue(()) >> head(borrowed(a))
            }
          } else {
            release(a)(continue)
          }
        } else {
          if (poolSize == 1) {
            if (super.compareAndSet(oldState, ShuttedDown())) {
              Trampoline.suspend {
                continue(()) >> shutDownHandler(())
              }
            } else {
              release(a)(continue)
            }
          } else {
            if (super.compareAndSet(oldState, ShuttingDown(1, shutDownHandler))) {
              Trampoline.suspend(continue(()))
            } else {
              release(a)(continue)
            }
          }
        }
      case oldState @ ShuttingDown(oldNumberOfShuttedDownResources, shutDownHandler) =>
        val newNumberOfShuttedDownResources = oldNumberOfShuttedDownResources + 1
        if (poolSize == newNumberOfShuttedDownResources) {
          if (super.compareAndSet(oldState, ShuttedDown())) {
            Trampoline.suspend {
              continue(()) >> shutDownHandler(())
            }
          } else {
            release(a)(continue)
          }
        } else {
          if (super.compareAndSet(oldState, ShuttingDown(newNumberOfShuttedDownResources, shutDownHandler))) {
            Trampoline.suspend(continue(()))
          } else {
            release(a)(continue)
          }
        }
      case ShuttedDown() =>
        throw new ClosedPoolException()
    }
  }

  def shutdown: UnitContinuation[Unit] = {
    @tailrec
    def retry(continue: Unit => Trampoline[Unit]): Trampoline[Unit] = {
      super.get() match {
        case oldState @ Available(availableResources) =>
          val numberOfAvailableResources = availableResources.length
          if (numberOfAvailableResources == poolSize) {
            if (super.compareAndSet(oldState, ShuttedDown())) {
              Trampoline.suspend {
                continue(())
              }
            } else {
              retry(continue)
            }
          } else {
            if (super.compareAndSet(oldState, ShuttingDown(numberOfAvailableResources, continue))) {
              Trampoline.done(())
            } else {
              retry(continue)
            }
          }
        case oldState @ Queued(tasks) =>
          if (super.compareAndSet(oldState, Flushing(tasks, continue))) {
            Trampoline.done(())
          } else {
            retry(continue)
          }
        case _: DisableAcquire[_] =>
          throw new ClosedPoolException()
      }

    }
    UnitContinuation(retry)
  }

  def acquire: Do[A] = Do(TryT(ResourceT(UnitContinuation(this))))
}
