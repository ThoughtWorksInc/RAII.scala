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

object asynchronouspool {
  private sealed trait State[A]
  private sealed trait DisableAcquire[A] extends State[A]

  private final case class Available[A](availableResources: List[A]) extends State[A]
  private final case class Queued[A](tasks: Queue[Resource[UnitContinuation, Try[A]] => Trampoline[Unit]])
      extends State[A]
  private final case class Flushing[A](tasks: Queue[Resource[UnitContinuation, Try[A]] => Trampoline[Unit]],
                                       shutDownHandler: Unit => Trampoline[Unit])
      extends DisableAcquire[A]
  private final case class ShuttingDown[A](numberOfShuttedDownResources: Int, shutDownHandler: Unit => Trampoline[Unit])
      extends DisableAcquire[A]
  private final case class ShuttedDown[A]() extends DisableAcquire[A]

  private final class StateReference[A](as: List[A])
      extends AtomicReference[State[A]](Available(as))
      with ((Resource[UnitContinuation, Try[A]] => Trampoline[Unit]) => Trampoline[Unit]) {
    private val capacity = as.length
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
            if (capacity == 1) {
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
          if (capacity == newNumberOfShuttedDownResources) {
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

    @tailrec
    private def shutdown(continue: Unit => Trampoline[Unit]): Trampoline[Unit] = {
      super.get() match {
        case oldState @ Available(availableResources) =>
          val numberOfAvailableResources = availableResources.length
          if (numberOfAvailableResources == capacity) {
            if (super.compareAndSet(oldState, ShuttedDown())) {
              Trampoline.suspend {
                continue(())
              }
            } else {
              shutdown(continue)
            }
          } else {
            if (super.compareAndSet(oldState, ShuttingDown(numberOfAvailableResources, continue))) {
              Trampoline.done(())
            } else {
              shutdown(continue)
            }
          }
        case oldState @ Queued(tasks) =>
          if (super.compareAndSet(oldState, Flushing(tasks, continue))) {
            Trampoline.done(())
          } else {
            shutdown(continue)
          }
        case _: DisableAcquire[_] =>
          throw new ClosedPoolException()
      }

    }

    def toResource: Resource[UnitContinuation, Success[Do[A]]] = {
      val doAcquire: Do[A] = Do(TryT(ResourceT(UnitContinuation(this))))
      Resource(Success(doAcquire), UnitContinuation(shutdown))
    }

  }

  def pool[A](doA: Do[A], capacity: Int): Do[Do[A]] = {
    doA.replicateM(capacity).flatMap { as: List[A] =>
      Do(TryT(ResourceT(UnitContinuation.now(new StateReference(as).toResource))))
    }
  }

  final class ClosedPoolException(message: String = "This pool has been closed.", cause: Throwable = null)
      extends IllegalStateException(message, cause)

}
