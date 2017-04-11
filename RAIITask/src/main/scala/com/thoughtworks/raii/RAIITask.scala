package com.thoughtworks.raii

import java.util.concurrent.ExecutorService

import com.thoughtworks.raii.RAIITask.RAIITask
import com.thoughtworks.raii.ResourceFactoryT.ResourceT

import scala.concurrent.ExecutionContext
import scalaz.{-\/, EitherT, \/, \/-}
import scalaz.concurrent.{Future, Task}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object RAIITask {
  private[raii] type RAIITask[A] = EitherT[ResourceFactoryT[Future, ?], Throwable, A]

  def managed[A <: AutoCloseable](task: Task[A]): RAIITask[A] = {
    new RAIITask[A]({ () =>
      task.get.map { either =>
        new ResourceT[Future, Throwable \/ A] {
          override def value: Throwable \/ A = either
          override def release(): Future[Unit] = {
            either match {
              case \/-(closeable) =>
                Future.delay(closeable.close())
              case -\/(e) =>
                Future.now(())
            }
          }
        }
      }
    })
  }

  def managed[A <: AutoCloseable](future: Future[A]): RAIITask[A] = {
    managed(new Task(future.map(\/-(_))))
  }

  def managed[A <: AutoCloseable](a: => A): RAIITask[A] = {
    managed(Task.delay(a))
  }

  def unmanaged[A](task: Task[A]): RAIITask[A] = {
    new RAIITask[A]({ () =>
      task.get.map { either =>
        new ResourceT[Future, Throwable \/ A] {
          override def value: Throwable \/ A = either
          override def release(): Future[Unit] = Future.now(())
        }
      }
    })
  }

  def unmanaged[A](future: Future[A]): RAIITask[A] = {
    unmanaged(new Task(future.map(\/-(_))))
  }

  def unmanaged[A](a: => A): RAIITask[A] = {
    unmanaged(Task.delay(a))
  }

  def delay[A](a: => A): RAIITask[A] = {
    unmanaged(a)
  }

  /** Create a [[RAIITask]] that will evaluate `a` using the given `ExecutorService`. */
  def apply[A](a: => A)(implicit executorService: ExecutorService): RAIITask[A] = {
    new RAIITask[A]({ () =>
      Task(a).get.map { either =>
        new ResourceT[Future, Throwable \/ A] {
          override def value: Throwable \/ A = either
          override def release(): Future[Unit] = Future.now(())
        }
      }
    })
  }

  def jump()(implicit executorService: ExecutionContext): RAIITask[Unit] = {
    unmanaged(Future.async { handler: (Unit => Unit) =>
      executorService.execute { () =>
        handler(())
      }
    })
  }

  def run[A](raiiTask: RAIITask[A]):Task[A] = {
    new Task(raiiTask.run.run)
  }

}
