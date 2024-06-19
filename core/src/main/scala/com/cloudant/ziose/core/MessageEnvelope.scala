package com.cloudant.ziose.core

import com.ericsson.otp.erlang.{
  OtpMsg,
  OtpErlangPid,
  OtpErlangException,
  OtpErlangExit,
  OtpErlangDecodeException,
  OtpErlangRangeException
}
import zio._

sealed trait MessageEnvelope extends WithWorkerId[Engine.WorkerId] {
  val from: Option[Codec.EPid]
  val to: Address
  val workerId: Engine.WorkerId
  def getPayload: Option[Codec.ETerm]
}

trait WithWorkerId[I] {
  val workerId: I
}

/*
TODO: Implement builder pattern
 */
object MessageEnvelope {
  // TODO we need a builder pattern for these

  case class Link(from: Option[Codec.EPid], to: Address, workerId: Engine.WorkerId) extends MessageEnvelope {
    def getPayload = None
  }
  case class Send(from: Option[Codec.EPid], to: Address, payload: Codec.ETerm, workerId: Engine.WorkerId)
      extends MessageEnvelope {
    def getPayload = Some(payload)
  }
  case class Exit(from: Option[Codec.EPid], to: Address, reason: Codec.ETerm, workerId: Engine.WorkerId)
      extends MessageEnvelope {
    def getPayload = Some(reason)
  }
  case class Unlink(from: Option[Codec.EPid], to: Address, id: Long, workerId: Engine.WorkerId)
      extends MessageEnvelope {
    def getPayload = None
  }

  case class Call(
    from: Option[Codec.EPid],
    to: Address,
    tag: Codec.EAtom,
    payload: Codec.ETerm,
    timeout: Option[Duration],
    workerId: Engine.WorkerId
  ) extends MessageEnvelope {
    def getPayload                = Some(payload)
    val workerId: Engine.WorkerId = base.workerId
    def toSend(f: Codec.ETerm => Codec.ETerm): MessageEnvelope = {
      Call(from, to, tag, f(payload), timeout, base)
    }
    def toResponse(payload: Option[Codec.ETerm]): Response = {
      payload match {
        case Some(p) =>
          Response(
            from = from,
            to = to,
            tag = tag,
            payload = Some(p),
            reason = None,
            workerId = workerId
          )
        case None if timeout.isDefined =>
          Response(
            from = from,
            to = to,
            tag = tag,
            payload = None,
            reason = Some(Node.Error.Timeout(timeout.get)),
            workerId = workerId
          )
        case None =>
          Response(
            from = from,
            to = to,
            tag = tag,
            payload = None,
            reason = Some(Node.Error.Nothing()),
            workerId = workerId
          )
      }
    }
  }
  case class Cast(
    from: Option[Codec.EPid],
    to: Address,
    tag: Codec.EAtom,
    payload: Codec.ETerm,
    workerId: Engine.WorkerId
  ) extends MessageEnvelope {
    def getPayload = Some(payload)
  }
  case class Response(
    from: Option[Codec.EPid],
    to: Address,
    tag: Codec.EAtom,
    payload: Option[Codec.ETerm],
    workerId: Engine.WorkerId,
    reason: Option[_ <: Node.Error]
  ) extends MessageEnvelope {
    def getPayload = payload
    def isError    = reason.isDefined
    def isSuccess  = reason.isEmpty
    def getCaller  = from.get
    // when makeCall is used the Address is a PID
    def getCallee = to.asInstanceOf[PID].pid
  }

  case class Monitor(from: Option[Codec.EPid], to: Address, ref: Codec.ERef, workerId: Engine.WorkerId)
      extends MessageEnvelope {
    def getPayload = None
  }

  case class Demonitor(from: Option[Codec.EPid], to: Address, ref: Codec.ERef, workerId: Engine.WorkerId)
      extends MessageEnvelope {
    def getPayload = None
  }

  // For debugging and testing only
  def makeSend(recipient: Address, msg: Codec.ETerm, workerId: Engine.WorkerId) = {
    Send(None, recipient, msg, workerId)
  }

