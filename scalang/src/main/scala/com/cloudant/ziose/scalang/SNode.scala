package com.cloudant.ziose.scalang

import com.cloudant.ziose.core
import core.Address
import core.MessageEnvelope
import core.Codec
import java.util.concurrent.TimeUnit
import com.cloudant.ziose.macros.checkEnv
import zio.{&, Duration, Runtime, Tag, ZIO}

case class SNode(metricsRegistry: ScalangMeterRegistry)(implicit
  val runtime: Runtime[core.EngineWorker & core.Node]
) {
  type RegName  = Symbol
  type NodeName = Symbol

  def self(implicit adapter: Adapter[_, _]) = Pid.toScala(adapter.self.pid)

  /// We are not accessing this type directly in Clouseau
  type Mailbox = Unit

  /// spawnService is overridden by ClouseauNode
  def spawnService[TS <: Service[A] with core.Actor: Tag, A <: Product](builder: core.ActorBuilder.Sealed[TS])(implicit
    adapter: Adapter[_, _]
  ): core.Result[core.Node.Error, core.AddressableActor[TS, core.ProcessContext]] = ???
  def spawnService[TS <: Service[A] with core.Actor: Tag, A <: Product](
    builder: core.ActorBuilder.Sealed[TS],
    reentrant: Boolean
  )(implicit adapter: Adapter[_, _]): core.Result[core.Node.Error, core.AddressableActor[TS, core.ProcessContext]] = ???
  def spawnServiceZIO[TS <: Service[A] with core.Actor: Tag, A <: Product](
    builder: core.ActorBuilder.Sealed[TS]
  ): ZIO[core.EngineWorker with core.Node with core.ActorFactory, core.Node.Error, core.AddressableActor[_, _]] = ???
  def spawnServiceZIO[TS <: Service[A] with core.Actor: Tag, A <: Product](
    builder: core.ActorBuilder.Sealed[TS],
    reentrant: Boolean
  ): ZIO[core.EngineWorker with core.Node with core.ActorFactory, core.Node.Error, core.AddressableActor[_, _]] = ???

  def spawn[T <: Process](): Pid                = ???
  def spawn(fun: Mailbox => Unit): Pid          = ???
  def spawn[T <: Process](regName: String): Pid = ???
  def spawn[T <: Process](regName: Symbol): Pid = ???

  def cast(to: Pid, msg: Any)(implicit adapter: Adapter[_, _]) = {
    val address = Address.fromPid(to.fromScala, adapter.self.workerId)
    val envelope = MessageEnvelope.makeCast(
      Codec.EAtom("$gen_cast"),
      adapter.self.pid,
      address,
      adapter.fromScala(msg),
      adapter.self.workerId
    )
    for {
      _ <- adapter.cast(envelope)
    } yield ()
  }
  def cast(to: Symbol, msg: Any)(implicit adapter: Adapter[_, _]) = {
    val address = Address.fromName(Codec.EAtom(to), adapter.self.workerId)
    val envelope = MessageEnvelope.makeCast(
      Codec.EAtom("$gen_cast"),
      adapter.self.pid,
      address,
      adapter.fromScala(msg),
      adapter.self.workerId
    )
    for {
      _ <- adapter.cast(envelope)
    } yield ()
  }
  def cast(to: (RegName, NodeName), msg: Any)(implicit adapter: Adapter[_, _]) = {
    val (name, nodeName) = to
    val address          = Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(nodeName), adapter.self.workerId)
    val envelope = MessageEnvelope.makeCast(
      Codec.EAtom("$gen_cast"),
      adapter.self.pid,
      address,
      adapter.fromScala(msg),
      adapter.self.workerId
    )
    for {
      _ <- adapter.cast(envelope)
    } yield ()
  }
  /// This function is only used once in the test suite and it does nothing
  /// as far as I can tell
  /// TODO Ask Bob
  def spawnMbox: Mailbox = ()
  // def spawnMbox(regName : String) : Mailbox = ???
  // def spawnMbox(regName : Symbol) : Mailbox = ???
  def call(to: Pid, msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    call(self, to, msg)
  }
  def call(to: Pid, msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    call(self, to, msg, timeout)
  }
  def call(from: Pid, to: Pid, msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    val address = Address.fromPid(to.fromScala, adapter.self.workerId)
    val envelope = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      from.fromScala,
      address,
      adapter.fromScala(msg),
      None,
      adapter.self.workerId
    )
    for {
      result <- adapter.call(envelope)
    } yield {
      if (result.isSuccess) {
        adapter.toScala(result.getPayload.get)
      } else {
        (Symbol("error"), result.reason)
      }
    }
  }
  def call(from: Pid, to: Pid, msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    val address  = Address.fromPid(to.fromScala, adapter.self.workerId)
    val duration = Duration(timeout, TimeUnit.MILLISECONDS)
    val envelope = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      from.fromScala,
      address,
      adapter.fromScala(msg),
      Some(duration),
      adapter.self.workerId
    )
    for {
      result <- adapter.call(envelope)
    } yield {
      if (result.isSuccess) {
        adapter.toScala(result.getPayload.get)
      } else {
        (Symbol("error"), result.reason)
      }
    }
  }
  def call(to: Symbol, msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    call(self, to, msg)
  }
  def call(to: Symbol, msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    call(self, to, msg, timeout)
  }
  def call(from: Pid, to: Symbol, msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    val address = Address.fromName(Codec.EAtom(to), adapter.self.workerId)
    val envelope = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      from.fromScala,
      address,
      adapter.fromScala(msg),
      None,
      adapter.self.workerId
    )
    for {
      result <- adapter.call(envelope)
    } yield {
      if (result.isSuccess) {
        adapter.toScala(result.getPayload.get)
      } else {
        (Symbol("error"), result.reason)
      }
    }
  }
  def call(from: Pid, to: Symbol, msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = {
    // assume the pid is on the same worker
    val address  = Address.fromName(Codec.EAtom(to), adapter.self.workerId)
    val duration = Duration(timeout, TimeUnit.MILLISECONDS)
    val envelope = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      from.fromScala,
      address,
      adapter.fromScala(msg),
      Some(duration),
      adapter.self.workerId
    )
    for {
      result <- adapter.call(envelope)
    } yield {
      if (result.isSuccess) {
        adapter.toScala(result.getPayload.get)
      } else {
        (Symbol("error"), result.reason)
      }
    }
  }
  def call(to: (RegName, NodeName), msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    call(self, to, msg)
  }
  def call(to: (RegName, NodeName), msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = {
    call(self, to, msg, timeout)
  }
  def call(from: Pid, to: (RegName, NodeName), msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    val (name, nodeName) = to
    val address          = Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(nodeName), adapter.self.workerId)
    val envelope = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      adapter.self.pid,
      address,
      adapter.fromScala(msg),
      None,
      adapter.self.workerId
    )
    for {
      result <- adapter.call(envelope)
    } yield {
      if (result.isSuccess) {
        adapter.toScala(result.getPayload.get)
      } else {
        (Symbol("error"), result.reason)
      }
    }
  }
  def call(from: Pid, to: (RegName, NodeName), msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = {
    val (name, nodeName) = to
    val address          = Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(nodeName), adapter.self.workerId)
    val duration         = Duration(timeout, TimeUnit.MILLISECONDS)
    val envelope = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      adapter.self.pid,
      address,
      adapter.fromScala(msg),
      Some(duration),
      adapter.self.workerId
    )
    for {
      result <- adapter.call(envelope)
    } yield {
      if (result.isSuccess) {
        adapter.toScala(result.getPayload.get)
      } else {
        (Symbol("error"), result.reason)
      }
    }
  }
  def link(from: Pid, to: Pid) {
    // TODO
  }

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"metricsRegistry=$metricsRegistry",
    s"runtime=$runtime"
  )
}
