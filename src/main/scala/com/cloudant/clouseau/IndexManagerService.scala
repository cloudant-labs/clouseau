/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import java.io.File
import scala.collection.mutable.Map
import org.apache.log4j.Logger
import scalang._
import com.yammer.metrics.scala._

class IndexManagerService(ctx : ServiceContext[ConfigurationArgs]) extends Service(ctx) with Instrumented {

  class Indexes() {

    val pathToPid = Map[String, Pid]()
    val pidToPath = Map[Pid, String]()

    def get(path : String) : Option[Pid] = {
      pathToPid.get(path)
    }

    def put(path: String, pid : Pid) {
      pathToPid.put(path, pid) match {
        case Some(prev) =>
          pidToPath.remove(prev)
        case None =>
          'ok
      }
      pidToPath.put(pid, path)
    }

    def remove(pid : Pid) {
      pidToPath.remove(pid) match {
        case Some(path) =>
          pathToPid.remove(path)
        case None =>
          'ok
      }
    }

    def isEmpty : Boolean = {
      pidToPath.isEmpty
    }

    def close() {
      pidToPath foreach {
        kv => kv._1 ! ('close, 'closing)
      }
    }

    def size: Int = {
      pathToPid.size
    }

  }

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = new File(ctx.args.config.getString("clouseau.dir", "target/indexes"))
  val openTimer = metrics.timer("opens")
  val indexes = new Indexes()
  val openGauge = metrics.gauge("open_count") { indexes.size }
  val waiters = Map[String, List[(Pid, Reference)]]()
  var closing = false
  var closingTag: (Pid, Reference) = null

  override def handleCall(tag : (Pid, Reference), msg : Any) : Any = closing match {
    case true =>
      ('error, 'closing)
    case false =>
      handleCall1(tag, msg)
  }

  private def handleCall1(tag : (Pid, Reference), msg : Any) : Any = msg match {
    case OpenIndexMsg(peer : Pid, path : String, options : Any) =>
      indexes.get(path) match {
        case None =>
          waiters.get(path) match {
            case None =>
              val manager = self
              node.spawn((_) => {
                openTimer.time {
                  IndexService.start(node, ctx.args.config, path, options) match {
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
        case Some(pid) =>
          ('ok, pid)
      }
    case ('delete, path : String) =>
      indexes.get(path) match {
        case None =>
          ('error, 'not_found)
        case Some(pid) =>
          pid ! 'delete
          'ok
      }
    case 'close_indexes =>
      indexes.close()
      'ok
    case 'close =>
      closingTag = tag
      logger.info("Closing down.")
      closing = true
      indexes.close()
      self ! 'maybe_exit
      'noreply
  }

  override def handleInfo(msg : Any) = msg match {
    case ('open_ok, path : String, peer : Pid, pid : Pid) =>
      indexes.put(path, pid)
      monitor(pid)
      node.link(peer, pid)
      replyAll(path, ('ok, pid))
      'noreply
    case ('open_error, path : String, error : Any) =>
      replyAll(path, error)
      'noreply
    case 'maybe_exit =>
      maybeExit()
  }

  override def trapMonitorExit(monitored : Any, ref : Reference, reason : Any) = monitored match {
    case pid : Pid =>
      indexes.remove(pid)
      maybeExit()
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

  private def maybeExit() {
    if (closing && indexes.isEmpty) {
      logger.info("Closing on request")
      closingTag._1 ! (closingTag._2, 'ok)
      System.exit(0)
    }
  }

}
