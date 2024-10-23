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
  val workerNodeName: Symbol
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
  case class Init(to: Address) extends MessageEnvelope {
    val from                      = None
    val workerId: Engine.WorkerId = to.workerId
    val workerNodeName: Symbol    = to.workerNodeName
    def getPayload                = None
  }

  case class Link(from: Option[Codec.EPid], to: Address, private val base: Address) extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = None
  }
  case class Send(from: Option[Codec.EPid], to: Address, payload: Codec.ETerm, private val base: Address)
      extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = Some(payload)
  }
  case class Exit(from: Option[Codec.EPid], to: Address, reason: Codec.ETerm, private val base: Address)
      extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = Some(reason)
  }
  case class Unlink(from: Option[Codec.EPid], to: Address, id: Long, private val base: Address)
      extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = None
  }

  case class Call(
    from: Option[Codec.EPid],
    to: Address,
    tag: Codec.EAtom,
    payload: Codec.ETerm,
    timeout: Option[Duration],
    private val base: Address
  ) extends MessageEnvelope {
    def getPayload                = Some(payload)
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
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
            base = base
          )
        case None if timeout.isDefined =>
          Response(
            from = from,
            to = to,
            tag = tag,
            payload = None,
            reason = Some(Node.Error.Timeout(timeout.get)),
            base = base
          )
        case None =>
          Response(
            from = from,
            to = to,
            tag = tag,
            payload = None,
            reason = Some(Node.Error.Nothing()),
            base = base
          )
      }
    }
  }
  case class Cast(
    from: Option[Codec.EPid],
    to: Address,
    tag: Codec.EAtom,
    payload: Codec.ETerm,
    private val base: Address
  ) extends MessageEnvelope {
    def getPayload                = Some(payload)
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
  }
  case class Response(
    from: Option[Codec.EPid],
    to: Address,
    tag: Codec.EAtom,
    payload: Option[Codec.ETerm],
    private val base: Address,
    reason: Option[_ <: Node.Error]
  ) extends MessageEnvelope {
    def getPayload                = payload
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def isError                   = reason.isDefined
    def isSuccess                 = reason.isEmpty
    def getCaller                 = from.get
    // when makeCall is used the Address is a PID
    def getCallee = to.asInstanceOf[PID].pid
  }

  case class MonitorExit(
    from: Option[Codec.EPid],
    to: Address,
    ref: Codec.ERef,
    reason: Codec.ETerm,
    private val base: Address
  ) extends MessageEnvelope {
    def getPayload = Some(Codec.ETuple(Codec.EAtom("DOWN"), ref, Codec.EAtom("process"), from.get, reason))
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
  }

  // For debugging and testing only
  def makeSend(recipient: Address, msg: Codec.ETerm, address: Address) = {
    Send(None, recipient, msg, address)
  }

  def makeRegSend(from: Codec.EPid, recipient: Address, msg: Codec.ETerm, address: Address) = {
    Send(Some(from), recipient, msg, address)
  }

  def makeCall(
    tag: Codec.EAtom,
    from: Codec.EPid,
    recipient: Address,
    msg: Codec.ETerm,
    timeout: Option[Duration],
    address: Address
  ) = {
    Call(Some(from), recipient, tag, msg, timeout, address)
  }

  def makeCast(tag: Codec.EAtom, from: Codec.EPid, recipient: Address, msg: Codec.ETerm, address: Address) = {
    Cast(Some(from), recipient, tag, msg, address)
  }

  def fromOtpMsg(msg: OtpMsg, address: Address): MessageEnvelope = {
    val tag = msg.`type`()
    tag match {
      case OtpMsg.linkTag => Link(Some(getSenderPid(msg)), getRecipient(msg, address), address)
      case OtpMsg.sendTag => Send(None, getRecipient(msg, address), getMsg(msg), address)
      case OtpMsg.exitTag =>
        Exit(Some(getSenderPid(msg)), getRecipient(msg, address), getMsg(msg), address)
      // The unlinkId is not exposed. However it should be handled by OtpMbox.deliver
      // case OtpMsg.unlinkTag => Unlink(Some(getSenderPid(msg)), getRecipientPid(msg), ???)
      case OtpMsg.regSendTag =>
        Send(Some(getSenderPid(msg)), getRecipient(msg, address), getMsg(msg), address)
      case OtpMsg.exit2Tag =>
        Exit(Some(getSenderPid(msg)), getRecipient(msg, address), getMsg(msg), address)
      case OtpMsg.monitorExitTag => {
        val reason = getMsg(msg) match {
          case m @ Codec.EBinary(str) if str.startsWith("Exception") => m.asPrintable
          case m                                                     => m
        }
        MonitorExit(Some(getSenderPid(msg)), getRecipient(msg, address), getRef(msg), reason, address)
      }
    }
  }

  /*
   * Creates Exit from an Exception
   *   All `OtpErlangException` other then `OtpErlangExit` are converted to tuples
   *     (exception_name(), exception_message(), stack_trace())
   * Where:
   *   - exception_name() is an EAtom which contains original name of the exception as defined in
   *     jInterface converted into underscore convention
   *     - OtpErlangDecodeException -> otp_erlang_decode_exception
   *     - OtpErlangRangeException  -> otp_erlang_range_exception
   *   - exception_name() -> EBinary containing error message produced by jInterface
   *   - stack_trace() -> EBinary containing textual representation of a stack trace
   * OtpErlangException
   */
  def fromOtpException(
    exception: OtpErlangException,
    pid: Codec.EPid,
    address: Address
  ): MessageEnvelope = {
    def encodeStackTrace(stackTrace: Array[java.lang.StackTraceElement]) = {
      Codec.EBinary(stackTrace.map(_.toString).mkString("\n"))
    }
    def camelToUnderscores(name: String) = "[A-Z\\d]".r
      .replaceAllIn(
        name,
        m => "_" + m.group(0).toLowerCase()
      )
      .stripPrefix("_")

    val (from, reason) = exception match {
      case exit: OtpErlangExit =>
        (Some(Codec.fromErlang(exit.pid).asInstanceOf[Codec.EPid]), Codec.fromErlang(exit.reason))
      case e: OtpErlangException =>
        (
          None,
          Codec.ETuple(
            Codec.EAtom(camelToUnderscores(e.getClass().getSimpleName)),
            Codec.EBinary(e.getMessage),
            encodeStackTrace(e.getStackTrace)
          )
        )
    }
    MessageEnvelope.Exit(from, Address.fromPid(pid, address.workerId, address.workerNodeName), reason, address)
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

  private def makePidAddress(pid: OtpErlangPid, address: Address): Address = {
    val term = Codec.fromErlang(pid).asInstanceOf[Codec.EPid]
    PID(term, address.workerId, address.workerNodeName).asInstanceOf[Address]
  }

  private def makeNameAddress(name: Symbol, address: Address): Address = {
    Name(Codec.EAtom(name), address.workerId, address.workerNodeName).asInstanceOf[Address]
  }

  private def getRecipient(msg: OtpMsg, address: Address): Address = {
    msg.getRecipientName() match {
      case null => makePidAddress(msg.getRecipientPid(), address)
      case name => makeNameAddress(Symbol(name), address)
    }
  }

  private def getMsg(msg: OtpMsg): Codec.ETerm = {
    Codec.fromErlang(msg.getMsg())
  }
}
