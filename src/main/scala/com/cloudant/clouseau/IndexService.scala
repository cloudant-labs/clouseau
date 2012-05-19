package com.cloudant.clouseau

import java.io.File
import java.io.IOException
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.search.Query
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
import scala.collection.mutable._

case class IndexServiceArgs(name: String, queryParser: QueryParser, writer: IndexWriter)

case class DeferredQuery(minSeq: Long, pid: Pid, ref: Reference, queryArgs: List[(Symbol,Any)])

class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case SearchMsg(query: String, limit: Int, refresh: Boolean) =>
      search(query, limit, refresh)
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
      ctx.args.writer.commit(Map("update_seq" -> commitSeq.toString))
      updateSeq = commitSeq
      logger.info("Committed sequence %d".format(commitSeq))
      'ok
  }

  override def handleInfo(msg: Any) = msg match {
    case 'close =>
      exit(msg)
  }

  override def exit(msg: Any) {
    logger.info("Closed with reason %s".format(msg))
    try {
      ctx.args.writer.rollback
    } catch {
      case e: AlreadyClosedException => 'ignored
      case e: IOException => logger.warn("Error while closing writer", e)
    } finally {
      super.exit(msg)
    }
  }

  private def search(query: String, limit: Int, refresh: Boolean): Any = {
    try {
      search(ctx.args.queryParser.parse(query), limit, refresh)
    } catch {
      case e: ParseException => ('error, e.getMessage)
      case e: NumberFormatException => ('error, e.getMessage)
    }
  }

  private def search(query: Query, limit: Int, refresh: Boolean): Any = {
      if (refresh) {
        val newReader = IndexReader.openIfChanged(reader)
        if (newReader != null) {
          reader.decRef
          reader = newReader
        }
      }

      reader.incRef
      try {
        val searcher = new IndexSearcher(reader)
        val start = System.currentTimeMillis
        val topDocs = searcher.search(query, limit)
        val duration = System.currentTimeMillis - start
        logger.info("search for '%s' limit=%d, refresh=%s had %d hits in %d ms".format(query, limit, refresh, topDocs.totalHits, duration))
        val hits = for (scoreDoc <- topDocs.scoreDocs) yield {
          val doc = searcher.doc(scoreDoc.doc)
          val fields = for (field <- doc.getFields) yield {
            field match {
              case numericField: NumericField =>
                (toBinary(field.name), numericField.getNumericValue)
              case _ =>
                (toBinary(field.name), toBinary(field.stringValue))
            }
          }
          (List(scoreDoc.score, toBinary(doc.getFieldable("_id").stringValue)), fields.toList)
        }
        ('ok, topDocs.totalHits, hits.toList)
      } finally {
        reader.decRef
      }
  }

  private def toBinary(str: String): Array[Byte] = {
    str.getBytes("UTF-8")
  }

  override def toString(): String = {
    ctx.args.name
  }

  val logger = Logger.getLogger("clouseau.%s".format(ctx.args.name))
  var reader = IndexReader.open(ctx.args.writer, true)
  var updateSeq = reader.getCommitUserData().get("update_seq") match {
    case null => 0L
    case seq => seq.toLong
  }
}

object IndexService {

  val version = Version.LUCENE_36

  def start(node: Node, rootDir: String, path: String): Any = {
    val dir = new NIOFSDirectory(new File(rootDir, path))
    val analyzer = Analyzers.getAnalyzer(version, "standard")
    val queryParser = new ClouseauQueryParser(version, "default", analyzer)
    val config = new IndexWriterConfig(version, analyzer)
    try {
      val writer = new IndexWriter(dir, config)
      ('ok, node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(path, queryParser, writer)))
    } catch {
      case e: IOException => ('error, e.getMessage)
    }
  }

}
