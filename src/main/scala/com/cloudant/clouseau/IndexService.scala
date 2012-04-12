package com.cloudant.clouseau

import java.io.File
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.queryParser.standard.StandardQueryParser
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.util.Version
import org.apache.lucene.search.IndexSearcher
import scalang._
import scalang.node._
import java.nio.charset.Charset
import java.nio.ByteBuffer
import org.apache.lucene.document.Field.Index
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.Field.TermVector
import collection.JavaConversions._
import scala.collection.mutable._

case class IndexServiceArgs(dbName: String, indexName: String, config: Configuration)
class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('_update_doc, seq: Int, _, _) if seq <= pendingSeq =>
      'ok
    case ('update_doc, seq: Int, doc: Document) =>
      val id = doc.getField("_id").stringValue()
      writer.updateDocument(new Term("_id", id), doc)
      pendingSeq = seq
      'ok
    case ('delete_doc, seq: Int, _) if seq <= pendingSeq =>
      'ok
    case ('delete_doc, seq: Int, id: String) =>
      writer.deleteDocuments(new Term("_id", id))
      pendingSeq = seq
      'ok
    case ('search, query: String, options: List[(Symbol, Any)]) =>
      search(query, options)
    case 'since =>
      ('ok, pendingSeq)
    case 'close =>
      exit('closed)
      'ok
  }

  override def handleInfo(msg: Any): Unit = msg match {
    case 'commit if pendingSeq > committedSeq =>
      logger.info("committing updates from " + committedSeq + " to " + pendingSeq)
      writer.commit(Map("update_seq" -> java.lang.Integer.toString(pendingSeq)))
      committedSeq = pendingSeq
    case 'commit =>
      'ignored
  }

  override def trapExit(from: Pid, msg: Any) {
    logger.info("closing")
    writer.close
    exit(msg)
  }

  private def search(query: String, options: List[(Symbol, Any)]) = {
      val parsedQuery = queryParser.parse(query, "default")

      val refresh: Boolean = options find {e => e._1 == 'stale} match {
        case None => true
        case Some(('stale, 'ok)) => false
      }

      if (refresh) {
        val newReader = IndexReader.openIfChanged(reader)
        if (newReader != null) {
          reader.decRef
          reader = newReader
        }
      }

      val limit: Int = Utils.findOrElse(options, 'limit, 25)

      reader.incRef
      try {
        val searcher = new IndexSearcher(reader)
        val topDocs = searcher.search(parsedQuery, limit)
        val hits = for (scoreDoc <- topDocs.scoreDocs) yield {
          val doc = searcher.doc(scoreDoc.doc)
          val fields = for (field <- doc.getFields) yield {
            field match {
              case numericField: NumericField =>
                (field.name, numericField.getNumericValue)
              case _ =>
                (field.name, field.stringValue)
            }
          }
          (scoreDoc.score, fields.toList)
        }
        ('ok, (topDocs.totalHits, hits.toList))
      } finally {
        reader.decRef
      }
  }

  val logger = Logger.getLogger("clouseau." + ctx.args.dbName + ":" + ctx.args.indexName)
  val rootDir = ctx.args.config.getString("clouseau.dir", "target/indexes")
  val dir = new NIOFSDirectory(new File(new File(rootDir, ctx.args.dbName), ctx.args.indexName))
  val version = Version.LUCENE_36
  val analyzer = new StandardAnalyzer(version)
  val queryParser = new StandardQueryParser
  val config = new IndexWriterConfig(version, analyzer)
  val writer = new IndexWriter(dir, config)
  var reader = IndexReader.open(writer, true)
  var committedSeq: Int = reader.getCommitUserData().get("update_seq") match {
    case null => 0
    case seq => java.lang.Integer.parseInt(seq)
  }
  var pendingSeq: Int = committedSeq

  sendEvery(self, 'commit, 10000)
  logger.info("opened at update_seq: " + committedSeq)
}

object IndexService {
  def start(node: Node, dbName: String, indexName: String, config: Configuration): Pid = {
     node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(dbName, indexName, config))
  }
}
