package com.cloudant.clouseau

import java.io.File
import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.search.Query
import org.apache.lucene.util.Version
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.queryParser.ParseException
import scalang._
import scalang.node._
import java.nio.charset.Charset
import java.nio.ByteBuffer
import org.apache.lucene.document.Field.Index
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.Field.TermVector
import collection.JavaConversions._
import scala.collection.mutable._

case class IndexServiceArgs(dbName: String, index: List[(Symbol, Any)], config: Configuration)

case class DeferredQuery(minSeq: Long, pid: Pid, ref: Reference, queryArgs: List[(Symbol,Any)])

class IndexService(ctx: ServiceContext[IndexServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('search, changes: (Symbol, Symbol), minSeq: Long, queryArgs:List[(Symbol,Any)]) if minSeq <= pendingSeq =>
      search(queryArgs)
    case ('search, changes: (Symbol, Symbol), minSeq: Long, queryArgs:List[(Symbol,Any)]) =>
      waiters = DeferredQuery(minSeq, tag._1, tag._2, queryArgs) :: waiters
      targetSeq = minSeq
      changesSource = changes
      triggerUpdate
      'noreply
    case 'close =>
      exit('closed)
      'ok
  }

  override def handleInfo(msg: Any) = msg match {
    case ('update, seq: Long, doc: Document) if seq <= pendingSeq =>
      'ok
    case ('update, seq: Long, doc: Document) =>
      val id = doc.getFieldable("_id").stringValue
      writer.updateDocument(new Term("_id", id), doc)
      pendingSeq = seq
    case ('delete, seq: Long, _) if seq <= pendingSeq =>
      'ok
    case ('delete, seq: Long, id: String) =>
      writer.deleteDocuments(new Term("_id", id))
      pendingSeq = seq
    case 'commit if pendingSeq > committedSeq =>
      logger.info("committing updates from " + committedSeq + " to " + pendingSeq)
      writer.commit(Map("update_seq" -> pendingSeq.toString))
      committedSeq = pendingSeq
    case 'commit =>
      'ok
    case 'batch_end if targetSeq > pendingSeq =>
      triggerUpdate
    case 'batch_end =>
      val (ready, pending) = waiters.partition( _.minSeq <= pendingSeq)
      ready foreach(req => req.pid ! (req.ref, search(req.queryArgs)))
      waiters = pending
      'ok
  }

  override def exit(msg: Any) {
    writer.rollback
    logger.info("closed because of " + msg)
    super.exit(msg)
  }

  private def triggerUpdate {
    call(changesSource, ('get_changes, self, ctx.args.dbName.getBytes("UTF-8"), pendingSeq, 100))
  }

  private def search(queryArgs: List[(Symbol,Any)]): Any = {
    queryArgs find {e => e._1 == 'q} match {
      case None =>
        ('error, "no q parameter")
      case Some((_, q: String)) =>
        try {
          search(queryParser.parse(q), queryArgs)
        } catch {
          case e: ParseException => ('error, e.getMessage)
          case e: NumberFormatException => ('error, e.getMessage)
        }
    }
  }

  private def search(query: Query, queryArgs: List[(Symbol,Any)]): Any = {
      val stale = Utils.findOrElse(queryArgs, 'stale, 'false)
      val limit = Utils.findOrElse(queryArgs, 'limit, 25)

      val refresh: Boolean = stale match {
        case 'ok => false
        case 'update_after => false
        case _ => true
      }

      if (refresh) {
        val newReader = IndexReader.openIfChanged(reader)
        if (newReader != null) {
          reader.decRef
          reader = newReader
        }
      }

      reader.incRef
      try {
        val searcher = new IndexSearcher(reader)
        val topDocs = searcher.search(query, limit)
        logger.info("search (%s, limit %d, stale %s) => %d hits".format(query, limit, stale, topDocs.totalHits))
        val hits = for (scoreDoc <- topDocs.scoreDocs) yield {
          val doc = searcher.doc(scoreDoc.doc)
          val fields = for (field <- doc.getFields) yield {
            field match {
              case numericField: NumericField =>
                (field.name.getBytes("UTF-8"), numericField.getNumericValue)
              case _ =>
                (field.name.getBytes("UTF-8"), field.stringValue.getBytes("UTF-8"))
            }
          }
          (scoreDoc.score, fields.toList)
        }
        ('ok, (topDocs.totalHits, hits.toList))
      } finally {
        reader.decRef
      }
  }

  val name = "%s:%s".format(ctx.args.dbName, IndexService.getSignature(ctx.args.index))
  val logger = Logger.getLogger("clouseau." + name)
  val rootDir = ctx.args.config.getString("clouseau.dir", "target/indexes")
  val dir = new NIOFSDirectory(new File(new File(rootDir, ctx.args.dbName), IndexService.getSignature(ctx.args.index)))
  val version = Version.LUCENE_36
  val analyzer = IndexService.getAnalyzer(version, ctx.args.index)
  val queryParser = new ClouseauQueryParser(version, "default", analyzer)
  val config = new IndexWriterConfig(version, analyzer)
  val writer = new IndexWriter(dir, config)
  var reader = IndexReader.open(writer, true)
  var committedSeq: Long = IndexService.getUpdateSeq(reader)
  var pendingSeq: Long = committedSeq
  var targetSeq: Long = committedSeq
  var changesSource: (Symbol, Symbol) = null
  var waiters: List[DeferredQuery] = Nil

  sendEvery(self, 'commit, 10000)
  logger.info("opened at update_seq: " + committedSeq)
}

object IndexService {

  def getUpdateSeq(reader: IndexReader): Long = {
    reader.getCommitUserData().get("update_seq") match {
      case null => 0L
      case seq => seq.toLong
    }
  }

  def getSignature(index: List[(Symbol, Any)]): String = {
    Utils.findOrElse[String](index, 'sig, null)
  }

  def getDef(index: List[(Symbol, Any)]): String = {
    Utils.findOrElse[String](index, 'def, null)
  }

  def getAnalyzer(version: Version, index: List[(Symbol, Any)]): Analyzer = {
    val name = Utils.findOrElse[String](index, 'analyzer, "standard")
    Analyzers.getAnalyzer(version, name)
  }

  def start(node: Node, dbName: String, index: List[(Symbol, Any)], config: Configuration): Pid = {
     node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(dbName, index, config))
  }
}
