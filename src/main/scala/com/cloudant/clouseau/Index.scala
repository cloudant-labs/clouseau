package com.cloudant.clouseau

import java.io.File
import scalang._
import scalang.node.Link

case class IndexArgs(path: File)
class Index(ctx: ServiceContext[IndexArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case 'close =>
      val (owner, _) = tag
      links.remove(Link(self, owner)) // unlink(owner) when it's fixed.
      exit("closing")
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