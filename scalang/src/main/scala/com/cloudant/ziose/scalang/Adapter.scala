package com.cloudant.ziose.scalang

import _root_.com.cloudant.ziose.core
import core.ProcessContext
import core.MessageEnvelope
import core.Codec

case class Adapter[C <: ProcessContext, F <: TypeFactory](ctx: C, node: SNode, factory: F) {
  val runtime                              = node.runtime
  def name                                 = ctx.name
  def self                                 = ctx.self
  def call(msg: core.MessageEnvelope.Call) = ctx.call(msg)
  def cast(msg: MessageEnvelope.Cast)      = ctx.cast(msg)
  def send(msg: MessageEnvelope.Send)      = ctx.send(msg)
  def exit(reason: core.Codec.ETerm)       = ctx.exit(reason)
  def unlink(to: Codec.EPid)               = ctx.unlink(to)
  def link(to: Codec.EPid)                 = ctx.link(to)
  def monitor(monitored: core.Address)     = ctx.monitor(monitored)
  def demonitor(ref: Codec.ERef)           = ctx.demonitor(ref)
  def makeRef()                            = ctx.makeRef()
  def toScala(term: Codec.ETerm): Any = {
    factory.parse(term)(this) match {
      case Some(msg) => msg
      case None      => Codec.toScala(term)
    }
  }
}
