package com.cloudant.clouseau

import java.io.File

import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.log4j.Logger
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.util.Version

import scalang._

case class IndexServiceArgs(dbName: String, indexName: String, config: HierarchicalConfiguration)
class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('search, query: String) =>
      'ok
    case 'close =>
      exit('closed)
      'ok
    case _ =>
      // Remove if Scalang gets supervisors.
      ('error, msg)
  }

  override def handleCast(msg: Any) = msg match {
    case ('maybe_update, seq: Number) =>
      logger.info("Updating to " + seq)
      'ok
    case _ =>
      'ignored // Remove if Scalang gets supervisors.
  }

  override def handleInfo(msg: Any) {
    // Remove if Scalang gets supervisors.
  }

  override def trapExit(from: Pid, msg: Any) {
    writer.close
  }

  val logger = Logger.getLogger(ctx.args.dbName + ":" + ctx.args.indexName)
  val rootDir = ctx.args.config.getString("clouseau.dir", "target/indexes")
  val dir = new NIOFSDirectory(new File(rootDir))
  val version = Version.LUCENE_35
  val analyzer = new StandardAnalyzer(version)
  val config = new IndexWriterConfig(version, analyzer)
  val writer = new IndexWriter(dir, config)
}