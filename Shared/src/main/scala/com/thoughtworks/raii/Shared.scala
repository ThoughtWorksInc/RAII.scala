package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.raii.transformers.{ResourceFactoryT, ResourceT}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scalaz.Free.Trampoline
import scalaz.concurrent.Future
import scalaz.syntax.all._
import scalaz.std.iterable._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object Shared {

  private[raii] sealed trait State[A]
  private[raii] final case class Closed[A]() extends State[A]
  private[raii] final case class Opening[A](handlers: Queue[ResourceT[Future, A] => Trampoline[Unit]]) extends State[A]
  private[raii] final case class Open[A](data: ResourceT[Future, A], count: Int) extends State[A]

  implicit final class SharedOps[A](raii: ResourceFactoryT[Future, A]) {

    def shared: ResourceFactoryT[Future, A] = {
      val sharedReference = new Shared(raii)
      ResourceFactoryT[Future, A](
        Future.Async { (handler: ResourceT[Future, A] => Trampoline[Unit]) =>
          sharedReference.acquire(handler)
        }
      )
    }

  }

  private[raii] final class Shared[A](underlying: ResourceFactoryT[Future, A])
      extends AtomicReference[State[A]](Closed())
      with ResourceT[Future, A] {
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
    private def complete(data: ResourceT[Future, A]): Trampoline[Unit] = {
      state.get() match {
        case oldState @ Opening(handlers) =>
          val newState = Open(data, handlers.length)
          if (state.compareAndSet(oldState, newState)) {
            handlers.traverse_ { f: (ResourceT[Future, A] => Trampoline[Unit]) =>
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
    private[raii] def acquire(handler: ResourceT[Future, A] => Trampoline[Unit]): Unit = {
      state.get() match {
        case oldState @ Closed() =>
          if (state.compareAndSet(oldState, Opening(Queue(handler)))) {
            ResourceFactoryT.unwrap[Future, A](underlying).unsafePerformListen(complete)
          } else {
            acquire(handler)
          }
        case oldState @ Opening(handlers: Queue[ResourceT[Future, A] => Trampoline[Unit]]) =>
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
