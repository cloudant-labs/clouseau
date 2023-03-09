package com.cloudant.zio.actors.helpers

import com.cloudant.zio.actors._
import com.cloudant.zio.actors.Codec._
import com.ericsson.otp.erlang._
import scala.collection.mutable
import zio.Random
import zio.test.{Gen, Sized}
import zio.test.Gen._

object Generators {

  /**
   * Naming convention:
   *   - somethingE: produces stream of ETerm objects
   *   - somethingO: produces stream of OtpErlangObject objects
   *   - somethingP: produces stream of (ETerm, OtpErlangObject) pairs
   */
  type SamplePair = (ETerm, OtpErlangObject)

  /**
   * A generator of ETerm objects representing EAtom variant. Shrinks toward the "" (empty string) atom.
   */
  def atomE: Gen[Any, ETerm] =
    for { xs <- alphaNumericString } yield EAtom(Symbol(xs))

  /**
   * A generator of ETerm objects representing EBoolean variant. Shrinks toward 'false'.
   */
  def booleanE: Gen[Any, ETerm] =
    for { b <- boolean } yield EBoolean(b)

  /**
   * A generator of ETerm objects representing EInt variant. Shrinks toward 0.
   */
  def intE: Gen[Any, ETerm] =
    for { i <- int(Int.MinValue, Int.MaxValue) } yield EInt(i)

  /**
   * A generator of ETerm objects representing ELong variant. Shrinks toward 0.
   */
  def longE: Gen[Any, ETerm] =
    for { i <- long(Long.MinValue, Long.MaxValue) } yield ELong(i)

  /**
   * A generator of ETerm objects representing EString variant. Shrinks toward empty string.
   */
  def stringE: Gen[Any, ETerm] =
    for { s <- asciiString } yield EString(s)

  /**
   * A generator of ETerm objects representing EPid variant. Shrinks toward EPid("", 0, 0, 0).
   */
  def pidE: Gen[Any, ETerm] =
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield EPid(node, id, serial, creation)

  /**
   * A generator of ETerm objects. The generated terms can be nested.
   *
   * Same as `termE(n, oneOf(stringE, atomE, booleanE, intE, longE, pidE))`
   */
  def anyE(n: Int): Gen[Any, ETerm] =
    termE(n, oneOf(stringE, atomE, booleanE, intE, longE, pidE))

  /**
   * A generator of ETerm objects representing ETuple variant.
   *
   * The generated terms can be nested.
   */
  def tupleE(n: Int): Gen[Any, ETerm] =
    for { term <- tupleContainerE(listOf(anyE(n))) } yield term

  /**
   * A generator of ETerm objects representing EList variant.
   *
   * The generated terms can be nested.
   */
  def listE(n: Int): Gen[Any, ETerm] =
    for { term <- listContainerE(listOf(anyE(n))) } yield term

  /**
   * A generator of ETerm objects representing EMap variant.
   *
   * The generated terms can be nested.
   */
  def mapE(n: Int): Gen[Any, ETerm] =
    for { term <- mapContainerE(listOf(anyE(n))) } yield term

  /**
   * A generator of ETerm objects. The generated terms can be nested.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `termE(n, oneOf(intE, longE))` would produce list of integers, tuple of integers, map where values
   * are integers.
   */
  def termE(n: Int, g: Gen[Any, ETerm]): Gen[Any, ETerm] =
    for {
      (term, _) <- treeP(n, liftE(g))
    } yield term

  /**
   * A generator of ETerm objects representing EList.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `listContainerE(n, oneOf(intE, longE))` would produce list of integers.
   */
  def listContainerE(g: Gen[Any, List[ETerm]]): Gen[Any, ETerm] = suspend {
    for { children <- g } yield EList(children)
  }

  /**
   * A generator of ETerm objects representing ETuple.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `tupleContainerE(n, oneOf(intE, longE))` would produce tuple of integers.
   */
  def tupleContainerE(g: Gen[Any, List[ETerm]]): Gen[Any, ETerm] = suspend {
    for { children <- g } yield ETuple(children)
  }

