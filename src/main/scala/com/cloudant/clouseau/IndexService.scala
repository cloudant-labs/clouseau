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
import java.nio.charset.Charset
import java.nio.ByteBuffer
import org.apache.lucene.document.Field.Index
import org.apache.lucene.document.Field.Store
import java.lang.Long
import collection.JavaConversions._
import scala.collection.mutable._

case class IndexServiceArgs(dbName: String, indexName: String, config: Configuration)
class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('search, queryString: ByteBuffer, options: List[(Symbol, Any)]) =>
      val query = queryParser.parse(Utils.toString(queryString), "default")

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

      val limit: Int = options find {e => e._1 == 'limit} match {
        case None => 25
        case Some(('limit, value: Int)) => value
      }

      reader.incRef
      try {
        val searcher = new IndexSearcher(reader)
        val topDocs = searcher.search(query, limit)
        val hits = for (doc <- topDocs.scoreDocs) yield (doc.doc, doc.score: Double)
        List(('total, topDocs.totalHits), ('hits, hits.toList))
      } finally {
        reader.decRef
      }
    case ('update_doc, seq: Int, id: ByteBuffer, doc: List[Any]) =>
      val idString = Utils.toString(id)
      writer.updateDocument(new Term("_id", idString), toDoc(idString, doc))
      pendingSeq = seq
      'ok
    case ('delete_doc, seq: Int, id: ByteBuffer) =>
      writer.deleteDocuments(new Term("_id", Utils.toString(id)))
      pendingSeq = seq
      'ok
    case 'since =>
      ('ok, pendingSeq)
    case 'close =>
      exit('closed)
      'ok
    case _ =>
      // Remove if Scalang gets supervisors.
      ('error, msg)
  }

  override def handleInfo(msg: Any): Unit = msg match {
    case 'commit if pendingSeq > committedSeq =>
      logger.info("committing updates from " + committedSeq + " to " + pendingSeq)
      writer.commit(Map("update_seq" -> Long.toString(pendingSeq)))
      committedSeq = pendingSeq
    case 'commit =>
      'ignored
    case _ =>
      'ignored
  }

  override def trapExit(from: Pid, msg: Any) {
    logger.info("Closing writer on trapExit")
    writer.close
  }

  private def toDoc(id: String, doc: List[Any]): Document = {
    val result = new Document
    result.add(new Field("_id", id, Store.YES, Index.NOT_ANALYZED))
    for (field <- doc) result add toFieldable(field)
    result
  }

  private def toFieldable(field: Any): Fieldable = field match {
    case (name: Any, value: Int) =>
      new NumericField(toName(name)).setIntValue(value)
    case (name: Any, value: Int, store: Symbol, index: Boolean) =>
      new NumericField(toName(name), toStore(store), index).setIntValue(value)
    case (name: Any, value: Double) =>
      new NumericField(toName(name)).setDoubleValue(value)
    case (name: Any, value: Double, store: Symbol, index: Boolean) =>
      new NumericField(toName(name), toStore(store), index).setDoubleValue(value)
    case (name: Any, value: Boolean) =>
      new Field(toName(name), value.toString, Store.NO, Index.NOT_ANALYZED)
    case (name: Any, value: Boolean, store: Symbol) =>
      new Field(toName(name), value.toString, toStore(store), Index.NOT_ANALYZED)
    case (name: Any, value: ByteBuffer) =>
      new Field(toName(name), Utils.toString(value), Store.NO, Index.ANALYZED)
    case (name: Any, value: ByteBuffer, store: Symbol) =>
      new Field(toName(name), Utils.toString(value), toStore(store), Index.ANALYZED)
    case (name: Any, value: ByteBuffer, store: Symbol, index: Symbol) =>
      new Field(toName(name), Utils.toString(value), toStore(store), toIndex(index))
    case (name: Any, value: ByteBuffer, store: Symbol, index: Symbol, termVector: Symbol) =>
      new Field(toName(name), Utils.toString(value), toStore(store), toIndex(index), toTermVector(termVector))
  }

  private def toName(name: Any) = name match {
    case name: ByteBuffer =>
      Utils.toString(name)
    case name: List[ByteBuffer] =>
      val builder = new StringBuilder
      for (part <- name) {
        builder.append(Utils.toString(part))
        builder.append(".")
      }
      builder.stripSuffix(".")
  }

  private def toStore(value: Symbol) = {
    Field.Store.valueOf(value.name toUpperCase)
  }

  private def toIndex(value: Symbol) = {
    Field.Index.valueOf(value.name toUpperCase)
  }

  private def toTermVector(value: Symbol) = {
    Field.TermVector.valueOf(value.name toUpperCase)
  }

  val logger = Logger.getLogger("clouseau." + ctx.args.dbName + ":" + ctx.args.indexName)
  val rootDir = ctx.args.config.getString("clouseau.dir", "target/indexes")
  val dir = new NIOFSDirectory(new File(new File(rootDir, ctx.args.dbName), ctx.args.indexName))
  val version = Version.LUCENE_35
  val analyzer = new StandardAnalyzer(version)
  val queryParser = new StandardQueryParser
  val config = new IndexWriterConfig(version, analyzer)
  val writer = new IndexWriter(dir, config)
  var reader = IndexReader.open(writer, true)
  var committedSeq = reader.getCommitUserData().get("update_seq") match {
    case null => 0
    case seq => Long.parseLong(seq)
  }
  var pendingSeq = committedSeq

  sendEvery(self, 'commit, 10000)
  logger.info("opened at update_seq: " + committedSeq)
}

object IndexService {
  def start(node: Node, dbName: String, indexName: String, config: Configuration): Pid = {
     node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(dbName, indexName, config))
  }
}
