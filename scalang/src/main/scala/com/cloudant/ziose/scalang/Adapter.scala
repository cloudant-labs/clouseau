package com.cloudant.ziose.scalang

import com.cloudant.ziose.core.{Address, Codec, MessageEnvelope, PID, ProcessContext}
import com.cloudant.ziose.macros.checkEnv
import zio.Runtime
import com.cloudant.ziose.core.Engine

case object InvalidAdapter extends Exception

class Adapter[C <: ProcessContext, F <: TypeFactory] private (
  process_ctx: Option[C],
  snode: Option[SNode],
  type_factory: Option[F],
  val workerId: Engine.WorkerId,
  val workerNodeName: Symbol
) {
  def ctx: C      = process_ctx.getOrElse(throw InvalidAdapter)
  def node: SNode = snode.getOrElse(throw InvalidAdapter)
  def factory: F  = type_factory.getOrElse(throw InvalidAdapter)

  def runtime: Runtime[Any] = snode match {
    case Some(node) => node.runtime
    case None       => Runtime.default
  }

  def name: Option[String]            = ctx.name
  def self: PID                       = ctx.self
  def call(msg: MessageEnvelope.Call) = ctx.call(msg)
  def cast(msg: MessageEnvelope.Cast) = ctx.cast(msg)
  def send(msg: MessageEnvelope.Send) = ctx.send(msg)
  def exit(reason: Codec.ETerm)       = ctx.exit(reason)
  def exit(msg: MessageEnvelope.Exit) = ctx.exit(msg)
  def unlink(to: Codec.EPid)          = ctx.unlink(to)
  def link(to: Codec.EPid)            = ctx.link(to)
  def monitor(monitored: Address)     = ctx.monitor(monitored)
  def demonitor(ref: Codec.ERef)      = ctx.demonitor(ref)
  def lookUpName(name: String)        = ctx.lookUpName(name)
  def toScala(term: Codec.ETerm): Any = {
    term match {
      case tuple: Codec.ETuple =>
        factory.parse(tuple)(this) match {
          case Some(msg) => msg
          case None      => Codec.toScala(term, factory.toScala)
        }
      case _ => Codec.toScala(term, factory.toScala)
    }
  }
  def fromScala(term: Any): Codec.ETerm = {
    Codec.fromScala(term, factory.fromScala)
  }

  def forkScoped[R, E, A](effect: zio.ZIO[R, E, A]): zio.URIO[R, zio.Fiber.Runtime[E, A]] = ctx.forkScoped(effect)

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"ctx=$ctx",
    s"node=$node",
    s"factory=$factory",
    s"runtime=$runtime",
    s"name=$name",
    s"self=$self"
  )
}

object Adapter {
  def apply[C <: ProcessContext, F <: TypeFactory](ctx: C, node: SNode, factory: F): Adapter[C, F] = {
    new Adapter(Some(ctx), Some(node), Some(factory), ctx.id.workerId, ctx.id.workerNodeName)
  }

  val mockAdapter: Adapter[_, _] = new Adapter(None, None, None, 1, Symbol("mock-node"))
  def mockAdapterWithFactory[F <: TypeFactory](factory: F): Adapter[_, _] = {
    new Adapter(None, None, Some(factory), 1, Symbol("mock-node")) {
      override def toString: String = s"MockOf[Adapter]"
    }
  }
}
