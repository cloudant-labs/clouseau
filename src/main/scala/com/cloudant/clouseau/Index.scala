package com.cloudant.clouseau

import java.io.File
import scalang._
import scalang.node.Link

case class IndexArgs(path: File)
class Index(ctx: ServiceContext[IndexArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case 'close =>
      val (owner, _) = tag
      links.remove(Link(self, owner))
      exit("closing")
      'ok
    case ('update_document, term: (String, String), doc: Any) =>
      'ok
    case ('update_documents, term: (String, String), docs: Any) =>
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

}