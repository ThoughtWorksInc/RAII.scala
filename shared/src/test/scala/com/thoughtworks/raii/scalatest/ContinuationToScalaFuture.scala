package com.thoughtworks.raii.scalatest

import scalaz.syntax.all._
import com.thoughtworks.continuation._
import com.thoughtworks.future._
import com.thoughtworks.tryt.covariant.TryT

import scala.concurrent.Promise
import scala.language.implicitConversions
import scala.util.Try
import scalaz.Trampoline

/**
  * @author 杨博 (Yang Bo)
  */
trait ContinuationToScalaFuture {

  implicit def continuationToScalaFuture[A](continuation: UnitContinuation[A]): scala.concurrent.Future[A] = {
    val continuationToScalaFuture = DummyImplicit.dummyImplicit
    TryT(continuation.map(Try(_)))
    val promise = Promise[A]
    continuation.onComplete { a =>
      val _ = promise.success(a)
    }
    promise.future
  }
}
