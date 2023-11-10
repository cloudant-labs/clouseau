package com.cloudant.ziose.clouseau.helpers

import com.cloudant.ziose.core.Codec._
import scala.collection.mutable
import com.cloudant.ziose.clouseau._
import com.cloudant.ziose.test.helpers.Generators._
import zio.test.Gen
import zio.test.Gen._

object Generators {
  /* --------------- ClouseauMessagePairGen --------------- */
  def cleanupDbMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      dbName     <- alphaNumericString
      activeSigs <- listOf(alphaNumericString)
    } yield (
      ETuple(List(EAtom(Symbol("cleanup")), EString(dbName), EList(activeSigs.map(EString)))),
      CleanupDbMsg(dbName, activeSigs)
    )
  }

  def cleanupPathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("cleanup")), EString(path))), CleanupPathMsg(path))
  }

  def closeLRUByPathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("close_lru_by_path")), EString(path))), CloseLRUByPathMsg(path))
  }

  def commitMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("commit")), ELong(seq))), CommitMsg(seq))
  }

  def deleteDocMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      id <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("delete")), EString(id))), DeleteDocMsg(id))
  }

  def diskSizeMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("disk_size")), EString(path))), DiskSizeMsg(path))
  }

  def group1MsgPairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      query          <- alphaNumericString
      field          <- alphaNumericString
      refresh        <- boolean
      (groupSort, _) <- anyP(depth)
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
  }

  def group2MsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      keys   <- setOf(alphaNumericStringBounded(1, 10))
      values <- listOfN(keys.size)(anyE(10))
      linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      pairs         = keys zip values
    } yield {
      pairs.foreach(i => linkedHashMap.put(EAtom(Symbol(i._1)), i._2))
      (
        ETuple(List(EAtom(Symbol("group2")), EMap(linkedHashMap))),
        Group2Msg((keys.map(Symbol(_)) zip values.map(toScala _)).toMap)
      )
    }
  }

  def openIndexMsgPairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      pid     <- pidE
      path    <- alphaNumericString
      options <- anyE(depth)
    } yield (
      ETuple(List(EAtom(Symbol("open")), pid, EString(path), options)),
      OpenIndexMsg(pid.asInstanceOf[EPid], path, options)
    )
  }

  def renamePathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      dbName <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("rename")), EString(dbName))), RenamePathMsg(dbName))
  }

  def searchRequestPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      keys   <- setOf(alphaNumericStringBounded(1, 10))
      values <- listOfN(keys.size)(anyE(10))
      linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      pairs         = keys zip values
    } yield {
      pairs.foreach(i => linkedHashMap.put(EAtom(Symbol(i._1)), i._2))
      (
        ETuple(List(EAtom(Symbol("search")), EMap(linkedHashMap))),
        SearchRequest((keys.map(Symbol(_)) zip values.map(toScala _)).toMap)
      )
    }
  }

  def setPurgeSeqMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("set_purge_seq")), ELong(seq))), SetPurgeSeqMsg(seq))
  }

  def setUpdateSeqMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("set_update_seq")), ELong(seq))), SetUpdateSeqMsg(seq))
  }

  def anyMessagePairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] = {
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
}