  /**
   * A generator of ETerm objects representing EMap.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `mapContainerE(n, oneOf(intE, longE))` would produce map where keys are strings and values are
   * integers.
   */
  def mapContainerE(g: Gen[Any, List[ETerm]]): Gen[Any, ETerm] = suspend {
    g.flatMap { children =>
      for {
        keys <- listOfN(children.size)(stringP)
        elements      = keys zip children.asInstanceOf[List[SamplePair]]
        linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
        _ = elements.foreach { case ((eKey, _), (eTerm, _)) =>
          linkedHashMap.put(eKey, eTerm)
        }
      } yield EMap(linkedHashMap)
    }
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangAtom variant.
   *
   * Shrinks toward the "" (empty string) atom.
   */
  def atomO: Gen[Any, OtpErlangObject] =
    for { s <- alphaNumericString } yield new OtpErlangAtom(s)

  /**
   * A generator of OtpErlangObject objects representing OtpErlangBoolean variant.
   *
   * Shrinks toward 'false'.
   */
  def booleanO: Gen[Any, OtpErlangObject] =
    for { b <- boolean } yield new OtpErlangBoolean(b)

  /**
   * A generator of OtpErlangObject objects representing OtpErlangInt variant.
   *
   * Shrinks toward '0'.
   */
  def intO: Gen[Any, OtpErlangObject] =
    for { i <- int(Int.MinValue, Int.MaxValue) } yield new OtpErlangInt(i)

  /**
   * A generator of OtpErlangObject objects representing OtpErlangLong variant.
   *
   * Shrinks toward '0'.
   */
  def longO: Gen[Any, OtpErlangObject] =
    for { i <- bigIntegerJava(Long.MinValue, Long.MaxValue) } yield new OtpErlangLong(i)

  /**
   * A generator of OtpErlangObject objects representing OtpErlangString variant.
   *
   * Shrinks toward empty string.
   */
  def stringO: Gen[Any, OtpErlangObject] =
    for { s <- asciiString } yield new OtpErlangString(s)

  /**
   * A generator of OtpErlangObject objects representing OtpErlangPid variant.
   *
   * Shrinks toward OtpErlangPid("", 0, 0, 0).
   */
  def pidO: Gen[Any, OtpErlangObject] =
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield new OtpErlangPid(node, id, serial, creation)

  /**
   * A generator of OtpErlangObject objects. The generated terms can be nested.
   *
   * Same as `termO(n, oneOf(stringO, atomO, booleanO, intO, longO, pidO))`
   */
  def anyO(n: Int): Gen[Any, OtpErlangObject] =
    termO(n, oneOf(stringO, atomO, booleanO, intO, longO, pidO))

  /**
   * A generator of OtpErlangObject objects representing OtpErlangTuple variant.
   *
   * The generated terms can be nested.
   */
  def tupleO(n: Int): Gen[Any, OtpErlangObject] =
    for { term <- tupleContainerO(listOf(anyO(n))) } yield term

  /**
   * A generator of OtpErlangObject objects representing OtpErlangList variant.
   *
   * The generated terms can be nested.
   */
  def listO(n: Int): Gen[Any, OtpErlangObject] =
    for { term <- listContainerO(listOf(anyO(n))) } yield term

  /**
   * A generator of OtpErlangObject objects representing OtpErlangMap variant.
   *
   * The generated terms can be nested.
   */
  def mapO(n: Int): Gen[Any, OtpErlangObject] =
    for { term <- mapContainerO(listOf(anyO(n))) } yield term

  /**
   * A generator of OtpErlangObject objects. The generated terms can be nested.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `termO(n, oneOf(intO, longO))` would produce list of integers, tuple of integers, map where values
   * are integers.
   */
  def termO(n: Int, g: Gen[Any, OtpErlangObject]): Gen[Any, OtpErlangObject] =
    for { (_, term) <- treeP(n, liftO(g)) } yield term

  /**
   * A generator of OtpErlangObject objects representing EList.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `listContainerO(n, oneOf(intO, longO))` would produce list of integers.
   */
  def listContainerO(g: Gen[Any, List[OtpErlangObject]]): Gen[Any, OtpErlangObject] =
    suspend(for { children <- g } yield new OtpErlangList(children.toArray))

  /**
   * A generator of OtpErlangObject objects representing ETuple.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `tupleContainerO(n, oneOf(intO, longO))` would produce tuple of integers.
   */
  def tupleContainerO(g: Gen[Any, List[OtpErlangObject]]): Gen[Any, OtpErlangObject] =
    suspend(for { children <- g } yield new OtpErlangTuple(children.toArray))

  /**
   * A generator of OtpErlangObject objects representing EMap.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `mapContainerO(n, oneOf(intO, longO))` would produce map where keys are strings and values are
   * integers.
   */
  def mapContainerO(g: Gen[Any, List[OtpErlangObject]]): Gen[Any, OtpErlangObject] =
    suspend {
      g.flatMap { children =>
        for {
          keys <- listOfN(children.size)(stringP)
          elements     = keys zip children.asInstanceOf[List[SamplePair]]
          otpErlangMap = new OtpErlangMap
          _ = elements.foreach { case ((_, otpKey), (_, otpTerm)) =>
            otpErlangMap.put(otpKey, otpTerm)
          }
        } yield otpErlangMap
      }
    }

  /**
   * A generator of tuples (EAtom, OtpErlangAtom).
   *
   * Shrinks toward the (EAtom(''), OtpErlangAtom('')) (empty string) atom.
   */
  def atomP: Gen[Any, SamplePair] =
    for { s <- alphaNumericString } yield (EAtom(Symbol(s)), new OtpErlangAtom(s))

  /**
   * A generator of tuples (EBoolean, OtpErlangBoolean).
   *
   * Shrinks toward the (EBoolean(false), OtpErlangBoolean(false)).
   */
  def booleanP: Gen[Any, SamplePair] =
    for { b <- boolean } yield (EBoolean(b), new OtpErlangBoolean(b))

  /**
   * A generator of tuples (EInt, OtpErlangInt).
   *
   * Shrinks toward the (EInt(0), OtpErlangInt(0)).
   */
  def intP: Gen[Any, SamplePair] =
    for { i <- int(Int.MinValue, Int.MaxValue) } yield (EInt(i), new OtpErlangInt(i))

  /**
   * A generator of tuples (ELong, OtpErlangLong).
   *
   * Shrinks toward the (ELong(0), OtpErlangLong(0)).
   */
  def longP: Gen[Any, SamplePair] =
    for { i <- bigIntegerJava(Long.MinValue, Long.MaxValue) } yield (ELong(i), new OtpErlangLong(i))

  /**
   * A generator of tuples (EString, OtpErlangString).
   *
   * Shrinks toward the (EString(""), OtpErlangString("")).
   */
  def stringP: Gen[Any, SamplePair] =
    for { s <- asciiString } yield (EString(s), new OtpErlangString(s))

  /**
   * A generator of tuples (EPid, OtpErlangPid) objects representing OtpErlangPid variant.
   *
   * Shrinks toward (EPid("", 0, 0, 0), OtpErlangPid("", 0, 0, 0)).
   */
  def pidP: Gen[Any, SamplePair] =
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield (EPid(node, id, serial, creation), new OtpErlangPid(node, id, serial, creation))

  /**
   * A generator of tuples (ETerm, OtpErlangObject) objects. The generated terms can be nested.
   *
   * Same as `termP(n, oneOf(stringP, atomP, booleanP, intP, longP, pidP))`
   */
  def anyP(n: Int): Gen[Any, SamplePair] =
    treeP(n, oneOf(stringP, atomP, booleanP, intP, longP, pidP))

  /**
   * A generator of tuples (ETuple, OtpErlangTuple). The generated terms can be nested.
   */
  def tupleP(n: Int): Gen[Any, SamplePair] =
    for { term <- tupleContainerP(listOf(anyP(n))) } yield term

  /**
   * A generator of tuples (EList, OtpErlangList). The generated terms can be nested.
   */
  def listP(n: Int): Gen[Any, SamplePair] =
    for { term <- listContainerP(listOf(anyP(n))) } yield term

  /**
   * A generator of tuples (EMap, OtpErlangMap). The generated terms can be nested.
   */
  def mapP(n: Int): Gen[Any, SamplePair] =
    for { term <- mapContainerP(listOf(anyP(n))) } yield term

  /**
   * A generator of tuples (ETerm, OtpErlangObject) objects. The generated terms can be nested.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `termP(n, oneOf(intP, longP))` would produce integers and terms which include list of integers,
   * tuple of integers, map where values are integers.
   */
  def termP(n: Int, g: Gen[Any, SamplePair]): Gen[Any, SamplePair] =
    treeP(n, g)

  /**
   * A generator of tuples (EList, OtpErlangList) objects.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `listContainerP(n, oneOf(intP, longP))` would produce list of integers.
   */
  def listContainerP(g: Gen[Any, List[SamplePair]]): Gen[Any, SamplePair] = suspend {
    for {
      children <- g
      (eTerms, otpTerms) = children.unzip
    } yield (EList(eTerms), new OtpErlangList(otpTerms.toArray))
  }

  /**
   * A generator of tuples (ETuple, OtpErlangTuple) objects.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `tupleContainerP(n, oneOf(intP, longP))` would produce tuple of integers.
   */
  def tupleContainerP(g: Gen[Any, List[SamplePair]]): Gen[Any, SamplePair] = suspend {
    for {
      children <- g
      (eTerms, otpTerms) = children.unzip
    } yield (ETuple(eTerms), new OtpErlangTuple(otpTerms.toArray))
  }

  /**
   * A generator of tuples (ETerm, OtpErlangObject) objects.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `mapContainerP(n, oneOf(intP, longP))` would produce map where keys are strings and values are
   * integers.
   */
  def mapContainerP(g: Gen[Any, List[SamplePair]]): Gen[Any, SamplePair] = suspend {
    g.flatMap { children =>
      for {
        keys <- listOfN(children.size)(stringP)
        elements      = keys zip children
        otpErlangMap  = new OtpErlangMap
        linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
        _ = elements.foreach { case ((eKey, otpKey), (eTerm, otpTerm)) =>
          linkedHashMap.put(eKey, eTerm)
          otpErlangMap.put(otpKey, otpTerm)
        }
      } yield (EMap(linkedHashMap), otpErlangMap)
    }
  }

  private def childrenP(
    n: Int,
    g: Gen[Any, SamplePair]
  ): Gen[Any, List[(ETerm, OtpErlangObject)]] = suspend {
    for {
      i <- Gen.int(1, n - 1)
      r <- listOfN(n - i)(treeP(i, g))
    } yield r
  }

  private def nodeP(n: Int, g: Gen[Any, SamplePair]): Gen[Any, (ETerm, OtpErlangObject)] =
    suspend {
      for {
        container <- oneOf(
          listContainerP(childrenP(n, g)),
          tupleContainerP(childrenP(n, g)),
          mapContainerP(childrenP(n, g))
        )
      } yield container
    }

  private def treeP(n: Int, g: Gen[Any, SamplePair]): Gen[Any, SamplePair] = suspend {
    if (n == 1) g
    else oneOf(nodeP(n, g), g)
  }

  /**
   * Converts generator of OtpErlangObject objects into generator of (ETerm, OtpErlangObject)
   */
  private def liftO(g: Gen[Any, OtpErlangObject]): Gen[Any, SamplePair] =
    for { o <- g } yield (EList(List.empty), o)

  /**
   * Converts generator of ETerm objects into generator of (ETerm, OtpErlangObject)
   */
  private def liftE(g: Gen[Any, ETerm]): Gen[Any, SamplePair] =
    for { e <- g } yield (e, new OtpErlangList())

  /* --------------- ClouseauMessagePairGen --------------- */
  def cleanupDbMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      dbName     <- alphaNumericString
      activeSigs <- listOf(alphaNumericString)
    } yield (
      ETuple(List(EAtom(Symbol("cleanup")), EString(dbName), EList(activeSigs.map(EString)))),
      CleanupDbMsg(dbName, activeSigs)
    )

