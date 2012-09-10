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
import org.apache.lucene.queryParser.QueryParser
import org.apache.lucene.queryParser.ParseException
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

case class DeferredQuery(minSeq : Long, pid : Pid, ref : Reference, queryArgs : List[(Symbol, Any)])

// These must match the records in dreyfus.
case class TopDocs(updateSeq : Long, totalHits : Long, hits : List[Hit])
case class Hit(score : Double, doc : Long, fields : List[Any])

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
    case SearchMsg(query : String, limit : Int, refresh : Boolean, after : Option[ScoreDoc]) =>
      search(query, limit, refresh, after)
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

  private def search(query : String, limit : Int, refresh : Boolean, after : Option[ScoreDoc]) : Any = {
    try {
      search(ctx.args.queryParser.parse(query), limit, refresh, after)
    } catch {
      case e : ParseException        =>
        logger.warn("Cannot parse %s".format(query))
        ('error, ('bad_request, "cannot parse query"))
      case e : NumberFormatException =>
        logger.warn("Cannot parse %s".format(query))
        ('error, ('bad_request, "cannot parse query"))
    }
  }

  private def search(query : Query, limit : Int, refresh : Boolean, after : Option[ScoreDoc]) : Any = {
    if (forceRefresh || refresh) {
      reopenIfChanged
    }

    reader.incRef
    try {
      val searcher = new IndexSearcher(reader)
      val topDocs = searchTimer.time {
        after match {
          case None =>
            searcher.search(query, limit)
          case Some(scoreDoc) =>
            searcher.searchAfter(scoreDoc, query, limit)
        }
      }
      logger.debug("search for '%s' limit=%d, refresh=%s had %d hits".format(query, limit, refresh, topDocs.totalHits))
      val hits = for (scoreDoc <- topDocs.scoreDocs) yield {
        val doc = searcher.doc(scoreDoc.doc)
        val fields = doc.getFields.foldLeft(Map[String,Any]())((acc,field) => {
          val value = field match {
            case numericField : NumericField =>
              numericField.getNumericValue
            case _ =>
              toBinary(field.stringValue)
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
        Hit(scoreDoc.score, scoreDoc.doc,
            fields.map {
              case(k,v:List[Any]) => (toBinary(k), v.reverse)
              case(k,v) => (toBinary(k), v)
            }.toList)
      }
      ('ok, TopDocs(updateSeq, topDocs.totalHits, hits.toList))
    } finally {
      reader.decRef
    }
  }

  private def reopenIfChanged() {
      val newReader = IndexReader.openIfChanged(reader)
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

  val version = Version.LUCENE_36

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
