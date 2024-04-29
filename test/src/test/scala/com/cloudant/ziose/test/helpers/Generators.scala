package com.cloudant.ziose.test.helpers

import com.cloudant.ziose.core.Codec._
import com.ericsson.otp.erlang._
import scala.collection.mutable
import zio.test.Gen
import zio.test.Gen._

object Generators {

  /**
   * Naming convention:
   *   - somethingE: produces stream of ETerm objects
   *   - somethingO: produces stream of OtpErlangObject objects
   *   - somethingP: produces stream of (ETerm, OtpErlangObject) pairs
   *   - somethingEq: produces stream of equal terms (ETerm, ETerm)
   */
  type SamplePair = (ETerm, OtpErlangObject)
  type EqPair     = (ETerm, ETerm)

  /**
   * A generator of ETerm objects representing EAtom variant. Shrinks toward the "" (empty string) atom.
   */
  def atomE: Gen[Any, ETerm] = {
    for { xs <- alphaNumericString } yield EAtom(Symbol(xs))
  }

  /**
   * A generator of ETerm objects representing EBoolean variant. Shrinks toward 'false'.
   */
  def booleanE: Gen[Any, ETerm] = {
    for { b <- boolean } yield EBoolean(b)
  }

  /**
   * A generator of ETerm objects representing EInt variant. Shrinks toward 0.
   */
  def intE: Gen[Any, ETerm] = {
    for { i <- int(Int.MinValue, Int.MaxValue) } yield EInt(i)
  }

  /**
   * A generator of ETerm objects representing ELong variant. Shrinks toward 0.
   */
  def longE: Gen[Any, ETerm] = {
    for { i <- long(Long.MinValue, Long.MaxValue) } yield ELong(i)
  }

  /**
   * A generator of ETerm objects representing EString variant. Shrinks toward empty string.
   */
  def stringE: Gen[Any, ETerm] = {
    for { s <- asciiString } yield EString(s)
  }

  /**
   * A generator of ETerm objects representing EPid variant. Shrinks toward EPid("", 0, 0, 0).
   */
  def pidE: Gen[Any, ETerm] = {
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield EPid(node, id, serial, creation)
  }

  /**
   * A generator of ETerm objects representing ERef variant. Shrinks toward ERef("", Array(), 0).
   */
  def refE: Gen[Any, ETerm] = {
    for {
      // empty string is special so we avoid generating it
      node <- alphaNumericStringBounded(1, 256)
      // values from 0..3 considered special that's why we start from 5
      ids      <- listOfBounded(1, 3)(int(5, Int.MaxValue))
      creation <- int(0, 10)
    } yield ERef(node, ids.toArray, creation)
  }

  /**
   * A generator of ETerm objects. The generated terms can be nested.
   *
   * Same as `termE(n, oneOf(stringE, atomE, booleanE, intE, longE, pidE, refE))`
   */
  def anyE(n: Int): Gen[Any, ETerm] = {
    termE(n, oneOf(stringE, atomE, booleanE, intE, longE, pidE, refE))
  }

  /**
   * A generator of ETerm objects representing ETuple variant.
   *
   * The generated terms can be nested.
   */
  def tupleE(n: Int): Gen[Any, ETerm] = {
    for { term <- tupleContainerE(listOf(anyE(n))) } yield term
  }

  /**
   * A generator of ETerm objects representing EList variant.
   *
   * The generated terms can be nested.
   */
  def listE(n: Int): Gen[Any, ETerm] = {
    for { term <- listContainerE(listOf(anyE(n))) } yield term
  }

  /**
   * A generator of ETerm objects representing EMap variant.
   *
   * The generated terms can be nested.
   */
  def mapE(n: Int): Gen[Any, ETerm] = {
    for { term <- mapContainerE(listOf(anyE(n))) } yield term
  }

  /**
   * A generator of ETerm objects. The generated terms can be nested.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `termE(n, oneOf(intE, longE))` would produce list of integers, tuple of integers, map where values
   * are integers.
   */
  def termE(n: Int, g: Gen[Any, ETerm]): Gen[Any, ETerm] = {
    for {
      (term, _) <- treeP(n, liftE(g))
    } yield term
  }

