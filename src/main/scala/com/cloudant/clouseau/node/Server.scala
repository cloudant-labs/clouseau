package com.cloudant.clouseau.node
import com.ericsson.otp.erlang._
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger;

object Gen {
  val call = new OtpErlangAtom("$gen_call")
  val cast = new OtpErlangAtom("$gen_cast")
  val info = new OtpErlangAtom("$gen_info")
}

trait GenServer {
  val mailbox: OtpMbox
  val config: Configuration
  val logger: Logger

  def processMailbox(): Boolean = {
    try {
      val msg = mailbox.receiveMsg(0L)
      this.handle(msg)

    } catch {
      case _ex: InterruptedException => {
        return false;
      }
    }
    true
  }

  def handle(msg: OtpMsg) = msg.`type`() match {
    case OtpMsg.sendTag | OtpMsg.regSendTag =>
      val msgObj = msg.getMsg()
      val tuple = msgObj.asInstanceOf[OtpErlangTuple]
      val msgType = tuple.elementAt(0).asInstanceOf[OtpErlangAtom]
      if (msgType.equals(Gen.call)) {

      } else if (msgType.equals(Gen.cast)) {

      } else if (msgType.equals(Gen.info)) {

      } else {
        logger.error("Unknown message received " + msgType.toString())
      }
  }

  def call(mailbox: Any, msg: Any): Any = {
    true
  }

  def handleCall(msg: Any): Unit
  def handleCast(msg: Any): Unit
  def handleInfo(msg: Any): Unit
}

//abstract class Server(mailbox: OtpMbox, config: CompositeConfiguration) {
//
//  val logger = Logger.getLogger("clouseau.server")
//
//  def processMailbox(): Boolean = {
//    try {
//      val msg = mailbox.receiveMsg(0L)
//      this.handle(msg)
//
//    } catch {
//      case ex: InterruptedException => {
//        return false;
//      }
//    }
//    true
//  }
//
//  def handle(msg: OtpMsg ) = msg.`type`() match {
//      case OtpMsg.sendTag | OtpMsg.regSendTag =>
//        val msgObj = msg.getMsg()
//        val tuple = msgObj.asInstanceOf[OtpErlangTuple]
//        val msgType = tuple.elementAt(0).asInstanceOf[OtpErlangAtom]
//        if (msgType.equals(Gen.call)) {
//
//        } else if (msgType.equals(Gen.cast)) {
//
//        } else if (msgType.equals(Gen.info)) {
//
//        } else {
//          logger.error("Unknown message received " + msgType.toString())
//        }
//  }
//
//  def handleCall(msg: Any): Unit
//  def handleCast(msg: Any): Unit
//  def handleInfo(msg: Any): Unit
//
//
//
//}
