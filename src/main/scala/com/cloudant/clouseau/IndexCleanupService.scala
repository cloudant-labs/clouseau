package com.cloudant.clouseau

import com.yammer.metrics.scala._
import java.io.File
import java.io.FilenameFilter
import java.util.regex.Pattern
import org.apache.log4j.Logger
import scalang._

class IndexCleanupService(ctx : ServiceContext[NoArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.cleanup")
  val rootDir = new File(Main.config.getString("clouseau.dir", "target/indexes"))

  override def handleCast(msg : Any) = msg match {
      case CleanupPathMsg(path : String) =>
      var dir = new File(rootDir, path)
      logger.info("Removing %s".format(dir))
      recursivelyDelete(dir)
    case CleanupDbMsg(dbName : String, activeSigs : List[String]) =>
      logger.info("Cleaning up " + dbName)
      val pattern = Pattern.compile("shards/[0-9a-f]+-[0-9a-f]+/" + dbName + "\\.[0-9]+/([0-9a-f]+)$")
      cleanup(rootDir, pattern, activeSigs)
  }

  override def exit(msg : Any) {
    logger.warn("Exiting with reason %s".format(msg))
    super.exit(msg)
    IndexCleanupService.start(node)
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

  private def recursivelyDelete(fileOrDir : File) {
    if (fileOrDir.isDirectory)
      for (file <- fileOrDir.listFiles)
        recursivelyDelete(file)
    if (fileOrDir.isFile)
      fileOrDir.delete
  }

}

object IndexCleanupService {

  def start(node : Node) : Pid = {
    node.spawnService[IndexCleanupService, NoArgs]('cleanup, NoArgs)
  }

}
