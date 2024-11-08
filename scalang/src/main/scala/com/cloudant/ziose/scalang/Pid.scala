package com.cloudant.ziose.scalang

import scala.language.implicitConversions

import _root_.com.cloudant.ziose.core
import core.Codec.EPid
import core.Codec.ToScala
import core.Codec.FromScala
import com.cloudant.ziose.macros.CheckEnv

case class Pid(node: Symbol, id: Int, serial: Int, creation: Int) extends FromScala {
  def fromScala = EPid(node.name, id, serial, creation)

  def toErlangString: String = {
    "<" + id + "." + serial + "." + creation + ">"
  }

  /*
  This method is used to do an implicit conversion of EPid into Pid
   */
  def unapply(pid: EPid) = {
    Pid.e2pid(pid)
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"node=$node",
    s"id=$id",
    s"serial=$serial",
    s"creation=$creation"
  )
}

object Pid extends ToScala[EPid] {
  /*
   * A combination of AddressableActor.unapply and this implicit conversion allow us to write
   *
   * ```scala
   * node.spawn(...) match {
   *   case (Symbol("ok"), pid: Pid) => ...
   * }
   * ```
   *
   * Here the unapply would convert AddressableActor to core.PID
   * and then implicit type convertor `address2pid` would convert core.PID to `scalang.Pid`
   */

  implicit def address2pid(address: core.PID): Pid = Pid.toScala(address.pid)
  implicit def e2pid(pid: EPid): Pid               = Pid.toScala(pid)
  override def toScala(pid: EPid): Pid = {
    Pid(pid.node, pid.id, pid.serial, pid.creation)
  }
}
