package com.thoughtworks.raii.scalatest

import com.thoughtworks.future.continuation.Continuation

import scala.concurrent.Promise
import scala.language.implicitConversions
import scalaz.Trampoline

/**
  * @author 杨博 (Yang Bo)
  */
trait ContinuationToScalaFuture {

  implicit def continuationToScalaFuture[A](continuation: Continuation[Unit, A]): scala.concurrent.Future[A] = {
    val promise = Promise[A]
    Continuation.onComplete(continuation) { a =>
      val _ = promise.success(a)
    }
    promise.future
  }
}
