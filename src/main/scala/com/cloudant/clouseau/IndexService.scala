package com.cloudant.clouseau

import java.io.File
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.search.Query
import org.apache.lucene.util.Version
import org.apache.lucene.search.IndexSearcher
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

case class IndexServiceArgs(rootDir: String, path: String)

case class DeferredQuery(minSeq: Long, pid: Pid, ref: Reference, queryArgs: List[(Symbol,Any)])

class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('search, query: String, limit: Int, refresh: Boolean) =>
      search(query, limit, refresh)
    case 'get_update_seq =>
      ('ok, getUpdateSeq)
    case ('update, doc: Document) =>
      val id = doc.getFieldable("_id").stringValue
      writer.updateDocument(new Term("_id", id), doc)
      'ok
    case ('delete, id: String) =>
      writer.deleteDocuments(new Term("_id", id))
      'ok
    case ('commit, seq: Long) =>
      commit(seq)
      'ok
  }

  override def handleInfo(msg: Any) = msg match {
    case 'close =>
      exit('closed)
  }

  override def exit(msg: Any) {
    writer.rollback
    logger.info("closed because of " + msg)
    super.exit(msg)
  }

  private def search(query: String, limit: Int, refresh: Boolean): Any = {
    try {
      search(queryParser.parse(query), limit, refresh)
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
        val topDocs = searcher.search(query, limit)
        logger.info("query:%s, limit:%d, refresh:%s => %d hits".format(query, limit, refresh, topDocs.totalHits))
        val hits = for (scoreDoc <- topDocs.scoreDocs) yield {
          val doc = searcher.doc(scoreDoc.doc)
          val fields = for (field <- doc.getFields) yield {
            field match {
              case numericField: NumericField =>
                (field.name.getBytes("UTF-8"), numericField.getNumericValue)
              case _ =>
                (field.name.getBytes("UTF-8"), field.stringValue.getBytes("UTF-8"))
            }
          }
          (scoreDoc.score, fields.toList)
        }
        ('ok, (topDocs.totalHits, hits.toList))
      } finally {
        reader.decRef
      }
  }

  private def commit(seq: Long) {
    writer.commit(Map("update_seq" -> seq.toString))
  }

  private def getUpdateSeq(): Long = {
    reader.getCommitUserData().get("update_seq") match {
      case null => 0L
      case seq => seq.toLong
    }
  }

  val logger = Logger.getLogger(ctx.args.path)
  val dir = new NIOFSDirectory(new File(ctx.args.rootDir, ctx.args.path))
  val version = Version.LUCENE_36
  val defaultAnalyzer = Analyzers.getAnalyzer(version, "standard")
  val queryParser = new ClouseauQueryParser(version, "default", defaultAnalyzer)
  val config = new IndexWriterConfig(version, defaultAnalyzer)
  val writer = new IndexWriter(dir, config)
  var reader = IndexReader.open(writer, true)
}

object IndexService {

  def start(node: Node, rootDir: String, path: String): Pid = {
     node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(rootDir, path))
  }

}
