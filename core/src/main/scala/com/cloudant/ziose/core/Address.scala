// TODO: Rename the package to com.cloudant.ziose.core.address (consider the same for other files in the dir)
// or move case classes inside the trait or object
package com.cloudant.ziose.core

sealed trait Address {
  val workerId: Engine.WorkerId
  val workerNodeName: Symbol
}
// TODO: Remove capitalization when we get rid of ActorSystem.scala
// TODO: Make these definitions private
case class PID(pid: Codec.EPid, workerId: Engine.WorkerId, workerNodeName: Symbol)    extends Address
case class Name(name: Codec.EAtom, workerId: Engine.WorkerId, workerNodeName: Symbol) extends Address
case class NameOnNode private (name: Codec.EAtom, node: Codec.EAtom, workerId: Engine.WorkerId, workerNodeName: Symbol)
    extends Address

object Address {
  def fromPid(pid: Codec.EPid, workerId: Engine.WorkerId, workerNodeName: Symbol) = {
    PID(pid, workerId, workerNodeName)
  }

  def fromName(name: Codec.EAtom, workerId: Engine.WorkerId, workerNodeName: Symbol) = {
    Name(name, workerId, workerNodeName)
  }
  def fromRemoteName(name: Codec.EAtom, node: Codec.EAtom, workerId: Engine.WorkerId, workerNodeName: Symbol) = {
    NameOnNode(name, node, workerId, workerNodeName)
  }

}
