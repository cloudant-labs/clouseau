/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import java.io.File
import java.io.IOException
import org.apache.log4j.Logger
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.search._
import grouping.SearchGroup
import grouping.term.{ TermSecondPassGroupingCollector, TermFirstPassGroupingCollector }
import org.apache.lucene.util.{ NumericUtils, BytesRef, Version }
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.queryparser.classic.ParseException
import scalang._
import collection.JavaConversions._
import com.yammer.metrics.scala._
import com.cloudant.clouseau.Utils._
import org.apache.commons.configuration.Configuration
import org.apache.lucene.facet.sortedset.{
  SortedSetDocValuesReaderState,
  SortedSetDocValuesAccumulator
}
import org.apache.lucene.facet.range.{
  DoubleRange,
  RangeAccumulator,
  RangeFacetRequest
}
import org.apache.lucene.facet.search._
import org.apache.lucene.facet.taxonomy.CategoryPath
import org.apache.lucene.facet.params.{ FacetIndexingParams, FacetSearchParams }
import scala.Some
import scalang.Pid
import scalang.Reference

case class IndexServiceArgs(config: Configuration, name: String, queryParser: QueryParser, writer: IndexWriter)

// These must match the records in dreyfus.
case class TopDocs(updateSeq: Long, totalHits: Long, hits: List[Hit])
case class Hit(order: List[Any], fields: List[Any])

