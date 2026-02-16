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

package com.cloudant.ziose.clouseau

import java.io.File
import java.io.IOException
import java.time.{ Duration, Instant }
import java.time.temporal.ChronoUnit
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.{ Map => JMap }
import scala.collection.mutable.Map
import _root_.com.cloudant.ziose.scalang

import scalang._

import scala.collection.JavaConverters._
import java.util.HashSet
import com.cloudant.ziose.core.ProcessContext
import com.cloudant.ziose.core.Codec
import zio.ZIO

class IndexManagerService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) with Instrumented {

  class LRU(initialCapacity: Int = 100, loadFactor: Float = 0.75f, trackIndexAccesses: Boolean = false) {

    class InnerLRU(initialCapacity: Int, loadFactor: Float) extends LinkedHashMap[String, Pid](initialCapacity, loadFactor, true)

    val capacity = ctx.args.config.getInt("clouseau.max_indexes_open", 100)
    val lruMisses = metrics.counter("lru.misses")
    val lruEvictions = metrics.counter("lru.evictions")

    val pathToPid: JMap[String, Pid] = new InnerLRU(initialCapacity, loadFactor)
    val pidToPath: JMap[Pid, String] = new HashMap(initialCapacity, loadFactor)
    val indexesSeen: Option[JMap[String, Long]] =
      if (trackIndexAccesses) Some(new ConcurrentHashMap(initialCapacity, loadFactor))
      else None

    def get(path: String): Pid = {
      assert(pathToPid.size == pidToPath.size)
      val pid: Pid = pathToPid.get(path)
      if (!Option(pid).isDefined) {
        lruMisses += 1
      }
      pid
    }

    def put(path: String, pid: Pid) = {
      assert(pathToPid.size == pidToPath.size)
      enforceCapacity
      val prev = pathToPid.put(path, pid)
      pidToPath.remove(prev)
      pidToPath.put(pid, path)
      trackIndexesSeen(path)
    }

    def remove(pid: Pid) = {
      assert(pathToPid.size == pidToPath.size)
      val path = pidToPath.remove(pid)
      pathToPid.remove(path)
      if (Option(path).isDefined) {
        lruEvictions += 1
      }
    }

    def isEmpty: Boolean = {
      pidToPath.isEmpty
    }

    def close() = {
      pidToPath.asScala foreach {
        kv => kv._1 ! ('close, 'closing)
      }
    }

    def closeByPath(path: String) = {
      pidToPath.asScala foreach {
        kv =>
          if (kv._2.startsWith(path)) {
            logger.info("closing lru for " + path)
            kv._1 ! ('close, 'closing)
          }
      }
    }

    private val trackIndexesSeenRange = Duration.ofDays(7).toSeconds

    private def trackIndexesSeen(path: String) = {
      indexesSeen.map({ store =>
        val cutOff = now - trackIndexesSeenRange
        store.asScala.retain { case (_, timestamp) => timestamp >= cutOff }
        store.put(path, now)
      })
    }

    def numberOfIndexesSeenRecently(duration: Long) = indexesSeen match {
      case Some(store) =>
        val reference = now - duration
        store.asScala.count { case (_, timestamp) => timestamp >= reference }
      case None => 0
    }

    private def enforceCapacity() {
      var excess = pathToPid.size - capacity
      if (excess > 0) {
        val it = pathToPid.entrySet.iterator
        while (excess > 0 && it.hasNext) {
          val eldest = it.next
          eldest.getValue ! ('close, 'lru)
          excess -= 1
        }
      }
    }

    private def now: Long = ChronoUnit.SECONDS.between(Instant.EPOCH, Instant.now)

  }

  val logger = LoggerFactory.getLogger("clouseau.main")
  val rootDir = new File(ctx.args.config.getString("clouseau.dir", "target/indexes"))
  val openTimer = metrics.timer("opens")
  val trackIndexATimes = ctx.args.config.getBoolean("clouseau.track_index_atimes", false)
  val lru = new LRU(trackIndexAccesses = trackIndexATimes)
  val waiters = Map[String, List[(Pid, Any)]]()
  val countLocksEnabled = ctx.args.config.getBoolean("clouseau.count_locks", false)
  if (countLocksEnabled) {
    val lockClass = Class.forName("org.apache.lucene.store.NativeFSLock")
    val field = lockClass.getDeclaredField("LOCK_HELD")
    field.setAccessible(true)
    val LOCK_HELD = field.get(null).asInstanceOf[HashSet[String]]
    metrics.gauge("NativeFSLock.count")(getNativeFSLockHeldSize(LOCK_HELD.asScala))
  }

  if (trackIndexATimes) {
    val indexRecencyTimes = List(
      ("1m", Duration.ofMinutes(1)),
      ("1h", Duration.ofHours(1)),
      ("1d", Duration.ofDays(1)),
      ("1w", Duration.ofDays(7))
    )

    for ((label, time) <- indexRecencyTimes) {
      metrics.gauge(s"indexes.seen.${label}")(lru.numberOfIndexesSeenRecently(time.toSeconds))
    }
  }

  def getNativeFSLockHeldSize(lockHeld: scala.collection.mutable.Set[String]) = lockHeld.synchronized {
    lockHeld.size
  }

  override def handleInit(): Unit = {
    logger.debug(s"handleInit(capacity = ${adapter.capacity})")
  }

  override def onTermination[PContext <: ProcessContext](reason: Codec.ETerm, ctx: PContext) = {
    ZIO.logTrace("onTermination")
  }

  override def handleCall(tag: (Pid, Any), msg: Any): Any = msg match {
    case OpenIndexMsg(peer: Pid, path: String, options: AnalyzerOptions) =>
      lru.get(path) match {
        case null =>
          waiters.get(path) match {
            case None =>
              val manager = self
              node.spawn(_ => {
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
    case ('get_root_dir) =>
      ('ok, rootDir.getAbsolutePath())
    case DeleteDocMsg(path: String) =>
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
    case 'version =>
      ('ok, getClass.getPackage.getImplementationVersion)
    case ('create_snapshot, indexName: String, snapshotDir: String) =>
      lru.get(indexName) match {
        case null =>
          createSnapshot(indexName, snapshotDir)
        case pid: Pid =>
          call(pid, ('create_snapshot, snapshotDir))
      }
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

  private def createSnapshot(indexName: String, snapshotDir: String): Any = {
    val originDir = new File(rootDir, indexName)
    // As the index is closed, we snapshot every file.
    val files = originDir.list
    try {
      ExternalSnapshotDeletionPolicy.snapshot(originDir, new File(snapshotDir), files.toSeq.asJavaCollection)
      'ok
    } catch {
      case e: IllegalStateException =>
        ('error, e.getMessage)
      case e: IOException =>
        ('error, e.getMessage)
    }
  }

  private def replyAll(path: String, msg: Any) = {
    waiters.remove(path) match {
      case Some(list) =>
        for (tag <- list) {
          Service.reply(tag, msg)
        }
      case None =>
        'ok
    }
  }

}
