package com.cloudant.clouseau

import java.io.File
import java.io.FilenameFilter
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import scalang._

case class IndexManagerServiceArgs()
class IndexManagerService(ctx : ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag : (Pid, Reference), msg : Any) : Any = msg match {
    case OpenIndexMsg(peer: Pid, path : String, analyzer : String) =>
      val start = System.currentTimeMillis
      IndexService.start(node, rootDir, path, analyzer) match {
        case ('ok, pid : Pid) =>
          node.link(peer, pid)
          val duration = System.currentTimeMillis - start
          logger.info("%s: opened in %d ms".format(path, duration))
          ('ok, pid)
        case error =>
          error
      }
    case CleanupPathMsg(path : String) =>
      var dir = new File(rootDir, path)
      logger.info("Removing %s".format(dir))
      delete(dir)
      'ok
    case CleanupDbMsg(dbName : String, activeSigs : List[String]) =>
      var filter = new FilenameFilter() {
        def accept(dir : File, name : String) : Boolean = {
          name.startsWith(dbName + ".")
        }
      }
      for (shard <- new File(rootDir, "shards").listFiles) {
        for (dir <- shard.listFiles(filter)) {
          for (sigDir <- dir.listFiles) {
            if (!activeSigs.contains(sigDir.getName)) {
              logger.info("Removing unreachable index %s".format(sigDir))
              delete(dir)
            }
          }
        }
      }
      'ok
  }

  override def exit(msg : Any) {
    logger.warn("Exiting with reason %s".format(msg))
    super.exit(msg)
    IndexManagerService.start(node)
  }

  private def delete(fileOrDir : File) {
    if (fileOrDir.isDirectory)
      for (file <- fileOrDir.listFiles)
        delete(file)
    if (fileOrDir.isFile)
      fileOrDir.delete
  }

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = Main.config.getString("clouseau.dir", "target/indexes")
}

object IndexManagerService {
  def start(node : Node) : Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs())
  }
}
