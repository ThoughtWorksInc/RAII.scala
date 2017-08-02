package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.continuation._
import com.thoughtworks.future._
import com.thoughtworks.raii.asynchronous.Do
import com.thoughtworks.raii.covariant.{Releasable, ResourceT}
import com.thoughtworks.tryt.covariant.TryT

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.util.{Success, Try}
import scalaz.{ContT, Trampoline}
import scalaz.syntax.functor._
import scalaz.Free.Trampoline

object AsynchronousSemaphore {
  private[AsynchronousSemaphore] sealed trait State
  private[AsynchronousSemaphore] final case class Available(restNumberOfPermits: Int) extends State
  private[AsynchronousSemaphore] final val Available1 = Available(1)
  private[AsynchronousSemaphore] final case class Unavailable(waiters: Queue[Unit => Trampoline[Unit]]) extends State

  @inline
  def apply(numberOfPermits: Int): AsynchronousSemaphore = {
    numberOfPermits.ensuring(_ > 0)
    new AtomicReference[State](Available(numberOfPermits)) with AsynchronousSemaphore {
      override protected def state: AtomicReference[State] = this
    }
  }

}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
trait AsynchronousSemaphore {
  import AsynchronousSemaphore._
  protected def state: AtomicReference[State]

  def toDo: Do[Unit] = {
    val releasableContinuation: UnitContinuation[Releasable[UnitContinuation, Try[Unit]]] = acquire().map { _ =>
      new Releasable[UnitContinuation, Try[Unit]] {
        override def value: Try[Unit] = Success(())

        override def release: UnitContinuation[Unit] = UnitContinuation.safeAsync[Unit] { continue =>
          AsynchronousSemaphore.this.release().flatMap(continue)
        }
      }
    }
    Do(TryT(ResourceT(releasableContinuation)))
  }

  @tailrec
  private def asyncAcquire(waiter: (Unit => Trampoline[Unit])): Trampoline[Unit] = {
    state.get() match {
      case oldState @ Available(1) =>
        if (state.compareAndSet(oldState, Unavailable(Queue.empty))) {
          waiter(())
        } else {
          asyncAcquire(waiter)
        }
      case oldState @ Available(restNumberOfPermits) if restNumberOfPermits > 1 =>
        if (state.compareAndSet(oldState, Available(restNumberOfPermits - 1))) { // TODO
          waiter(())
        } else {
          asyncAcquire(waiter)
        }
      case oldState @ Unavailable(waiters) =>
        if (state.compareAndSet(oldState, Unavailable(waiters.enqueue(waiter)))) {
          Trampoline.done(())
        } else {
          asyncAcquire(waiter)
        }
    }
  }

  final def acquire(): UnitContinuation[Unit] = {
    UnitContinuation.safeAsync(asyncAcquire)
  }

  @tailrec
  final def release(): Trampoline[Unit] = {
    state.get() match {
      case oldState @ Unavailable(waiters) =>
        if (waiters.nonEmpty) {
          val (head, tail) = waiters.dequeue
          if (state.compareAndSet(oldState, Unavailable(tail))) {
            head(())
          } else {
            release()
          }
        } else {
          if (state.compareAndSet(oldState, Available1)) {
            Trampoline.done(())
          } else {
            release()
          }
        }
      case oldState @ Available(restNumberOfPermits) =>
        if (state.compareAndSet(oldState, Available(restNumberOfPermits + 1))) {
          Trampoline.done(())
        } else {
          release()
        }
    }
  }
}
