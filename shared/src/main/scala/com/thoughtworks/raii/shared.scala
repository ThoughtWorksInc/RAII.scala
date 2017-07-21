package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.future.continuation.{Continuation, UnitContinuation}
import com.thoughtworks.raii.covariant.{Releasable, ResourceT}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scalaz.Free.Trampoline
import scalaz.{ContT, Trampoline}
import scalaz.syntax.all._
import scalaz.std.iterable._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object shared {

  private[shared] sealed trait State[A]
  private[shared] final case class Closed[A]() extends State[A]
  private[shared] final case class Opening[A](handlers: Queue[Releasable[UnitContinuation, A] => Trampoline[Unit]])
      extends State[A]
  private[shared] final case class Open[A](data: Releasable[UnitContinuation, A], count: Int) extends State[A]

  implicit final class SharedOps[A](raii: ResourceT[UnitContinuation, A]) {

    def shared: ResourceT[UnitContinuation, A] = {
      val sharedReference = new SharedStateMachine(raii)
      ResourceT[UnitContinuation, A](sharedReference.acquire)
    }

  }

  private[shared] final class SharedStateMachine[A](underlying: ResourceT[UnitContinuation, A])
      extends AtomicReference[State[A]](Closed())
      with Releasable[UnitContinuation, A] {
    private def sharedCloseable = this
    override def value: A = state.get().asInstanceOf[Open[A]].data.value

    override def release: UnitContinuation[Unit] = {
      @tailrec
      def retry(continue: Unit => Trampoline[Unit]): Trampoline[Unit] = {
        state.get() match {
          case oldState @ Open(data, count) =>
            if (count == 1) {
              if (state.compareAndSet(oldState, Closed())) {
                val Continuation(contT) = data.release
                contT(continue)
              } else {
                retry(continue)
              }
            } else {
              if (state.compareAndSet(oldState, oldState.copy(count = count - 1))) {
                Trampoline.suspend(continue(()))
              } else {
                retry(continue)
              }
            }
          case Opening(_) | Closed() =>
            throw new IllegalStateException("Cannot release more than once")

        }
      }
      Continuation(ContT(retry))
    }

    private def state = this

    @tailrec
    private def complete(data: Releasable[UnitContinuation, A]): Trampoline[Unit] = {
      state.get() match {
        case oldState @ Opening(handlers) =>
          val newState = Open(data, handlers.length)
          if (state.compareAndSet(oldState, newState)) {
            handlers.traverse_ { continue: (Releasable[UnitContinuation, A] => Trampoline[Unit]) =>
              Trampoline.suspend(continue(sharedCloseable))
            }
          } else {
            complete(data)
          }
        case Open(_, _) | Closed() =>
          throw new IllegalStateException("Cannot trigger handler more than once")
      }
    }

    private[shared] def acquire: UnitContinuation[Releasable[UnitContinuation, A]] = {
      @tailrec
      def retry(handler: Releasable[UnitContinuation, A] => Trampoline[Unit]): Trampoline[Unit] = {
        state.get() match {
          case oldState @ Closed() =>
            if (state.compareAndSet(oldState, Opening(Queue(handler)))) {
              val ResourceT(Continuation(contT)) = underlying
              contT(complete)
            } else {
              retry(handler)
            }
          case oldState @ Opening(handlers: Queue[Releasable[UnitContinuation, A] => Trampoline[Unit]]) =>
            if (state.compareAndSet(oldState, Opening(handlers.enqueue(handler)))) {
              Trampoline.done(())
            } else {
              retry(handler)
            }
          case oldState @ Open(data, count) =>
            if (state.compareAndSet(oldState, oldState.copy(count = count + 1))) {
              handler(sharedCloseable)
            } else {
              retry(handler)
            }
        }

      }
      Continuation(ContT(retry))
    }
  }

}