  def makeRegSend(from: Codec.EPid, recipient: Address, msg: Codec.ETerm, workerId: Engine.WorkerId) = {
    Send(Some(from), recipient, msg, workerId)
  }

  def makeCall(
    tag: Codec.EAtom,
    from: Codec.EPid,
    recipient: Address,
    msg: Codec.ETerm,
    timeout: Option[Duration],
    workerId: Engine.WorkerId
  ) = {
    Call(Some(from), recipient, tag, msg, timeout, workerId)
  }

  def makeCast(tag: Codec.EAtom, from: Codec.EPid, recipient: Address, msg: Codec.ETerm, workerId: Engine.WorkerId) = {
    Cast(Some(from), recipient, tag, msg, workerId)
  }

  def fromOtpMsg(msg: OtpMsg, workerId: Engine.WorkerId): MessageEnvelope = {
    val tag = msg.`type`()
    tag match {
      case OtpMsg.linkTag => Link(Some(getSenderPid(msg)), getRecipient(msg, workerId), workerId)
      case OtpMsg.sendTag => Send(None, getRecipient(msg, workerId), getMsg(msg), workerId)
      case OtpMsg.exitTag => Exit(Some(getSenderPid(msg)), getRecipient(msg, workerId), getMsg(msg), workerId)
      // The unlinkId is not exposed. However it should be handled by OtpMbox.deliver
      // case OtpMsg.unlinkTag => Unlink(Some(getSenderPid(msg)), getRecipientPid(msg), ???)
      case OtpMsg.regSendTag   => Send(Some(getSenderPid(msg)), getRecipient(msg, workerId), getMsg(msg), workerId)
      case OtpMsg.exit2Tag     => Exit(Some(getSenderPid(msg)), getRecipient(msg, workerId), getMsg(msg), workerId)
      case OtpMsg.monitorTag   => Monitor(Some(getSenderPid(msg)), getRecipient(msg, workerId), getRef(msg), workerId)
      case OtpMsg.demonitorTag => Demonitor(Some(getSenderPid(msg)), getRecipient(msg, workerId), getRef(msg), workerId)
    }
  }

  def fromOtpException(exception: OtpErlangException, pid: Codec.EPid, workerId: Engine.WorkerId): MessageEnvelope = {
    val address = Address.fromPid(pid, workerId)
    val (from, reason) = exception match {
      case exit: OtpErlangExit =>
        (Some(Codec.fromErlang(exit.pid).asInstanceOf[Codec.EPid]), Codec.fromErlang(exit.reason))
      case decode: OtpErlangDecodeException =>
        (None, Codec.EAtom("term_decode_error"))
      case range: OtpErlangRangeException =>
        (None, Codec.EAtom("term_range_error"))
    }
    MessageEnvelope.Exit(from, address, reason, 0)
  }

  private def getSenderPid(msg: OtpMsg): Codec.EPid = {
    Codec.fromErlang(msg.getSenderPid()).asInstanceOf[Codec.EPid]
  }

  private def getRecipientPid(msg: OtpMsg): Codec.EPid = {
    Codec.fromErlang(msg.getRecipientPid()).asInstanceOf[Codec.EPid]
  }

  private def getRef(msg: OtpMsg): Codec.ERef = {
    Codec.fromErlang(msg.getRef()).asInstanceOf[Codec.ERef]
  }

  private def makePidAddress(pid: OtpErlangPid, workerId: Engine.WorkerId): Address = {
    val term = Codec.fromErlang(pid).asInstanceOf[Codec.EPid]
    PID(term, workerId).asInstanceOf[Address]
  }

  private def makeNameAddress(name: Symbol, workerId: Engine.WorkerId): Address = {
    Name(Codec.EAtom(name), workerId).asInstanceOf[Address]
  }

  private def getRecipient(msg: OtpMsg, workerId: Engine.WorkerId): Address = {
    msg.getRecipientName() match {
      case null => makePidAddress(msg.getRecipientPid(), workerId)
      case name => makeNameAddress(Symbol(name), workerId)
    }
  }

  private def getMsg(msg: OtpMsg): Codec.ETerm = {
    Codec.fromErlang(msg.getMsg())
  }
}
