package com.cloudant.clouseau

import java.io.File
import scalang._
import scalang.node.Link
import org.apache.lucene.store._
import org.apache.lucene.index._
import org.apache.lucene.util.Version
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document._

case class IndexServerArgs(path: File)
class IndexServer(ctx: ServiceContext[IndexServerArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case 'close =>
      writer.close
      val (owner, _) = tag
      links.remove(Link(self, owner))
      exit("closing")
    case ('trigger_update, pid: Pid) =>
      // Pull stuff from pid of db?
    case ('update_document, term: (String, String), doc: Any) =>
      writer.updateDocument(toTerm(term), toDoc(doc))
      'ok
    case ('update_documents, term: (String, String), docs: List[Any]) =>
      'ok
    case ('delete_documents, term: (String, String)) =>
      'ok
    case ('search, query: String) =>
      'ok
    case _ =>
      // Remove if Scalang gets supervisors.
      ('error, msg)
  }

  override def handleCast(msg: Any) {
    // Remove if Scalang gets supervisors.
  }

  override def handleInfo(msg: Any) {
    // Remove if Scalang gets supervisors.
  }
  
  private def toTerm(term: (String, String)): Term = {
    val (field, text) = term
    new Term(field, text)
  }
  
  private def toDoc(doc: Any): Document = {
    null
  }

  val dir = new NIOFSDirectory(ctx.args.path)
  val version = Version.LUCENE_35
  val analyzer = new StandardAnalyzer(version)
  val config = new IndexWriterConfig(version, analyzer)
  val writer = new IndexWriter(dir, config)
}