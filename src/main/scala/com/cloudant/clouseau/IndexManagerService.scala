package com.cloudant.clouseau

import java.io.File
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.{Map => JMap}
import java.util.Map.Entry
import org.apache.log4j.Logger
import scalang._
import scala.collection.mutable._
import com.yammer.metrics.scala._

class IndexManagerService(ctx : ServiceContext[NoArgs]) extends Service(ctx) with Instrumented {

  class LRU(initialCapacity : Int = 100, loadFactor : Float = 0.75f) {

    class InnerLRU(initialCapacity : Int, loadFactor: Float) extends LinkedHashMap[String, Pid](initialCapacity, loadFactor, true) {

      override def removeEldestEntry(eldest : Entry[String, Pid]) : Boolean = {
        val result = size() > Main.config.getInt("clouseau.max_indexes_open", 100)
        if (result) {
          eldest.getValue ! ('close, 'lru)
        }
        result
      }
    }

    val pathToPid : JMap[String, Pid] = new InnerLRU(initialCapacity, loadFactor)
    val pidToPath : JMap[Pid, String] = new HashMap(initialCapacity, loadFactor)

    def get(path : String) : Pid = {
      pathToPid.get(path)
    }

    def put(path: String, pid : Pid) {
      val prev = pathToPid.put(path, pid)
      pidToPath.remove(prev)
      pidToPath.put(pid, path)
    }

    def remove(pid : Pid) {
      val path = pidToPath.remove(pid)
      pathToPid.remove(path)
    }

  }

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = new File(Main.config.getString("clouseau.dir", "target/indexes"))
  val openTimer = metrics.timer("opens")
  val lru = new LRU()
  val waiters = Map[String, List[(Pid, Reference)]]()

  override def handleCall(tag : (Pid, Reference), msg : Any) : Any = msg match {
    case OpenIndexMsg(peer : Pid, path : String, options : Any) =>
      lru.get(path) match {
        case null =>
          waiters.get(path) match {
            case None =>
              waiters.put(path, List(tag))
            case Some(list) =>
              waiters.put(path, (tag :: list))
          }
          val manager = self
          node.spawn((_) => {
            openTimer.time {
              IndexService.start(node, rootDir, path, options) match {
                case ('ok, pid : Pid) =>
                  manager ! ('open_ok, path, peer, pid)
                case error =>
                  manager ! ('open_error, path, error)
              }
            }
          })
          'noreply
        case pid =>
          ('ok, pid)
      }
    case msg : CleanupPathMsg => // deprecated
      cast('cleanup, msg)
      'ok
    case msg : CleanupDbMsg => // deprecated
      cast('cleanup, msg)
      'ok
  }

  override def handleInfo(msg : Any) = msg match {
    case ('open_ok, path : String, peer : Pid, pid : Pid) =>
      lru.put(path, pid)
      monitor(pid)
      node.link(peer, pid)
      replyAll(path, ('ok, pid))
      'noreply
    case ('open_error, path : String, error : Any) =>
      replyAll(path, error)
      'noreply
  }

  override def exit(msg : Any) {
    logger.warn("Exiting with reason %s".format(msg))
    super.exit(msg)
    IndexManagerService.start(node)
  }

  override def trapMonitorExit(monitored : Any, ref : Reference, reason : Any) = monitored match {
    case pid : Pid =>
      lru.remove(pid)
    case _ =>
      'ignored
  }

  private def replyAll(path : String, msg : Any) {
    waiters.remove(path) match {
      case Some(list) =>
        for ((pid,ref) <- list) {
          pid ! (ref, msg)
        }
      case None =>
        'ok
    }
  }

}

object IndexManagerService {
  def start(node : Node) : Pid = {
    node.spawnService[IndexManagerService, NoArgs]('main, NoArgs)
  }
}
