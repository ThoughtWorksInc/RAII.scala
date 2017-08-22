package com.thoughtworks.raii

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import util.Try
import scalaz.syntax.all._
import com.thoughtworks.tryt.covariant._
import com.thoughtworks.future._
import com.thoughtworks.continuation._
import com.thoughtworks.raii.asynchronous._
import com.thoughtworks.raii.covariant._

object Remote {
  object ActorSystem {
    def apply(actorSystem: ActorSystem): Do[ActorSystem] = {
      val terminateFuture: Future[Terminated] = {
        import actorSystem.dispatcher
        actorSystem.terminate.toThoughtworksFuture
      }
      val Future(TryT(terminateCont)) = terminateFuture
      val finalizerCont: UnitContinuation[Unit] = terminateCont.void
      val resource: Resource[UnitContinuation, Try[ActorSystem]] = Resource(Try(actorSystem), finalizerCont)
      val resourceCont: UnitContinuation[Resource[UnitContinuation, Try[ActorSystem]]] = UnitContinuation.now(resource)
      Do(TryT(ResourceT(resourceCont)))
    }
  }

  object Implicits {
    implicit val globalActorSystem: Do[ActorSystem] = ActorSystem(akka.actor.ActorSystem("globalActorSystem"))
  }

}
