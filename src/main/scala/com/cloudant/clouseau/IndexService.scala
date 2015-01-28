package com.cloudant.clouseau

import java.io.{ File, IOException }

import com.yammer.metrics.scala.Instrumented
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import org.apache.lucene.index.{ IndexWriter, IndexWriterConfig }
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.SortField
import org.apache.lucene.store.{ Directory, LockFactory }
import org.apache.lucene.util.Version

import scalang._

case class IndexServiceArgs(config: Configuration, name: String, queryParser: QueryParser, writer: IndexWriter)

class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.%s".format(ctx.args.name))

  val writer = node.spawnService[IndexWriterService, IndexServiceArgs](ctx.args)
  link(writer)

  val reader = node.spawnService[IndexReaderService, IndexServiceArgs](ctx.args)
  link(reader)

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case _: SearchRequest =>
      call(reader, msg)
    case _: Group1Msg =>
      call(reader, msg)
    case _: Group2Msg =>
      call(reader, msg)
    case 'get_update_seq =>
      call(reader, msg)
    case _: UpdateDocMsg =>
      call(writer, msg)
    case _: DeleteDocMsg =>
      call(writer, msg)
    case _: CommitMsg => // deprecated
      call(writer, msg)
    case _: SetUpdateSeqMsg =>
      call(writer, msg)
    case 'info =>
      call(reader, msg)
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
  }

}

object IndexService {

  val version = Version.LUCENE_46
  val INVERSE_FIELD_SCORE = new SortField(null, SortField.Type.SCORE, true)
  val INVERSE_FIELD_DOC = new SortField(null, SortField.Type.DOC, true)
  val SORT_FIELD_RE = """^([-+])?([\.\w]+)(?:<(\w+)>)?$""".r
  val FP = """([-+]?[0-9]+(?:\.[0-9]+)?)"""
  val DISTANCE_RE = "^([-+])?<distance,([\\.\\w]+),([\\.\\w]+),%s,%s,(mi|km)>$".format(FP, FP).r

  def start(node: Node, config: Configuration, path: String, options: Any): Any = {
    val rootDir = new File(config.getString("clouseau.dir", "target/indexes"))
    val dir = newDirectory(config, new File(rootDir, path))
    try {
      SupportedAnalyzers.createAnalyzer(options) match {
        case Some(analyzer) =>
          val queryParser = new ClouseauQueryParser(version, "default", analyzer)
          val writerConfig = new IndexWriterConfig(version, analyzer)
          val writer = new IndexWriter(dir, writerConfig)
          ('ok, node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(config, path, queryParser, writer)))
        case None =>
          ('error, 'no_such_analyzer)
      }
    } catch {
      case e: IllegalArgumentException => ('error, e.getMessage)
      case e: IOException => ('error, e.getMessage)
    }
  }

  private def newDirectory(config: Configuration, path: File): Directory = {
    val lockClassName = config.getString("clouseau.lock_class",
      "org.apache.lucene.store.NativeFSLockFactory")
    val lockClass = Class.forName(lockClassName)
    val lockFactory = lockClass.newInstance().asInstanceOf[LockFactory]

    val dirClassName = config.getString("clouseau.dir_class",
      "org.apache.lucene.store.NIOFSDirectory")
    val dirClass = Class.forName(dirClassName)
    val dirCtor = dirClass.getConstructor(classOf[File], classOf[LockFactory])
    dirCtor.newInstance(path, lockFactory).asInstanceOf[Directory]
  }

}
