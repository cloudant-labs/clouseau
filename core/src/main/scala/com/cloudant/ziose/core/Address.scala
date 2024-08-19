// TODO: Rename the package to com.cloudant.ziose.core.address (consider the same for other files in the dir)
// or move case classes inside the trait or object
package com.cloudant.ziose.core

sealed trait Address {
  val workerId: Engine.WorkerId
  val workerNodeName: Symbol
  val isLocal: Boolean
  val isRemote: Boolean = !isLocal
}

case class PID(pid: Codec.EPid, workerId: Engine.WorkerId, workerNodeName: Symbol) extends Address {
  lazy val isLocal        = pid.node == workerNodeName
  override def toString() = s"<\"${workerId}:${workerNodeName.name}\", ${pid.toString()}>"
}
case class Name(name: Codec.EAtom, workerId: Engine.WorkerId, workerNodeName: Symbol) extends Address {
  val isLocal             = true
  override def toString() = s"<\"${workerId}:${workerNodeName.name}\", ${name.asString}>"
}
case class NameOnNode private (name: Codec.EAtom, node: Codec.EAtom, workerId: Engine.WorkerId, workerNodeName: Symbol)
    extends Address {
  lazy val isLocal        = node.atom == workerNodeName
  override def toString() = s"<\"${workerId}:${workerNodeName.name}\", ${name.asString}@${node.asString}>"
}

// TODO: Remove capitalization when we get rid of ActorSystem.scala
// TODO: Make these definitions private

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
