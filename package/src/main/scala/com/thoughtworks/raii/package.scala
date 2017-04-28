package com.thoughtworks

import com.thoughtworks.raii.transformers.ResourceFactoryT

import scala.language.higherKinds
import scalaz._
import scalaz.concurrent._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
package object raii {

  /** @template */
  type ResourceFactory[A] = ResourceFactoryT[Throwable \/ ?, A]

  /** @template */
  type RAIIT[F[_], A] = ResourceFactoryT[F, A]

  /** @template */
  type RAII[A] = ResourceFactory[A]

  /** @template */
  type RAIIFuture[A] = ResourceFactoryT[Future, A]

}