  /**
   * A generator of ETerm objects representing EList.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `listContainerE(n, oneOf(intE, longE))` would produce list of integers.
   */
  def listContainerE(g: Gen[Any, List[ETerm]]): Gen[Any, ETerm] = suspend {
    for {
      maybeIsProper <- boolean
      children      <- g
      isProper = {
        if (children.size < 2) { true }
        else { maybeIsProper }
      }
    } yield new EList(children, isProper)
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
    for {
      children <- g
      elements = children.zip(children)
      emap = elements.foldLeft(mutable.LinkedHashMap.empty[ETerm, ETerm]) { case (a, (k, v)) =>
        a += (k -> v)
      }
    } yield EMap(emap)
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangAtom variant.
   *
   * Shrinks toward the "" (empty string) atom.
   */
  def atomO: Gen[Any, OtpErlangObject] = {
    for { s <- alphaNumericString } yield new OtpErlangAtom(s)
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangBoolean variant.
   *
   * Shrinks toward 'false'.
   */
  def booleanO: Gen[Any, OtpErlangObject] = {
    for { b <- boolean } yield new OtpErlangBoolean(b)
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangInt variant.
   *
   * Shrinks toward '0'.
   */
  def intO: Gen[Any, OtpErlangObject] = {
    for { i <- int(Int.MinValue, Int.MaxValue) } yield new OtpErlangInt(i)
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangLong variant.
   *
   * Shrinks toward '0'.
   */
  def longO: Gen[Any, OtpErlangObject] = {
    for { i <- bigIntegerJava(Long.MinValue, Long.MaxValue) } yield new OtpErlangLong(i)
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangString variant.
   *
   * Shrinks toward empty string.
   */
  def stringO: Gen[Any, OtpErlangObject] = {
    for { s <- asciiString } yield new OtpErlangString(s)
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangPid variant.
   *
   * Shrinks toward OtpErlangPid("", 0, 0, 0).
   */
  def pidO: Gen[Any, OtpErlangObject] = {
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield new OtpErlangPid(node, id, serial, creation)
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangRef variant.
   *
   * Shrinks toward OtpErlangRef("", Array(), 0).
   */
  def refO: Gen[Any, OtpErlangObject] = {
    for {
      // empty string is special so we avoid generating it
      node <- alphaNumericStringBounded(1, 256)
      // values from 0..3 considered special that's why we start from 5
      ids      <- listOfBounded(1, 3)(int(5, Int.MaxValue))
      creation <- int(0, 10)
    } yield new OtpErlangRef(node, ids.toArray, creation)
  }

  // Gen.sized.flatMap(listOfN(_))
  // ids      <- listOf(int(Int.MinValue, Int.MaxValue))

  /**
   * A generator of OtpErlangObject objects. The generated terms can be nested.
   *
   * Same as `termO(n, oneOf(stringO, atomO, booleanO, intO, longO, pidO, refO))`
   */
  def anyO(n: Int): Gen[Any, OtpErlangObject] = {
    termO(n, oneOf(stringO, atomO, booleanO, intO, longO, pidO, refO))
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangTuple variant.
   *
   * The generated terms can be nested.
   */
  def tupleO(n: Int): Gen[Any, OtpErlangObject] = {
    for { term <- tupleContainerO(listOf(anyO(n))) } yield term
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangList variant.
   *
   * The generated terms can be nested.
   */
  def listO(n: Int): Gen[Any, OtpErlangObject] = {
    for { term <- listContainerO(listOf(anyO(n))) } yield term
  }

  /**
   * A generator of OtpErlangObject objects representing OtpErlangMap variant.
   *
   * The generated terms can be nested.
   */
  def mapO(n: Int): Gen[Any, OtpErlangObject] = {
    for { term <- mapContainerO(listOf(anyO(n))) } yield term
  }

  /**
   * A generator of OtpErlangObject objects. The generated terms can be nested.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `termO(n, oneOf(intO, longO))` would produce list of integers, tuple of integers, map where values
   * are integers.
   */
  def termO(n: Int, g: Gen[Any, OtpErlangObject]): Gen[Any, OtpErlangObject] = {
    for { (_, term) <- treeP(n, liftO(g)) } yield term
  }

  /**
   * A generator of OtpErlangObject objects representing EList.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `listContainerO(n, oneOf(intO, longO))` would produce list of integers.
   */
  def listContainerO(g: Gen[Any, List[OtpErlangObject]]): Gen[Any, OtpErlangObject] = {
    suspend(for { children <- g } yield new OtpErlangList(children.toArray))
  }

  /**
   * A generator of OtpErlangObject objects representing ETuple.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `tupleContainerO(n, oneOf(intO, longO))` would produce tuple of integers.
   */
  def tupleContainerO(g: Gen[Any, List[OtpErlangObject]]): Gen[Any, OtpErlangObject] = {
    suspend(for { children <- g } yield new OtpErlangTuple(children.toArray))
  }

  /**
   * A generator of OtpErlangObject objects representing EMap.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `mapContainerO(n, oneOf(intO, longO))` would produce map where keys are strings and values are
   * integers.
   */
  def mapContainerO(g: Gen[Any, List[OtpErlangObject]]): Gen[Any, OtpErlangObject] = {
    suspend {
      for {
        children <- g
        elements     = children.zip(children)
        otpErlangMap = new OtpErlangMap
        _ = elements.foreach { case (otpKey, otpTerm) =>
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
  def atomP: Gen[Any, SamplePair] = {
    for { s <- alphaNumericString } yield (EAtom(Symbol(s)), new OtpErlangAtom(s))
  }

  def atomSP: Gen[Any, SamplePair] = {
    for { s <- alphaNumericString } yield (EAtom(s), new OtpErlangAtom(s))
  }

  /**
   * A generator of tuples (EBoolean, OtpErlangBoolean).
   *
   * Shrinks toward the (EBoolean(false), OtpErlangBoolean(false)).
   */
  def booleanP: Gen[Any, SamplePair] = {
    for { b <- boolean } yield (EBoolean(b), new OtpErlangBoolean(b))
  }

  /**
   * A generator of tuples (EInt, OtpErlangInt).
   *
   * Shrinks toward the (EInt(0), OtpErlangInt(0)).
   */
  def intP: Gen[Any, SamplePair] = {
    for { i <- int(Int.MinValue, Int.MaxValue) } yield (EInt(i), new OtpErlangInt(i))
  }

  /**
   * A generator of tuples (ELong, OtpErlangLong).
   *
   * Shrinks toward the (ELong(0), OtpErlangLong(0)).
   */
  def longP: Gen[Any, SamplePair] = {
    for { i <- bigIntegerJava(Long.MinValue, Long.MaxValue) } yield (ELong(i), new OtpErlangLong(i))
  }

  /**
   * A generator of tuples (EString, OtpErlangString).
   *
   * Shrinks toward the (EString(""), OtpErlangString("")).
   */
  def stringP: Gen[Any, SamplePair] = {
    for { s <- asciiString } yield (EString(s), new OtpErlangString(s))
  }

  /**
   * A generator of tuples (EPid, OtpErlangPid) objects representing OtpErlangPid variant.
   *
   * Shrinks toward (EPid("", 0, 0, 0), OtpErlangPid("", 0, 0, 0)).
   */
  def pidP: Gen[Any, SamplePair] = {
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield (EPid(node, id, serial, creation), new OtpErlangPid(node, id, serial, creation))
  }

  /**
   * A generator of tuples (ERef, OtpErlangRef) objects representing OtpErlangRef variant.
   *
   * Shrinks toward (ERef("", Array(), 0), OtpErlangRef("", Array(), 0)).
   */
  def refP: Gen[Any, SamplePair] = {
    for {
      // empty string is special so we avoid generating it
      node <- alphaNumericStringBounded(1, 256)
      // values from 0..3 considered special that's why we start from 5
      ids      <- listOfBounded(1, 3)(int(5, Int.MaxValue))
      creation <- int(0, 10)
    } yield {
      val idsArray = ids.toArray
      (ERef(node, idsArray, creation), new OtpErlangRef(node, idsArray, creation))
    }
  }

  /**
   * A generator of tuples (ETerm, OtpErlangObject) objects. The generated terms can be nested.
   *
   * Same as `termP(n, oneOf(stringP, atomP, booleanP, intP, longP, pidP, refP))`
   */
  def anyP(n: Int): Gen[Any, SamplePair] = {
    treeP(n, oneOf(stringP, atomP, booleanP, intP, longP, pidP, refP))
  }

  /**
   * A generator of tuples (ETuple, OtpErlangTuple). The generated terms can be nested.
   */
  def tupleP(n: Int): Gen[Any, SamplePair] = {
    for { term <- tupleContainerP(listOf(anyP(n))) } yield term
  }

  /**
   * A generator of tuples (EList, OtpErlangList). The generated terms can be nested.
   */
  def listP(n: Int): Gen[Any, SamplePair] = {
    for { term <- listContainerP(listOf(anyP(n))) } yield term
  }

  /**
   * A generator of tuples (EMap, OtpErlangMap). The generated terms can be nested.
   */
  def mapP(n: Int): Gen[Any, SamplePair] = {
    for { term <- mapContainerP(listOf(anyP(n))) } yield term
  }

  /**
   * A generator of tuples (ETerm, OtpErlangObject) objects. The generated terms can be nested.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `termP(n, oneOf(intP, longP))` would produce integers and terms which include list of integers,
   * tuple of integers, map where values are integers.
   */
  def termP(n: Int, g: Gen[Any, SamplePair]): Gen[Any, SamplePair] = {
    treeP(n, g)
  }

  /**
   * A generator of tuples (EList, OtpErlangList) objects.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `listContainerP(n, oneOf(intP, longP))` would produce list of integers.
   */
  def listContainerP(g: Gen[Any, List[SamplePair]]): Gen[Any, SamplePair] = suspend {
    for {
      maybeIsProper <- boolean
      children      <- g
      isProper = {
        if (children.size < 2) { true }
        else { maybeIsProper }
      }
      (eTerms, otpTerms) = children.unzip
    } yield (new EList(eTerms, isProper), otpList(otpTerms, isProper))
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

  private def nodeP(n: Int, g: Gen[Any, SamplePair]): Gen[Any, (ETerm, OtpErlangObject)] = {
    suspend {
      for {
        container <- oneOf(
          listContainerP(childrenP(n, g)),
          tupleContainerP(childrenP(n, g)),
          mapContainerP(childrenP(n, g))
        )
      } yield container
    }
  }

  private def treeP(n: Int, g: Gen[Any, SamplePair]): Gen[Any, SamplePair] = suspend {
    if (n == 1) g
    else oneOf(nodeP(n, g), g)
  }

  /**
   * A generator of tuples containing equal elements (EAtom, EAtom).
   *
   * Shrinks toward the (EAtom(''), EAtom('')) (empty string) atom.
   */
  def atomEq: Gen[Any, EqPair] = {
    for { s <- alphaNumericString } yield (EAtom(Symbol(s)), EAtom(Symbol(s)))
  }

  /**
   * A generator of tuples containing equal elements (EBoolean, EBoolean).
   *
   * Shrinks toward the (EBoolean(false), EBoolean(false)).
   */
  def booleanEq: Gen[Any, EqPair] = {
    for { b <- boolean } yield (EBoolean(b), EBoolean(b))
  }

  /**
   * A generator of tuples containing equal elements (EInt, EInt).
   *
   * Shrinks toward the (EInt(0), EInt(0)).
   */
  def intEq: Gen[Any, EqPair] = {
    for { i <- int(Int.MinValue, Int.MaxValue) } yield (EInt(i), EInt(i))
  }

  /**
   * A generator of tuples containing equal elements (ELong, ELong).
   *
   * Shrinks toward the (ELong(0), ELong(0)).
   */
  def longEq: Gen[Any, EqPair] = {
    for { i <- bigIntegerJava(Long.MinValue, Long.MaxValue) } yield (ELong(i), ELong(i))
  }

  /**
   * A generator of tuples containing equal elements (EString, EString).
   *
   * Shrinks toward the (EString(""), EString("")).
   */
  def stringEq: Gen[Any, EqPair] = {
    for { s <- asciiString } yield (EString(s), EString(s))
  }

  /**
   * A generator of tuples containing equal elements (EPid, EPid) objects representing EPid variant.
   *
   * Shrinks toward (EPid("", 0, 0, 0), EPid("", 0, 0, 0)).
   */
  def pidEq: Gen[Any, EqPair] = {
    for {
      node     <- alphaNumericString
      id       <- int(0, 10)
      serial   <- int(0, 10)
      creation <- int(0, 10)
    } yield (EPid(node, id, serial, creation), EPid(node, id, serial, creation))
  }

  /**
   * A generator of tuples containing equal elements (ERef, ERef) objects representing ERef variant.
   *
   * Shrinks toward (ERef("", Array(), 0), ERef("", Array(), 0)).
   */
  def refEq: Gen[Any, EqPair] = {
    for {
      node     <- alphaNumericString
      ids      <- listOf(int(Int.MinValue, Int.MaxValue))
      creation <- int(0, 10)
    } yield (ERef(node, ids.toArray, creation), ERef(node, ids.toArray, creation))
  }

  /**
   * A generator of tuples containing equal elements (ETerm, ETerm) objects. The generated terms can be nested.
   *
   * Same as `termEq(n, oneOf(stringEq, atomEq, booleanEq, intEq, longEq, pidEq, refEq))`
   */
  def anyEq(n: Int): Gen[Any, EqPair] = {
    treeEq(n, oneOf(stringEq, atomEq, booleanEq, intEq, longEq, pidEq, refEq))
  }

  /**
   * A generator of tuples containing equal elements (ETuple, ETuple). The generated terms can be nested.
   */
  def tupleEq(n: Int): Gen[Any, EqPair] = {
    for { term <- tupleContainerEq(listOf(anyEq(n))) } yield term
  }

  /**
   * A generator of tuples containing equal elements (EList, EList). The generated terms can be nested.
   */
  def listEq(n: Int): Gen[Any, EqPair] = {
    for { term <- listContainerEq(listOf(anyEq(n))) } yield term
  }

  /**
   * A generator of tuples containing equal elements (EMap, EMap). The generated terms can be nested.
   */
  def mapEq(n: Int): Gen[Any, EqPair] = {
    for { term <- mapContainerEq(listOf(anyEq(n))) } yield term
  }

  /**
   * A generator of tuples containing equal elements (ETerm, ETerm) objects. The generated terms can be nested.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `termEq(n, oneOf(intEq, longEq))` would produce integers and terms which include list of integers,
   * tuple of integers, map where values are integers.
   */
  def termEq(n: Int, g: Gen[Any, EqPair]): Gen[Any, EqPair] = {
    treeEq(n, g)
  }

  /**
   * A generator of tuples containing equal elements (EList, EList) objects.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `listContainerEq(n, oneOf(intEq, longEq))` would produce list of integers.
   */
  def listContainerEq(g: Gen[Any, List[EqPair]]): Gen[Any, EqPair] = suspend {
    for {
      maybeIsProper <- boolean
      children      <- g
      isProper = {
        if (children.size < 2) { true }
        else { maybeIsProper }
      }
      (aTerms, bTerms) = children.unzip
    } yield (new EList(aTerms, isProper), new EList(bTerms, isProper))
  }

  /**
   * A generator of tuples containing equal elements (ETuple, ETuple) objects.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `tupleContainerEq(n, oneOf(intEq, longEq))` would produce tuple of integers.
   */
  def tupleContainerEq(g: Gen[Any, List[EqPair]]): Gen[Any, EqPair] = suspend {
    for {
      children <- g
      (aTerms, bTerms) = children.unzip
    } yield (ETuple(aTerms), ETuple(bTerms))
  }

  /**
   * A generator of tuples containing equal elements (ETerm, EObject) objects.
   *
   * The type of the children is defined by passed generator.
   *
   * For example the `mapContainerEq(n, oneOf(intEq, longEq))` would produce map where keys are strings and values are
   * integers.
   */
  def mapContainerEq(g: Gen[Any, List[EqPair]]): Gen[Any, EqPair] = suspend {
    g.flatMap { children =>
      for {
        keys <- listOfN(children.size)(stringEq)
        elements = keys zip children
        aHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
        bHashMap = mutable.LinkedHashMap.empty[ETerm, ETerm]
        _ = elements.foreach { case ((aKey, bKey), (aTerm, bTerm)) =>
          aHashMap.put(aKey, aTerm)
          bHashMap.put(bKey, bTerm)
        }
      } yield (EMap(aHashMap), EMap(bHashMap))
    }
  }

  private def childrenEq(
    n: Int,
    g: Gen[Any, EqPair]
  ): Gen[Any, List[(ETerm, ETerm)]] = suspend {
    for {
      i <- Gen.int(1, n - 1)
      r <- listOfN(n - i)(treeEq(i, g))
    } yield r
  }

  private def nodeEq(n: Int, g: Gen[Any, EqPair]): Gen[Any, (ETerm, ETerm)] = {
    suspend {
      for {
        container <- oneOf(
          listContainerEq(childrenEq(n, g)),
          tupleContainerEq(childrenEq(n, g)),
          mapContainerEq(childrenEq(n, g))
        )
      } yield container
    }
  }

  private def treeEq(n: Int, g: Gen[Any, EqPair]): Gen[Any, EqPair] = suspend {
    if (n == 1) g
    else oneOf(nodeEq(n, g), g)
  }

  /**
   * Converts generator of OtpErlangObject objects into generator of (ETerm, OtpErlangObject)
   */
  private def liftO(g: Gen[Any, OtpErlangObject]): Gen[Any, SamplePair] = {
    for { o <- g } yield (EList(List.empty), o)
  }

  /**
   * Converts generator of ETerm objects into generator of (ETerm, OtpErlangObject)
   */
  private def liftE(g: Gen[Any, ETerm]): Gen[Any, SamplePair] = {
    for { e <- g } yield (e, new OtpErlangList())
  }

  def otpList(otpTerms: List[OtpErlangObject], isProper: Boolean) = {
    if (isProper) {
      new OtpErlangList(otpTerms.toArray)
    } else {
      val head = otpTerms.dropRight(1)
      val tail = otpTerms.takeRight(1).head
      new OtpErlangList(head.toArray, tail)
    }
  }
}
