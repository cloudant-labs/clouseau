package com.cloudant.zio.actors

import com.ericsson.otp.erlang._
import scala.collection.mutable

object Codec {
  sealed trait ETerm {
    def toOtpErlangObject: OtpErlangObject
  }

  case class EPid(node: String, id: Int, serial: Int, creation: Int) extends ETerm {
    def this(obj: OtpErlangPid) = this(obj.node, obj.id, obj.serial, obj.creation)
    override def toOtpErlangObject: OtpErlangPid = new OtpErlangPid(node, id, serial, creation)
    override def toString: String                = s"<$id.$serial.$creation>"
  }

  case class EAtom(atom: Symbol) extends ETerm {
    def this(obj: OtpErlangAtom) = this(Symbol(obj.atomValue))
    override def toOtpErlangObject: OtpErlangAtom = new OtpErlangAtom(atom.name)
    override def toString: String                 = this.toOtpErlangObject.toString
  }

  case class EBoolean(boolean: Boolean) extends ETerm {
    def this(obj: OtpErlangBoolean) = this(obj.booleanValue)
    override def toOtpErlangObject: OtpErlangBoolean = new OtpErlangBoolean(boolean)
    override def toString: String                    = s"$boolean"
  }

  case class EInt(int: Int) extends ETerm {
    def this(obj: OtpErlangInt) = this(obj.intValue)
    override def toOtpErlangObject: OtpErlangInt = new OtpErlangInt(int)
    override def toString: String                = s"$int"
  }

  case class ELong(long: BigInt) extends ETerm {
    def this(obj: OtpErlangLong) = this(obj.longValue)
    override def toOtpErlangObject: OtpErlangLong = new OtpErlangLong(long.bigInteger)
    override def toString: String                 = s"$long"
  }

  case class EString(str: String) extends ETerm {
    def this(obj: OtpErlangString) = this(obj.stringValue)
    override def toOtpErlangObject: OtpErlangString = new OtpErlangString(str)
    override def toString: String                   = s"\"$str\""
  }

  case class EList(elems: List[ETerm]) extends ETerm {
    def this(obj: OtpErlangList) = this(obj.elements.map(toETerm).toList)
    override def toOtpErlangObject: OtpErlangList = new OtpErlangList(elems.map(_toOtpErlangObject).toArray)
    override def toString: String                 = s"[${elems.mkString(",")}]"
  }

  case class EMap(mapLH: mutable.LinkedHashMap[ETerm, ETerm]) extends ETerm {
    def this(obj: OtpErlangMap) = this {
      val eMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      obj.entrySet.forEach(i => eMap.update(toETerm(i.getKey), toETerm(i.getValue)))
      eMap
    }

    override def toOtpErlangObject: OtpErlangMap = {
      val jMap = new OtpErlangMap()
      mapLH.foreachEntry((k, v) => jMap.put(k.toOtpErlangObject, v.toOtpErlangObject))
      jMap
    }

    override def toString: String = s"#{${this.mapLH.map(_.productIterator.mkString(" => ")).mkString(",")}}"
  }

  case class ETuple(elems: List[ETerm]) extends ETerm {
    def this(obj: OtpErlangTuple) = this(obj.elements.map(toETerm).toList)
    override def toOtpErlangObject: OtpErlangTuple = new OtpErlangTuple(elems.map(_toOtpErlangObject).toArray)
    override def toString: String                  = s"{${elems.mkString(",")}}"
  }

  def toETerm(obj: Any): ETerm =
    obj match {
      case otpPid: OtpErlangPid         => new EPid(otpPid)
      case otpBoolean: OtpErlangBoolean => new EBoolean(otpBoolean)
      case otpAtom: OtpErlangAtom       => new EAtom(otpAtom)
      case otpInt: OtpErlangInt         => new EInt(otpInt)
      case otpLong: OtpErlangLong       => new ELong(otpLong)
      case otpString: OtpErlangString   => new EString(otpString)
      case otpList: OtpErlangList       => new EList(otpList)
      case otpMap: OtpErlangMap         => new EMap(otpMap)
      case otpTuple: OtpErlangTuple     => new ETuple(otpTuple)
    }

  private def _toOtpErlangObject(eTerm: ETerm): OtpErlangObject =
    eTerm.toOtpErlangObject

  def getValue(obj: Any): Any = obj match {
    case b: EBoolean   => b.boolean
    case a: EAtom      => a.atom
    case i: EInt       => i.int
    case l: ELong      => l.long
    case s: EString    => s.str
    case pid: EPid     => pid
    case list: EList   => list.elems.map(getValue)
    case tuple: ETuple => tuple.elems.map(getValue)
    case map: EMap =>
      map.mapLH.foldLeft(Map.empty[Any, Any]) { case (newMap, (k: ETerm, v: ETerm)) =>
        newMap + (getValue(k) -> getValue(v))
      }
  }
}
