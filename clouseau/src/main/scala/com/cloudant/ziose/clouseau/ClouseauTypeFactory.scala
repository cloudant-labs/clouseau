package com.cloudant.ziose.clouseau

import com.cloudant.ziose._
import core.Codec._
import scalang.{Adapter, Pid, TypeFactory}
import org.apache.lucene.document.Field.{Index, Store, TermVector}
import org.apache.lucene.document.{Document, Field, StringField, DoubleField, DoubleDocValuesField}
import org.apache.lucene.facet.params.FacetIndexingParams
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetFields
import org.apache.lucene.facet.taxonomy.CategoryPath

import scala.collection.JavaConverters._

class TermReader // we could just have a reference to mailbox here
// but we should not remove abstraction

sealed trait ClouseauMessage
case class CleanupDbMsg(dbName: String, activeSigs: List[String]) extends ClouseauMessage
case class CleanupPathMsg(path: String)                           extends ClouseauMessage
case class CloseLRUByPathMsg(path: String)                        extends ClouseauMessage
case class CommitMsg(seq: Long)                                   extends ClouseauMessage
case class DeleteDocMsg(id: String)                               extends ClouseauMessage
case class DiskSizeMsg(path: String)                              extends ClouseauMessage
case class Group1Msg(query: String, field: String, refresh: Boolean, groupSort: Any, groupOffset: Int, groupLimit: Int)
    extends ClouseauMessage
case class Group2Msg(options: Map[Symbol, Any])                extends ClouseauMessage
case class OpenIndexMsg(peer: Pid, path: String, options: Any) extends ClouseauMessage
case class RenamePathMsg(dbName: String)                       extends ClouseauMessage
case class SearchRequest(options: Map[Symbol, Any])            extends ClouseauMessage
case class SetPurgeSeqMsg(seq: Long)                           extends ClouseauMessage
case class SetUpdateSeqMsg(seq: Long)                          extends ClouseauMessage
case class UpdateDocMsg(id: String, doc: Document)             extends ClouseauMessage

object ClouseauTypeFactory extends TypeFactory {
  type T = ClouseauMessage
  val logger = LoggerFactory.getLogger("clouseau.tf")

