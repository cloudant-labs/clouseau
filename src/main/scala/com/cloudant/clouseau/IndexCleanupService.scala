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

import com.yammer.metrics.scala._
import java.io.File
import java.util.regex.Pattern
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone
import org.apache.log4j.Logger
import scalang._

class IndexCleanupService(ctx: ServiceContext[ConfigurationArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.cleanup")
  val rootDir = new File(ctx.args.config.getString("clouseau.dir", "target/indexes"))

  override def handleCast(msg: Any) = msg match {
    case CleanupPathMsg(path: String) =>
      val dir = new File(rootDir, path)
      logger.info("Removing %s".format(path))
      recursivelyDelete(dir)
    case RenamePathMsg(dbName: String) =>
      val srcDir = new File(rootDir, dbName)
      val sdf = new SimpleDateFormat("yyyyMMdd'.'HHmmss")
      sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
      val sdfNow = sdf.format(Calendar.getInstance().getTime())
      // move timestamp information in dbName to end of destination path
      // for example, from foo.1234567890 to foo.20170912.092828.deleted.1234567890
      val destPath = dbName.dropRight(10) + sdfNow + ".deleted." + dbName.takeRight(10)
      val destDir = new File(rootDir, destPath)
      logger.info("Renaming '%s' to '%s'".format(
        srcDir.getAbsolutePath, destDir.getAbsolutePath)
      )
      rename(srcDir, destDir)
    case CleanupDbMsg(dbName: String, activeSigs: List[String]) =>
      logger.info("Cleaning up " + dbName)
      val pattern = Pattern.compile("shards/[0-9a-f]+-[0-9a-f]+/" + dbName + "\\.[0-9]+/([0-9a-f]+)$")
      cleanup(rootDir, pattern, activeSigs)
  }

  private def cleanup(fileOrDir: File, includePattern: Pattern, activeSigs: List[String]) {
    if (!fileOrDir.isDirectory) {
      return
    }
    for (file <- fileOrDir.listFiles) {
      cleanup(file, includePattern, activeSigs)
    }
    val m = includePattern.matcher(fileOrDir.getAbsolutePath)
    if (m.find && !activeSigs.contains(m.group(1))) {
      logger.info("Removing unreachable index " + m.group)
      call('main, ('delete, m.group)) match {
        case 'ok =>
          'ok
        case ('error, 'not_found) =>
          recursivelyDelete(fileOrDir)
          fileOrDir.delete
      }
    }
  }

  private def recursivelyDelete(fileOrDir: File) {
    if (fileOrDir.isDirectory)
      for (file <- fileOrDir.listFiles)
        recursivelyDelete(file)
    if (fileOrDir.isFile)
      fileOrDir.delete
  }

  private def rename(srcDir: File, destDir: File) {
    if (!srcDir.isDirectory) {
      return
    }
    if (!srcDir.renameTo(destDir)) {
      logger.error("Failed to rename directory from '%s' to '%s'".format(
        srcDir.getAbsolutePath, destDir.getAbsolutePath))
    }
  }

}
