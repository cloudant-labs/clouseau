package com.cloudant.ziose.core

import com.ericsson.otp.erlang._

import java.nio.charset.StandardCharsets
import scala.collection.{AbstractMap, mutable}
import scala.language.implicitConversions

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
    def apply(obj: OtpErlangRef): ERef                      = new ERef(obj)
    def unapply(x: ERef): Option[(String, Array[Int], Int)] = Some((x.obj.node, x.obj.ids, x.obj.creation))
  }

  case class EPid(node: Symbol, id: Int, serial: Int, creation: Int) extends ETerm {
    def this(obj: OtpErlangPid) = this(Symbol(obj.node), obj.id, obj.serial, obj.creation)
    override def toOtpErlangObject: OtpErlangPid = new OtpErlangPid(node.name, id, serial, creation)
    override def toString: String                = s"<$id.$serial.$creation>"
  }

  object EPid {
    def apply(node: Symbol, id: Int, serial: Int, creation: Int) = new EPid(node, id, serial, creation)
    def apply(node: String, id: Int, serial: Int, creation: Int) = new EPid(Symbol(node), id, serial, creation)
    def apply(obj: OtpErlangPid): EPid = new EPid(Symbol(obj.node), obj.id, obj.serial, obj.creation)
  }

  class EAtom(val atom: Symbol) extends ETerm {
    override def hashCode(): Int = atom.hashCode()
    override def equals(other: Any): Boolean = other match {
      case other: EAtom => atom == other.atom
      case _            => false
    }

    override def toOtpErlangObject: OtpErlangAtom = new OtpErlangAtom(atom.name)
    override def toString: String                 = this.toOtpErlangObject.toString
    def asString: String                          = atom.name
  }

  object EAtom {
    def apply(atom: Symbol)       = new EAtom(atom)
    def apply(obj: OtpErlangAtom) = new EAtom(Symbol(obj.atomValue))
    def apply(str: String)        = new EAtom(Symbol(str))

    def unapply(eAtom: EAtom): Option[String] = Some(eAtom.atom.name)
  }

  case class EBoolean(boolean: Boolean) extends ETerm {
    def this(obj: OtpErlangBoolean) = this(obj.booleanValue)
    override def toOtpErlangObject: OtpErlangBoolean = new OtpErlangBoolean(boolean)
    override def toString: String                    = s"$boolean"
  }

  object EBoolean {
    def apply(boolean: Boolean)      = new EBoolean(boolean)
    def apply(obj: OtpErlangBoolean) = new EBoolean(obj.booleanValue)
  }

  val trueAtom  = EAtom("true")
  val falseAtom = EAtom("false")

  val trueOtpAtom  = trueAtom.toOtpErlangObject
  val falseOtpAtom = falseAtom.toOtpErlangObject

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

  case class EFloat(float: Float) extends ETerm {
    def this(obj: OtpErlangFloat) = this(obj.floatValue)
    override def toOtpErlangObject: OtpErlangFloat = new OtpErlangFloat(float)
    override def toString: String                  = s"$float"
  }

  object EFloat {
    def apply(float: Float)        = new EFloat(float)
    def apply(obj: OtpErlangFloat) = new EFloat(obj.floatValue)
  }

  case class EDouble(double: Double) extends ETerm {
    def this(obj: OtpErlangDouble) = this(obj.doubleValue)
    override def toOtpErlangObject: OtpErlangDouble = new OtpErlangDouble(double)
    override def toString: String                   = s"$double"
  }

  object EDouble {
    def apply(double: Double)       = new EDouble(double)
    def apply(obj: OtpErlangDouble) = new EDouble(obj.doubleValue)
  }

  case class EString(str: String) extends ETerm {
    def this(obj: OtpErlangString) = this(obj.stringValue)
    override def toOtpErlangObject: OtpErlangString = new OtpErlangString(str)
    override def toString: String                   = s"\"$str\""
  }

  object EString {
    def apply(str: String)          = new EString(str)
    def apply(obj: OtpErlangString) = new EString(obj.stringValue)
  }

  class EList(val elems: List[ETerm], val isProper: Boolean = true)
      extends ETerm
      with scala.collection.LinearSeq[ETerm] {
    def this(obj: OtpErlangList) = this(EList.maybeImproper(obj), obj.isProper)
    override def hashCode: Int = elems.hashCode() + isProper.hashCode()
    override def equals(other: Any): Boolean = {
      other match {
        case other: EList if other.isProper == isProper => elems.equals(other.elems)
        case _                                          => false
      }
    }
    override def toOtpErlangObject: OtpErlangList = {
      if (isProper) {
        new OtpErlangList(elems.map(_.toOtpErlangObject).toArray)
      } else {
        val head = elems.dropRight(1)
        val tail = elems.takeRight(1).head
        new OtpErlangList(head.map(_.toOtpErlangObject).toArray, tail.toOtpErlangObject)
      }
    }
    override def toString: String = {
      if (isProper) {
        s"[${elems.mkString(",")}]"
      } else {
        val head = elems.dropRight(1)
        val tail = elems.takeRight(1).head
        s"[${head.mkString(",")}|$tail]"
      }
    }
    override def isEmpty        = elems.isEmpty
    override def head           = elems.head
    override def tail           = new EList(elems.tail, isProper)
    override def knownSize: Int = elems.knownSize
    def ::(head: ETerm)         = new EList(head :: this.elems, isProper)
  }

  object EList {
    def apply(xs: List[ETerm])                    = new EList(xs, true)
    def apply(xs: List[ETerm], isProper: Boolean) = new EList(xs, isProper)
    def apply(xs: ETerm*)                         = new EList(xs.toList, true)
    def apply(obj: OtpErlangList)                 = new EList(maybeImproper(obj), obj.isProper)

    def unapplySeq(x: EList): Option[List[ETerm]] = {
      if (x.isProper) { Some(x.toList) }
      else { None }
    }

    def maybeImproper(obj: OtpErlangList): List[ETerm] = {
      if (obj.isProper) {
        obj.elements.map(fromErlang).toList
      } else {
        val head = obj.elements.map(fromErlang).toList
        val tail = fromErlang(obj.getLastTail)
        head :+ tail
      }
    }
  }

  class EListImproper

  object EListImproper {
    def apply(xs: List[ETerm]) = new EList(xs, false)
    def apply(xs: ETerm*)      = new EList(xs.toList, false)

    def unapplySeq(x: EList): Option[List[ETerm]] = {
      if (x.isProper) { None }
      else { Some(x.toList) }
    }
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

  object EMap {
    def apply(obj: OtpErlangMap): EMap = new EMap(obj)
  }

  // TODO switch to Array for internal container
  class ETuple(val elems: List[ETerm]) extends ETerm {
    def this(obj: OtpErlangTuple) = this(obj.elements.map(fromErlang).toList)
    override def hashCode: Int = elems.hashCode()
    override def equals(other: Any): Boolean = {
      other match {
        case other: ETuple => elems.equals(other.elems)
        case _             => false
      }
    }

    override def toOtpErlangObject: OtpErlangTuple = new OtpErlangTuple(elems.map(_.toOtpErlangObject).toArray)
    override def toString: String                  = s"{${elems.mkString(",")}}"
  }

  object ETuple {
    def apply(xs: List[ETerm])             = new ETuple(xs)
    def apply(xs: ETerm*)                  = new ETuple(xs.toList)
    def apply(obj: OtpErlangTuple): ETuple = new ETuple(obj)

    def unapplySeq(x: ETuple): Option[List[ETerm]] = {
      Some(x.elems)
    }
  }

  // TODO add tests
  case class EBitString(payload: Array[Byte]) extends ETerm {
    def this(obj: OtpErlangBitstr) = this(obj.binaryValue)
    override def toOtpErlangObject: OtpErlangObject = {
      new OtpErlangBitstr(payload)
    }
    override def toString: String = s"<<${payload.mkString(",")}>>"
  }

  object EBitString {
    def apply(obj: OtpErlangBitstr): EBitString = new EBitString(obj.binaryValue)
  }

  // TODO add tests
  class EBinary(payload: Array[Byte], val isPrintable: Boolean = false) extends ETerm {
    override def hashCode: Int = payload.toList.hashCode()
    override def equals(other: Any): Boolean = {
      other match {
        case other: EBinary => payload.toList.equals(other.asBytes.toList)
        case _              => false
      }
    }
    def this(obj: OtpErlangBinary) = this(obj.binaryValue)
    override def toOtpErlangObject: OtpErlangObject = {
      new OtpErlangBinary(payload)
    }
    override def toString: String = {
      if (isPrintable) {
        s"<<\"${asString}\">>"
      } else {
        s"<<${payload.mkString(",")}>>"
      }
    }
    def asString: String     = new String(payload, StandardCharsets.UTF_8)
    def asBytes: Array[Byte] = payload
    def asPrintable: EBinary = {
      if (isPrintable) {
        this
      } else {
        new EBinary(payload, true)
      }
    }
  }

  object EBinary {
    def apply(atom: Symbol)         = new EBinary(atom.name.getBytes(), true)
    def apply(obj: OtpErlangBinary) = new EBinary(obj.binaryValue)
    def apply(str: String)          = new EBinary(str.getBytes(StandardCharsets.UTF_8), true)
    def apply(bytes: Array[Byte])   = new EBinary(bytes)

    def unapply(eBinary: EBinary): Option[Array[Byte]] = Some(eBinary.asBytes)
  }

  /*
  jInterface represent all of the following types as one

    - [OtpErlangLong](https://github.com/erlang/otp/blob/b8d646f77d6f33e6aa06c38cb9da2c9ac2dc9d9b/lib/jinterface/java_src/com/ericsson/otp/erlang/OtpInputStream.java#L1225) represents any of
      - OtpExternal.smallIntTag:
      - OtpExternal.intTag:
      - OtpExternal.smallBigTag:
      - OtpExternal.largeBigTag:
    - OtpErlangAtom
      - OtpErlangAtom
      - OtpErlangBoolean
   */

  def fromErlang(obj: Any): ETerm = {
    obj match {
      case otpPid: OtpErlangPid                              => EPid(otpPid)
      case otpBoolean: OtpErlangBoolean                      => EBoolean(otpBoolean)
      case otpAtom: OtpErlangAtom if otpAtom == trueOtpAtom  => EBoolean(true)
      case otpAtom: OtpErlangAtom if otpAtom == falseOtpAtom => EBoolean(false)
      case otpAtom: OtpErlangAtom                            => EAtom(otpAtom)
      case otpLong: OtpErlangLong =>
        otpLong.bitLength() match {
          case int if int < 32 => EInt(otpLong.intValue)
          case _               => ELong(otpLong.bigIntegerValue)
        }
      case otpFloat: OtpErlangFloat   => EFloat(otpFloat)
      case otpDouble: OtpErlangDouble => EDouble(otpDouble)
      case otpString: OtpErlangString => EString(otpString)
      case otpList: OtpErlangList     => EList(otpList)
      case otpMap: OtpErlangMap       => EMap(otpMap)
      case otpTuple: OtpErlangTuple   => ETuple(otpTuple)
      case otpRef: OtpErlangRef       => ERef(otpRef)
      case otpBinary: OtpErlangBinary => EBinary(otpBinary)
      case otpBitstr: OtpErlangBitstr => EBitString(otpBitstr)
    }
  }

  object ENeverMatch extends ETerm {
    def toOtpErlangObject = new OtpErlangAtom("never match")
  }

  def toScala(
    obj: ETerm,
    top: PartialFunction[ETerm, Option[Any]] = { case ENeverMatch => None },
    bottom: PartialFunction[ETerm, Option[Any]] = { case ENeverMatch => None }
  ): Any = {
    val topRule    = top.Extractor
    val bottomRule = bottom.Extractor
    obj match {
      case topRule(e) =>
        e match {
          case Some(o) => o
          case None    => obj
        }
      case b: EBoolean   => b.boolean
      case a: EAtom      => a.atom
      case i: EInt       => i.int
      case l: ELong      => l.long
      case f: EFloat     => f.float
      case d: EDouble    => d.double
      case s: EString    => s.str
      case pid: EPid     => pid
      case ref: ERef     => ref.obj
      case list: EList   => list.elems.map(e => toScala(e, top, bottom))
      case tuple: ETuple => product(tuple.elems.map(e => toScala(e, top, bottom)))
      case map: EMap =>
        map.mapLH.foldLeft(Map.empty[Any, Any]) { case (newMap, (k: ETerm, v: ETerm)) =>
          newMap + (toScala(k, top, bottom) -> toScala(v, top, bottom))
        }
      // *Important* clouseau encodes strings as binaries
      case binary: EBinary    => binary.asString
      case bitstr: EBitString => bitstr.payload
      case bottomRule(e) =>
        e match {
          case Some(o) => o
          case None    => obj
        }
      case ENeverMatch => ()
    }
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

  case class NeverMatch()

  def camelToUnderscores(name: String) = "[A-Z\\d]".r
    .replaceAllIn(
      name,
      m => "_" + m.group(0).toLowerCase()
    )
    .stripPrefix("_")

  def fromScala(
    scala: Any,
    top: PartialFunction[Any, ETerm] = { case NeverMatch => EAtom("never match") },
    bottom: PartialFunction[Any, ETerm] = { case NeverMatch => EAtom("never match") }
  ): ETerm = {
    val topRule    = top.Extractor
    val bottomRule = bottom.Extractor
    scala match {
      case topRule(e)     => e
      case e: ETerm       => e
      case any: FromScala => any.fromScala
      case b: Boolean     => EBoolean(b)
      case a: Symbol      => EAtom(a)
      case i: Int         => EInt(i)
      case l: BigInt      => ELong(l.bigInteger)
      // TODO Add test for Long
      case l: Long =>
        l match {
          case int if int.isValidInt => EInt(int.intValue)
          case _                     => ELong(l)
        }
      case f: Float  => EFloat(f)
      case d: Double => EDouble(d)
      // *Important* clouseau encodes strings as binaries
      case s: String     => EBinary(s)
      case list: List[_] => EList(list.map(e => fromScala(e, top, bottom)), true)
      case list: Seq[_]  => EList(List.from(list.map(e => fromScala(e, top, bottom))), true)
      case tuple: Product => {
        tuple.getClass().getPackageName() match {
          case "scala" => ETuple(tuple.productIterator.map(e => fromScala(e, top, bottom)).toList)
          case _       =>
            // Encode the classes which are not defined in `scala` package as
            // {className.toLowerCase(), ....}
            ETuple(
              tuple.productIterator
                .map(e => fromScala(e, top, bottom))
                .toList
                .prepended(EAtom(camelToUnderscores(tuple.productPrefix)))
            )
        }
      }
      case tuple: Unit => ETuple()
      // TODO Add test for HashMap (which implements AbstractMap)
      case m: AbstractMap[_, _] =>
        EMap(mutable.LinkedHashMap.from(m map { case (k, v) =>
          (fromScala(k), fromScala(v))
        }))
      case m: Map[_, _] =>
        EMap(mutable.LinkedHashMap.from(m map { case (k, v) =>
          (fromScala(k), fromScala(v))
        }))
      // This is ambiguous how we can distinguish bitstr from binary?
      case binary: Array[Byte] => EBinary(binary)
      case null                => EAtom("null")
      case bottomRule(e)       => e
    }
  }

  trait FromScala {
    def fromScala: ETerm
  }

  class ExtendedPF[A, B](val pf: PartialFunction[A, B]) {
    object Extractor {
      def unapply(a: A): Option[B] = pf.lift(a)
    }
  }

  implicit def extendPartialFunction[A, B](pf: PartialFunction[A, B]): ExtendedPF[A, B] = {
    new ExtendedPF(pf)
  }
}
