package com.thoughtworks.raii

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}
import scala.util.{Failure, Success, Try, DynamicVariable}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.event.{Logging, LogSource}
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

class Remote(val remoteActorSystem: Remote.RemoteActorSystem)(implicit val timeout: Timeout) extends Serializable {

  import Remote._
  import remoteActorSystem.actorSystem
  import actorSystem.dispatcher

  val log = {
    implicit val logSource: LogSource[Remote] = new LogSource[Remote] {
      override def genString(t: Remote): String = toString
    }
    Logging(remoteActorSystem.actorSystem, this)
  }

  log.info(s"remote context $this constructed")

  def jump: Do[ActorRef] = Do.async { (remoteContinuation: RemoteContinuation[ActorRef]) =>
    {
      log.info(s"jump of $this is invoked")
      remoteStore.withValue(this) {
        val newActor = actorSystem.actorOf(Props(new RemoteActor))
        val remoteContinuationBuffer = {
          val byteArrayOutputStream = new ByteArrayOutputStream()
          new ObjectOutputStream(byteArrayOutputStream).writeObject(remoteContinuation)
          byteArrayOutputStream.toByteArray
        }

        (newActor ? Dispatch(remoteContinuationBuffer)).onComplete {
          case Success(Receipt(receipt)) =>
            receipt match {
              case Success(returnedActor) => log.info(s"remote execution returned from $returnedActor")
              case Failure(err)           => log.error(s"remote execution failed with $err")
            }
          case Failure(err) => log.error(s"akka messaging failed with $err")
        }
      }
    }
  }

  def writeReplace: Any = {
    log.info(s"writeReplace of $this is invoked")
    RemoteProxy
  }
}

case object Remote {
  type RemoteContinuation[A] = (Resource[UnitContinuation, Try[A]]) => Unit

  val remoteStore: DynamicVariable[Remote] = new DynamicVariable[Remote](null)

  implicit class RemoteActorSystem(val actorSystem: ActorSystem)

  object RemoteProxy extends Serializable {
    def readResolve: Any = {
      val remote = remoteStore.value
      val log = {
        implicit val logSource: LogSource[Remote] = new LogSource[Remote] {
          override def genString(t: Remote): String = toString
        }
        Logging(remote.remoteActorSystem.actorSystem, remote)
      }
      log.info(s"readResolve of $remote is invoked")
      remote
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
          val log = {
            implicit val logSource: LogSource[Remote.this.type] = new LogSource[Remote.this.type] {
              override def genString(t: Remote.this.type): String = toString
            }
            Logging(actorSystem, this)
          }
          tryFinalizer.map {
            case Success(_)   => log.info(s"actorSystem $actorSystem terminated")
            case Failure(err) => log.error(s"termination of actorSystem $actorSystem failed with $err")
          }
        }.join
      )
    }
  }
}
