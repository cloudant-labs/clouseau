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

import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.NumericUtils
import org.apache.log4j.Logger
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.text.SimpleDateFormat

object Utils {

  def doubleToTerm(field: String, value: Double): Term = {
    val bytesRef = new BytesRef
    val asLong = NumericUtils.doubleToSortableLong(value)
    NumericUtils.longToPrefixCoded(asLong, 0, bytesRef)
    new Term(field, bytesRef)
  }

  implicit def stringToBytesRef(string: String): BytesRef = {
    new BytesRef(string)
  }

  def rename(rootDir: File, dbName: String, sig: String) {
    val logger = Logger.getLogger("clouseau.utils")
    val srcParentDir = new File(rootDir, dbName)
    val sdf = new SimpleDateFormat("yyyyMMdd'.'HHmmss")
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
    val sdfNow = sdf.format(Calendar.getInstance().getTime())
    // move timestamp information in dbName to end of destination path
    // for example, from foo.1234567890 to foo.20170912.092828.deleted.1234567890
    val destParentPath = dbName.dropRight(10) + sdfNow + ".deleted." + dbName.takeRight(10)
    val destParentDir = new File(rootDir, destParentPath)
    logger.info("Renaming '%s' to '%s'".format(
      srcParentDir.getAbsolutePath, destParentDir.getAbsolutePath)
    )
    if (!srcParentDir.isDirectory) {
      return
    }
    if (!destParentDir.exists) {
      destParentDir.mkdirs
    }

    val srcDir = new File(srcParentDir, sig)
    val destDir = new File(destParentDir, sig)

    if (!srcDir.renameTo(destDir)) {
      logger.error("Failed to rename directory from '%s' to '%s'".format(
        srcDir.getAbsolutePath, destDir.getAbsolutePath))
    }
  }

}
