// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.ziose.clouseau

import java.io.File
import java.io.IOException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.search._
import grouping.SearchGroup
import grouping.term.{ TermSecondPassGroupingCollector, TermFirstPassGroupingCollector }
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.Version
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.search.highlight.{
  Highlighter,
  QueryScorer,
  SimpleHTMLFormatter,
  SimpleFragmenter
}
import org.apache.lucene.analysis.Analyzer
import scalang._
import collection.JavaConverters._
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
import com.spatial4j.core.context.SpatialContext
import com.spatial4j.core.distance.DistanceUtils
import java.util.HashSet
import conversions._
import Utils.ensureElementsType
import java.lang.Throwable

case class IndexServiceArgs(config: Configuration, name: String, queryParser: QueryParser, writer: IndexWriter)
case class HighlightParameters(highlighter: Highlighter, highlightFields: List[String], highlightNumber: Int, analyzers: List[Analyzer])

// These must match the records in dreyfus.
case class TopDocs(updateSeq: Long, totalHits: Long, hits: List[Hit])
case class Hit(order: List[Any], fields: List[Any])

class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) with Instrumented {
  import IndexService.{
    getDrilldown,
    getGroups,
    getIncludeFields,
    getListOfStringsOption,
    getOption,
    getRanges,
    GroupName,
    RangesLabel,
    RangesName,
    RangesQuery
  }

  var reader = DirectoryReader.open(ctx.args.writer, true)
  var updateSeq = getCommittedSeq
  var pendingSeq = updateSeq
  var purgeSeq = getCommittedPurgeSeq
  var pendingPurgeSeq = purgeSeq
  var committing = false
  var forceRefresh = false
  var idle = true

  val searchTimer = metrics.timer("searches")
  val updateTimer = metrics.timer("updates")
  val deleteTimer = metrics.timer("deletes")
  val commitTimer = metrics.timer("commits")

  val parSearchTimeOutCount = metrics.counter("partition_search.timeout.count")

