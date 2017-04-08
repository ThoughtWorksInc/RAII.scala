package com.thoughtworks

import scala.language.higherKinds
import scalaz.\/

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

}
