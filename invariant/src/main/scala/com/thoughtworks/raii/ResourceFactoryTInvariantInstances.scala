package com.thoughtworks.raii

import scalaz._
import invariant._
import scalaz.syntax.all._
import scala.language.higherKinds

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
private[raii] trait ResourceFactoryTInvariantInstances { this: ResourceT.type =>

  /** @group Type classes */
  implicit def resourceTMonadTrans: MonadTrans[ResourceT] = new Serializable with MonadTrans[ResourceT] {

    override def liftM[F[_]: Monad, A](fa: F[A]): ResourceT[F, A] =
      garbageCollected(fa)

    override def apply[F[_]: Monad]: Monad[ResourceT[F, ?]] = invariantResourceTMonad
  }
}
