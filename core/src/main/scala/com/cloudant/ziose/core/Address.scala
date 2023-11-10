// TODO: Rename the package to com.cloudant.ziose.core.address (consider the same for other files in the dir)
// or move case classes inside the trait or object
package com.cloudant.ziose.core

sealed trait Address {
  val workerId: Engine.WorkerId
}
// TODO: Remove capitalization when we get rid of ActorSystem.scala
// TODO: Make these definitions private
case class PID(pid: Codec.EPid, workerId: Engine.WorkerId)                             extends Address
case class Name(name: Codec.EAtom, workerId: Engine.WorkerId)                          extends Address
case class NameOnNode(name: Codec.EAtom, node: Codec.EAtom, workerId: Engine.WorkerId) extends Address

object Address {
  def fromPid(pid: Codec.EPid, workerId: Engine.WorkerId)                             = PID(pid, workerId)
  def fromName(name: Codec.EAtom, workerId: Engine.WorkerId)                          = Name(name, workerId)
  def fromRemoteName(name: Codec.EAtom, node: Codec.EAtom, workerId: Engine.WorkerId) = NameOnNode(name, node, workerId)
}
