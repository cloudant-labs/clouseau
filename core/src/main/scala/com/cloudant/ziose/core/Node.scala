package com.cloudant.ziose.core

import com.cloudant.ziose.macros.checkEnv
import zio.{Duration, FiberId, Scope, ZIO}

trait Node {
  def spawn[A <: Actor](
    builder: ActorBuilder.Sealed[A]
  ): ZIO[Node with Scope, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]]
  def close: ZIO[Node, _ <: Node.Error, Unit]
  def ping(nodeName: String, timeout: Option[Duration] = None): ZIO[Node, _ <: Node.Error, Boolean]
  // def register[E <: Node.Error](actor: Actor, name: String): ZIO[Node, E, Boolean]
  // testing only
  def listNames(): ZIO[Node, _ <: Node.Error, List[String]]
  // testing only
  def lookUpName(name: String): ZIO[Node, _ <: Node.Error, Option[Codec.EPid]]
  def stopActor(
    actor: AddressableActor[_ <: Actor, _ <: ProcessContext],
    reason: Option[Codec.ETerm] = None
  ): ZIO[Node, _ <: Node.Error, Unit]
  def monitorRemoteNode(name: String, timeout: Option[Duration] = None): ZIO[Node, _ <: Node.Error, Unit]
  def makeRef(): ZIO[Any, _ <: Node.Error, Codec.ERef]
}

object Node {
  trait Error extends Throwable

  object Error {
    case class Disconnected() extends Error
    case class NoSuchActor()  extends Error
    case class NameInUse(name: String) extends Error with Codec.FromScala {
      def fromScala: Codec.ETerm = Codec.fromScala((Symbol("name_in_use"), Symbol(name)))

      @checkEnv(System.getProperty("env"))
      def toStringMacro: List[String] = List(
        s"Error.${getClass.getSimpleName}",
        s"name=$name"
      )
    }
    case class ActorFailure()               extends Error
    case class SpawnFailure(err: Throwable) extends Error
    case class Constructor(err: Throwable) extends Error with Codec.FromScala {
      def fromScala: Codec.ETerm = Codec.fromScala((Symbol("bad_constructor"), err.getMessage()))

      @checkEnv(System.getProperty("env"))
      def toStringMacro: List[String] = List(
        s"Error.${getClass.getSimpleName}",
        s"err=${err.getMessage}"
      )
    }
    case class Unknown(err: Throwable)     extends Error
    case class Interrupt(fiberId: FiberId) extends Error
    case class Nothing()                   extends Error
    case class Timeout(duration: Duration) extends Error
  }

  def spawn[A <: Actor](
    builder: ActorBuilder.Sealed[A]
  ): ZIO[Node with Scope, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
    ZIO.serviceWithZIO[Node](_.spawn(builder))
  }

  def close: ZIO[Node, _ <: Node.Error, Unit] = {
    ZIO.serviceWithZIO[Node](_.close)
  }

  def ping(nodeName: String, timeout: Option[Duration] = None): ZIO[Node, _ <: Node.Error, Boolean] = {
    ZIO.serviceWithZIO[Node](_.ping(nodeName, timeout))
  }

  // def register[E <: Node.Error](actor: Actor, name: String): ZIO[Node, E, Boolean] =
  //   ZIO.serviceWithZIO[Node](_.register(actor, name))

  // testing only
  def listNames(): ZIO[Node, _ <: Node.Error, List[String]] = {
    ZIO.serviceWithZIO[Node](_.listNames())
  }

  // testing only
  def lookUpName(name: String): ZIO[Node, _ <: Node.Error, Option[Codec.EPid]] = {
    ZIO.serviceWithZIO[Node](_.lookUpName(name))
  }

  def stopActor(
    actor: AddressableActor[_ <: Actor, _ <: ProcessContext],
    reason: Option[Codec.ETerm]
  ): ZIO[Node, _ <: Node.Error, Unit] = {
    ZIO.serviceWithZIO[Node](_.stopActor(actor, reason))
  }
}
