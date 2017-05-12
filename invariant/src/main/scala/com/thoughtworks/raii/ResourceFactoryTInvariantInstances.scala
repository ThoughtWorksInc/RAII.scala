package com.thoughtworks.raii

import scalaz._
import invariant._
import scalaz.syntax.all._
import scala.language.higherKinds

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
private[raii] trait ResourceFactoryTInvariantInstances { this: ResourceT.type =>

  implicit val resourceTMonadTrans = new MonadTrans[ResourceT] {

    override def liftM[F[_]: Monad, A](fa: F[A]): ResourceT[F, A] =
      opacityTypes.apply(fa.map(Releasable.now(_)))

    override def apply[F[_]: Monad]: Monad[ResourceT[F, ?]] = resourceTMonad
  }
}
