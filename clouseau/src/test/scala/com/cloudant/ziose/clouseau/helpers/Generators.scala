package com.cloudant.ziose.clouseau.helpers

import com.cloudant.ziose.core.Codec
import com.cloudant.ziose.core.Codec._
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
      ETuple(EAtom("cleanup"), EBinary(dbName), EList(activeSigs.map(EBinary(_)))),
      CleanupDbMsg(dbName, activeSigs)
    )
  }

  def cleanupPathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      path <- alphaNumericString
    } yield (ETuple(EAtom("cleanup"), EBinary(path)), CleanupPathMsg(path))
  }

  def closeLRUByPathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      path <- alphaNumericString
    } yield (ETuple(EAtom("close_lru_by_path"), EBinary(path)), CloseLRUByPathMsg(path))
  }

  def commitMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(EAtom("commit"), ENumber(seq)), CommitMsg(seq))
  }

  def deleteDocMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      id <- alphaNumericString
    } yield (ETuple(EAtom("delete"), EBinary(id)), DeleteDocMsg(id))
  }

  def diskSizeMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      path <- alphaNumericString
    } yield (ETuple(EAtom("disk_size"), EBinary(path)), DiskSizeMsg(path))
  }

  def group1MsgPairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      query       <- alphaNumericString
      field       <- alphaNumericString
      refresh     <- boolean
      groupSort   <- oneOf(atomE, stringBinaryE, fieldsGen(1))
      groupOffset <- int(0, 10)
      groupLimit  <- int(0, 10)
    } yield (
      ETuple(
        EAtom("group1"),
        EBinary(query),
        EBinary(field),
        EBoolean(refresh),
        groupSort,
        ENumber(groupOffset),
        ENumber(groupLimit)
      ),
      Group1Msg(query, field, refresh, toScala(groupSort), groupOffset, groupLimit)
    )
  }

  def group2MsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      groupsValue <- listOf(queryArg)
      term = EList(groupsValue)
    } yield (
      ETuple(EAtom("group2"), term),
      Group2Msg((toScala(term).asInstanceOf[List[(Symbol, Any)]]).toMap)
    )
  }

  def openIndexMsgPairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      pid     <- pidE
      path    <- alphaNumericString
      options <- analyzerOptionsGen(depth)
    } yield (
      ETuple(EAtom("open"), pid, EBinary(path), options),
      OpenIndexMsg(pid.asInstanceOf[EPid], path, AnalyzerOptions.from(Codec.toScala(options)).get)
    )
  }

  def renamePathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      dbName <- alphaNumericString
    } yield (ETuple(EAtom("rename"), EBinary(dbName)), RenamePathMsg(dbName))
  }

  def searchRequestPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      eArgs <- listOf(searchArg)
      term  = EList(eArgs)
      sArgs = toScala(term).asInstanceOf[List[(Symbol, Any)]]
    } yield (
      ETuple(EAtom("search"), term),
      SearchRequest(sArgs.toMap)
    )
  }

  def setPurgeSeqMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(EAtom("set_purge_seq"), ENumber(seq)), SetPurgeSeqMsg(seq))
  }

  def setUpdateSeqMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] = {
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(EAtom("set_update_seq"), ENumber(seq)), SetUpdateSeqMsg(seq))
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

  def optionValueGen(depth: Int): Gen[Any, ETerm] = {
    termE(depth, oneOf(stringBinaryE, atomE, booleanE, intE))
  }

  def keyValuePairEGen(depth: Int): Gen[Any, ETerm] = {
    for {
      key   <- stringBinaryE
      value <- optionValueGen(depth)
    } yield ETuple(key, value)
  }

  def analyzerOptionsGen(depth: Int): Gen[Any, ETerm] = {
    oneOf(
      stringBinaryE,
      mapKVContainerE(stringBinaryE, listOf(optionValueGen(depth))),
      listContainerE(listOfN(1)(stringBinaryE)),
      listContainerE(listOf(keyValuePairEGen(depth)))
    )
  }

  def fieldsGen(size: Int): Gen[Any, ETerm] = for {
    term <- listContainerE(listOfN(size)(stringBinaryE))
  } yield term

  def nullE: Gen[Any, ETerm] = const(EAtom("null").asInstanceOf[ETerm])

  def sortValues: Gen[Any, ETerm] = oneOf(stringBinaryE, nullE)

  def groups: Gen[Any, ETerm] = {
    for {
      eKeys   <- fieldNames
      eValues <- listOfN(eKeys.size)(sortValues)
      ePairs  = eKeys.toList zip eValues
      eTuples = ePairs.map(e => ETuple(e._1, e._2))
    } yield EList(eTuples).asInstanceOf[ETerm]
  }

  def groupSort: Gen[Any, ETerm] = for {
    term <- oneOf(
      const(EAtom("relevance").asInstanceOf[ETerm]),
      stringBinaryE,
      listContainerE(listOf(stringBinaryE))
    )
  } yield term

  def queryArg: Gen[Any, ETerm] = for {
    stringValue    <- stringBinaryE
    booleanValue   <- booleanE
    groupsValue    <- groups
    groupSortValue <- groupSort
    limitValue     <- int(Int.MinValue, Int.MaxValue)
    fieldsValue    <- fieldNames
    tag            <- stringBinaryE
    intValue       <- int(Int.MinValue, Int.MaxValue)
    term <- oneOf(
      const(ETuple(EAtom("query"), stringValue)),
      const(ETuple(EAtom("field"), stringValue)),
      const(ETuple(EAtom("refresh"), booleanValue)),
      const(ETuple(EAtom("groups"), groupsValue)),
      const(ETuple(EAtom("group_sort"), groupSortValue)),
      const(ETuple(EAtom("limit"), ENumber(limitValue))),
      const(ETuple(EAtom("include_fields"), EList(fieldsValue))),
      const(ETuple(EAtom("highlight_fields"), EList(fieldsValue))),
      const(ETuple(EAtom("highlight_pre_tag"), tag)),
      const(ETuple(EAtom("highlight_post_tag"), tag)),
      const(ETuple(EAtom("highlight_number"), ENumber(intValue))),
      const(ETuple(EAtom("highlight_size"), ENumber(intValue)))
    )
  } yield term

  def searchArg: Gen[Any, ETerm] = for {
    stringValue    <- stringBinaryE
    partitionValue <- stringBinaryE
    bookmarkValue  <- stringBinaryE
    booleanValue   <- booleanE
    groupsValue    <- groups
    groupSortValue <- groupSort
    limitValue     <- int(Int.MinValue, Int.MaxValue)
    fieldsValue    <- fieldNames
    rangesValue    <- ranges
    tag            <- stringBinaryE
    intValue       <- int(Int.MinValue, Int.MaxValue)
    legacyValue    <- booleanE
    term <- oneOf(
      const(ETuple(EAtom("query"), stringValue)),
      const(ETuple(EAtom("partition"), partitionValue)),
      const(ETuple(EAtom("after"), bookmarkValue)),
      const(ETuple(EAtom("refresh"), booleanValue)),
      const(ETuple(EAtom("sort"), groupSortValue)),
      const(ETuple(EAtom("limit"), ENumber(limitValue))),
      const(ETuple(EAtom("include_fields"), EList(fieldsValue))),
      const(ETuple(EAtom("counts"), EList(fieldsValue))),
      const(ETuple(EAtom("ranges"), rangesValue)),
      const(ETuple(EAtom("highlight_fields"), EList(fieldsValue))),
      const(ETuple(EAtom("highlight_pre_tag"), tag)),
      const(ETuple(EAtom("highlight_post_tag"), tag)),
      const(ETuple(EAtom("highlight_number"), ENumber(intValue))),
      const(ETuple(EAtom("highlight_size"), ENumber(intValue))),
      const(ETuple(EAtom("legacy"), legacyValue))
    )
  } yield term

  def ranges: Gen[Any, ETerm] = for {
    names  <- fieldNames
    values <- listOfN(names.size)(listKVContainerE(stringBinaryE, listOf(stringBinaryE)))
    ePairs  = names.toList zip values
    eTuples = ePairs.map(e => ETuple(e._1, e._2))
  } yield EList(eTuples).asInstanceOf[ETerm]

  def fieldName: Gen[Any, ETerm] = for {
    s <- alphaNumericStringBounded(1, 10)
  } yield EBinary(s).asInstanceOf[ETerm]

  def fieldNames: Gen[Any, List[ETerm]] = for {
    s <- setOf(fieldName)
  } yield s.toList

}
