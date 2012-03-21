package com.cloudant.clouseau

import java.nio.charset.Charset
import java.nio.ByteBuffer

import org.apache.log4j.Logger
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Field.Index
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.queryParser.standard.StandardQueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store._
import org.apache.lucene.util.Version

import scalang._

class POCService(ctx: ServiceContext[NoArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('search, queryString: String, limit: Int) =>
      val query = queryParser.parse(queryString, "default")

      // Refresh reader if needed.
      val newReader = IndexReader.openIfChanged(reader)
      if (newReader != null) {
        println("reopened")
        reader.decRef
        reader = newReader
      }

      reader.incRef
      try {
        val searcher = new IndexSearcher(reader)
        val topDocs = searcher.search(query, limit)
        println(query + " " + topDocs.totalHits)
        val hits = for (doc <- topDocs.scoreDocs) yield (doc.doc, doc.score: Double)
        List(('total, topDocs.totalHits), ('hits, hits.toList))
      } finally {
        reader.decRef
      }
    case ('update_doc, seq: Int, id: ByteBuffer, doc: List[Any]) =>
      val idString = toString(id)
      writer.updateDocument(new Term("_id", idString), toDoc(idString, doc))
      currentSeq = seq
      'ok
    case ('delete_doc, seq: Int, id: ByteBuffer) =>
      writer.deleteDocuments(new Term("_id", toString(id)))
      currentSeq = seq
      'ok
    case 'since =>
      ('ok, currentSeq)
    case _ =>
      println("error " + msg)
      'error
  }

  override def handleInfo(msg: Any): Unit = msg match {
    case 'commit if currentSeq > committedSeq =>
      log.info("committing updates from " + committedSeq + " to " + currentSeq)
      writer.commit
      committedSeq = currentSeq
    case 'commit =>
      'ignored
    case _ =>
      'ignored
  }

  private def toDoc(id: String, doc: List[Any]): Document = {
    val result = new Document
    result.add(new Field("_id", id, Store.YES, Index.NOT_ANALYZED))
    for (field <- doc) result add toFieldable(field)
    result
  }

  private def toFieldable(field: Any): Fieldable = field match {
    case (name: ByteBuffer, value: Int) =>
      new NumericField(toString(name)).setIntValue(value)
    case (name: ByteBuffer, value: Int, store: Symbol, index: Boolean) =>
      new NumericField(toString(name), toStore(store), index).setIntValue(value)
    case (name: ByteBuffer, value: Double) =>
      new NumericField(toString(name)).setDoubleValue(value)
    case (name: ByteBuffer, value: Double, store: Symbol, index: Boolean) =>
      new NumericField(toString(name), toStore(store), index).setDoubleValue(value)
    case (name: ByteBuffer, value: ByteBuffer) =>
      new Field(toString(name), toString(value), Store.NO, Index.ANALYZED)
    case (name: ByteBuffer, value: ByteBuffer, store: Symbol) =>
      new Field(toString(name), toString(value), toStore(store), Index.ANALYZED)
    case (name: ByteBuffer, value: ByteBuffer, store: Symbol, index: Symbol) =>
      new Field(toString(name), toString(value), toStore(store), toIndex(index))
    case (name: ByteBuffer, value: ByteBuffer, store: Symbol, index: Symbol, termVector: Symbol) =>
      new Field(toString(name), toString(value), toStore(store), toIndex(index), toTermVector(termVector))
  }

  private def toString(buf: ByteBuffer): String = {
    val charset = Charset.forName("UTF-8")
    val decoder = charset.newDecoder();
    decoder.decode(buf).toString
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

  val logger = Logger.getLogger("poc")
  val dir = new RAMDirectory
  val version = Version.LUCENE_35
  val analyzer = new StandardAnalyzer(version)
  val queryParser = new StandardQueryParser
  val config = new IndexWriterConfig(version, analyzer)
  val writer = new IndexWriter(dir, config)
  var reader = IndexReader.open(writer, true)
  var committedSeq = 0
  var currentSeq = 0

  sendEvery(self, 'commit, 10000)
}

object POC extends App {
  val node = Node("poc@127.0.0.1", "monster")
  node.spawnService[POCService, NoArgs]('poc, NoArgs)
}
