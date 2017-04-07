package com.thoughtworks

import scala.language.higherKinds

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
package object raii {

  type ResourceFactory[A] = ResourceFactoryT[scalaz.Id.Id, A]

  type RAIIT[F[_], A] = ResourceFactoryT[F, A]

  type RAII[A] = ResourceFactory[A]

}
