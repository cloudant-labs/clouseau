package com.cloudant.clouseau
import java.io.File

import scalang.{ Service, ServiceContext, Reference, Pid, NoArgs }

case class IndexManagerArgs(dir: File)
class IndexManager(ctx: ServiceContext[IndexManagerArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('start_link, path: String, options: List[Any]) =>
      val pid = node.spawnService[Index, IndexArgs](IndexArgs(new File(path)))
      node.link(pid, tag._1)
      ('ok, pid)
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