  // Start committer heartbeat
  val commitInterval = ctx.args.config.getInt("commit_interval_secs", 30)
  val timeAllowed = ctx.args.config.getLong("clouseau.search_allowed_timeout_msecs", 5000)
  sendEvery(self, 'maybe_commit, commitInterval * 1000)
  val countFieldsEnabled = ctx.args.config.getBoolean("clouseau.count_fields", false)
  send(self, 'count_fields)

  // Check if the index is idle and optionally close it if there is no activity between
  //Two consecutive idle status checks.
  val closeIfIdleEnabled = ctx.args.config.getBoolean("clouseau.close_if_idle", false)
  val idleTimeout = ctx.args.config.getInt("clouseau.idle_check_interval_secs", 300)
  if (closeIfIdleEnabled) {
    sendEvery(self, 'close_if_idle, idleTimeout * 1000)
  }

  debug("Opened at update_seq %d".format(updateSeq))

  override def handleCall(tag: (Pid, Any), msg: Any): Any = {
    idle = false
    send('main, ('touch_lru, ctx.args.name))
    internalHandleCall(tag, msg)
  }

  def internalHandleCall(tag: (Pid, Any), msg: Any): Any = msg match {
    case request: SearchRequest =>
      search(request)
    case Group1Msg(query: String, field: String, refresh: Boolean, groupSort: Any, groupOffset: Int,
      groupLimit: Int) =>
      group1(query, field, refresh, groupSort, groupOffset, groupLimit)
    case request: Group2Msg =>
      group2(request)
    case 'get_update_seq =>
      ('ok, updateSeq)
    case 'get_purge_seq =>
      ('ok, purgeSeq)
    case UpdateDocMsg(id: String, doc: Document) =>
      debug("Updating %s".format(id))
      updateTimer.time {
        ctx.args.writer.updateDocument(new Term("_id", id), doc)
      }
      'ok
    case DeleteDocMsg(id: String) =>
      debug("Deleting %s".format(id))
      deleteTimer.time {
        ctx.args.writer.deleteDocuments(new Term("_id", id))
      }
      'ok
    case CommitMsg(commitSeq: Long) => // deprecated
      pendingSeq = commitSeq
      debug("Pending sequence is now %d".format(commitSeq))
      'ok
    case SetUpdateSeqMsg(newSeq: Long) =>
      pendingSeq = newSeq
      debug("Pending sequence is now %d".format(newSeq))
      'ok
    case SetPurgeSeqMsg(newPurgeSeq: Long) =>
      pendingPurgeSeq = newPurgeSeq
      debug("purge sequence is now %d".format(newPurgeSeq))
      'ok
    case 'info =>
      ('ok, getInfo)
    case ('create_snapshot, snapshotDir: String) =>
      createSnapshot(snapshotDir)
  }

  override def handleCast(msg: Any) = msg match {
    case ('merge, maxNumSegments: Int) =>
      debug("Forcibly merging index to no more than " + maxNumSegments + " segments.")
      node.spawn((_) => {
        ctx.args.writer.forceMerge(maxNumSegments, true)
        ctx.args.writer.commit
        forceRefresh = true
        debug("Forced merge complete.")
      })
    case _ =>
      'ignored
  }

  override def handleInfo(msg: Any) = msg match {
    case 'close =>
      exit(msg)
    case ('close, reason) =>
      exit(reason)
    case ('close_if_idle) =>
      if (idle && !ctx.args.writer.hasPendingMerges()) {
        exit("Idle Timeout")
      }
      idle = true
    case 'count_fields =>
      countFields
    case 'delete =>
      val dir = ctx.args.writer.getDirectory
      ctx.args.writer.close()
      for (name <- dir.listAll) {
        dir.deleteFile(name)
      }
      exit('deleted)
    case 'maybe_commit =>
      commit(pendingSeq, pendingPurgeSeq)
    case ('committed, newUpdateSeq: Long, newPurgeSeq: Long) =>
      updateSeq = newUpdateSeq
      purgeSeq = newPurgeSeq
      forceRefresh = true
      committing = false
      debug("Committed update sequence %d and purge sequence %d".format(newUpdateSeq, newPurgeSeq))
    case 'commit_failed =>
      committing = false
  }

  def countFields() = {
    if (countFieldsEnabled) {
      val leaves = reader.leaves().iterator()
      val warningThreshold = ctx.args.config.
        getInt("clouseau.field_count_warn_threshold", 5000)
      val fields = new HashSet[String]()
      while (leaves.hasNext() && fields.size <= warningThreshold) {
        val fieldInfoIter = leaves.next.reader().getFieldInfos().iterator()
        while (fieldInfoIter.hasNext() && fields.size <= warningThreshold) {
          fields.add(fieldInfoIter.next().name)
        }
      }
      if (fields.size > warningThreshold) {
        warn("Index has more than %d fields, ".format(warningThreshold) +
          "too many fields will lead to heap exhuastion")
      }
    }
  }

  override def exit(msg: Any) = {
    debug("Closed with reason: %.1000s".format(msg))
    try {
      reader.close()
    } catch {
      case e: IOException => warn("Error while closing reader", e)
    }
    try {
      ctx.args.writer.rollback()
    } catch {
      case e: AlreadyClosedException => 'ignored
      case e: IOException =>
        warn("Error while closing writer", e)
        val dir = ctx.args.writer.getDirectory
        if (IndexWriter.isLocked(dir)) {
          IndexWriter.unlock(dir);
        }
    } finally {
      super.exit(msg)
    }
  }

  private def commit(newUpdateSeq: Long, newPurgeSeq: Long) = {
    if (!committing && (newUpdateSeq > updateSeq || newPurgeSeq > purgeSeq)) {
      committing = true
      val index = self
      node.spawn((_) => {
        ctx.args.writer.setCommitData((ctx.args.writer.getCommitData.asScala +
          ("update_seq" -> newUpdateSeq.toString) +
          ("purge_seq" -> newPurgeSeq.toString)).asJava)
        try {
          commitTimer.time {
            ctx.args.writer.commit()
          }
          index ! ('committed, newUpdateSeq, newPurgeSeq)
        } catch {
          case e: AlreadyClosedException =>
            error("Commit failed to closed writer", e)
            index ! 'commit_failed
          case e: IOException =>
            error("Failed to commit changes", e)
            index ! 'commit_failed
        }
      })
    }
  }

  private def search(request: SearchRequest): Any = {
    val queryString = request.options.getOrElse('query, "*:*").asInstanceOf[String]
    val refresh = request.options.getOrElse('refresh, true).asInstanceOf[Boolean]
    val limit = request.options.getOrElse('limit, 25).asInstanceOf[Int]

    val partition: Option[String] = getOption[String](request.options, 'partition)

    val counts: Option[List[String]] = getListOfStringsOption(request.options, 'counts)

    val ranges = getRanges(request.options)

    val includeFields: Option[Set[String]] = getIncludeFields(request.options)

    val legacy = request.options.getOrElse('legacy, false).asInstanceOf[Boolean]

    parseQuery(queryString, partition) match {
      case baseQuery: Query =>
        safeSearch {
          val query = getDrilldown(request.options) match {
            case Some(categories: List[_]) => {
              val drilldownQuery = new DrillDownQuery(
                FacetIndexingParams.DEFAULT, baseQuery)
              for (category <- categories) {
                val category1 = category.toArray
                val len = category1.length
                try {
                  if (len < 3) {
                    drilldownQuery.add(new CategoryPath(category1: _*))
                  } else { //if there are multiple values OR'd them, delete this else part after updating to Apache Lucene > 4.6
                    val dim = category1(0)
                    val categoryPaths: Array[CategoryPath] = new Array[CategoryPath](len - 1)
                    for (i <- 1 until len) {
                      categoryPaths(i - 1) = new CategoryPath(Array(dim, category1(i)): _*)
                    }
                    drilldownQuery.add(categoryPaths: _*)
                  }
                } catch {
                  case e: IllegalArgumentException =>
                    throw new ParseException(e.getMessage)
                  case e: ArrayStoreException =>
                    throw new ParseException(category +
                      " contains a non-string item")
                }
              }
              drilldownQuery
            }
            case Some(_) =>
              throw new ParseException("invalid drilldown query")
            case None => baseQuery
          }
          val searcher = getSearcher(refresh)
          val weight = searcher.createNormalizedWeight(query)
          val docsScoredInOrder = !weight.scoresDocsOutOfOrder

          val sort = parseSort(request.options.getOrElse('sort, 'relevance)).rewrite(searcher)
          val after = toScoreDoc(sort, getOption(request.options, 'after))

          val hitsCollector = (limit, after, sort) match {
            case (0, _, _) =>
              new TotalHitCountCollector
            case (_, None, Sort.RELEVANCE) =>
              TopScoreDocCollector.create(limit, docsScoredInOrder)
            case (_, Some(scoreDoc), Sort.RELEVANCE) =>
              TopScoreDocCollector.create(limit, scoreDoc, docsScoredInOrder)
            case (_, None, sort1: Sort) =>
              TopFieldCollector.create(sort1, limit, true, false, false,
                docsScoredInOrder)
            case (_, Some(fieldDoc: FieldDoc), sort1: Sort) =>
              TopFieldCollector.create(sort1, limit, fieldDoc, true, false,
                false, docsScoredInOrder)
          }

          val countsCollector = createCountsCollector(counts)

          val rangesCollector = ranges match {
            case None =>
              null
            case Some(rangeList: List[_]) =>
              val rangeFacetRequests: List[FacetRequest] = for ((name: RangesName, ranges: List[_]) <- rangeList) yield {
                new RangeFacetRequest(name, ranges.map({
                  case (label: RangesLabel, rangeQuery: RangesQuery) =>
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
                }).asJava)
              }
              val acc = new RangeAccumulator(rangeFacetRequests.asJava)
              FacetsCollector.create(acc)
            case Some(other) =>
              throw new ParseException(other + " is not a valid ranges query")
          }

          val collector = MultiCollector.wrap(
            hitsCollector, countsCollector, rangesCollector)

          searchTimer.time {
            partition match {
              case None =>
                searcher.search(query, collector)
              case Some(p) =>
                val tlcollector = new TimeLimitingCollector(collector,
                  TimeLimitingCollector.getGlobalCounter, timeAllowed)
                try {
                  searcher.search(query, tlcollector)
                } catch {
                  case x: TimeLimitingCollector.TimeExceededException => {
                    parSearchTimeOutCount += 1
                    throw new ParseException("Query exceeded allowed time: " + timeAllowed + "ms.")
                  }
                }
            }
          }
          debug("search for '%s' limit=%d, refresh=%s had %d hits".
            format(query, limit, refresh, getTotalHits(hitsCollector)))
          val HPs = getHighlightParameters(request.options, query)

          val hits = getHits(hitsCollector, searcher, includeFields, HPs)

          if (legacy) {
            ('ok, TopDocs(updateSeq, getTotalHits(hitsCollector), hits))
          } else {
            ('ok, List(
              ('update_seq, updateSeq),
              ('total_hits, getTotalHits(hitsCollector)),
              ('hits, hits)
            ) ++ convertFacets('counts, countsCollector)
              ++ convertFacets('ranges, rangesCollector))
          }
        }
      case error =>
        error
    }
  }

  private def getTotalHits(collector: Collector) = collector match {
    case c: TopDocsCollector[_] =>
      c.getTotalHits
    case c: TotalHitCountCollector =>
      c.getTotalHits
  }

  private def getHits(collector: Collector, searcher: IndexSearcher,
                      includeFields: Option[Set[String]], HPs: Option[HighlightParameters] = None) =
    collector match {
      case c: TopDocsCollector[_] =>
        c.topDocs.scoreDocs.map({ docToHit(searcher, _, includeFields, HPs) }).toList
      case c: TotalHitCountCollector =>
        Nil
    }

  private def createCountsCollector(counts: Option[List[String]]): FacetsCollector = {
    counts match {
      case None =>
        null
      case Some(counts: List[_]) =>
        val state = try {
          new SortedSetDocValuesReaderState(reader)
        } catch {
          case e: IllegalArgumentException =>
            if (e.getMessage contains "was not indexed with SortedSetDocValues")
              return null
            else
              throw e
        }
        val countFacetRequests: List[FacetRequest] = for (count <- counts) yield {
          new CountFacetRequest(new CategoryPath(count), Int.MaxValue)
        }
        val facetSearchParams = new FacetSearchParams(countFacetRequests.asJava)
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
                     groupOffset: Int, groupLimit: Int): Any = parseQuery(queryString, None) match {
    case query: Query =>
      val searcher = getSearcher(refresh)
      safeSearch {
        val fieldName = validateGroupField(field)
        val collector = new TermFirstPassGroupingCollector(fieldName,
          parseSort(groupSort).rewrite(searcher), groupLimit)
        searchTimer.time {
          searcher.search(query, collector)
          collector.getTopGroups(groupOffset, true) match {
            case null =>
              ('ok, List())
            case topGroups =>
              ('ok, topGroups.asScala map {
                g => (g.groupValue, convertOrder(g.sortValues))
              })
          }
        }
      }
    case error =>
      error
  }

  private def group2(request: Group2Msg): Any = {
    val queryString = request.options.getOrElse('query, "*:*").asInstanceOf[String]
    val field = request.options('field).asInstanceOf[String]
    val refresh = request.options.getOrElse('refresh, true).asInstanceOf[Boolean]
    val groups = getGroups(request.options).getOrElse(List())
    val groupSort = request.options('group_sort)
    val docSort = request.options('sort)
    val docLimit = request.options.getOrElse('limit, 25).asInstanceOf[Int]
    val includeFields: Option[Set[String]] = getIncludeFields(request.options)
    parseQuery(queryString, None) match {
      case query: Query =>
        val searcher = getSearcher(refresh)
        val groups1 = groups.map {
          g => makeSearchGroup(g)
        }
        safeSearch {
          val fieldName = validateGroupField(field)
          val collector = new TermSecondPassGroupingCollector(fieldName, groups1.asJava,
            parseSort(groupSort).rewrite(searcher),
            parseSort(docSort).rewrite(searcher), docLimit, true, false, true)
          searchTimer.time {
            searcher.search(query, collector)
            collector.getTopGroups(0) match {
              case null =>
                ('ok, 0, 0, List())
              case topGroups => {
                val HPs = getHighlightParameters(request.options, query)
                ('ok, topGroups.totalHitCount, topGroups.totalGroupedHitCount,
                  topGroups.groups.map {
                    g =>
                      (
                        g.groupValue,
                        g.totalHits,
                        g.scoreDocs.map({
                          docToHit(searcher, _, includeFields, HPs)
                        }).toList
                      )
                  }.toList)
              }
            }
          }
        }
      case error =>
        error
    }
  }

  private def getHighlightParameters(options: Map[Symbol, Any], query: Query): Option[HighlightParameters] =
    getListOfStringsOption(options, 'highlight_fields).map(highlightFields => {
      val preTag = options.getOrElse('highlight_pre_tag,
        "<em>").asInstanceOf[String]
      val postTag = options.getOrElse('highlight_post_tag,
        "</em>").asInstanceOf[String]
      val highlightNumber = options.getOrElse('highlight_number,
        1).asInstanceOf[Int] //number of fragments
      val highlightSize = options.getOrElse('highlight_size, 0).
        asInstanceOf[Int]
      val htmlFormatter = new SimpleHTMLFormatter(preTag, postTag)
      val highlighter = new Highlighter(htmlFormatter, new QueryScorer(query))
      if (highlightSize > 0) {
        highlighter.setTextFragmenter(new SimpleFragmenter(highlightSize))
      }
      val analyzers = highlightFields.map { field =>
        ctx.args.queryParser.getAnalyzer() match {
          case a1: PerFieldAnalyzer =>
            a1.getWrappedAnalyzer(field)
          case a2: Analyzer =>
            a2
        }
      }
      HighlightParameters(highlighter, highlightFields, highlightNumber, analyzers)
    })

  private def validateGroupField(field: String) = {
    IndexService.SORT_FIELD_RE.findFirstMatchIn(field) match {
      case Some(IndexService.SORT_FIELD_RE(_fieldOrder, fieldName, "string")) =>
        (fieldName)
      case Some(IndexService.SORT_FIELD_RE(_fieldOrder, fieldName, null)) =>
        (fieldName)
      case Some(IndexService.SORT_FIELD_RE(_fieldOrder, fieldName, "number")) =>
        throw new ParseException("Group by number not supported. Group by string terms only.")
      case None =>
        throw new ParseException("Unrecognized group_field parameter: "
          + field)
    }
  }

  private def makeSearchGroup(group: Any): SearchGroup[BytesRef] = group match {
    case (None, order: List[AnyRef @unchecked]) =>
      val result: SearchGroup[BytesRef] = new SearchGroup
      result.sortValues = order.collect({ case ref: AnyRef => ref }).toArray
      result
    case (Some(name: String), order: List[AnyRef @unchecked]) =>
      val result: SearchGroup[BytesRef] = new SearchGroup
      result.groupValue = name
      result.sortValues = order.collect({ case ref: AnyRef => ref }).toArray
      result
  }

  private def parseQuery(query: String, partition: Option[String]): Any = {
    safeSearch {
      partition match {
        case None =>
          ctx.args.queryParser.parse(query)
        case Some(p) =>
          val q = new BooleanQuery();
          q.add(new TermQuery(new Term("_partition", p)), Occur.MUST);
          q.add(ctx.args.queryParser.parse(query), Occur.MUST);
          q
      }
    }
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
    case e: Throwable =>
      ('error, e.getMessage)
  }

  private def getSearcher(refresh: Boolean): IndexSearcher = {
    if (forceRefresh || refresh) {
      reopenIfChanged()
    }
    new IndexSearcher(reader)
  }

  private def reopenIfChanged() = {
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
      ('committed_seq, getCommittedSeq),
      ('purge_seq, purgeSeq)
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

  private def getCommittedPurgeSeq = {
    val commitData = ctx.args.writer.getCommitData
    commitData.get("purge_seq") match {
      case null =>
        0L
      case seq =>
        seq.toLong
    }
  }

  private def parseSort(v: Any): Sort = v match {
    case 'relevance =>
      Sort.RELEVANCE
    case field: String =>
      new Sort(toSortField(field))
    case fields: List[String @unchecked] =>
      new Sort(fields.map(toSortField).toArray: _*)
  }

  private def docToHit(searcher: IndexSearcher, scoreDoc: ScoreDoc,
                       includeFields: Option[Set[String]] = null, HPs: Option[HighlightParameters] = None): Hit = {
    val doc = includeFields match {
      case None =>
        searcher.doc(scoreDoc.doc)
      case Some(fields) =>
        searcher.doc(scoreDoc.doc, fields.asJava)
    }

    var fields = doc.getFields.asScala.foldLeft(Map[String, Any]())((acc, field) => {
      val value = field.numericValue match {
        case null =>
          field.stringValue
        case num =>
          num
      }
      acc.get(field.name) match {
        case None =>
          acc + (field.name -> value)
        case Some(list: List[_]) =>
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

    HPs match {
      case Some(parameters: HighlightParameters) => {
        val highlights = (parameters.highlightFields zip parameters.analyzers).map {
          case (field, analyzer) =>
            (field, doc.getValues(field).flatMap { v =>
              parameters.highlighter.getBestFragments(analyzer, field,
                v, parameters.highlightNumber).toList
            }.toList)
        }
        fields += "_highlights" -> highlights.toList
      }
      case None => ()
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
      IndexService.INVERSE_FIELD_SCORE
    case "-<score>" =>
      SortField.FIELD_SCORE
    case "<doc>" =>
      SortField.FIELD_DOC
    case "-<doc>" =>
      IndexService.INVERSE_FIELD_DOC
    case IndexService.DISTANCE_RE(fieldOrder, fieldLon, fieldLat, lon, lat, units) =>
      val radius = units match {
        case "mi" => DistanceUtils.EARTH_EQUATORIAL_RADIUS_MI
        case "km" => DistanceUtils.EARTH_EQUATORIAL_RADIUS_KM
        case null => DistanceUtils.EARTH_EQUATORIAL_RADIUS_KM
      }
      val ctx = SpatialContext.GEO
      val point = ctx.makePoint(lon.toDouble, lat.toDouble)
      val degToKm = DistanceUtils.degrees2Dist(1, radius)
      val valueSource = new DistanceValueSource(ctx, fieldLon, fieldLat, degToKm, point)
      valueSource.getSortField(fieldOrder == "-")
    case IndexService.SORT_FIELD_RE(fieldOrder, fieldName, fieldType) =>
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
    case _ =>
      throw new ParseException("Unrecognized sort parameter: " + field)
  }

  private def convertFacets(name: Symbol, c: FacetsCollector): List[_] = c match {
    case null =>
      Nil
    case _ =>
      List((name, c.getFacetResults.asScala.map { f => convertFacet(f) }.toList))
  }

  private def convertFacet(facet: FacetResult): Any = {
    convertFacetNode(facet.getFacetResultNode)
  }

  private def convertFacetNode(node: FacetResultNode): Any = {
    val children = node.subResults.asScala.map { n => convertFacetNode(n) }.toList
    (node.label.components.toList, node.value, children)
  }

  private def toScoreDoc(sort: Sort, after: Option[Any]): Option[ScoreDoc] = after match {
    case None =>
      None
    case Some((score: Any, doc: Any)) =>
      Some(new ScoreDoc(ClouseauTypeFactory.toInteger(doc),
        ClouseauTypeFactory.toFloat(score)))
    case Some(list: List[Object @unchecked]) =>
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
            Float.NaN, sortfields.zip(fields).map {
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
            }.toArray))
      }
  }

  private def createSnapshot(snapshotDir: String) = {
    try {
      getSnapshotDeletionPolicy().snapshot(new File(snapshotDir))
      'ok
    } catch {
      case e: IllegalStateException =>
        ('error, e.getMessage)
      case e: IOException =>
        ('error, e.getMessage)
    }
  }

  private def getSnapshotDeletionPolicy(): ExternalSnapshotDeletionPolicy = {
    ctx.args.writer.getConfig().getIndexDeletionPolicy().asInstanceOf[ExternalSnapshotDeletionPolicy]
  }

  private def debug(str: String) = {
    IndexService.logger.debug(prefix_name(str))
  }

  private def info(str: String) = {
    IndexService.logger.info(prefix_name(str))
  }

  private def warn(str: String) = {
    IndexService.logger.warn(prefix_name(str))
  }

  private def warn(str: String, e: Throwable) = {
    IndexService.logger.warn(prefix_name(str), e)
  }

  private def error(str: String, e: Throwable) = {
    IndexService.logger.error(prefix_name(str), e)
  }

  private def prefix_name(str: String): String = {
    ctx.args.name + " " + str
  }

  override def toString: String = {
    ctx.args.name
  }

}

object IndexService {

  val logger = LoggerFactory.getLogger("clouseau")
  val version = Version.LUCENE_46
  val INVERSE_FIELD_SCORE = new SortField(null, SortField.Type.SCORE, true)
  val INVERSE_FIELD_DOC = new SortField(null, SortField.Type.DOC, true)
  val SORT_FIELD_RE = """^([-+])?([\.\w]+)(?:<(\w+)>)?$""".r
  val FP = """([-+]?[0-9]+(?:\.[0-9]+)?)"""
  val DISTANCE_RE = "^([-+])?<distance,([\\.\\w]+),([\\.\\w]+),%s,%s,(mi|km)>$".format(FP, FP).r

  def start(node: Node, config: Configuration, path: String, options: AnalyzerOptions): Any = {
    val rootDir = new File(config.getString("clouseau.dir", "target/indexes"))
    val dir = newDirectory(config, new File(rootDir, path))
    try {
      SupportedAnalyzers.createAnalyzer(options) match {
        case Some(analyzer) =>
          val queryParser = new ClouseauQueryParser(version, "default", analyzer)
          val writerConfig = new IndexWriterConfig(version, analyzer)
          writerConfig.setIndexDeletionPolicy(new ExternalSnapshotDeletionPolicy(dir))
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

  private def newDirectory(config: Configuration, path: File): FSDirectory = {
    val lockClassName = config.getString("clouseau.lock_class",
      "org.apache.lucene.store.NativeFSLockFactory")
    val lockClass = Class.forName(lockClassName)
    val lockFactory = lockClass.newInstance().asInstanceOf[LockFactory]

    val dirClassName = config.getString("clouseau.dir_class",
      "org.apache.lucene.store.NIOFSDirectory")
    val dirClass = Class.forName(dirClassName)
    val dirCtor = dirClass.getConstructor(classOf[File], classOf[LockFactory])
    dirCtor.newInstance(path, lockFactory).asInstanceOf[FSDirectory]
  }

  /**
   * Returns the Option[List[List[String]]] of a `drilldown` key from given map of options if it is in correct format.
   *
   * @param options: Map[Symbol, Any] - map of options to extract `drilldown` key
   * @return An optional value of a `drilldown` key as `Option[List[List[String]]]`
   * @throws ParseException
   *
   */

  private[clouseau] def getDrilldown(options: Map[Symbol, Any]): Option[List[List[String]]] = {
    val asListOfStrings: PartialFunction[Any, List[String]] = ensureElementsType(
      { case string: String => string },
      { case (container, element) => throw new ParseException(container.toString + " contains non-string element " + element.toString) }
    )

    val asListofListsOfStrings: PartialFunction[Any, List[List[String]]] = ensureElementsType(
      asListOfStrings,
      { case (container, element) => throw new ParseException("invalid drilldown query " + container.toString) }
    )

    val extractor = asListofListsOfStrings.Extractor
    getOption[Any](options, 'drilldown) match {
      case Some(extractor(value)) =>
        Some(value)
      case None =>
        None
    }
  }

  /**
   * Returns the Option[value] of a `ranges` key from given map of options if it is in correct format.
   *
   * @param options: Map[Symbol, Any] - map of options to extract `ranges` key
   * @return An optional value of a `ranges` key as `Option[List[Product2[RangesName, List[Product2[RangesLabel, RangesQuery]]]]]`
   * @throws ParseException
   *
   * The function expects the `ranges` value to be in a following format
   *
   * ```erlang
   * -type name :: string().
   * -type label :: string().
   * -type query :: string().
   * [{name(), [
   *   {label(), query()}
   * ]}]
   * ```
   *
   * or the same in scala format
   *
   * ```scala
   * type Name = String
   * type Label = String
   * type Query = String
   * List[(Name, List(
   *   (Label, Query)
   * ))]
   * ```
   *
   */
  private[clouseau]type RangesLabel = String
  private[clouseau]type RangesQuery = String
  private[clouseau]type RangesName = String

  private[clouseau] def getRanges(options: Map[Symbol, Any]): Option[List[Product2[RangesName, List[Product2[RangesLabel, RangesQuery]]]]] = {
    val asQueries: PartialFunction[Any, List[Product2[RangesLabel, RangesQuery]]] = ensureElementsType(
      { case (label: RangesLabel, query: RangesQuery) => (label, query) },
      { case (_container, _element) => throw new ParseException("invalid ranges query") }
    )

    val asQueriesExtractor = asQueries.Extractor

    val asRange: PartialFunction[Any, List[Product2[RangesName, List[Product2[RangesLabel, RangesQuery]]]]] = ensureElementsType(
      { case (name: RangesName, asQueriesExtractor(queries)) => (name, queries) },
      { case (container, _element) => throw new ParseException(container.toString + " is not a valid ranges query") }
    ).orElse({ case notAList => throw new ParseException("invalid ranges query") })

    val extractor = asRange.Extractor

    getOption[Any](options, 'ranges) match {
      case Some(extractor(value)) =>
        Some(value)
      case None =>
        None
    }
  }

  /**
   * Returns the Option[List[String]] of a given option key from a map of options if each element of a list is indeed a String.
   *
   * @param options: Map[Symbol, Any] - map of options to extract given key
   * @return An optional value of a given key as `Option[List[String]]`
   * @throws ParseException
   *
   */
  private[clouseau] def getListOfStringsOption(options: Map[Symbol, Any], field: Symbol): Option[List[String]] = {
    val asListOfStrings: PartialFunction[Any, List[String]] = ensureElementsType(
      { case string: String => string },
      { case (container, _element) => throw new ParseException(container.toString + " is not a valid " + field + " query") }
    ).orElse({ case notAList => throw new ParseException(notAList.toString + " is not a valid " + field + " query") })

    val extractor = asListOfStrings.Extractor
    getOption[Any](options, field) match {
      case Some(extractor(value)) =>
        Some(value)
      case None =>
        None
    }
  }

  /**
   * Returns the Option[Set[String]] of a `include_fields` key from given map of options if each element of a list
   * is indeed a String.
   *
   * It does convert List[String] to Set[String] before returning the result. It also injects an "_id" key into Set[String].
   *
   * @param options: Map[Symbol, Any] - map of options to extract given key
   * @return An optional value of a `include_fields` key as `Option[Set[String]]`
   * @throws ParseException
   *
   */

  private[clouseau] def getIncludeFields(options: Map[Symbol, Any]): Option[Set[String]] = {
    getListOfStringsOption(options, 'include_fields).map(value => Set[String]() ++ ("_id" :: value).toSet)
  }

  /**
   * Returns the Option[List[Product2[Option[GroupName], List[Any]]]] of a `groups` key from given map of options
   * if each element of a list of tuples with arity 2 and first element is a String.
   * It does not check whether the second element of the tuple whether it is AnyRef or Any because of auto unboxing in scala.
   *
   * @param options: Map[Symbol, Any] - map of options to extract given key from
   * @return An optional value of a `groups` key as `Option[List[Product2[Option[GroupName], List[Any]]]]`
   * @throws ParseException
   *
   */

  private[clouseau]type GroupName = String
  private[clouseau] def getGroups(options: Map[Symbol, Any]): Option[List[Product2[Option[GroupName], List[Any]]]] = {
    val asGroup: PartialFunction[Any, List[Product2[Option[GroupName], List[Any]]]] = ensureElementsType(
      {
        case (name: GroupName, order: List[_]) => (Some(name), order)
        case ('null, order: List[_]) => (None, order)
      },
      { case (container, _element) => throw new ParseException(container.toString + " is not a valid groups query") }
    ).orElse({ case notAList => throw new ParseException("invalid groups query") })

    val extractor = asGroup.Extractor

    getOption[Any](options, 'groups) match {
      case Some(extractor(value)) =>
        Some(value)
      case None =>
        None
    }
  }

  private[clouseau] def getOption[T](options: Map[Symbol, Any], field: Symbol): Option[T] = {
    // Unfortunately CouchDB sends 'nil over the wire
    options.get(field) match {
      case Some('nil) => None
      case Some(value) => Some(value.asInstanceOf[T])
      case None => None
    }
  }
}
