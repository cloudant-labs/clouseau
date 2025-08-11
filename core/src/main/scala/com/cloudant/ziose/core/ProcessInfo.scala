package com.cloudant.ziose.core

import zio._

import Codec._

case class ProcessInfo(
  pid: PID,
  name: Option[Symbol],
  tags: List[String],
  queueLength: Int
) {
  def asETerm = {
    val nameAtom = name.getOrElse(Symbol("none"))
    ETuple(
      EAtom("process_info"),
      Codec.fromScala(
        Map(
          Symbol("pid")               -> pid.pid,
          Symbol("name")              -> EAtom(nameAtom),
          Symbol("tags")              -> Codec.fromScala(tags),
          Symbol("message_queue_len") -> ENumber(queueLength)
        )
      )
    )
  }

  /*
   * This is used for cases where we print terms on the console and want to make it machine-readable.
   * Special handling is done to:
   *   - pid
   *   - binaries
   */
  def asPrettyPrintedETerm = {
    val nameAtom = name.getOrElse(Symbol("none"))
    ETuple(
      EAtom("process_info"),
      Codec.fromScala(
        Map(
          Symbol("pid")               -> EString(pid.pid.toString()),
          Symbol("name")              -> EAtom(nameAtom),
          Symbol("tags")              -> EList(tags.map(EString(_))),
          Symbol("message_queue_len") -> ENumber(queueLength)
        )
      )
    )
  }

}

object ProcessInfo {
  def from(actor: AddressableActor[_ <: Actor, _ <: ProcessContext]): UIO[ProcessInfo] = for {
    queueLength <- actor.messageQueueLength()
  } yield ProcessInfo(actor.self, actor.name.map(Symbol(_)), actor.getTags, queueLength)
  def valueFun(key: Symbol) = key match {
    case Symbol("message_queue_len") => Some((_: ProcessInfo).queueLength)
    case _                           => None
  }
}
