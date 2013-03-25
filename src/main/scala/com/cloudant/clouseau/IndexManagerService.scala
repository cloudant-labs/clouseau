/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import java.io.File
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.{Map => JMap}
import java.util.Map.Entry
import scala.collection.mutable.Map
import org.apache.log4j.Logger
import scalang._
import com.yammer.metrics.scala._
import scala.collection.JavaConversions._

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

    def isEmpty : Boolean = {
      pidToPath.isEmpty
    }

    def close() : Unit = {
      pidToPath foreach {
        kv => kv._1 ! ('close, 'closing)
      }
    }

  }

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = new File(Main.config.getString("clouseau.dir", "target/indexes"))
  val openTimer = metrics.timer("opens")
  val lru = new LRU()
  val waiters = Map[String, List[(Pid, Reference)]]()
  var closing = false

  override def handleCall(tag : (Pid, Reference), msg : Any) : Any = closing match {
    case true =>
      ('error, 'closing)
    case false =>
      handleCall1(tag, msg)
  }

  private def handleCall1(tag : (Pid, Reference), msg : Any) : Any = msg match {
    case OpenIndexMsg(peer : Pid, path : String, options : Any) =>
      lru.get(path) match {
        case null =>
          waiters.get(path) match {
            case None =>
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
              waiters.put(path, List(tag))
            case Some(list) =>
              waiters.put(path, (tag :: list))
          }
          'noreply
        case pid =>
          ('ok, pid)
      }
    case ('delete, path : String) =>
      lru.get(path) match {
        case null =>
          ('error, 'not_found)
        case pid : Pid =>
          pid ! 'delete
          'ok
      }
    case 'close =>
      logger.info("Closing down.")
      closing = true
      lru.close()
      self ! 'maybe_exit
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
    case 'maybe_exit =>
      maybeExit
  }

  override def trapMonitorExit(monitored : Any, ref : Reference, reason : Any) = monitored match {
    case pid : Pid =>
      lru.remove(pid)
      maybeExit
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

  private def maybeExit : Unit = {
    if (closing && lru.isEmpty) {
      logger.info("Closing on request")
      System.exit(0)
    }
  }

}