class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.%s".format(ctx.args.name))
  val sortFieldRE = """^([-+])?([\.\w]+)(?:<(\w+)>)?$""".r
  var reader = DirectoryReader.open(ctx.args.writer, true)
  var updateSeq = getCommittedSeq
  var pendingSeq = updateSeq
  var committing = false
  var forceRefresh = false

  val searchTimer = metrics.timer("searches")
  val commitTimer = metrics.timer("commits")

  // Start committer heartbeat
  val commitInterval = ctx.args.config.getInt("commit_interval_secs", 30)
  sendEvery(self, 'maybe_commit, commitInterval * 1000)

  logger.info("Opened at update_seq %d".format(updateSeq))

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case request: SearchRequest =>
      search(request)
    case Group1Msg(query: String, field: String, refresh: Boolean, groupSort: Any, groupOffset: Int,
      groupLimit: Int) =>
      group1(query, field, refresh, groupSort, groupOffset, groupLimit)
    case Group2Msg(query: String, field: String, refresh: Boolean, groups: List[Any], groupSort: Any,
      docSort: Any, docLimit: Int) =>
      group2(query, field, refresh, groups, groupSort, docSort, docLimit)
    case 'get_update_seq =>
      ('ok, updateSeq)
    case UpdateDocMsg(id: String, doc: Document) =>
      logger.debug("Updating %s".format(id))
      ctx.args.writer.updateDocument(new Term("_id", id), doc)
      'ok
    case DeleteDocMsg(id: String) =>
      logger.debug("Deleting %s".format(id))
      ctx.args.writer.deleteDocuments(new Term("_id", id))
      'ok
    case CommitMsg(commitSeq: Long) => // deprecated
      pendingSeq = commitSeq
      logger.debug("Pending sequence is now %d".format(commitSeq))
      'ok
    case SetUpdateSeqMsg(newSeq: Long) =>
      pendingSeq = newSeq
      logger.debug("Pending sequence is now %d".format(newSeq))
      'ok
    case 'info =>
      ('ok, getInfo)
  }

  override def handleInfo(msg: Any) = msg match {
    case 'close =>
      exit(msg)
    case ('close, reason) =>
      exit(reason)
    case 'delete =>
      val dir = ctx.args.writer.getDirectory
      ctx.args.writer.close()
      for (name <- dir.listAll) {
        dir.deleteFile(name)
      }
      exit('deleted)
    case 'maybe_commit =>
      commit(pendingSeq)
    case ('committed, newSeq: Long) =>
      updateSeq = newSeq
      forceRefresh = true
      committing = false
      logger.info("Committed sequence %d".format(newSeq))
    case 'commit_failed =>
      committing = false
  }

  override def exit(msg: Any) {
    logger.info("Closed with reason: %.1000s".format(msg))
    try {
      reader.close()
    } catch {
      case e: IOException => logger.warn("Error while closing reader", e)
    }
    try {
      ctx.args.writer.rollback()
    } catch {
      case e: AlreadyClosedException => 'ignored
      case e: IOException => logger.warn("Error while closing writer", e)
    } finally {
      super.exit(msg)
    }
  }

  private def commit(newSeq: Long) {
    if (!committing && newSeq > updateSeq) {
      committing = true
      val index = self
      node.spawn((_) => {
        ctx.args.writer.setCommitData(ctx.args.writer.getCommitData +
          ("update_seq" -> newSeq.toString))
        try {
          commitTimer.time {
            ctx.args.writer.commit()
          }
          index ! ('committed, newSeq)
        } catch {
          case e: AlreadyClosedException =>
            logger.error("Commit failed to closed writer", e)
            index ! 'commit_failed
          case e: IOException =>
            logger.error("Failed to commit changes", e)
            index ! 'commit_failed
        }
      })
    }
  }

  private def search(request: SearchRequest): Any = {
    val queryString = request.options.getOrElse('query, "*:*").asInstanceOf[String]
    val refresh = request.options.getOrElse('fresh, true).asInstanceOf[Boolean]
    val limit = request.options.getOrElse('limit, 25).asInstanceOf[Int]
    val counts = request.options.getOrElse('counts, 'nil) match {
      case 'nil =>
        None
      case value =>
        Some(value)
    }
    val ranges = request.options.getOrElse('ranges, 'nil) match {
      case 'nil =>
        None
      case value =>
        Some(value)
    }
    val legacy = request.options.getOrElse('legacy, false).asInstanceOf[Boolean]

    parseQuery(queryString) match {
      case baseQuery: Query =>
        safeSearch {
          val query = request.options.getOrElse('drilldown, Nil) match {
            case Nil =>
              baseQuery
            case categories: List[List[String]] =>
              val drilldownQuery = new DrillDownQuery(
                FacetIndexingParams.DEFAULT, baseQuery)
              for (category <- categories) {
                try {
                  drilldownQuery.add(new CategoryPath(category.toArray: _*))
                } catch {
                  case e: IllegalArgumentException =>
                    throw new ParseException(e.getMessage)
                  case e: ArrayStoreException =>
                    throw new ParseException(category +
                      " contains a non-string item")
                }
              }
              drilldownQuery
            case _ =>
              throw new ParseException("invalid drilldown query")
          }

          val searcher = getSearcher(refresh)
          val weight = searcher.createNormalizedWeight(query)
          val docsScoredInOrder = !weight.scoresDocsOutOfOrder

          val sort = parseSort(request.options.getOrElse('sort, 'relevance))
          val after = toScoreDoc(sort, request.options.getOrElse('after, 'nil))

          val topDocsCollector = (after, sort) match {
            case (None, Sort.RELEVANCE) =>
              TopScoreDocCollector.create(limit, docsScoredInOrder)
            case (Some(scoreDoc), Sort.RELEVANCE) =>
              TopScoreDocCollector.create(limit, scoreDoc, docsScoredInOrder)
            case (None, sort1: Sort) =>
              TopFieldCollector.create(sort1, limit, true, false, false,
                docsScoredInOrder)
            case (Some(fieldDoc: FieldDoc), sort1: Sort) =>
              TopFieldCollector.create(sort1, limit, fieldDoc, true, false,
                false, docsScoredInOrder)
          }

          val countsCollector = createCountsCollector(counts)

          val rangesCollector = ranges match {
            case None =>
              null
            case Some(rangeList: List[_]) =>
              val rangeFacetRequests = for ((name: String, ranges: List[_]) <- rangeList) yield {
                new RangeFacetRequest(name, ranges.map({
                  case (label: String, rangeQuery: String) =>
                    ctx.args.queryParser.parse(rangeQuery) match {
                      case q: NumericRangeQuery[_] =>
                        new DoubleRange(
                          label,
                          ClouseauTypeFactory.toDouble(q.getMin).get,
                          q.includesMin,
                          ClouseauTypeFactory.toDouble(q.getMax).get,
                          q.includesMax)
                      case _ =>
                        throw new ParseException(rangeQuery +
                          " was not a well-formed range specification")
                    }
                  case _ =>
                    throw new ParseException("invalid ranges query")
                }))
              }
              val acc = new RangeAccumulator(rangeFacetRequests)
              FacetsCollector.create(acc)
            case Some(other) =>
              throw new ParseException(other + " is not a valid ranges query")
          }

          val collector = MultiCollector.wrap(
            topDocsCollector, countsCollector, rangesCollector)

          searchTimer.time {
            searcher.search(query, collector)
          }
          logger.debug("search for '%s' limit=%d, refresh=%s had %d hits".
            format(query, limit, refresh, topDocsCollector.getTotalHits))

          val hits = topDocsCollector.topDocs.scoreDocs.map({
            docToHit(searcher, _)
          }).toList

          if (legacy) {
            ('ok, TopDocs(updateSeq, topDocsCollector.getTotalHits, hits))
          } else {
            ('ok, List(
              ('update_seq, updateSeq),
              ('total_hits, topDocsCollector.getTotalHits),
              ('hits, hits)
            ) ++ convertFacets('counts, countsCollector)
              ++ convertFacets('ranges, rangesCollector))
          }
        }
      case error =>
        error
    }
  }

  private def createCountsCollector(counts: Option[Any]): FacetsCollector = {
    counts match {
      case None =>
        null
      case Some(counts: List[String]) =>
        val state = try {
          new SortedSetDocValuesReaderState(reader)
        } catch {
          case e: IllegalArgumentException =>
            if (e.getMessage contains "was not indexed with SortedSetDocValues")
              return null
            else
              throw e
        }
        val countFacetRequests = for (count <- counts) yield {
          new CountFacetRequest(new CategoryPath(count), Int.MaxValue)
        }
        val facetSearchParams = new FacetSearchParams(countFacetRequests)
        val acc = try {
          new SortedSetDocValuesAccumulator(state, facetSearchParams)
        } catch {
          case e: IllegalArgumentException =>
            throw new ParseException(e.getMessage)
        }
        FacetsCollector.create(acc)
      case Some(other) =>
        throw new ParseException(other + " is not a valid counts query")
    }
  }

  private def group1(queryString: String, field: String, refresh: Boolean, groupSort: Any,
                     groupOffset: Int, groupLimit: Int): Any = parseQuery(queryString) match {
    case query: Query =>
      val searcher = getSearcher(refresh)
      safeSearch {
        val (fieldName, _) = parseGroupField(field)
        val collector = new TermFirstPassGroupingCollector(fieldName,
          parseSort(groupSort), groupLimit)
        searchTimer.time {
          searcher.search(query, collector)
          collector.getTopGroups(groupOffset, true) match {
            case null =>
              ('ok, List())
            case topGroups =>
              ('ok, topGroups map {
                g => (g.groupValue, convertOrder(g.sortValues))
              })
          }
        }
      }
    case error =>
      error
  }

  private def group2(queryString: String, field: String, refresh: Boolean, groups: List[Any],
                     groupSort: Any, docSort: Any, docLimit: Int): Any = parseQuery(queryString) match {
    case query: Query =>
      val searcher = getSearcher(refresh)
      val groups1 = groups.map {
        g => makeSearchGroup(g)
      }
      safeSearch {
        val (fieldName, fieldType) = parseGroupField(field)
        val collector = new TermSecondPassGroupingCollector(fieldName,
          groups1, parseSort(groupSort), parseSort(docSort),
          docLimit, true, false, true)
        searchTimer.time {
          searcher.search(query, collector)
          collector.getTopGroups(0) match {
            case null =>
              ('ok, 0, 0, List())
            case topGroups =>
              ('ok, topGroups.totalHitCount, topGroups.totalGroupedHitCount,
                topGroups.groups.map {
                  g =>
                    (
                      fieldType match {
                        case "number" =>
                          NumericUtils.sortableLongToDouble(
                            NumericUtils.prefixCodedToLong(g.groupValue))
                        case "string" =>
                          g.groupValue
                        case null =>
                          g.groupValue
                        case _ =>
                          throw new ParseException(
                            "Unrecognized type for group_field: " + fieldType)
                      },
                      g.totalHits,
                      g.scoreDocs.map({
                        docToHit(searcher, _)
                      }).toList
                    )
                }.toList)
          }
        }
      }
    case error =>
      error
  }

  private def makeSearchGroup(group: Any): SearchGroup[BytesRef] = group match {
    case ('null, order: List[AnyRef]) =>
      val result: SearchGroup[BytesRef] = new SearchGroup
      result.sortValues = order.toArray
      result
    case (name: String, order: List[AnyRef]) =>
      val result: SearchGroup[BytesRef] = new SearchGroup
      result.groupValue = name
      result.sortValues = order.toArray
      result
  }

  private def parseQuery(query: String): Any = {
    safeSearch { ctx.args.queryParser.parse(query) }
  }

  private def safeSearch[A](fun: => A): Any = try {
    fun
  } catch {
    case e: NumberFormatException =>
      ('error, ('bad_request, "cannot sort string field as numeric field"))
    case e: ClassCastException =>
      ('error, ('bad_request, "Malformed query syntax"))
    case e: ParseException =>
      ('error, ('bad_request, e.getMessage))
    case e =>
      ('error, e.getMessage)
  }

  private def getSearcher(refresh: Boolean): IndexSearcher = {
    if (forceRefresh || refresh) {
      reopenIfChanged()
    }
    new IndexSearcher(reader)
  }

  private def reopenIfChanged() {
    val newReader = DirectoryReader.openIfChanged(reader)
    if (newReader != null) {
      reader.close()
      reader = newReader
      forceRefresh = false
    }
  }

  private def getInfo: List[Any] = {
    reopenIfChanged()
    List(
      ('disk_size, getDiskSize),
      ('doc_count, reader.numDocs),
      ('doc_del_count, reader.numDeletedDocs),
      ('pending_seq, pendingSeq),
      ('committed_seq, getCommittedSeq)
    )
  }

  private def getDiskSize = {
    val sizes = reader.directory.listAll map {
      reader.directory.fileLength
    }
    sizes.sum
  }

  private def getCommittedSeq = {
    val commitData = ctx.args.writer.getCommitData
    commitData.get("update_seq") match {
      case null =>
        0L
      case seq =>
        seq.toLong
    }
  }

  private def parseGroupField(field: String) = {
    sortFieldRE.findFirstMatchIn(field) match {
      case Some(sortFieldRE(_fieldOrder, fieldName, fieldType)) =>
        (fieldName, fieldType)
      case None =>
        throw new ParseException("Unrecognized group_field parameter: "
          + field)
    }
  }

  private def parseSort(v: Any): Sort = v match {
    case 'relevance =>
      Sort.RELEVANCE
    case field: String =>
      new Sort(toSortField(field))
    case fields: List[String] =>
      new Sort(fields.map(toSortField).toArray: _*)
  }

  private def docToHit(searcher: IndexSearcher, scoreDoc: ScoreDoc): Hit = {
    val doc = searcher.doc(scoreDoc.doc)
    val fields = doc.getFields.foldLeft(Map[String, Any]())((acc, field) => {
      val value = field.numericValue match {
        case null =>
          field.stringValue
        case num =>
          num
      }
      acc.get(field.name) match {
        case None =>
          acc + (field.name -> value)
        case Some(list: List[Any]) =>
          acc + (field.name -> (value :: list))
        case Some(existingValue: Any) =>
          acc + (field.name -> List(value, existingValue))
      }
    })
    val order = scoreDoc match {
      case fieldDoc: FieldDoc =>
        convertOrder(fieldDoc.fields) :+ scoreDoc.doc
      case _ =>
        List[Any](scoreDoc.score, scoreDoc.doc)
    }
    Hit(order, fields.toList)
  }

  private def convertOrder(order: Array[AnyRef]): List[Any] = {
    order.map {
      case (null) =>
        'null
      case (v) =>
        v
    }.toList
  }

  private def toSortField(field: String): SortField = field match {
    case "<score>" =>
      SortField.FIELD_SCORE
    case "-<score>" =>
      IndexService.INVERSE_FIELD_SCORE
    case "<doc>" =>
      SortField.FIELD_DOC
    case "-<doc>" =>
      IndexService.INVERSE_FIELD_DOC
    case _ =>
      sortFieldRE.findFirstMatchIn(field) match {
        case Some(sortFieldRE(fieldOrder, fieldName, fieldType)) =>
          new SortField(fieldName,
            fieldType match {
              case "string" =>
                SortField.Type.STRING
              case "number" =>
                SortField.Type.DOUBLE
              case null =>
                SortField.Type.DOUBLE
              case _ =>
                throw new ParseException("Unrecognized type: " + fieldType)
            }, fieldOrder == "-")
        case None =>
          throw new ParseException("Unrecognized sort parameter: " + field)
      }
  }

  private def convertFacets(name: Symbol, c: FacetsCollector): List[_] = c match {
    case null =>
      Nil
    case _ =>
      List((name, c.getFacetResults.map { f => convertFacet(f) }.toList))
  }

  private def convertFacet(facet: FacetResult): Any = {
    convertFacetNode(facet.getFacetResultNode)
  }

  private def convertFacetNode(node: FacetResultNode): Any = {
    val children = node.subResults.map { n => convertFacetNode(n) }.toList
    (node.label.components.toList, node.value, children)
  }

  private def toScoreDoc(sort: Sort, after: Any): Option[ScoreDoc] = after match {
    case 'nil =>
      None
    case (score: Any, doc: Any) =>
      Some(new ScoreDoc(ClouseauTypeFactory.toInteger(doc),
        ClouseauTypeFactory.toFloat(score)))
    case list: List[Object] =>
      val doc = list.last
      sort.getSort match {
        case Array(SortField.FIELD_SCORE) =>
          Some(new ScoreDoc(ClouseauTypeFactory.toInteger(doc),
            ClouseauTypeFactory.toFloat(list.head)))
        case _ =>
          val fields = list dropRight 1
          val sortfields = sort.getSort.toList
          if (fields.length != sortfields.length) {
            throw new ParseException("sort order not compatible with given bookmark")
          }
          Some(new FieldDoc(ClouseauTypeFactory.toInteger(doc),
            Float.NaN, (sortfields zip fields) map {
              case (_, 'null) =>
                null
              case (_, str: String) =>
                Utils.stringToBytesRef(str)
              case (SortField.FIELD_SCORE, number: java.lang.Double) =>
                java.lang.Float.valueOf(number.floatValue())
              case (IndexService.INVERSE_FIELD_SCORE, number: java.lang.Double) =>
                java.lang.Float.valueOf(number.floatValue())
              case (SortField.FIELD_DOC, number: java.lang.Double) =>
                java.lang.Integer.valueOf(number.intValue())
              case (IndexService.INVERSE_FIELD_DOC, number: java.lang.Double) =>
                java.lang.Integer.valueOf(number.intValue())
              case (_, field) =>
                field
            } toArray))
      }
  }

  override def toString: String = {
    ctx.args.name
  }

}

object IndexService {

  val version = Version.LUCENE_46
  val INVERSE_FIELD_SCORE = new SortField(null, SortField.Type.SCORE, true)
  val INVERSE_FIELD_DOC = new SortField(null, SortField.Type.DOC, true)

  def start(node: Node, config: Configuration, path: String, options: Any): Any = {
    val rootDir = new File(config.getString("clouseau.dir", "target/indexes"))
    val dir = newDirectory(config, new File(rootDir, path))
    try {
      SupportedAnalyzers.createAnalyzer(options) match {
        case Some(analyzer) =>
          val queryParser = new ClouseauQueryParser(version, "default", analyzer)
          val writerConfig = new IndexWriterConfig(version, analyzer)
          val writer = new IndexWriter(dir, writerConfig)
          ('ok, node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(config, path, queryParser, writer)))
        case None =>
          ('error, 'no_such_analyzer)
      }
    } catch {
      case e: IllegalArgumentException => ('error, e.getMessage)
      case e: IOException => ('error, e.getMessage)
    }
  }

  private def newDirectory(config: Configuration, path: File): Directory = {
    val clazzName = config.getString("clouseau.dir_class",
      "org.apache.lucene.store.NIOFSDirectory")
    val clazz = Class.forName(clazzName)
    val ctor = clazz.getConstructor(classOf[File])
    ctor.newInstance(path).asInstanceOf[Directory]
  }

}