  def cleanupPathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("cleanup")), EString(path))), CleanupPathMsg(path))

  def closeLRUByPathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("close_lru_by_path")), EString(path))), CloseLRUByPathMsg(path))

  def commitMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("commit")), ELong(seq))), CommitMsg(seq))

  def deleteDocMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      id <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("delete")), EString(id))), DeleteDocMsg(id))

  def diskSizeMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      path <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("disk_size")), EString(path))), DiskSizeMsg(path))

  def group1MsgPairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] =
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

  def group2MsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      keys   <- setOf(alphaNumericStringBounded(1, 10))
      values <- listOfN(keys.size)(anyE(10))
      linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      pairs         = keys zip values
    } yield {
      pairs.foreach(i => linkedHashMap.put(EAtom(Symbol(i._1)), i._2))
      (
        ETuple(List(EAtom(Symbol("group2")), EMap(linkedHashMap))),
        Group2Msg((keys.map(Symbol(_)) zip values.map(getValue)).toMap)
      )
    }

  def openIndexMsgPairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      pid     <- pidE
      path    <- alphaNumericString
      options <- anyE(depth)
    } yield (
      ETuple(List(EAtom(Symbol("open")), pid, EString(path), options)),
      OpenIndexMsg(pid.asInstanceOf[EPid], path, options)
    )

  def renamePathMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      dbName <- alphaNumericString
    } yield (ETuple(List(EAtom(Symbol("rename")), EString(dbName))), RenamePathMsg(dbName))

  def searchRequestPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      keys   <- setOf(alphaNumericStringBounded(1, 10))
      values <- listOfN(keys.size)(anyE(10))
      linkedHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
      pairs         = keys zip values
    } yield {
      pairs.foreach(i => linkedHashMap.put(EAtom(Symbol(i._1)), i._2))
      (
        ETuple(List(EAtom(Symbol("search")), EMap(linkedHashMap))),
        SearchRequest((keys.map(Symbol(_)) zip values.map(getValue)).toMap)
      )
    }

  def setPurgeSeqMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("set_purge_seq")), ELong(seq))), SetPurgeSeqMsg(seq))

  def setUpdateSeqMsgPairGen: Gen[Any, (ETerm, ClouseauMessage)] =
    for {
      seq <- long(Long.MinValue, Long.MaxValue)
    } yield (ETuple(List(EAtom(Symbol("set_update_seq")), ELong(seq))), SetUpdateSeqMsg(seq))

  def anyMessagePairGen(depth: Int): Gen[Any, (ETerm, ClouseauMessage)] =
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
