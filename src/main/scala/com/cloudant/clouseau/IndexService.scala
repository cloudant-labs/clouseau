package com.cloudant.clouseau

import java.io.File
import org.apache.commons.configuration.HierarchicalConfiguration
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
import java.util.Collections

case class IndexServiceArgs(dbName: String, indexName: String, config: HierarchicalConfiguration)
class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('search, queryString: ByteBuffer, limit: Int) =>
      val query = queryParser.parse(Utils.toString(queryString), "default")

      // Refresh reader if needed.
      val newReader = IndexReader.openIfChanged(reader)
      if (newReader != null) {
        reader.decRef
        reader = newReader
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
      writer.commit(Collections.singletonMap("update_seq", Long.toString(pendingSeq)))
      committedSeq = pendingSeq
    case 'commit =>
      'ignored
    case _ =>
      'ignored
  }

  override def trapExit(from: Pid, msg: Any) {
    writer.close
  }

  private def toDoc(id: String, doc: List[Any]): Document = {
    val result = new Document
    result.add(new Field("_id", id, Store.YES, Index.NOT_ANALYZED))
    for (field <- doc) result add toFieldable(field)
    result
  }

  private def toFieldable(field: Any): Fieldable = field match {
    case (name: ByteBuffer, value: Int) =>
      new NumericField(Utils.toString(name)).setIntValue(value)
    case (name: ByteBuffer, value: Int, store: Symbol, index: Boolean) =>
      new NumericField(Utils.toString(name), toStore(store), index).setIntValue(value)
    case (name: ByteBuffer, value: Double) =>
      new NumericField(Utils.toString(name)).setDoubleValue(value)
    case (name: ByteBuffer, value: Double, store: Symbol, index: Boolean) =>
      new NumericField(Utils.toString(name), toStore(store), index).setDoubleValue(value)
    case (name: ByteBuffer, value: ByteBuffer) =>
      new Field(Utils.toString(name), Utils.toString(value), Store.NO, Index.ANALYZED)
    case (name: ByteBuffer, value: ByteBuffer, store: Symbol) =>
      new Field(Utils.toString(name), Utils.toString(value), toStore(store), Index.ANALYZED)
    case (name: ByteBuffer, value: ByteBuffer, store: Symbol, index: Symbol) =>
      new Field(Utils.toString(name), Utils.toString(value), toStore(store), toIndex(index))
    case (name: ByteBuffer, value: ByteBuffer, store: Symbol, index: Symbol, termVector: Symbol) =>
      new Field(Utils.toString(name), Utils.toString(value), toStore(store), toIndex(index), toTermVector(termVector))
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

  val logger = Logger.getLogger(ctx.args.dbName + ":" + ctx.args.indexName)
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
    case seq => java.lang.Long.parseLong(seq)
  }
  var pendingSeq = committedSeq

  sendEvery(self, 'commit, 10000)
  logger.info("opened at update_seq: " + committedSeq)
}