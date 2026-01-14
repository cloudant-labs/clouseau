/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.IndexServiceSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.util.BytesRef
import org.apache.lucene.queryparser.classic.ParseException

import java.io.File
import java.util.concurrent.TimeUnit

import com.cloudant.ziose.core._
import com.cloudant.ziose.core.Codec._
import com.cloudant.ziose.scalang.{Adapter, ServiceContext}

import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.Asserts._
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class IndexServiceSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val environment   = Utils.testEnvironment(1, 1, "IndexService") ++ Utils.logger
  val adapter       = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  val path         = java.lang.System.currentTimeMillis().toString
  val indexRootDir = new File("target", "indexes")
  val indexDir     = new File(indexRootDir, path)

  if (indexDir.exists) {
    for (f <- indexDir.listFiles) {
      f.delete
    }
  }

  def groups(value: Any): Map[Symbol, Any] = Map('groups -> value)

  val groupsParserSuite: Spec[Any, Throwable] = {
    suite("groups parser")(
      test("return None if option map doesn't contain 'groups'") {
        assert(IndexService.getGroups(Map().asInstanceOf[Map[Symbol, Any]]))(equalTo(None))
      },
      test("return None if 'groups option contain 'nil") {
        assert(IndexService.getGroups(groups('nil)))(equalTo(None))
      },
      test("return correct result for empty list groups") {
        assert(IndexService.getGroups(groups(List())))(equalTo(Some(List())))
      },
      test("return correct result for groups in the expected format") {
        val input = List(
          ("first", List(List())),
          ("second", List(Seq())),
          ("third", List("String")),
          ('null, List(List())),
          ('null, List(Seq())),
          ('null, List("String"))
        )
        val expected = Some(
          List(
            (Some("first"), List(List())),
            (Some("second"), List(Seq())),
            (Some("third"), List("String")),
            (None, List(List())),
            (None, List(Seq())),
            (None, List("String"))
          )
        )

        assert(IndexService.getGroups(groups(input)))(equalTo(expected))
      },
      test("return error when groups is not a list") {
        def action = IndexService.getGroups(groups("not a List"))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("invalid groups query"))))
      }
    )
  }

  def includeFields(value: Any): Map[Symbol, Any] = Map('include_fields -> value)

  val includeFieldsParserSuite: Spec[Any, Throwable] = {
    suite("include_fields parser")(
      test("return None if option map doesn't contain 'include_fields'") {
        assert(IndexService.getIncludeFields(Map().asInstanceOf[Map[Symbol, Any]]))(equalTo(None))
      },
      test("return None if 'include_fields option contain 'nil") {
        assert(IndexService.getIncludeFields(includeFields('nil)))(equalTo(None))
      },
      test("return correct result for empty list include_fields") {
        assert(IndexService.getIncludeFields(includeFields(List())))(equalTo(Some(Set("_id"))))
      },
      test("return correct result for List[String]") {
        val input    = List("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")
        val expected = Some(Set("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "_id"))

        assert(IndexService.getIncludeFields(includeFields(input)))(equalTo(expected))
      },
      test("return error when include_fields is not a list") {
        def action = IndexService.getIncludeFields(includeFields("not a List"))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("not a List is not a valid Symbol(include_fields) query"))))
      },
      test("return error when one of the elements of include_fields is not a String") {
        def action = IndexService.getIncludeFields(includeFields(List("one", 2)))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("List(one, 2) is not a valid Symbol(include_fields) query"))))
      }
    )
  }

  def ranges(value: Any): Map[Symbol, Any] = Map('ranges -> value)

  val rangesParserSuite: Spec[Any, Throwable] = {
    suite("ranges parser")(
      test("return None if option map doesn't contain 'ranges'") {
        assert(IndexService.getRanges(Map().asInstanceOf[Map[Symbol, Any]]))(equalTo(None))
      },
      test("return None if 'ranges option contain 'nil") {
        assert(IndexService.getRanges(ranges('nil)))(equalTo(None))
      },
      test("return correct result for empty list ranges") {
        assert(IndexService.getRanges(ranges(List())))(equalTo(Some(List())))
      },
      test("return correct result for empty list of queries") {
        val input = List(("first", List()))

        assert(IndexService.getRanges(ranges(input)))(equalTo(Some(input)))
      },
      test("return correct result when ranges are in correct format") {
        val input = List(("first", List(("second", "third"))))

        assert(IndexService.getRanges(ranges(input)))(equalTo(Some(input)))
      },
      test("return error when ranges is not a list") {
        def action = IndexService.getRanges(ranges("not a List"))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("invalid ranges query"))))
      },
      test("return error when range name is not a String") {
        def action = IndexService.getRanges(ranges(List(1, List())))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("List(1, List()) is not a valid ranges query"))))
      },
      test("return error when query is not tuple with arity 2") {
        def action = IndexService.getRanges(ranges(List("first", List(("second", "third"), (1, 2, 3)))))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(
          throws(hasMessage(containsString("List(first, List((second,third), (1,2,3))) is not a valid ranges query")))
        )
      },
      test("return error when query label is not a String") {
        def action = IndexService.getRanges(ranges(List("first", List((1, "query")))))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("List(first, List((1,query))) is not a valid ranges query"))))
      },
      test("return error when query is not a list") {
        def action = IndexService.getRanges(ranges(List("first", List(("second", 1)))))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("List(first, List((second,1))) is not a valid ranges query"))))
      },
      test("return error when queries is not a list") {
        def action = IndexService.getRanges(ranges(List("first", "not a List")))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("List(first, not a List) is not a valid ranges query"))))
      }
    )
  }

  def drilldown(value: Any): Map[Symbol, Any] = Map('drilldown -> value)

  val drilldownParserSuite: Spec[Any, Throwable] = {
    suite("drilldown parser")(
      test("return None if option map doesn't contain 'drilldown'") {
        assert(IndexService.getDrilldown(Map().asInstanceOf[Map[Symbol, Any]]))(equalTo(None))
      },
      test("return None if 'drilldown option contain 'nil") {
        assert(IndexService.getDrilldown(drilldown('nil)))(equalTo(None))
      },
      test("return correct result for single inner list of List[List[String]]") {
        val input = List(List("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"))

        assert(IndexService.getDrilldown(drilldown(input)))(equalTo(Some(input)))
      },
      test("return correct result for multiple inner lists of List[List[String]]") {
        val input = {
          List(List("Mercury", "Venus", "Earth"), List("Mars", "Jupiter", "Saturn"), List("Uranus", "Neptune"))
        }

        assert(IndexService.getDrilldown(drilldown(input)))(equalTo(Some(input)))
      },
      test("return error on List[NotAList]") {
        def action = IndexService.getDrilldown(drilldown(List(1, 2)))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("invalid drilldown query List(1, 2)"))))
      },
      test("return error on List[MixOFListsAndNoneLists]") {
        def action = IndexService.getDrilldown(drilldown(List(List("1", "2"), "3", "4")))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("invalid drilldown query List(List(1, 2), 3, 4)"))))
      },
      test("return error on List[List[NotAString]]") {
        def action = IndexService.getDrilldown(drilldown(List(List(1, 2))))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("List(1, 2) contains non-string element 1"))))
      },
      test("return error on List[List[MixOfStringsAndNoneStrings]]") {
        def action = IndexService.getDrilldown(drilldown(List(List("1", 2, "3"))))

        assert(action)(throwsA[ParseException]) &&
        assert(action)(throws(hasMessage(containsString("List(1, 2, 3) contains non-string element 2"))))
      }
    )
  }

  def startIndex(config: ClouseauConfiguration, options: AnalyzerOptions) = {
    for {
      node   <- Utils.clouseauNode
      cfg    <- Utils.mkConfig(config)
      worker <- ZIO.service[EngineWorker]
      val analyzer     = SupportedAnalyzers.createAnalyzer(options).get
      val queryParser  = new ClouseauQueryParser("default", analyzer)
      val directory    = new NIOFSDirectory(indexDir.toPath)
      val writerConfig = new IndexWriterConfig(analyzer)
      _ <- ZIO.succeed(writerConfig.setIndexDeletionPolicy(new ExternalSnapshotDeletionPolicy(directory)))
      val writer   = new IndexWriter(directory, writerConfig)
      val indexCfg = IndexServiceArgs(cfg, indexDir.getPath, queryParser, writer)
      val ctx      = new ServiceContext[IndexServiceArgs] { val args = indexCfg }
      service <- node.spawnServiceZIO[IndexService, IndexServiceArgs](IndexServiceBuilder.make(node, ctx))
    } yield service
  }

  def callIndex(index: AddressableActor[_, _], msg: Any) = {
    for {
      result <- index
        .doTestCallTimeout(adapter.fromScala(msg), 3.seconds)
        .delay(100.millis)
        .repeatUntil(_.isSuccess)
        .map(result => result.payload.get)
        .timeout(3.seconds)
    } yield result
  }

  def updateD(index: AddressableActor[_, _], id: String, fields: List[(String, Any, List[(String, Any)])]) = {
    for {
      result <- callIndex(index, ('update, id, fields))
    } yield result
  }

  def update(index: AddressableActor[_, _], id: String) = {
    for {
      timestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
      result    <- updateD(index, id, List(("timestamp", timestamp, List())))
    } yield result
  }

  def search(index: AddressableActor[_, _], options: List[(Symbol, Any)]) = {
    for {
      result <- callIndex(index, ('search, options))
    } yield result
  }

  def query(index: AddressableActor[_, _], query: String) = {
    search(index, List(('query, query)))
  }

  def sort(index: AddressableActor[_, _], sort: String) = {
    search(index, List(('sort, sort)))
  }

  def highlight(index: AddressableActor[_, _], query: String, highlightFields: List[String]) = {
    search(index, List(('query, query), ('highlight_fields, highlightFields)))
  }

  def group1(
    index: AddressableActor[_, _],
    query: String,
    field: String,
    refresh: Boolean,
    groupSort: Any,
    groupOffset: Int,
    groupLimit: Int
  ) = {
    for {
      result <- callIndex(index, ('group1, query, field, refresh, groupSort, groupOffset, groupLimit))
    } yield result
  }

  def setUpdateSeq(index: AddressableActor[_, _], seq: Int) = {
    for {
      result <- callIndex(index, ('set_update_seq, seq))
    } yield result
  }

  def stopIndex(index: AddressableActor[_, _]) = {
    for {
      _ <- index.exit(EAtom("normal"))
    } yield ()
  }

  def indexAfterTimeout(config: ClouseauConfiguration, shouldBeClosed: Boolean) = {
    for {
      index   <- startIndex(config, standardAnalyzer)
      result1 <- update(index, "foo")
      result2 <- query(index, "_id:foo")
      _       <- ZIO.sleep(4200.milliseconds)
      _       <- if (shouldBeClosed) assertNotAlive(index.id) else assertAlive(index.id)
      _       <- ZIO.when(!shouldBeClosed)(stopIndex(index))
    } yield assertTrue(
      result1.isDefined,
      adapter.toScala(result1.get) == 'ok,
      result2.isDefined,
      adapter.toScala(result2.get) match {
        case ('ok, List(_, ('total_hits, 1), _)) => true
        case _                                   => false
      }
    )
  }

  def indexNotClosedAfterTimeout(config: ClouseauConfiguration) = indexAfterTimeout(config, false)
  def indexClosedAfterTimeout(config: ClouseauConfiguration)    = indexAfterTimeout(config, true)

  def indexNotClosedAfterActivityBetweenTwoIdleChecks(config: ClouseauConfiguration) = {
    for {
      index   <- startIndex(config, AnalyzerOptions.fromAnalyzerName("standard"))
      result1 <- update(index, "foo")
      result2 <- query(index, "_id:foo")
      _       <- ZIO.sleep(3000.milliseconds)
      result3 <- update(index, "foo2")
      _       <- ZIO.sleep(2000.milliseconds)
      _       <- assertAlive(index.id)
      _       <- ZIO.sleep(1200.milliseconds)
      _       <- assertNotAlive(index.id)
    } yield assertTrue(
      result1.isDefined,
      adapter.toScala(result1.get) == 'ok,
      result2.isDefined,
      adapter.toScala(result2.get) match {
        case ('ok, List(_, ('total_hits, 1), _)) => true
        case _                                   => false
      },
      result3.isDefined,
      adapter.toScala(result3.get) == 'ok
    )
  }

  val defaultConfig = ClouseauConfiguration()

  def isSearchable(value: String, q: String, options: AnalyzerOptions) = {
    for {
      index   <- startIndex(defaultConfig, options)
      result1 <- update(index, value)
      result2 <- query(index, s"_id:$q")
      _       <- stopIndex(index)
    } yield assertTrue(
      result1.isDefined,
      adapter.toScala(result1.get) == 'ok,
      result2.isDefined,
      adapter.toScala(result2.get) match {
        case ('ok, List(_, ('total_hits, 1), _)) => true
        case _                                   => false
      }
    )
  }

  val standardAnalyzer = AnalyzerOptions.fromAnalyzerName("standard")

  val indexSuite: Spec[Any, Throwable] = {
    suite("index")(
      test("not be closed if close_if_idle and idle_check_interval_secs not set") {
        indexNotClosedAfterTimeout(ClouseauConfiguration())
      },
      test("not be closed if idle_check_interval_secs set and close_if_idle set to false") {
        indexNotClosedAfterTimeout(
          ClouseauConfiguration(
            close_if_idle = Some(false),
            idle_check_interval_secs = Some(2)
          )
        )
      },
      test("not be closed if close_if_idle set to false") {
        indexNotClosedAfterTimeout(
          ClouseauConfiguration(
            idle_check_interval_secs = Some(2)
          )
        )
      },
      test("be closed after idle timeout") {
        indexClosedAfterTimeout(
          ClouseauConfiguration(
            close_if_idle = Some(true),
            idle_check_interval_secs = Some(2)
          )
        )
      },
      test("not be closed if there is any activity before two consecutive idle checks") {
        indexNotClosedAfterActivityBetweenTwoIdleChecks(
          ClouseauConfiguration(
            close_if_idle = Some(true),
            idle_check_interval_secs = Some(2)
          )
        )
      },
      test("perform basic queries") {
        isSearchable("foo", "foo", standardAnalyzer)
      },
      test("be able to search uppercase _id") {
        isSearchable("FOO", "FOO", standardAnalyzer)
      },
      test("be able to search uppercase _id with prefix") {
        isSearchable("FOO", "FO*", standardAnalyzer)
      },
      test("be able to search uppercase _id with wildcards") {
        isSearchable("FOO", "F?O*", standardAnalyzer)
      },
      test("be able to search uppercase _id with range") {
        isSearchable("FOO", "[FOO TO FOO]", standardAnalyzer)
      },
      test("be able to search uppercase _id with regexp") {
        isSearchable("FOO", "/FOO/", standardAnalyzer)
      },
      test("be able to search uppercase _id with fuzzy") {
        isSearchable("FOO", "FO~", standardAnalyzer)
      },
      test("be able to search uppercase _id with perfield") {
        isSearchable("FOO", "FOO", AnalyzerOptions.fromMap(Map("name" -> "perfield", "default" -> "english")))
      },
      test("perform sorting") {
        for {
          index   <- startIndex(defaultConfig, standardAnalyzer)
          result1 <- update(index, "foo")
          result2 <- update(index, "bar")
          result3 <- sort(index, "_id<string>")
          result4 <- sort(index, "-_id<string>")
          result5 <- sort(index, "foo<string>")
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, _, List(("_id", "bar"))),
                        ('hit, _, List(("_id", "foo")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result4.isDefined,
          adapter.toScala(result4.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, _, List(("_id", "foo"))),
                        ('hit, _, List(("_id", "bar")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result5.isDefined,
          adapter.toScala(result5.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, _, List(("_id", "foo"))),
                        ('hit, _, List(("_id", "bar")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          }
        )
      },
      test("support highlighting") {
        for {
          index <- startIndex(defaultConfig, standardAnalyzer)
          val document = List(
            ("field1", "bar", List(("store", true))),
            ("field2", "bar", List(("store", false))),
            ("field3", "bar", List(("store", true))),
            ("field3", "bar", List(("store", true)))
          )
          result1 <- updateD(index, "foo", document)
          // basic
          result2 <- search(index, List(('highlight_fields, List("field1")), ('query, "field1:bar")))
          // attempted highlight on non-stored field
          result3 <- search(index, List(('highlight_fields, List("field1", "field2")), ('query, "field1:bar")))
          // highlights on duplicated field
          result4 <- search(index, List(('highlight_fields, List("field3")), ('query, "field3:bar")))
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 1),
                    (
                      'hits,
                      List(
                        (
                          'hit,
                          _,
                          List(
                            ("_id", "foo"),
                            ("field1", "bar"),
                            ("field3", List("bar", "bar")),
                            ("_highlights", List(("field1", List("<em>bar</em>"))))
                          )
                        )
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 1),
                    (
                      'hits,
                      List(
                        (
                          'hit,
                          _,
                          List(
                            ("_id", "foo"),
                            ("field1", "bar"),
                            ("field3", List("bar", "bar")),
                            ("_highlights", List(("field1", List("<em>bar</em>")), ("field2", Nil)))
                          )
                        )
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result4.isDefined,
          adapter.toScala(result4.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 1),
                    (
                      'hits,
                      List(
                        (
                          'hit,
                          _,
                          List(
                            ("_id", "foo"),
                            ("field1", "bar"),
                            ("field3", List("bar", "bar")),
                            ("_highlights", List(("field3", List("<em>bar</em>", "<em>bar</em>"))))
                          )
                        )
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          }
        )
      },
      test("when limit=0 return only the number of hits") {
        for {
          index   <- startIndex(defaultConfig, standardAnalyzer)
          result1 <- update(index, "foo")
          result2 <- update(index, "bar")
          result3 <- search(index, List(('limit, 0)))
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case ('ok, List(_, ('total_hits, 2), ('hits, List()))) => true
            case _                                                 => false
          }
        )
      },
      test("support include_fields") {
        for {
          index <- startIndex(defaultConfig, standardAnalyzer)
          val document1 = List(
            ("field1", "f11", List(("store", true))),
            ("field2", "f21", List(("store", true))),
            ("field3", "f31", List(("store", true)))
          )
          result1 <- updateD(index, "foo", document1)
          val document2 = List(
            ("field1", "f12", List(("store", true))),
            ("field2", "f22", List(("store", true))),
            ("field3", "f32", List(("store", true)))
          )
          result2 <- updateD(index, "bar", document2)
          // include only field1
          result3 <- search(index, List(('include_fields, List("field1"))))
          // include only field1 and field2
          result4 <- search(index, List(('include_fields, List("field1", "field2"))))
          // include no field
          result5 <- search(index, List(('include_fields, List())))
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, _, List(("_id", "foo"), ("field1", "f11"))),
                        ('hit, _, List(("_id", "bar"), ("field1", "f12")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result4.isDefined,
          adapter.toScala(result4.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, _, List(("_id", "foo"), ("field1", "f11"), ("field2", "f21"))),
                        ('hit, _, List(("_id", "bar"), ("field1", "f12"), ("field2", "f22")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result5.isDefined,
          adapter.toScala(result5.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, _, List(("_id", "foo"))),
                        ('hit, _, List(("_id", "bar")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          }
        )
      },
      test("support faceting and drilldown") {
        for {
          index <- startIndex(defaultConfig, standardAnalyzer)
          val document1 = List(
            ("ffield", "f1", List(("store", true), ("facet", true)))
          )
          result1 <- updateD(index, "foo", document1)
          val document2 = List(
            ("ffield", "f1", List(("store", true), ("facet", true)))
          )
          result2 <- updateD(index, "foo2", document2)
          val document3 = List(
            ("ffield", "f3", List(("store", true), ("facet", true)))
          )
          result3 <- updateD(index, "foo3", document3)
          // counts
          result4 <- search(index, List(('counts, List("ffield"))))
          // drilldown - one value
          result5 <- search(index, List(('counts, List("ffield")), ('drilldown, List(List("ffield", "f1")))))
          // drilldown - multivalued
          result6 <- search(index, List(('counts, List("ffield")), ('drilldown, List(List("ffield", "f1", "f3")))))
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) == 'ok,
          result4.isDefined,
          adapter.toScala(result4.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 3),
                    _,
                    (
                      'counts,
                      List(
                        (
                          List("ffield"),
                          3.0,
                          List((List("ffield", "f1"), 2.0, List()), (List("ffield", "f3"), 1.0, List()))
                        )
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result5.isDefined,
          adapter.toScala(result5.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    _,
                    (
                      'counts,
                      List(
                        (
                          List("ffield"),
                          2.0,
                          List((List("ffield", "f1"), 2.0, List()))
                        )
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result6.isDefined,
          adapter.toScala(result6.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 3),
                    _,
                    (
                      'counts,
                      List(
                        (
                          List("ffield"),
                          3.0,
                          List((List("ffield", "f1"), 2.0, List()), (List("ffield", "f3"), 1.0, List()))
                        )
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          }
        )
      },
      test("support bookmarks") {
        for {
          index    <- startIndex(defaultConfig, standardAnalyzer)
          result1  <- update(index, "foo")
          result2  <- update(index, "bar")
          result3  <- search(index, List(('limit, 1)))
          result4  <- search(index, List(('limit, 1), ('after, (1.0, 0))))
          result5  <- search(index, List(('limit, 1), ('sort, "_id<string>")))
          result6  <- search(index, List(('limit, 1), ('sort, "_id<string>"), ('after, List(new BytesRef("bar"), 1))))
          result7  <- search(index, List(('limit, 1), ('sort, "nonexistent<string>"), ('after, List(null, 0))))
          result8  <- search(index, List(('limit, 1), ('sort, List("<score>"))))
          result9  <- search(index, List(('limit, 1), ('sort, List("<doc>"))))
          result10 <- search(index, List(('limit, 1), ('sort, List("<score>", "_id<string>"))))
          result11 <- search(index, List(('limit, 1), ('sort, List("<doc>", "_id<string>"))))
          _        <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(1.0, 0), List(("_id", "foo")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result4.isDefined,
          adapter.toScala(result4.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(1.0, 1), List(("_id", "bar")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result5.isDefined,
          adapter.toScala(result5.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(_, 1), List(("_id", "bar")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result6.isDefined,
          adapter.toScala(result6.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(_, 0), List(("_id", "foo")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result7.isDefined,
          adapter.toScala(result7.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List('null, 1), List(("_id", "bar")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result8.isDefined,
          adapter.toScala(result8.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(1.0, 0), List(("_id", "foo")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result9.isDefined,
          adapter.toScala(result9.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(0, 0), List(("_id", "foo")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result10.isDefined,
          adapter.toScala(result10.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(1.0, "bar", 1), List(("_id", "bar")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          },
          result11.isDefined,
          adapter.toScala(result11.get) match {
            case (
                  'ok,
                  List(
                    _,
                    ('total_hits, 2),
                    (
                      'hits,
                      List(
                        ('hit, List(0, "foo", 0), List(("_id", "foo")))
                      )
                    )
                  )
                ) =>
              true
            case _ => false
          }
        )
      },
      test("support only group by string") {
        for {
          index <- startIndex(defaultConfig, standardAnalyzer)
          val document = List(
            ("num", 1.0, List(("store", true))),
            ("num", 2.0, List(("store", true)))
          )
          result1 <- updateD(index, "foo", document)
          result2 <- update(index, "bar")
          result3 <- group1(index, "_id:foo", "_id", true, "num", 0, 10)
          result4 <- group1(index, "_id:foo", "_id<string>", true, "num", 0, 10)
          result5 <- group1(index, "_id:foo", "num<number>", true, "num", 0, 10)
          result6 <- group1(index, "_id:foo", "_id<number>", true, "num", 0, 10)
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case ('ok, List(("foo", List(2.0)))) => true
            case _                               => false
          },
          result4.isDefined,
          adapter.toScala(result4.get) match {
            case ('ok, List(("foo", List(2.0)))) => true
            case _                               => false
          },
          result5.isDefined,
          adapter.toScala(result5.get) match {
            case ('error, ('bad_request, "Group by number not supported. Group by string terms only.")) => true
            case _                                                                                      => false
          },
          result6.isDefined,
          adapter.toScala(result6.get) match {
            case ('error, ('bad_request, "Group by number not supported. Group by string terms only.")) => true
            case _                                                                                      => false
          }
        )
      },
      test("support sort by distance in group search") {
        for {
          index <- startIndex(defaultConfig, standardAnalyzer)
          val document1 = List(
            ("lon", 0.5, List(("store", true))),
            ("lat", 57.15, List(("store", true)))
          )
          result1 <- updateD(index, "foo", document1)
          val document2 = List(
            ("lon", 10, List(("store", true))),
            ("lat", 57.15, List(("store", true)))
          )
          result2 <- updateD(index, "bar", document2)
          val document3 = List(
            ("lon", 3, List(("store", true))),
            ("lat", 57.15, List(("store", true)))
          )
          result3 <- updateD(index, "zzz", document3)
          result4 <- group1(index, "*:*", "_id", true, "<distance,lon,lat,0.2,57.15,km>", 0, 10)
          result5 <- group1(index, "*:*", "_id", true, "<distance,lon,lat,12,57.15,km>", 0, 10)
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) == 'ok,
          result4.isDefined,
          adapter.toScala(result4.get) match {
            case ('ok, List(("foo", _), ("zzz", _), ("bar", _))) => true
            case _                                               => false
          },
          result5.isDefined,
          adapter.toScala(result5.get) match {
            case ('ok, List(("bar", _), ("zzz", _), ("foo", _))) => true
            case _                                               => false
          }
        )
      },
      test("supports partitioned databases") {
        for {
          index <- startIndex(defaultConfig, standardAnalyzer)
          val document1 = List(
            ("field", "fieldvalue", List(("store", true))),
            ("_partition", "foo", List(("store", true)))
          )
          result1 <- updateD(index, "foo:hello", document1)
          val document2 = List(
            ("field", "fieldvalue", List(("store", true))),
            ("_partition", "bar", List(("store", true)))
          )
          result2 <- updateD(index, "bar:world", document2)
          result3 <- search(index, List(('query, "field:fieldvalue"), ('partition, "foo")))
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case ('ok, (List(_, ('total_hits, 1), _))) => true
            case _                                     => false
          }
        )
      },
      test("ignores partitioned key if partition missing") {
        for {
          index <- startIndex(defaultConfig, standardAnalyzer)
          val document1 = List(
            ("field", "fieldvalue", List(("store", true))),
            ("_partition", "foo", List(("store", true)))
          )
          result1 <- updateD(index, "foo:hello", document1)
          val document2 = List(
            ("field", "fieldvalue", List(("store", true))),
            ("_partition", "bar", List(("store", true)))
          )
          result2 <- updateD(index, "bar:world", document2)
          result3 <- search(index, List(('query, "field:fieldvalue")))
          _       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result3.get) match {
            case ('ok, (List(_, ('total_hits, 2), _))) => true
            case _                                     => false
          }
        )
      },
      test("can make a snapshot") {
        for {
          index     <- startIndex(defaultConfig, standardAnalyzer)
          result1   <- update(index, "foo:hello")
          result2   <- setUpdateSeq(index, 10)
          _         <- index.send(adapter.fromScala('maybe_commit))
          _         <- ZIO.sleep(1.second)
          timestamp <- Clock.currentTime(TimeUnit.MILLISECONDS)
          val snapshotDir = new File(indexRootDir, timestamp.toString)
          snapshotDirExistsBefore <- ZIO.succeed(snapshotDir.exists)
          result3                 <- callIndex(index, adapter.fromScala('create_snapshot, snapshotDir.getAbsolutePath))
          snapshotDirExistsAfter  <- ZIO.succeed(snapshotDir.exists)
          snapshotDirContents     <- ZIO.succeed(snapshotDir.list.sorted)
          _                       <- stopIndex(index)
        } yield assertTrue(
          result1.isDefined,
          adapter.toScala(result1.get) == 'ok,
          result2.isDefined,
          adapter.toScala(result2.get) == 'ok,
          result3.isDefined,
          adapter.toScala(result2.get) == 'ok,
          !snapshotDirExistsBefore,
          snapshotDirExistsAfter,
          snapshotDirContents.sameElements(Array("_0.cfe", "_0.cfs", "_0.si", "segments_1"))
        )
      }
    ).provideLayer(environment) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("IndexServiceSpec")(
      groupsParserSuite,
      includeFieldsParserSuite,
      rangesParserSuite,
      drilldownParserSuite,
      indexSuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.IndexServiceSpecMain
 * ```
 */
object IndexServiceSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("IndexServiceSpec", new IndexServiceSpec().spec)
  }
}
