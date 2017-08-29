package com.thoughtworks.raii

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}
import scala.util.{Failure, Success, Try, DynamicVariable}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import scalaz.syntax.all._
import com.thoughtworks.tryt._
import com.thoughtworks.tryt.covariant._
import com.thoughtworks.continuation._
import com.thoughtworks.future._
import com.thoughtworks.raii.asynchronous._
import com.thoughtworks.raii.covariant._

case class Dispatch(remoteContinuationBuffer: Array[Byte])

case class Receipt[A](receipt: Try[A])

class RemoteActor extends Actor {

  import Remote.RemoteContinuation

  override def receive: Receive = {
    case Dispatch(remoteContinuationBuffer) =>
      val remoteContinuation: RemoteContinuation[ActorRef] = new ObjectInputStream(
        new ByteArrayInputStream(remoteContinuationBuffer)).readObject().asInstanceOf[RemoteContinuation[ActorRef]]
      val value: Try[ActorRef] = Success(self)
      val remoteActorSystem = context.system.asInstanceOf[Remote].remoteActorSystem
      val release: UnitContinuation[Unit] = UnitContinuation.delay {
        remoteActorSystem.actorSystem.stop(self)
      }
      val remoteContinuationParameter: Resource[UnitContinuation, Try[ActorRef]] = Resource(value, release)
      remoteContinuation(remoteContinuationParameter)
      sender ! Receipt(value)
  }
}

class Remote(val remoteActorSystem: Remote.RemoteActorSystem)(implicit val timeout: Timeout) {

  import Remote._
  import remoteActorSystem.actorSystem
  import actorSystem.dispatcher

  def jump: Do[ActorRef] = Do.async { (remoteContinuation: RemoteContinuation[ActorRef]) =>
    {
      remoteActorSystemStore.withValue(remoteActorSystem) {
        val newActor = actorSystem.actorOf(Props(new RemoteActor))
        val remoteContinuationBuffer = {
          val byteArrayOutputStream = new ByteArrayOutputStream()
          new ObjectOutputStream(byteArrayOutputStream).writeObject(remoteContinuation)
          byteArrayOutputStream.toByteArray
        }
        (newActor ? Dispatch(remoteContinuationBuffer)).onComplete {
          case Success(Receipt(receipt)) =>
            receipt match {
              case Success(_)   =>
              case Failure(err) =>
            }
          case Failure(err) =>
        }
      }
    }
  }
}

object Remote {
  type RemoteContinuation[A] = (Resource[UnitContinuation, Try[A]]) => Unit

  val remoteActorSystemStore: DynamicVariable[RemoteActorSystem] = new DynamicVariable[RemoteActorSystem](null)

  implicit class RemoteActorSystem(val actorSystem: ActorSystem) extends Serializable {
    def writeReplace: Any = {
      RemoteActorSystemProxy
    }
  }

  object RemoteActorSystemProxy extends Serializable {
    def readResolve: Any = {
      remoteActorSystemStore.value
    }
  }

  def apply(makeActorSystem: => ActorSystem)(implicit timeout: Timeout): Do[Remote] = {
    Do.resource {
      val actorSystem = makeActorSystem
      Resource(
        new Remote(actorSystem),
        UnitContinuation.delay {
          import actorSystem.dispatcher
          val Future(TryT(tryFinalizer)) = actorSystem.terminate.toThoughtworksFuture
          tryFinalizer.map {
            case Success(_)   => // todo
            case Failure(err) =>
          }
        }.join
      )
    }
  }
}
