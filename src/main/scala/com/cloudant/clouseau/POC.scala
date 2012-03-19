package com.cloudant.clouseau

import org.apache.log4j.Logger
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.search._
import org.apache.lucene.util.Version
import scalang._
import org.apache.lucene.queryParser.standard.StandardQueryParser
import java.nio.ByteBuffer
import java.nio.charset.Charset

class POCService(ctx: ServiceContext[NoArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('search, queryString: String, limit: Int) =>
      val query = queryParser.parse("default", queryString)

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
        val hits = for (doc <- topDocs.scoreDocs) yield (doc.doc, doc.score)
        List(('total, topDocs.totalHits), ('hits, hits.toList))
      } finally {
        reader.decRef
      }
    case ('update_doc, seq: Int, id: ByteBuffer, doc: Any) =>
      println(doc)
      writer.updateDocument(new Term("_id", decodeUtf8(id)), new Document)
      since = seq
      'ok
    case ('delete_doc, seq: Int, id: ByteBuffer) =>
      writer.deleteDocuments(new Term("_id", decodeUtf8(id)))
      since = seq
      'ok
    case 'since =>
      ('ok, since)
    case _ =>
      'error
  }

  private def decodeUtf8(buf: ByteBuffer): String = {
    val charset = Charset.forName("UTF-8")
    val decoder = charset.newDecoder();
    decoder.decode(buf).toString
  }

  val logger = Logger.getLogger("poc")
  val dir = new RAMDirectory
  val version = Version.LUCENE_35
  val analyzer = new StandardAnalyzer(version)
  val queryParser = new StandardQueryParser
  val config = new IndexWriterConfig(version, analyzer)
  val writer = new IndexWriter(dir, config)
  var reader = IndexReader.open(writer, true)
  var since = 0
}

object POC extends App {
  val node = Node("poc@127.0.0.1")
  node.spawnService[POCService, NoArgs]('poc, NoArgs)
}