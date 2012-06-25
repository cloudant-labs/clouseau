package com.cloudant.clouseau

import java.io.File
import java.io.FilenameFilter
import java.util.regex.Pattern
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import scalang._
import com.yammer.metrics.scala._

case class IndexManagerServiceArgs()

class IndexManagerService(ctx : ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = new File(Main.config.getString("clouseau.dir", "target/indexes"))
  val openTimer = metrics.timer("opens", instrumentedName)

  override def handleCall(tag : (Pid, Reference), msg : Any) : Any = msg match {
    case OpenIndexMsg(peer: Pid, path : String, options : Any) =>
      openTimer.time {
        IndexService.start(node, rootDir, path, options) match {
          case ('ok, pid : Pid) =>
            node.link(peer, pid)
            ('ok, pid)
          case error =>
            error
        }
      }
    case CleanupPathMsg(path : String) =>
      var dir = new File(rootDir, path)
      logger.info("Removing %s".format(dir))
      recursivelyDelete(dir)
      'ok
    case CleanupDbMsg(dbName : String, activeSigs : List[String]) =>
      logger.info("Cleaning up " + dbName)
      val pattern = Pattern.compile("shards/[0-9a-f]+-[0-9a-f]+/" + dbName + "\\.[0-9]+/([0-9a-f]+)$")
      cleanup(rootDir, pattern, activeSigs)
      'ok
  }

  override def exit(msg : Any) {
    logger.warn("Exiting with reason %s".format(msg))
    super.exit(msg)
    IndexManagerService.start(node)
  }

  private def recursivelyDelete(fileOrDir : File) {
    if (fileOrDir.isDirectory)
      for (file <- fileOrDir.listFiles)
        recursivelyDelete(file)
    if (fileOrDir.isFile)
      fileOrDir.delete
  }

  private def cleanup(fileOrDir : File, includePattern : Pattern, activeSigs : List[String]) {
    if (!fileOrDir.isDirectory) {
      return
    }
    for (file <- fileOrDir.listFiles) {
      cleanup(file, includePattern, activeSigs)
    }
    val m = includePattern.matcher(fileOrDir.getAbsolutePath)
    if (m.find && !activeSigs.contains(m.group(1))) {
      logger.info("Removing unreachable index " + fileOrDir)
      recursivelyDelete(fileOrDir)
      fileOrDir.delete
    }
  }

}

object IndexManagerService {
  def start(node : Node) : Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs())
  }
}
