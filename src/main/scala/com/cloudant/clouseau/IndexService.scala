package com.cloudant.clouseau

import java.io.File
import java.io.IOException
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.search._
import org.apache.lucene.util.Version
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.queryparser.classic.ParseException
import scalang._
import scalang.node._
import java.nio.charset.Charset
import java.nio.ByteBuffer
import org.apache.lucene.document.Field.Index
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.Field.TermVector
import collection.JavaConversions._
import com.cloudant.clouseau.Utils._
import com.yammer.metrics.scala._

case class IndexServiceArgs(name : String, queryParser : QueryParser, writer : IndexWriter)

// These must match the records in dreyfus.
case class TopDocs(updateSeq : Long, totalHits : Long, hits : List[Hit])
case class Hit(order : List[Any], fields : List[Any])

class IndexService(ctx : ServiceContext[IndexServiceArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.%s".format(ctx.args.name))
  var reader = IndexReader.open(ctx.args.writer, true)
  var updateSeq = reader.getIndexCommit().getUserData().get("update_seq") match {
    case null => 0L
    case seq  => seq.toLong
  }
  var forceRefresh = false

  val searchTimer = metrics.timer("searches")

  logger.info("Opened at update_seq %d".format(updateSeq))

  override def handleCall(tag : (Pid, Reference), msg : Any) : Any = msg match {
    case SearchMsg(query : String, limit : Int, refresh : Boolean, after : Option[ScoreDoc], sort : Option[Sort]) =>
      search(query, limit, refresh, after, sort)
    case 'get_update_seq =>
      ('ok, updateSeq)
    case UpdateDocMsg(id : String, doc : Document) =>
      logger.debug("Updating %s".format(id))
      ctx.args.writer.updateDocument(new Term("_id", id), doc)
      'ok
    case DeleteDocMsg(id : String) =>
      logger.debug("Deleting %s".format(id))
      ctx.args.writer.deleteDocuments(new Term("_id", id))
      'ok
    case CommitMsg(commitSeq : Long) =>
      ctx.args.writer.commit(Map("update_seq" -> commitSeq.toString))
      updateSeq = commitSeq
      logger.info("Committed sequence %d".format(commitSeq))
      forceRefresh = true
      'ok
    case 'info =>
      ('ok, getInfo)
  }

  override def handleInfo(msg : Any) = msg match {
    case 'close =>
      exit(msg)
    case ('close, reason) =>
      exit(reason)
    case 'delete =>
      val dir = ctx.args.writer.getDirectory
      ctx.args.writer.close
      for (name <- dir.listAll) {
        dir.deleteFile(name)
      }
      exit('deleted)
  }

  override def exit(msg : Any) {
    logger.info("Closed with reason %s".format(msg))
    try {
      ctx.args.writer.rollback
    } catch {
      case e : AlreadyClosedException => 'ignored
      case e : IOException            => logger.warn("Error while closing writer", e)
    } finally {
      super.exit(msg)
    }
  }

  private def search(query : String, limit : Int, refresh : Boolean, after : Option[ScoreDoc], sort : Option[Sort]) : Any = {
    try {
      search(ctx.args.queryParser.parse(query), limit, refresh, after, sort)
    } catch {
      case e : ParseException        =>
        logger.warn("Cannot parse %s".format(query))
        ('error, ('bad_request, "cannot parse query"))
      case e : NumberFormatException =>
        logger.warn("Cannot parse %s".format(query))
        ('error, ('bad_request, "cannot parse query"))
    }
  }

  private def search(query : Query, limit : Int, refresh : Boolean, after : Option[ScoreDoc], sort : Option[Sort]) : Any = {
    if (forceRefresh || refresh) {
      reopenIfChanged
    }

    val searcher = new IndexSearcher(reader)
    val topDocs = searchTimer.time {
      (after, sort) match {
        case (None, None) =>
          searcher.search(query, limit)
        case (Some(scoreDoc), None) =>
          searcher.searchAfter(scoreDoc, query, limit)
        case (None, Some(sort)) =>
          searcher.search(query, limit, sort)
        case (Some(fieldDoc), Some(sort)) =>
          searcher.searchAfter(fieldDoc, query, limit, sort)
      }
    }
    logger.debug("search for '%s' limit=%d, refresh=%s had %d hits".format(query, limit, refresh, topDocs.totalHits))
    val hits = for (scoreDoc <- topDocs.scoreDocs) yield {
      val doc = searcher.doc(scoreDoc.doc)
      val fields = doc.getFields.foldLeft(Map[String,Any]())((acc,field) => {
        val value = field.numericValue match {
          case null =>
            toBinary(field.stringValue)
          case num =>
            num
        }
        acc.get(field.name) match {
          case None =>
            acc + (field.name -> value)
          case Some(list : List[Any]) =>
            acc + (field.name -> (value :: list))
          case Some(existingValue : Any) =>
            acc + (field.name -> List(value, existingValue))
        }
      })
      val order = scoreDoc match {
        case fieldDoc : FieldDoc =>
          fieldDoc.fields.toList :+ scoreDoc.doc
        case _ =>
          List[Any](scoreDoc.score, scoreDoc.doc)
      }
      Hit(order,
          fields.map {
            case(k,v:List[Any]) => (toBinary(k), v.reverse)
              case(k,v) => (toBinary(k), v)
          }.toList)
    }
    ('ok, TopDocs(updateSeq, topDocs.totalHits, hits.toList))
  }

  private def reopenIfChanged() {
      val newReader = DirectoryReader.openIfChanged(reader)
      if (newReader != null) {
        reader.decRef
        reader = newReader
        forceRefresh = false
      }
  }

  private def getInfo() : List[Any] = {
    reopenIfChanged
    var sizes = reader.directory.listAll map {reader.directory.fileLength(_)}
    var diskSize = sizes.sum
    List(
      ('disk_size, diskSize),
      ('doc_count, reader.numDocs),
      ('doc_del_count, reader.numDeletedDocs)
    )
  }

  private def toBinary(str : String) : Array[Byte] = {
    str.getBytes("UTF-8")
  }

  override def toString() : String = {
    ctx.args.name
  }

}

object IndexService {

  val version = Version.LUCENE_40

  def start(node : Node, rootDir : File, path : String, options : Any) : Any = {
    val dir = newDirectory(new File(rootDir, path))
    try {
      createAnalyzer(options) match {
        case Some(analyzer) =>
          val queryParser = new ClouseauQueryParser(version, "default", analyzer)
          val config = new IndexWriterConfig(version, analyzer)
          val writer = new IndexWriter(dir, config)
          ('ok, node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(path, queryParser, writer)))
        case None =>
          ('error, 'no_such_analyzer)
      }
    } catch {
      case e : IllegalArgumentException => ('error, e.getMessage)
      case e : IOException => ('error, e.getMessage)
    }
  }

  def createAnalyzer(options : Any) : Option[Analyzer] = {
    SupportedAnalyzers.createAnalyzer(options match {
      case name : ByteBuffer =>
        Map("name" -> byteBufferToString(name))
      case options : List[(ByteBuffer, Any)] =>
        toMap(options)
    })
  }

  private def newDirectory(path : File) : Directory = {
    val clazzName = Main.config.getString("clouseau.dir_class",
      "org.apache.lucene.store.NIOFSDirectory")
    val clazz = Class.forName(clazzName)
    val ctor = clazz.getConstructor(classOf[File])
    ctor.newInstance(path).asInstanceOf[Directory]
  }

}
