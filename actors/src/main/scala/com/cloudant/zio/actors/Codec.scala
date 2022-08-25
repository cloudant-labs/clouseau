package com.cloudant.zio.actors

import com.ericsson.otp.erlang._;

object Codec {
  sealed trait ETerm {
    def toJava(): OtpErlangObject
  }
  case class EAtom(atom: Symbol) extends ETerm {
    def apply(atom: Symbol)        = EAtom(atom)
    def toJava()                  = new OtpErlangAtom(atom.name)
    override def toString: String = s"'${atom.name}'"
  }
  case class EString(str: String) extends ETerm {
    def apply(str: String)        = EString(str)
    def toJava()                  = new OtpErlangString(str)
    override def toString: String = s"\"${str}\""
  }
  case class EList(elems: List[ETerm]) extends ETerm {
    def apply(elems: List[ETerm]) = EList(elems)
    def toJava()                  = new OtpErlangList(elems.map(_toJava).toArray)
    override def toString: String = s"[${elems.mkString(",")}]"
  }
  case class ETuple(elems: List[ETerm]) extends ETerm {
    def apply(elems: List[ETerm]) = ETuple(elems)
    def toJava()                  = new OtpErlangTuple(elems.map(_toJava).toArray)
    override def toString: String = s"{${elems.mkString(",")}}"
  }
  case class ELong(value: BigInt) extends ETerm {
    def apply(value: BigInt) = ELong(value)
    def toJava()                  = new OtpErlangLong(value.bigInteger)
    override def toString: String = s"${value}"
  }
  /*
   TODO: Add the rest of the types
   */
  private def _toJava(eTerm: ETerm): OtpErlangObject =
    eTerm.toJava()
    /*
     TODO: Add the rest of the types
     */
}
