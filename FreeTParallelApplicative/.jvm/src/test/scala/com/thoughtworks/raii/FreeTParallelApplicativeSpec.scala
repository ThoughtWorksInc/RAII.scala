package com.thoughtworks.raii

import org.scalatest._

import scala.concurrent.Promise
import scalaz.{-\/, @@, Applicative, BindRec, Coyoneda, FreeT, Monad, \/, \/-, ~>}
import scalaz.Tags.Parallel
import scalaz.concurrent.Future
import scalaz.syntax.all._
import com.thoughtworks.each.Monadic._
import FreeTParallelApplicative._

import Future._
import scala.collection.mutable.ArrayBuffer
import scala.util.{Success, Try}

object FreeTParallelApplicativeSpec {

  type CoyonedaLog[A] = Coyoneda[Log, A]

  type LoggingFuture[A] = FreeT[CoyonedaLog, Future, A]

  sealed trait Log[A]

  sealed trait Info extends Log[Unit]

  case object Operand0IsProcessing extends Info
  case object Operand1IsProcessing extends Info

  sealed trait Warning extends Log[Unit]
  sealed trait Severe extends Throwable with Log[Severe.Handling]

  final case class Operand2ThrowsAnException() extends Exception with Severe

  object Severe {
    sealed trait Handling
    object Handling {
      final case object Abort extends Handling
      final case object Ignore extends Handling
    }
  }

  implicit object FutureBindRec extends BindRec[Future] {
    override def tailrecM[A, B](f: (A) => Future[\/[A, B]])(a: A): Future[B] = {
      f(a).flatMap {
        case \/-(b) => Future.futureInstance.point(b)
        case -\/(a) => tailrecM(f)(a)
      }
    }

    override def bind[A, B](fa: Future[A])(f: (A) => Future[B]): Future[B] = Future.futureInstance.bind(fa)(f)

    override def map[A, B](fa: Future[A])(f: (A) => B): Future[B] = Future.futureInstance.map(fa)(f)
  }

  implicit def loggingFutureMonad: Monad[LoggingFuture] with BindRec[LoggingFuture] = FreeT.freeTMonad

  implicit def loggingFutureParallelApplicative: Applicative[Lambda[A => LoggingFuture[A] @@ Parallel]] =
    FreeTParallelApplicative.freeTParallelApplicative[CoyonedaLog, Future]
}
import com.thoughtworks.raii.FreeTParallelApplicativeSpec._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class FreeTParallelApplicativeSpec extends AsyncFreeSpec with Matchers with Inside {

  def log[A](l: Log[A]): LoggingFuture[A] = {
    FreeT.liftF[Coyoneda[Log, ?], Future, A](Coyoneda.lift(l))
  }

  def async[A](promise: Promise[A]): LoggingFuture[Try[A]] = {
    FreeT.liftM[Coyoneda[Log, ?], Future, Try[A]](Future.async[Try[A]] { handler =>
      promise.future.onComplete(handler)
    })(Future.futureInstance)
  }

  "Parallel Applicative for FreeT should run in parallel" in {
    val promise0 = Promise[Int]
    val promise1 = Promise[Int]

    val operand0 = monadic[LoggingFuture] {
      log(Operand0IsProcessing).each
      inside(async(promise0).each) {
        case Success(value) =>
          value
      }
    }

    val operand1 = monadic[LoggingFuture] {
      log(Operand1IsProcessing).each
      inside(async(promise1).each) {
        case Success(value) =>
          value
      }
    }

    val result =
      Parallel.unwrap(loggingFutureParallelApplicative.tuple2(Parallel(operand0), Parallel(operand1))).flatMap {
        case (i0, i1) =>
          monadic[LoggingFuture] {
            i0 should be(100)
            i1 should be(42)
            log(new Operand2ThrowsAnException).each should be(Severe.Handling.Ignore)
            i0 + i1
          }
      }
    val logs = ArrayBuffer.empty[Log[_]]
    val future = result.runM { s =>
      s.trans(new (Log ~> Future) {
          private def check() = {}
          override def apply[A](log: Log[A]): Future[A] = {
            logs += log
            log match {
              case Operand0IsProcessing | Operand1IsProcessing =>
                val allOperationStarted = logs.length == 2
                if (allOperationStarted) {
                  promise0.complete(Success(100))
                  promise1.complete(Success(42))
                }
                Future.now(())
              case Operand2ThrowsAnException() =>
                Future.now(Severe.Handling.Ignore)
            }
          }
        })
        .run(Future.futureInstance)
    }
    val p = Promise[Assertion]
    future.unsafePerformAsync { output: Int =>
      p.complete(Try {
        inside(logs) {
          case upstreamLogs :+ lastLog =>
            lastLog should be(Operand2ThrowsAnException())
            upstreamLogs.toSet should be(Set(Operand0IsProcessing, Operand1IsProcessing))
        }
        output should be(142)
      })
    }
    p.future

  }

}
