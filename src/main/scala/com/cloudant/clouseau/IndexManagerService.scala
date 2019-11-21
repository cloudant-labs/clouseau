// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.clouseau

import java.io.File
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.{ Map => JMap }
import java.util.Map.Entry
import scala.collection.mutable.Map
import org.apache.log4j.Logger
import scalang._
import com.yammer.metrics.scala._
import scala.collection.JavaConversions._

class IndexManagerService(ctx: ServiceContext[ConfigurationArgs]) extends Service(ctx) with Instrumented {

  class LRU(initialCapacity: Int = 100, loadFactor: Float = 0.75f) {

    class InnerLRU(initialCapacity: Int, loadFactor: Float) extends LinkedHashMap[String, Pid](initialCapacity, loadFactor, true) {

      override def removeEldestEntry(eldest: Entry[String, Pid]): Boolean = {
        val result = size() > ctx.args.config.getInt("clouseau.max_indexes_open", 100)
        if (result) {
          eldest.getValue ! ('close, 'lru)
        }
        result
      }
    }

    val lruMisses = metrics.counter("lru.misses")
    val lruEvictions = metrics.counter("lru.evictions")

    val pathToPid: JMap[String, Pid] = new InnerLRU(initialCapacity, loadFactor)
    val pidToPath: JMap[Pid, String] = new HashMap(initialCapacity, loadFactor)

    def get(path: String): Pid = {
      val pid: Pid = pathToPid.get(path)
      if (!Option(pid).isDefined) {
        lruMisses += 1
      }
      pid
    }

    def put(path: String, pid: Pid) {
      val prev = pathToPid.put(path, pid)
      pidToPath.remove(prev)
      pidToPath.put(pid, path)
    }

    def remove(pid: Pid) {
      val path = pidToPath.remove(pid)
      pathToPid.remove(path)
      if (Option(path).isDefined) {
        lruEvictions += 1
      }
    }

    def isEmpty: Boolean = {
      pidToPath.isEmpty
    }

    def close() {
      pidToPath foreach {
        kv => kv._1 ! ('close, 'closing)
      }
    }

    def closeByPath(path: String) {
      pidToPath foreach {
        kv =>
          if (kv._2.startsWith(path)) {
            logger.info("closing lru for " + path)
            kv._1 ! ('close, 'closing)
          }
      }
    }
  }

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = new File(ctx.args.config.getString("clouseau.dir", "target/indexes"))
  val openTimer = metrics.timer("opens")
  val lru = new LRU()
  val waiters = Map[String, List[(Pid, Reference)]]()

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case OpenIndexMsg(peer: Pid, path: String, options: Any) =>
      openIndex(tag, peer, path, options)
    case ('get_root_dir) =>
      ('ok, rootDir.getAbsolutePath())
    case ('delete, path: String) =>
      lru.get(path) match {
        case null =>
          ('error, 'not_found)
        case pid: Pid =>
          pid ! 'delete
          'ok
      }
    case DiskSizeMsg(path: String) =>
      getDiskSize(path)
    case 'close_lru =>
      lru.close()
      'ok
    case CloseLRUByPathMsg(path: String) =>
      lru.closeByPath(path)
      'ok
    case SoftDeleteMsg(path: String) =>
      openIndex(tag, node.spawnMbox.self, path, "standard") match {
        case ('ok, pid: Pid) =>
          pid ! 'soft_delete
          'ok
        case (error: Any) =>
          'error
      }
    case 'version =>
      ('ok, getClass.getPackage.getImplementationVersion)
  }

  override def handleInfo(msg: Any) = msg match {
    case ('open_ok, path: String, peer: Pid, pid: Pid) =>
      lru.put(path, pid)
      monitor(pid)
      node.link(peer, pid)
      replyAll(path, ('ok, pid))
      'noreply
    case ('open_error, path: String, error: Any) =>
      replyAll(path, error)
      'noreply
    case ('touch_lru, path: String) =>
      lru.get(path)
      'noreply
  }

  override def trapMonitorExit(monitored: Any, ref: Reference, reason: Any) = monitored match {
    case pid: Pid =>
      lru.remove(pid)
    case _ =>
      'ignored
  }

  private def openIndex(tag: (Pid, Reference), peer: Pid, path: String, options: Any) = {
    lru.get(path) match {
      case null =>
        waiters.get(path) match {
          case None =>
            val manager = self
            node.spawn((_) => {
              openTimer.time {
                IndexService.start(node, ctx.args.config, path, options) match {
                  case ('ok, pid: Pid) =>
                    manager ! ('open_ok, path, peer, pid)
                  case error =>
                    manager ! ('open_error, path, error)
                }
              }
            })
            waiters.put(path, List(tag))
          case Some(list) =>
            waiters.put(path, tag :: list)
        }
        'noreply
      case pid =>
        ('ok, pid)
    }
  }

  private def getDiskSize(path: String) = {
    val indexDir = new File(rootDir, path)
    val files = indexDir.list()
    if (files != null) {
      val size = files.foldLeft(0L)((acc, fileName) =>
        acc + (new File(indexDir, fileName)).length())
      ('ok, List(('disk_size, size)))
    } else {
      ('ok, List(('disk_size, 0)))
    }
  }

  private def replyAll(path: String, msg: Any) {
    waiters.remove(path) match {
      case Some(list) =>
        for ((pid, ref) <- list) {
          pid ! (ref, msg)
        }
      case None =>
        'ok
    }
  }

}