  def parse(term: ETerm)(implicit adapter: Adapter[_, _]): Option[ClouseauMessage] = {
    term match {
      case ETuple(EAtom("cleanup"), dbName: EBinary, activeSigs: EList) =>
        val sigs = activeSigs.collect { case sig: EBinary => sig.asString }.toList
        Some(CleanupDbMsg(dbName.asString, sigs))
      case ETuple(EAtom("cleanup"), path: EBinary) =>
        Some(CleanupPathMsg(path.asString))
      case ETuple(EAtom("close_lru_by_path"), path: EBinary) =>
        Some(CloseLRUByPathMsg(path.asString))
      case ETuple(EAtom("commit"), ELong(seq)) =>
        Some(CommitMsg(seq.toLong))
      case ETuple(EAtom("delete"), id: EBinary) =>
        Some(DeleteDocMsg(id.asString))
      case ETuple(EAtom("disk_size"), path: EBinary) =>
        Some(DiskSizeMsg(path.asString))
      case ETuple(
            EAtom("group1"),
            query: EBinary,
            field: EBinary,
            EBoolean(refresh),
            groupSort,
            EInt(groupOffset),
            EInt(groupLimit)
          ) =>
        Some(Group1Msg(query.asString, field.asString, refresh, groupSort, groupOffset, groupLimit))
      case ETuple(EAtom("group2"), EMap(options)) =>
        Some(Group2Msg(options.foldLeft(Map.empty[Symbol, Any]) { case (map, (k, v)) =>
          map + (k.asInstanceOf[EAtom].atom -> toScala(v))
        }))
      case ETuple(EAtom("update"), id: EBinary, fields: EList) => { // TODO verify maybe it should be EBinary(id)
        var doc = new Document()
        doc.add(new StringField("_id", id.asString, Store.YES))
        for (fieldE <- fields) {
          val fieldS = toScala(fieldE)
          fieldS match {
            case (name: String, value: String, options: List[(String, Any) @unchecked]) =>
              val map = {
                options.collect { case t @ (key: String, value: Any) => t }.asInstanceOf[List[(String, Any)]].toMap
              }
              // case ETuple(List(EString(name), EString(value), EList(options))) =>
              // val map = options.collect { case t @ ETuple(List(EString(key), value: ETerm)) => (key, Codec.toScala(value)) }.asInstanceOf[List[(String, Any)]].toMap
              constructField(name, value, toStore(map), toIndex(map), toTermVector(map)) match {
                case Some(field: Field) =>
                  map.get("boost") match {
                    case Some(boost: Number) =>
                      field.setBoost(toFloat(1.2))
                      'ok
                    // make the match exhaustive
                    case Some(_) =>
                      'ok
                    case None =>
                      'ok
                  }
                  doc.add(field)
                  if (isFacet(map) && value.nonEmpty) {
                    val fp    = FacetIndexingParams.DEFAULT
                    val delim = fp.getFacetDelimChar
                    if (!name.contains(delim) && !value.contains(delim)) {
                      val facets = new SortedSetDocValuesFacetFields(fp)
                      facets.addFields(doc, List(new CategoryPath(name, value)).asJava)
                    }
                  }
                case None =>
                  'ok
              }
            case (name: String, value: Boolean, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.asInstanceOf[List[(String, Any)]].toMap
              constructField(name, value.toString, toStore(map), Index.NOT_ANALYZED, toTermVector(map)) match {
                case Some(field) =>
                  doc.add(field)
                case None =>
                  'ok
              }
            case (name: String, value: Double, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.asInstanceOf[List[(String, Any)]].toMap
              doc.add(new DoubleField(name, value, toStore(map)))
              if (isFacet(map)) {
                doc.add(new DoubleDocValuesField(name, value))
              }
            case (name: String, value: Integer, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.asInstanceOf[List[(String, Any)]].toMap
              val doubleValue = value.doubleValue
              doc.add(new DoubleField(name, doubleValue, toStore(map)))
              if (isFacet(map)) {
                doc.add(new DoubleDocValuesField(name, doubleValue))
              }
            case (name: String, value: Float, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.asInstanceOf[List[(String, Any)]].toMap
              val doubleValue = value.doubleValue
              doc.add(new DoubleField(name, doubleValue, toStore(map)))
              if (isFacet(map)) {
                doc.add(new DoubleDocValuesField(name, doubleValue))
              }
            case (name: String, value: Long, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.asInstanceOf[List[(String, Any)]].toMap
              val doubleValue = value.doubleValue
              doc.add(new DoubleField(name, doubleValue, toStore(map)))
              if (isFacet(map)) {
                doc.add(new DoubleDocValuesField(name, doubleValue))
              }
            case (name: String, value: BigInt, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.asInstanceOf[List[(String, Any)]].toMap
              val doubleValue = value.doubleValue
              doc.add(new DoubleField(name, doubleValue, toStore(map)))
              if (isFacet(map)) {
                doc.add(new DoubleDocValuesField(name, doubleValue))
              }
          }
        }
        val docId = doc.getField("_id").stringValue
        Some(UpdateDocMsg(docId, doc))
      }
      case ETuple(EAtom("open"), peer, path: EBinary, options) =>
        Some(OpenIndexMsg(peer.asInstanceOf[EPid], path.asString, options))
      case ETuple(EAtom("rename"), dbName: EBinary) =>
        Some(RenamePathMsg(dbName.asString))
      case ETuple(EAtom("search"), EMap(options)) =>
        Some(SearchRequest(options.foldLeft(Map.empty[Symbol, Any]) { case (map, (k, v)) =>
          map + (k.asInstanceOf[EAtom].atom -> toScala(v))
        }))
      case ETuple(EAtom("set_purge_seq"), ELong(seq)) =>
        Some(SetPurgeSeqMsg(seq.toLong))
      case ETuple(EAtom("set_update_seq"), ELong(seq)) =>
        Some(SetUpdateSeqMsg(seq.toLong))
      case other => None
    }
  }

  private def constructField(name: String, value: String, store: Store, index: Index, tv: TermVector)(implicit
    adapter: Adapter[_, _]
  ): Option[Field] = {
    try {
      Some(new Field(name, value, store, index, tv))
    } catch {
      case e: IllegalArgumentException =>
        logger.error("Failed to construct field '%s' with reason '%s'".format(name, e.getMessage), e)
        None
      case e: NullPointerException =>
        logger.error("Failed to construct field '%s' with reason '%s'".format(name, e.getMessage), e)
        None
    }
  }

  // These to* methods are stupid.

  def toFloat(a: Any): Float = a match {
    case v: java.lang.Double  => v.floatValue
    case v: java.lang.Float   => v
    case v: java.lang.Integer => v.floatValue
    case v: java.lang.Long    => v.floatValue
  }

  def toDouble(a: Any): Option[Double] = a match {
    case v: java.lang.Double  => Some(v)
    case v: java.lang.Float   => Some(v.doubleValue)
    case v: java.lang.Integer => Some(v.doubleValue)
    case v: java.lang.Long    => Some(v.doubleValue)
    case v: scala.math.BigInt => Some(v.doubleValue)
    case _                    => None
  }

  def toLong(a: Any): Long = a match {
    case v: java.lang.Integer => v.longValue
    case v: java.lang.Long    => v
  }

  def toInteger(a: Any): Integer = a match {
    case v: java.lang.Integer => v
    case v: java.lang.Long    => v.intValue
  }

  def toStore(options: Map[String, Any]): Store = {
    options.getOrElse("store", "no") match {
      case true  => Store.YES
      case false => Store.NO
      case str: String =>
        try {
          Store.valueOf(str.toUpperCase)
        } catch {
          case _: IllegalArgumentException =>
            Store.NO
        }
      case _ =>
        Store.NO
    }
  }

  def toIndex(options: Map[String, Any]): Index = {
    options.getOrElse("index", "analyzed") match {
      case true  => Index.ANALYZED
      case false => Index.NO
      case str: String =>
        try {
          Index.valueOf(str.toUpperCase)
        } catch {
          case _: IllegalArgumentException =>
            Index.ANALYZED
        }
      case _ =>
        Index.ANALYZED
    }
  }

  def toTermVector(options: Map[String, Any]): TermVector = {
    val termVector = options.getOrElse("termvector", "no").asInstanceOf[String]
    TermVector.valueOf(termVector.toUpperCase)
  }

  def isFacet(options: Map[String, Any]) = {
    options.get("facet") match {
      case Some(bool: Boolean) =>
        bool
      case _ =>
        false
    }
  }
}
