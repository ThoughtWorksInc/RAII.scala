package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.raii.covariant.{ResourceT, Releasable}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scalaz.Free.Trampoline
import scalaz.concurrent.Future
import scalaz.syntax.all._
import scalaz.std.iterable._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object shared {

  private[shared] sealed trait State[A]
  private[shared] final case class Closed[A]() extends State[A]
  private[shared] final case class Opening[A](handlers: Queue[Releasable[Future, A] => Trampoline[Unit]])
      extends State[A]
  private[shared] final case class Open[A](data: Releasable[Future, A], count: Int) extends State[A]

  implicit final class SharedOps[A](raii: ResourceT[Future, A]) {

    def shared: ResourceT[Future, A] = {
      val sharedReference = new SharedStateMachine(raii)
      ResourceT[Future, A](
        Future.Async { (handler: Releasable[Future, A] => Trampoline[Unit]) =>
          sharedReference.acquire(handler)
        }
      )
    }

  }

  private[shared] final class SharedStateMachine[A](underlying: ResourceT[Future, A])
      extends AtomicReference[State[A]](Closed())
      with Releasable[Future, A] {
    private def sharedCloseable = this
    override def value: A = state.get().asInstanceOf[Open[A]].data.value

    override def release(): Future[Unit] = Future.Suspend { () =>
      @tailrec
      def retry(): Future[Unit] = {
        state.get() match {
          case oldState @ Open(data, count) =>
            if (count == 1) {
              if (state.compareAndSet(oldState, Closed())) {
                data.release()
              } else {
                retry()
              }
            } else {
              if (state.compareAndSet(oldState, oldState.copy(count = count - 1))) {
                Future.now(())
              } else {
                retry()
              }
            }
          case Opening(_) | Closed() =>
            throw new IllegalStateException("Cannot release more than once")

        }
      }
      retry()
    }

    private def state = this

    @tailrec
    private def complete(data: Releasable[Future, A]): Trampoline[Unit] = {
      state.get() match {
        case oldState @ Opening(handlers) =>
          val newState = Open(data, handlers.length)
          if (state.compareAndSet(oldState, newState)) {
            handlers.traverse_ { f: (Releasable[Future, A] => Trampoline[Unit]) =>
              f(sharedCloseable)
            }
          } else {
            complete(data)
          }
        case Open(_, _) | Closed() =>
          throw new IllegalStateException("Cannot trigger handler more than once")
      }
    }

    @tailrec
    private[shared] def acquire(handler: Releasable[Future, A] => Trampoline[Unit]): Unit = {
      state.get() match {
        case oldState @ Closed() =>
          if (state.compareAndSet(oldState, Opening(Queue(handler)))) {
            val ResourceT(future) = underlying
            future.unsafePerformListen(complete)
          } else {
            acquire(handler)
          }
        case oldState @ Opening(handlers: Queue[Releasable[Future, A] => Trampoline[Unit]]) =>
          if (state.compareAndSet(oldState, Opening(handlers.enqueue(handler)))) {
            ()
          } else {
            acquire(handler)
          }
        case oldState @ Open(data, count) =>
          if (state.compareAndSet(oldState, oldState.copy(count = count + 1))) {
            handler(sharedCloseable).run
          } else {
            acquire(handler)
          }
      }

    }
  }

}
