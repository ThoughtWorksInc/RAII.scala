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
import scala.concurrent.duration.{SECONDS, FiniteDuration}

case class Dispatch(remoteContinuationBuffer: Array[Byte])

case class Receipt[A](receipt: Try[A])

class RemoteActor extends Actor {

  import Remote.{RemoteContinuation, actorSystemStore}

  override def receive: Receive = {
    case Dispatch(remoteContinuationBuffer) =>
      val remoteContinuation: RemoteContinuation[ActorRef] = actorSystemStore.withValue(context.system) {
        new ObjectInputStream(new ByteArrayInputStream(remoteContinuationBuffer))
          .readObject()
          .asInstanceOf[RemoteContinuation[ActorRef]]
      }
      val value: Try[ActorRef] = Success(self)
      val actorSystem = context.system.asInstanceOf[Remote].actorSystem
      val release: UnitContinuation[Unit] = UnitContinuation.delay {
        actorSystem.stop(self)
      }
      val remoteContinuationParameter: Resource[UnitContinuation, Try[ActorRef]] = Resource(value, release)
      remoteContinuation(remoteContinuationParameter)
      sender ! Receipt(value)
  }
}

class Remote(val actorSystem: ActorSystem)(implicit val timeout: Timeout) extends Serializable {

  import Remote._
  import actorSystem.dispatcher

  val log = {
    implicit val logSource: LogSource[Remote] = new Serializable with LogSource[Remote] {
      override def genString(t: Remote): String = toString
    }
    Logging(actorSystem, this)
  }

  log.info(s"remote context $this constructed")

  def jump: Do[ActorRef] = Do.async { (remoteContinuation: RemoteContinuation[ActorRef]) =>
    {
      log.info(s"jump of $this is invoked")

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

  def writeReplace: Any = {
    log.info(s"writeReplace of $this is invoked")
    RemoteProxy
  }
}

case object Remote {
  type RemoteContinuation[A] = (Resource[UnitContinuation, Try[A]]) => Unit

  val actorSystemStore: DynamicVariable[ActorSystem] = new DynamicVariable[ActorSystem](null)

  object RemoteProxy extends Serializable {
    def readResolve: Any = {
      val actorSystem = actorSystemStore.value
      implicit val timeout: Timeout = FiniteDuration(10, SECONDS)
      val remote = new Remote(actorSystem)
      val log = {
        implicit val logSource: LogSource[Remote] = new Serializable with LogSource[Remote] {
          override def genString(t: Remote): String = toString
        }
        Logging(remote.actorSystem, remote)
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
            implicit val logSource: LogSource[Remote.this.type] = new Serializable with LogSource[Remote.this.type] {
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
