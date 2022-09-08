package com.cloudant.zio.actors

import _root_.com.ericsson.otp.erlang._;

trait TypeFactory {
  def createType(name: Symbol, arity: Int, reader: Any): Option[ClouseauMessage]
  def parse(term: Codec.ETerm): Option[ClouseauMessage]
}

class TermReader // we could just have a reference to mailbox here
// but we should not remove abstraction

sealed trait ClouseauMessage
case class OpenIndexMsg(peer: Codec.EPid, path: String, options: Any) extends ClouseauMessage

object ClouseauMessage {
  implicit def apply(peer: Codec.EPid, path: String, options: Any): ClouseauMessage =
    OpenIndexMsg(peer: Codec.EPid, path: String, options: Any)
}

object ClouseauTypeFactory extends TypeFactory {
  def createType(name: Symbol, arity: Int, _reader: Any): Option[ClouseauMessage] =
    (name, arity) match {
      case (Symbol("open"), 4) => {
        // I'll keep this comment for now to remind myself how it is done in original Scalang
        // Some(OpenIndexMsg(reader.readAs[Pid], reader.readAs[String], reader.readTerm))
        None // FIXME by properly constructing OpenIndexMsg message
      }
    }
  def parse(term: Codec.ETerm): Option[ClouseauMessage] =
    term match {
      case Codec.ETuple(List(Codec.EAtom(Symbol("open")), pid, string, term)) => {
        val erlString = string.toJava().asInstanceOf[OtpErlangString]
        Some(OpenIndexMsg(pid.asInstanceOf[Codec.EPid], erlString.stringValue(), term))
      }
      case _ => {
        None // FIXME by implementing all message types from original ClouseauTypeFactory
      }
    }
}
