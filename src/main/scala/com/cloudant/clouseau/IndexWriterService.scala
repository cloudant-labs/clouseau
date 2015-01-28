/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import java.io.IOException

import com.yammer.metrics.scala._
import org.apache.log4j.Logger
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._

import scala.collection.JavaConversions._
import scalang.{ Pid, Reference, _ }

class IndexWriterService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.%s".format(ctx.args.name))
  var reader = DirectoryReader.open(ctx.args.writer, true)
  var updateSeq = getCommittedSeq
  var pendingSeq = updateSeq
  var committing = false
  var forceRefresh = false

  val updateTimer = metrics.timer("updates")
  val deleteTimer = metrics.timer("deletes")
  val commitTimer = metrics.timer("commits")

  // Start committer heartbeat
  val commitInterval = ctx.args.config.getInt("commit_interval_secs", 30)
  sendEvery(self, 'maybe_commit, commitInterval * 1000)

  logger.info("Opened at update_seq %d".format(updateSeq))

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case UpdateDocMsg(id: String, doc: Document) =>
      logger.debug("Updating %s".format(id))
      updateTimer.time {
        ctx.args.writer.updateDocument(new Term("_id", id), doc)
      }
      'ok
    case DeleteDocMsg(id: String) =>
      logger.debug("Deleting %s".format(id))
      deleteTimer.time {
        ctx.args.writer.deleteDocuments(new Term("_id", id))
      }
      'ok
    case CommitMsg(commitSeq: Long) => // deprecated
      pendingSeq = commitSeq
      logger.debug("Pending sequence is now %d".format(commitSeq))
      'ok
    case SetUpdateSeqMsg(newSeq: Long) =>
      pendingSeq = newSeq
      logger.debug("Pending sequence is now %d".format(newSeq))
      'ok
  }

  override def handleInfo(msg: Any) = msg match {
    case 'close =>
      exit(msg)
    case ('close, reason) =>
      exit(reason)
    case 'delete =>
      val dir = ctx.args.writer.getDirectory
      ctx.args.writer.close()
      for (name <- dir.listAll) {
        dir.deleteFile(name)
      }
      exit('deleted)
    case 'maybe_commit =>
      commit(pendingSeq)
    case ('committed, newSeq: Long) =>
      updateSeq = newSeq
      forceRefresh = true
      committing = false
      logger.info("Committed sequence %d".format(newSeq))
    case 'commit_failed =>
      committing = false
  }

  override def exit(msg: Any) {
    logger.info("Closed with reason: %.1000s".format(msg))
    try {
      reader.close()
    } catch {
      case e: IOException => logger.warn("Error while closing reader", e)
    }
    try {
      ctx.args.writer.rollback()
    } catch {
      case e: AlreadyClosedException => 'ignored
      case e: IOException => logger.warn("Error while closing writer", e)
    } finally {
      super.exit(msg)
    }
  }

  private def getCommittedSeq = {
    val commitData = ctx.args.writer.getCommitData
    commitData.get("update_seq") match {
      case null =>
        0L
      case seq =>
        seq.toLong
    }
  }

  private def commit(newSeq: Long) {
    if (!committing && newSeq > updateSeq) {
      committing = true
      val index = self
      node.spawn((_) => {
        ctx.args.writer.setCommitData(ctx.args.writer.getCommitData +
          ("update_seq" -> newSeq.toString))
        try {
          commitTimer.time {
            ctx.args.writer.commit()
          }
          index ! ('committed, newSeq)
        } catch {
          case e: AlreadyClosedException =>
            logger.error("Commit failed to closed writer", e)
            index ! 'commit_failed
          case e: IOException =>
            logger.error("Failed to commit changes", e)
            index ! 'commit_failed
        }
      })
    }
  }

  override def toString: String = {
    ctx.args.name
  }

}
