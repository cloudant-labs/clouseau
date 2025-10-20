package com.cloudant.ziose.core

import com.ericsson.otp.erlang.{OtpMsg, OtpErlangPid, OtpErlangException, OtpErlangExit}
import Codec.{EAtom, EBinary, EListImproper, EPid, ERef, ETerm, ETuple}
import zio._

sealed trait MessageEnvelope extends WithWorkerId[Engine.WorkerId] {
  val from: Option[EPid]
  val to: Address
  val workerId: Engine.WorkerId
  val workerNodeName: Symbol
  def getPayload: Option[ETerm]
  def redirect(mapFn: Address => Address): MessageEnvelope
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
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }

  case class Link(from: Option[EPid], to: Address, private val base: Address) extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = None
    // when we forward the `Link` event we need to replace the `to` address so our message
    // would reach the local actor
    // Assume `PID` here and also that the dest `PID` is on the same worker
    def forward = Link(Some(to.asInstanceOf[PID].pid), Address.fromPid(from.get, workerId, workerNodeName), base)
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }
  case class Send(from: Option[EPid], to: Address, payload: ETerm, private val base: Address) extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = Some(payload)
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }
  case class Exit(from: Option[EPid], to: Address, reason: ETerm, private val base: Address) extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = Some(reason)
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }
  case class Unlink(from: Option[EPid], to: Address, id: Long, private val base: Address) extends MessageEnvelope {
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def getPayload                = None
    // when we forward the `Unlink` event we need to replace the `to` address so our message
    // would reach the local actor
    // Assume `PID` here and also that the dest `PID` is on the same worker
    def forward = Unlink(Some(to.asInstanceOf[PID].pid), Address.fromPid(from.get, workerId, workerNodeName), id, base)
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }

  case class Call(
    from: Option[EPid],
    to: Address,
    tag: EAtom,
    ref: ERef,
    replyRef: ETerm,
    payload: ETerm,
    timeout: Option[Duration],
    private val base: Address
  ) extends MessageEnvelope {
    def getPayload                = Some(ETuple(tag, ETuple(from.get, replyRef), payload))
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }

  case class Cast(
    from: Option[EPid],
    to: Address,
    tag: EAtom,
    payload: ETerm,
    private val base: Address
  ) extends MessageEnvelope {
    def getPayload                = Some(payload)
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }

  case class Response(
    from: Option[EPid],
    to: Address,
    tag: EAtom,
    ref: ERef,
    replyRef: ETerm,
    payload: Option[ETerm],
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
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }

  object Response {
    def make(from: PID, caller: Codec.ETerm, payload: Codec.ETerm) = {
      val (to, replyRef, ref) = caller match {
        case ETuple(to: EPid, replyRef: ETerm, ref: ERef) =>
          (to, replyRef, ref)
        case t =>
          throw new Throwable("Invalid caller")
      }
      val address = Address.fromPid(to, from.workerId, from.workerNodeName)
      Response(
        from = Some(from.pid),
        to = address,
        tag = Codec.EAtom("$gen_call"),
        ref = ref,
        replyRef = replyRef,
        payload = Some(payload),
        reason = None,
        base = from
      )
    }

    def timeout(msg: Call) = {
      error(msg, Node.Error.Timeout(msg.timeout.get))
    }

    def error(msg: Call, reason: Node.Error) = {
      // Call envelopes always have from, so it is safe to call `.get`
      val base = Address.fromPid(msg.from.get, msg.workerId, msg.workerNodeName)
      Response(
        from = msg.from,
        to = msg.to,
        tag = msg.tag,
        ref = msg.ref,
        replyRef = msg.replyRef,
        payload = None,
        reason = Some(reason),
        base = base
      )
    }

  }

  case class MonitorExit(
    from: Option[EPid],
    to: Address,
    ref: ERef,
    reason: ETerm,
    private val base: Address
  ) extends MessageEnvelope {
    def getPayload                = Some(ETuple(EAtom("DOWN"), ref, EAtom("process"), from.get, reason))
    val workerId: Engine.WorkerId = base.workerId
    val workerNodeName: Symbol    = base.workerNodeName
    def redirect(mapFn: Address => Address): MessageEnvelope = {
      copy(to = mapFn(to))
    }
  }

  // For debugging and testing only
  def makeSend(recipient: Address, msg: ETerm, address: Address) = {
    Send(None, recipient, msg, address)
  }

  def makeRegSend(from: EPid, recipient: Address, msg: ETerm, address: Address) = {
    Send(Some(from), recipient, msg, address)
  }

  def makeCall(
    recipient: Address,
    fromTag: ETerm,
    msg: ETerm,
    timeout: Option[Duration]
  ) = {
    fromTag match {
      case ETuple(from: EPid, replyRef @ EListImproper(EAtom("alias"), ref: ERef)) =>
        Some(Call(Some(from), recipient, Codec.EAtom("$gen_call"), ref, replyRef, msg, timeout, recipient))
      case ETuple(from: EPid, ref: ERef) =>
        Some(Call(Some(from), recipient, Codec.EAtom("$gen_call"), ref, ref, msg, timeout, recipient))
      case _ =>
        None
    }
  }

  def makeCast(tag: EAtom, from: EPid, recipient: Address, msg: ETerm, address: Address) = {
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
          case m @ EBinary(str) if str.startsWith("Exception") => m.asPrintable
          case m                                               => m
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
    pid: EPid,
    address: Address
  ): MessageEnvelope = {
    def encodeStackTrace(stackTrace: Array[java.lang.StackTraceElement]) = {
      EBinary(stackTrace.map(_.toString).mkString("\n"))
    }
    def camelToUnderscores(name: String) = "[A-Z\\d]".r
      .replaceAllIn(
        name,
        m => "_" + m.group(0).toLowerCase()
      )
      .stripPrefix("_")

    val (from, reason) = exception match {
      case exit: OtpErlangExit =>
        (Some(Codec.fromErlang(exit.pid).asInstanceOf[EPid]), Codec.fromErlang(exit.reason))
      case e: OtpErlangException =>
        (
          None,
          ETuple(
            EAtom(camelToUnderscores(e.getClass().getSimpleName)),
            EBinary(e.getMessage),
            encodeStackTrace(e.getStackTrace)
          )
        )
    }
    MessageEnvelope.Exit(from, Address.fromPid(pid, address.workerId, address.workerNodeName), reason, address)
  }

  private def getSenderPid(msg: OtpMsg): EPid = {
    Codec.fromErlang(msg.getSenderPid()).asInstanceOf[EPid]
  }

  private def getRecipientPid(msg: OtpMsg): EPid = {
    Codec.fromErlang(msg.getRecipientPid()).asInstanceOf[EPid]
  }

  private def getRef(msg: OtpMsg): ERef = {
    Codec.fromErlang(msg.getRef()).asInstanceOf[ERef]
  }

  private def makePidAddress(pid: OtpErlangPid, address: Address): Address = {
    val term = Codec.fromErlang(pid).asInstanceOf[EPid]
    PID(term, address.workerId, address.workerNodeName).asInstanceOf[Address]
  }

  private def makeNameAddress(name: Symbol, address: Address): Address = {
    Name(EAtom(name), address.workerId, address.workerNodeName).asInstanceOf[Address]
  }

  private def getRecipient(msg: OtpMsg, address: Address): Address = {
    msg.getRecipientName() match {
      case null => makePidAddress(msg.getRecipientPid(), address)
      case name => makeNameAddress(Symbol(name), address)
    }
  }

  private def getMsg(msg: OtpMsg): ETerm = {
    Codec.fromErlang(msg.getMsg())
  }

  def extractCallerTag(msg: MessageEnvelope): Option[ETuple] = {
    msg.getPayload match {
      case Some(
            ETuple(
              EAtom("$gen_call"),
              // Match on either
              // - {pid(), ref()}
              // - {pid(), [alias | ref()]}
              fromTag @ ETuple(from: EPid, _ref),
              _
            )
          ) =>
        Some(fromTag)
      case _ =>
        None
    }
  }
}
