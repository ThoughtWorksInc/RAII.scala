package com.thoughtworks

import scala.language.higherKinds
import scalaz._
import scalaz.concurrent._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
package object raii extends T {

  /** @template */
  type ResourceFactory[A] = ResourceFactoryT[Throwable \/ ?, A]

  /** @template */
  type RAIIT[F[_], A] = ResourceFactoryT[F, A]

  /** @template */
  type RAII[A] = ResourceFactory[A]

  /** @template */
  type RAIIFuture[A] = ResourceFactoryT[Future, A]

  /** @template */
  type RAIITask[A] = EitherT[RAIIFuture, Throwable, A]

}

trait T {

  /** @template */
  type RAIITask2[A] >: com.thoughtworks.raii.RAIITask[_ <: A] <: com.thoughtworks.raii.RAIITask[_ <: A]

}
