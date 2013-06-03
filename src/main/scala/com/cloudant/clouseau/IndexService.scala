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
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.Version
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.queryparser.classic.ParseException
import scalang._
import collection.JavaConversions._
import com.yammer.metrics.scala._
import com.cloudant.clouseau.Utils._
import org.apache.commons.configuration.Configuration

case class IndexServiceArgs(config: Configuration, name: String, queryParser: QueryParser, writer: IndexWriter)

// These must match the records in dreyfus.
case class TopDocs(updateSeq: Long, totalHits: Long, hits: List[Hit])
case class Hit(order: List[Any], fields: List[Any])

class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.%s".format(ctx.args.name))
  val sortFieldRE = """^([-+])?([\.\w]+)(?:<(\w+)>)?$""".r
  var reader = DirectoryReader.open(ctx.args.writer, true)
  var updateSeq = reader.getIndexCommit().getUserData().get("update_seq") match {
    case null => 0L
    case seq => seq.toLong
  }
  var forceRefresh = false

  val searchTimer = metrics.timer("searches")

  logger.info("Opened at update_seq %d".format(updateSeq))

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case SearchMsg(query: String, limit: Int, refresh: Boolean, after: Option[ScoreDoc], sort: Any) =>
      search(query, limit, refresh, after, sort)
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
    case CommitMsg(commitSeq: Long) =>
      ctx.args.writer.setCommitData(Map("update_seq" -> commitSeq.toString))
      ctx.args.writer.commit()
      updateSeq = commitSeq
      logger.info("Committed sequence %d".format(commitSeq))
      forceRefresh = true
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

  private def search(queryString: String, limit: Int, refresh: Boolean, after: Option[ScoreDoc], sort: Any): Any = parseQuery(queryString) match {
    case query: Query =>
      safeSearch {
        val searcher = getSearcher(refresh)
        val topDocs = searchTimer.time {
          (after, parseSort(sort)) match {
            case (None, Sort.RELEVANCE) =>
              searcher.search(query, limit)
            case (Some(scoreDoc), Sort.RELEVANCE) =>
              searcher.searchAfter(scoreDoc, query, limit)
            case (None, sort1) =>
              searcher.search(query, limit, sort1)
            case (Some(fieldDoc), sort1) =>
              searcher.searchAfter(fieldDoc, query, limit, sort1)
          }
        }
        logger.debug("search for '%s' limit=%d, refresh=%s had %d hits".format(query, limit, refresh, topDocs.totalHits))
        val hits = topDocs.scoreDocs.map({
          docToHit(searcher, _)
        }).toList
        ('ok, TopDocs(updateSeq, topDocs.totalHits, hits))
      }
    case error =>
      error
  }

  private def group1(queryString: String, field: String, refresh: Boolean, groupSort: Any,
                     groupOffset: Int, groupLimit: Int): Any = parseQuery(queryString) match {
    case query: Query =>
      val searcher = getSearcher(refresh)
      safeSearch {
        val collector = new TermFirstPassGroupingCollector(field, parseSort(groupSort), groupLimit)
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
        val collector = new TermSecondPassGroupingCollector(field, groups1, parseSort(groupSort), parseSort(docSort),
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
                      g.groupValue,
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
    val sizes = reader.directory.listAll map { reader.directory.fileLength(_) }
    val diskSize = sizes.sum
    List(
      ('disk_size, diskSize),
      ('doc_count, reader.numDocs),
      ('doc_del_count, reader.numDeletedDocs)
    )
  }

  private def parseSort(v: Any): Sort = v match {
    case 'relevance =>
      Sort.RELEVANCE
    case field: String =>
      new Sort(toSortField(field))
    case fields: List[String] =>
      new Sort(fields.map(toSortField(_)).toArray: _*)
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

  private def toSortField(field: String): SortField = sortFieldRE.findFirstMatchIn(field) match {
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

  override def toString: String = {
    ctx.args.name
  }

}

object IndexService {

  val version = Version.LUCENE_43

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
