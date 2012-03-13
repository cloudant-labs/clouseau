package com.cloudant.clouseau
import java.io.File
import scalang.{ Service, ServiceContext, Reference, Pid, NoArgs }
import org.cliffc.high_scale_lib.NonBlockingHashMap

case class IndexManagerArgs(dir: File)
class IndexManager(ctx: ServiceContext[IndexManagerArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, indexName:String) =>
      val pid = node.spawnService[IndexServer, IndexServerArgs](args = IndexServerArgs(new File("")), reentrant = true)
      val (owner, _) = tag
      node.link(pid, owner)
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

  val indexes = new NonBlockingHashMap[(String, String), Pid]
}