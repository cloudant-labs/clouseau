package com.cloudant.ziose.clouseau

import java.io.File
import java.io.IOException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.Files
import java.util.List
import java.util.Collection
import java.util.UUID
import org.apache.lucene.index.IndexCommit
import org.apache.lucene.index.IndexDeletionPolicy
import org.apache.lucene.store.FSDirectory
import scala.collection.JavaConverters._

class ExternalSnapshotDeletionPolicy(dir: FSDirectory) extends IndexDeletionPolicy {

  val originDir: File = dir.getDirectory.toFile
  var lastCommit: Option[IndexCommit] = None

  def snapshot(snapshotDir: File): Unit = {
    synchronized {
      lastCommit match {
        case None =>
          throw new IllegalStateException("No index commit to snapshot");
        case Some(commit) =>
          ExternalSnapshotDeletionPolicy.snapshot(originDir, snapshotDir, commit.getFileNames)
      }
    }
  }

  def onInit(commits: List[_ <: IndexCommit]): Unit = {
    keepOnlyLastCommit(commits)
  }

  def onCommit(commits: List[_ <: IndexCommit]): Unit = {
    keepOnlyLastCommit(commits)
  }

  private def keepOnlyLastCommit(commits: List[_ <: IndexCommit]): Unit = {
    synchronized {
      for (commit <- commits.asScala.reverse.drop(1)) {
        commit.delete
      }
      lastCommit = commits.asScala.lastOption
    }
  }

}

object ExternalSnapshotDeletionPolicy {

  def snapshot(originDir: File, snapshotDir: File, files: Collection[String]) = {
    if (!originDir.isAbsolute) {
      throw new IOException(originDir + " is not an absolute path")
    }
    if (!snapshotDir.isAbsolute) {
      throw new IOException(snapshotDir + " is not an absolute path")
    }
    if (!originDir.isDirectory) {
      throw new IOException(originDir + " is not a directory")
    }
    if (snapshotDir.exists) {
      throw new IllegalStateException("Snapshot directory already exists")
    }
    if (files == null) {
      throw new IOException("No files selected for snapshot")
    }
    /* Prepare the snapshot directory in a temporary location so we can atomically
     rename it into place at successful completion. */
    val tmpDir = new File(snapshotDir.getParentFile, UUID.randomUUID.toString)
    if (!tmpDir.mkdir) {
      throw new IOException("Failed to make temporary directory for snapshot")
    }
    var success = false
    try {
      for (filename <- files.asScala) {
        Files.createLink(new File(tmpDir, filename).toPath, new File(originDir, filename).toPath);
      }
      Files.move(tmpDir.toPath, snapshotDir.toPath, ATOMIC_MOVE)
      success = true
    } finally {
      // Try to clean up if unsuccessful
      if (!success) {
        for (file <- tmpDir.listFiles) {
          file.delete
        }
        tmpDir.delete
      }
    }
  }

}
