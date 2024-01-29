package com.cloudant.ziose.core

import com.ericsson.otp.erlang._
import scala.collection.mutable

// TODO https://murraytodd.medium.com/putting-it-all-together-with-type-classes-d6b545202803
// TODO https://medium.com/beingprofessional/use-of-implicit-to-transform-case-classes-in-scala-47a72dfa9450

object Codec {
  sealed trait ETerm {
    def toOtpErlangObject: OtpErlangObject
  }

  trait ToScala[From] {
    def toScala(from: From): Any
  }

  /*
    ERef is implemented differently compared to the rest of the ETerms.
    The main reason is because we want to leverage hashCode provided by jInterface
    The OtpErlangRef has special logic which can be summarized as:
      - it uses different algorithms depending on how many ids are provided
      - it considers only first three ids

    https://github.com/erlang/otp/blob/413da54ce2eca7c40786871859b87930cc21d239/lib/jinterface/java_src/com/ericsson/otp/erlang/OtpErlangRef.java#L298C2-L306C6
   */
  class ERef(val obj: OtpErlangRef) extends ETerm {
    override def hashCode: Int = obj.hashCode()
    override def equals(other: Any): Boolean = {
      other match {
        case other: ERef => obj.equals(other.obj)
        case _           => false
      }
    }

    override def toOtpErlangObject: OtpErlangRef = obj
    override def toString: String                = obj.toString
    val node                                     = obj.node
    val ids                                      = obj.ids
    val creation                                 = obj.creation
  }
  object ERef {
    def apply(node: String, ids: Array[Int], creation: Int) = new ERef(new OtpErlangRef(node, ids, creation))
    def unapply(x: ERef): Option[(String, Array[Int], Int)] = Some((x.obj.node, x.obj.ids, x.obj.creation))
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
    def this(obj: OtpErlangList) = this(obj.elements.map(fromErlang).toList)
    override def toOtpErlangObject: OtpErlangList = new OtpErlangList(elems.map(_.toOtpErlangObject).toArray)
    override def toString: String                 = s"[${elems.mkString(",")}]"
  }

  case class EMap(mapLH: mutable.LinkedHashMap[ETerm, ETerm]) extends ETerm {
    def this(obj: OtpErlangMap) = this {
      val eMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      obj.entrySet.forEach(i => eMap.update(fromErlang(i.getKey), fromErlang(i.getValue)))
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
    def this(obj: OtpErlangTuple) = this(obj.elements.map(fromErlang).toList)
    override def toOtpErlangObject: OtpErlangTuple = new OtpErlangTuple(elems.map(_.toOtpErlangObject).toArray)
    override def toString: String                  = s"{${elems.mkString(",")}}"
  }

  // TODO add tests
  case class EBitString(payload: Array[Byte]) extends ETerm {
    def this(obj: OtpErlangBitstr) = this(obj.binaryValue())
    override def toOtpErlangObject: OtpErlangObject = {
      new OtpErlangBitstr(payload)
    }
    override def toString: String = s"<<${payload.mkString(",")}>>"
  }

  // TODO add tests
  case class EBinary(payload: Array[Byte]) extends ETerm {
    def this(obj: OtpErlangBinary) = this(obj.binaryValue())
    override def toOtpErlangObject: OtpErlangObject = {
      new OtpErlangBinary(payload)
    }
    override def toString: String = s"<<${payload.mkString(",")}>>"
  }

  def fromErlang(obj: Any): ETerm = {
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
      case otpRef: OtpErlangRef         => new ERef(otpRef)
      case otpBinary: OtpErlangBinary   => new EBinary(otpBinary)
      case otpBitstr: OtpErlangBitstr   => new EBitString(otpBitstr)
    }
  }

  def toScala(obj: ETerm): Any = obj match {
    case b: EBoolean   => b.boolean
    case a: EAtom      => a.atom
    case i: EInt       => i.int
    case l: ELong      => l.long
    case s: EString    => s.str
    case pid: EPid     => pid
    case ref: ERef     => ref.obj
    case list: EList   => list.elems.map(toScala)
    case tuple: ETuple => product(tuple.elems.map(toScala))
    case map: EMap =>
      map.mapLH.foldLeft(Map.empty[Any, Any]) { case (newMap, (k: ETerm, v: ETerm)) =>
        newMap + (toScala(k) -> toScala(v))
      }
    // TODO ambiguous result
    case binary: EBinary    => binary.payload
    case bitstr: EBitString => bitstr.payload
  }

  def product(list: List[_]): Any = list match {
    // the max size of a tuple in scala is 22 elements
    case List()                                                  => ()
    case List(a)                                                 => (a)
    case List(a, b)                                              => (a, b)
    case List(a, b, c)                                           => (a, b, c)
    case List(a, b, c, d)                                        => (a, b, c, d)
    case List(a, b, c, d, e)                                     => (a, b, c, d, e)
    case List(a, b, c, d, e, f)                                  => (a, b, c, d, e, f)
    case List(a, b, c, d, e, f, g)                               => (a, b, c, d, e, f, g)
    case List(a, b, c, d, e, f, g, h)                            => (a, b, c, d, e, f, g, h)
    case List(a, b, c, d, e, f, g, h, i)                         => (a, b, c, d, e, f, g, h, i)
    case List(a, b, c, d, e, f, g, h, i, j)                      => (a, b, c, d, e, f, g, h, i, j)
    case List(a, b, c, d, e, f, g, h, i, j, k)                   => (a, b, c, d, e, f, g, h, i, j, k)
    case List(a, b, c, d, e, f, g, h, i, j, k, l)                => (a, b, c, d, e, f, g, h, i, j, k, l)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m)             => (a, b, c, d, e, f, g, h, i, j, k, l, m)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n)          => (a, b, c, d, e, f, g, h, i, j, k, l, m, n)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)       => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p)    => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r) =>
      (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s) =>
      (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t) =>
      (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u) =>
      (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u)
    case List(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v) =>
      (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)
    case list: List[_] => new BigTuple(list)
  }

  def fromScala(scala: Any): ETerm = scala match {
    case e: ETerm       => e
    case b: Boolean     => EBoolean(b)
    case a: Symbol      => EAtom(a)
    case i: Int         => EInt(i)
    case l: BigInt      => ELong(l)
    case s: String      => EString(s)
    case tuple: Product => ETuple(tuple.productIterator.map(fromScala).toList)
    case list: List[_]  => EList(list.map(fromScala))
    case list: Seq[_]   => EList(List.from(list.map(fromScala)))
    case m: Map[_, _] =>
      EMap(mutable.LinkedHashMap.from(m map { case (k, v) =>
        (fromScala(k), fromScala(v))
      }))
    case any: FromScala => fromScala(any)
    // This is ambiguous how we can distinguish bitstr from binary?
    case binary: Array[Byte] => EBinary(binary)
  }

  trait FromScala {
    def fromScala: ETerm
  }
}
