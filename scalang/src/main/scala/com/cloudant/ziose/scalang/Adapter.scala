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
  def unlink(to: Codec.EPid)          = ctx.unlink(to)
  def link(to: Codec.EPid)            = ctx.link(to)
  def monitor(monitored: Address)     = ctx.monitor(monitored)
  def demonitor(ref: Codec.ERef)      = ctx.demonitor(ref)
  def toScala(tuple: Codec.ETuple): Any = {
    factory.parse(tuple)(this) match {
      case Some(msg) => msg
      case None      => Codec.product(tuple.elems.map(toScala))
    }
  }
  def toScala(term: Codec.ETerm): Any = {
    Codec.toScala(
      term,
      {
        case tuple: Codec.ETuple => Some(toScala(tuple))
        case pid: Codec.EPid     => Some(Pid.toScala(pid))
        case ref: Codec.ERef     => Some(Reference.toScala(ref))
      }
    )
  }
  def fromScala(term: Any): Codec.ETerm = {
    Codec.fromScala(
      term,
      {
        case (alias @ Codec.EListImproper(Codec.EAtom("alias"), ref: Codec.ERef), reply: Any) =>
          Codec.ETuple(alias, reply.asInstanceOf[Codec.ETerm])
        case (ref: Codec.ERef, reply: Any) =>
          Codec.ETuple(makeTag(ref), fromScala(reply))
        case pid: Pid       => pid.fromScala
        case ref: Reference => ref.fromScala
      }
    )
  }

  // OTP uses improper list in `gen.erl`
  // https://github.com/erlang/otp/blob/master/lib/stdlib/src/gen.erl#L252C11-L252C20
  //  Tag = [alias | Mref],
  def makeTag(ref: Codec.ERef) = Codec.EListImproper(Codec.EAtom("alias"), ref)

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
    new Adapter(None, None, Some(factory), 1, Symbol("mock-node"))
  }
}
