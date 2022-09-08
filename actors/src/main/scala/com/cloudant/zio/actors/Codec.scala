package com.cloudant.zio.actors

import _root_.com.ericsson.otp.erlang._;

object Codec {
  sealed trait ETerm {
    def toJava(): OtpErlangObject
  }
  case class EPid(node: String, id: Integer, serial: Integer, creation: Integer) extends ETerm {
    def this(obj: OtpErlangPid) =
      this(obj.node, obj.id, obj.serial, obj.creation)
    def apply(node: String, id: Integer, serial: Integer, creation: Integer) =
      EPid(node, id, serial, creation)
    def toJava()                  = new OtpErlangPid(node, id, serial, creation)
    override def toString: String = s"<$id.$serial.$creation>"
  }
  case class EAtom(atom: Symbol) extends ETerm {
    def this(obj: OtpErlangAtom) =
      this(Symbol(obj.atomValue))
    def apply(atom: Symbol)       = EAtom(atom)
    def toJava()                  = new OtpErlangAtom(atom.name)
    override def toString: String = s"'${atom.name}'"
  }
  case class EString(str: String) extends ETerm {
    def this(obj: OtpErlangString) =
      this(obj.stringValue)
    def apply(str: String)        = EString(str)
    def toJava()                  = new OtpErlangString(str)
    override def toString: String = s"\"$str\""
  }
  case class EList(elems: List[ETerm]) extends ETerm {
    def this(obj: OtpErlangList) =
      this(obj.elements.map(fromJava).toList)
    def apply(elems: List[ETerm]) = EList(elems)
    def toJava()                  = new OtpErlangList(elems.map(_toJava).toArray)
    override def toString: String = s"[${elems.mkString(",")}]"
  }
  case class ETuple(elems: List[ETerm]) extends ETerm {
    def this(obj: OtpErlangTuple) =
      this(obj.elements.map(fromJava).toList)
    def apply(elems: List[ETerm]) = ETuple(elems)
    def toJava()                  = new OtpErlangTuple(elems.map(_toJava).toArray)
    override def toString: String = s"{${elems.mkString(",")}}"
  }
  case class ELong(value: BigInt) extends ETerm {
    def this(obj: OtpErlangLong) =
      this(obj.longValue)
    def apply(value: BigInt)      = ELong(value)
    def toJava()                  = new OtpErlangLong(value.bigInteger)
    override def toString: String = s"$value"
  }
  /*
   TODO: Add the rest of the types
   */
  private def _toJava(eTerm: ETerm): OtpErlangObject =
    eTerm.toJava()
  /*
     TODO: Add the rest of the types
   */
  def fromJava(obj: OtpErlangObject): ETerm =
    obj match {
      case otpPid: OtpErlangPid       => new EPid(otpPid)
      case otpAtom: OtpErlangAtom     => new EAtom(otpAtom)
      case otpString: OtpErlangString => new EString(otpString)
      case otpList: OtpErlangList     => new EList(otpList)
      case otpTuple: OtpErlangTuple   => new ETuple(otpTuple)
      case otpLong: OtpErlangLong     => new ELong(otpLong)
    }
}
