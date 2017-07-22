package com.thoughtworks.raii.scalatest

import com.thoughtworks.future._
import scala.language.implicitConversions

/**
  * @author 杨博 (Yang Bo)
  */
trait ThoughtWorksFutureToScalaFuture {

  implicit def scalazTaskToScalaFuture[A](future: Future[A]): scala.concurrent.Future[A] = {
    future.asScala
  }

}
