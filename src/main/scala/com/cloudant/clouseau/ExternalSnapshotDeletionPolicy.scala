package com.cloudant.clouseau

import java.io.File
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.Files
import java.util.List
import java.util.UUID
import org.apache.lucene.index.IndexCommit
import org.apache.lucene.index.IndexDeletionPolicy
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy
import org.apache.lucene.store.FSDirectory
import scala.collection.JavaConversions._

class ExternalSnapshotDeletionPolicy(dir: FSDirectory) extends IndexDeletionPolicy {

  val originDir: File = dir.getDirectory
  var lastCommit: Option[IndexCommit] = None
  val delegate: IndexDeletionPolicy = new KeepOnlyLastCommitDeletionPolicy()

  def snapshot(snapshotDir: File): Unit = {
    synchronized {
      lastCommit match {
        case None =>
          throw new IllegalStateException("No index commit to snapshot");
        case Some(commit) =>
          if (snapshotDir.exists) {
            throw new IllegalStateException("Snapshot directory already exists")
          }
          /* Prepare the snapshot directory in a temporary location so we can atomically
          rename it into place at successful completion. */
          val tmpDir = new File(snapshotDir.getParentFile, UUID.randomUUID.toString)
          if (!tmpDir.mkdir) {
            throw new IllegalStateException("Failed to make temporary directory for snapshot")
          }
          var success = false
          try {
            for (filename <- commit.getFileNames) {
              Files.createLink(new File(tmpDir, filename).toPath, new File(originDir, filename).toPath);
            }
            Files.move(tmpDir.toPath, snapshotDir.toPath, ATOMIC_MOVE)
            success = true
          } finally {
            // Try to clean up
            if (!success) {
              for (file <- tmpDir.listFiles) {
                file.delete
              }
              tmpDir.delete
            }
          }
      }
    }
  }

  def onInit(commits: List[_ <: IndexCommit]): Unit = {
    delegate.onInit(commits)
  }

  def onCommit(commits: List[_ <: IndexCommit]): Unit = {
    delegate.onInit(commits)
  }

}
