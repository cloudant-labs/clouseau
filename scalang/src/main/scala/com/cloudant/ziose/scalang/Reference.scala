package com.cloudant.ziose.scalang

import _root_.com.cloudant.ziose.core
import core.Codec.ERef
import core.Codec.ToScala
import core.Codec.FromScala

case class Reference(node: Symbol, ids: Seq[Int], creation: Int) extends FromScala {
  def fromScala: ERef = ERef(node.name, ids.toArray, creation)
}

object Reference extends ToScala[ERef] {
  def toScala(ref: ERef): Reference = {
    new Reference(Symbol(ref.node), ref.ids.toSeq, ref.creation)
  }
}
