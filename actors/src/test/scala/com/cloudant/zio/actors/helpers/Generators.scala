package com.cloudant.zio.actors.helpers

import _root_.com.ericsson.otp.erlang._
import com.cloudant.zio.actors._
import com.cloudant.zio.actors.Codec._

import java.math.BigInteger
import scala.collection.mutable
import zio.Random
import zio.test.{Gen, Sized}
import zio.test.Gen._

object Generators {
  def atomPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    alphaNumericString.map(xs => (EAtom(Symbol(xs)), new OtpErlangAtom(xs)))

  def booleanPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    boolean.map(i => (EBoolean(i), new OtpErlangBoolean(i)))

  def intPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    int(Int.MinValue, Int.MaxValue).map(i => (EInt(i), new OtpErlangInt(i)))

  def longPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    bigInt(Long.MinValue, Long.MaxValue).map(i => (ELong(i), new OtpErlangLong(new BigInteger(i.toString))))

  def stringPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    asciiString.map(xs => (EString(xs), new OtpErlangString(xs)))

  def listPairGen(depth: Int, withPid: Boolean = true): Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    if (depth > 0)
      listOf(anyPairGen(depth - 1, withPid)).map { e =>
        val (terms, objects) = e.unzip
        (EList(terms), new OtpErlangList(objects.toArray))
      }
    else
      primitivePairGen

  def mapPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    for {
      keys   <- setOf(alphaNumericStringBounded(1, 10))
      values <- listOfN(keys.size)(anyGen)
      otpErlangMap  = new OtpErlangMap
      linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      pairs         = keys zip values
    } yield {
      for ((k, v) <- pairs) {
        linkedHashMap.put(EString(k), toETerm(v))
        otpErlangMap.put(new OtpErlangString(k), matchToOtpErlangObject(v))
      }
      (EMap(linkedHashMap), otpErlangMap)
    }

  def tuplePairGen(depth: Int, withPid: Boolean = true): Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    if (depth > 0)
      listOf(anyPairGen(depth - 1, withPid)).map { e =>
        val (terms, objects) = e.unzip
        (ETuple(terms), new OtpErlangTuple(objects.toArray))
      }
    else
      primitivePairGen

  def pidPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield (EPid(node, id, serial, creation), new OtpErlangPid(node, id, serial, creation))

  /*
     TODO: Add the rest of the types
   */

  def cleanupDbMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      dbName     <- alphaNumericString
      activeSigs <- listOf(alphaNumericString)
    } yield (
      ETuple(List(EAtom(Symbol("cleanup")), EString(dbName), EList(activeSigs.map(EString)))),
      CleanupDbMsg(dbName, activeSigs)
    )

  def cleanupPathMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("cleanup")), EString(path))), CleanupPathMsg(path))

  def closeLRUByPathMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("close_lru_by_path")), EString(path))), CloseLRUByPathMsg(path))

  def commitMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("commit")), ELong(seq))), CommitMsg(seq))

  def deleteDocMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      id <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("delete")), EString(id))), DeleteDocMsg(id))

  def diskSizeMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("disk_size")), EString(path))), DiskSizeMsg(path))

  def group1MsgPairGen(depth: Int): Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      query          <- alphaNumericString
      field          <- alphaNumericString
      refresh        <- boolean
      (groupSort, _) <- anyPairGen(depth)
      groupOffset    <- int(0, 10)
      groupLimit     <- int(0, 10)
    } yield (
      ETuple(
        List(
          EAtom(Symbol("group1")),
          EString(query),
          EString(field),
          EBoolean(refresh),
          groupSort,
          EInt(groupOffset),
          EInt(groupLimit)
        )
      ),
      Group1Msg(query, field, refresh, groupSort, groupOffset, groupLimit)
    )

  def group2MsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      keys   <- setOf(alphaNumericStringBounded(1, 10))
      values <- listOfN(keys.size)(anyGen)
      linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      pairs         = keys zip values
    } yield {
      pairs.foreach(i => linkedHashMap.put(EAtom(Symbol(i._1)), toETerm(i._2)))
      (
        ETuple(List(EAtom(Symbol("group2")), EMap(linkedHashMap))),
        Group2Msg((keys.map(Symbol(_)) zip values).toMap)
      )
    }

  def openIndexMsgPairGen(depth: Int): Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      pid          <- pidGen
      path         <- alphaNumericString
      (options, _) <- anyPairGen(depth)
    } yield (ETuple(List(EAtom(Symbol("open")), pid, EString(path), options)), OpenIndexMsg(pid, path, options))

  def renamePathMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      dbName <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("rename")), EString(dbName))), RenamePathMsg(dbName))

  def searchRequestPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      keys   <- setOf(alphaNumericStringBounded(1, 10))
      values <- listOfN(keys.size)(anyGen)
      linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      pairs         = keys zip values
    } yield {
      pairs.foreach(i => linkedHashMap.put(EAtom(Symbol(i._1)), toETerm(i._2)))
      (
        ETuple(List(EAtom(Symbol("search")), EMap(linkedHashMap))),
        SearchRequest((keys.map(Symbol(_)) zip values).toMap)
      )
    }

  def setPurgeSeqMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("set_purge_seq")), ELong(seq))), SetPurgeSeqMsg(seq))

  def setUpdateSeqMsgPairGen: Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("set_update_seq")), ELong(seq))), SetUpdateSeqMsg(seq))

  def primitivePairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    oneOf(
      atomPairGen,
      booleanPairGen,
      intPairGen,
      longPairGen,
      stringPairGen
    )

  def anyGen: Gen[Random with Sized, Any] =
    small(_ => oneOf(boolean, int, long, string, listOfN(2)(alphaNumericStringBounded(1, 10))))

  def pidGen: Gen[Random with Sized, EPid] =
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield EPid(node, id, serial, creation)

  /**
   * toString() is different in OtpErlangPid and EPid:
   *   - OtpErlangPid: #Pid<0.82.0>
   *   - Erlang Pid: <0.82.0>
   *
   * listPairGen and tuplePairGen are depend on anyPairGen(depth, withPid)
   *   - withPid: default is true.
   *
   * In order to test that EList.toString == OtpErlangList.toString, we need to
   * exclude the pid in the element, so we pass `withPid=false` here.
   */
  def anyPairGen(depth: Int, withPid: Boolean = true): Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    small { size =>
      if (withPid && size > 1)
        oneOf(primitivePairGen, pidPairGen, mapPairGen, listPairGen(depth - 1), tuplePairGen(depth - 1))
      else if (size > 1)
        oneOf(primitivePairGen, mapPairGen, listPairGen(depth - 1, withPid), tuplePairGen(depth - 1, withPid))
      else
        primitivePairGen
    }

  def anyMessagePairGen(depth: Int): Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    small { _ =>
      oneOf(
        cleanupDbMsgPairGen,
        cleanupPathMsgPairGen,
        closeLRUByPathMsgPairGen,
        commitMsgPairGen,
        deleteDocMsgPairGen,
        diskSizeMsgPairGen,
        group1MsgPairGen(depth - 1),
        group2MsgPairGen,
        openIndexMsgPairGen(depth - 1),
        renamePathMsgPairGen,
        searchRequestPairGen,
        setPurgeSeqMsgPairGen,
        setUpdateSeqMsgPairGen
      )
    }
}
