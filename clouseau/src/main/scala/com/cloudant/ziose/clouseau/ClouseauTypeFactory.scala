package com.cloudant.ziose.clouseau

import com.cloudant.ziose._
import core.Codec._
import scalang.{Adapter, Pid, TypeFactory, Reference}
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.{Document, StringField, TextField, DoubleDocValuesField, SortedDocValuesField}

import org.apache.lucene.util.BytesRef
import org.apache.lucene.document.DoublePoint
import org.apache.lucene.document.StoredField
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField
import org.apache.lucene.facet.FacetsConfig
import org.apache.lucene.index.IndexableField
import scala.collection.mutable.{Map => SMap}

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
  val facetsConfig = new FacetsConfig

  /*
   * The parse function try to match the events for which we have dedicated ClouseauMessages.
   * If there is no match, it returns None, so we can continue matching elsewhere.
   */
  def parse(term: ETerm)(implicit adapter: Adapter[_, _]): Option[ClouseauMessage] = {
    term match {
      case ETuple(EAtom("cleanup"), dbName: EBinary, activeSigs: EList) =>
        val sigs = activeSigs.collect { case sig: EBinary => sig.asString }.toList
        Some(CleanupDbMsg(dbName.asString, sigs))
      case ETuple(EAtom("cleanup"), path: EBinary) =>
        Some(CleanupPathMsg(path.asString))
      case ETuple(EAtom("close_lru_by_path"), path: EBinary) =>
        Some(CloseLRUByPathMsg(path.asString))
      case ETuple(EAtom("commit"), seq: ENumber) =>
        seq.toLong.map(CommitMsg)
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
            groupOffset: ENumber,
            groupLimit: ENumber
          ) => {
        (groupOffset.toInt, groupLimit.toInt) match {
          case (Some(groupOffset), Some(groupLimit)) => {
            Some(
              Group1Msg(query.asString, field.asString, refresh, adapter.toScala(groupSort), groupOffset, groupLimit)
            )
          }
          case _ => None
        }
      }
      case ETuple(EAtom("group2"), options: EList) => {
        Some(Group2Msg(options.map(adapter.toScala).asInstanceOf[List[(Symbol, Any)]].toMap))
      }
      case ETuple(EAtom("update"), id: EBinary, fields: EList) => { // TODO verify maybe it should be EBinary(id)
        val dvs = SMap[Tuple2[String,Symbol], IndexableField]()
        var doc = new Document()
        doc.add(new StringField("_id", id.asString, Store.YES))
        doc.add(new SortedDocValuesField("_id", new BytesRef(id.asString)))
        for (fieldE <- fields) {
          val fieldS = adapter.toScala(fieldE)
          fieldS match {
            case (name: String, value: String, options: List[(String, Any) @unchecked]) =>
              if (value.nonEmpty) {
                val map = options.collect { case t @ (key: String, value: Any) => t }.toMap
                if (toIndex(map)) {
                  doc.add(new TextField(name, value, toStore(map)))
                } else {
                  doc.add(new StringField(name, value, toStore(map)))
                }
                dvs.put((name, 'sdv), new SortedDocValuesField(name, new BytesRef(value)))
                dvs.put((name, 'ssdvf), new SortedSetDocValuesFacetField(name, value))
              }
            case (name: String, value: Boolean, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.toMap
              doc.add(new StringField(name, value.toString, toStore(map)))
              dvs.put((name, 'sdv), new SortedDocValuesField(name, new BytesRef(value.toString)))
            case (name: String, value: Double, options: List[(String, Any) @unchecked]) =>
              val map = options.collect { case t @ (_: String, _: Any) => t }.toMap
              doc.add(new DoublePoint(name, value))
              dvs.put((name, 'ddv), new DoubleDocValuesField(name, value))
              if (toStore(map) == Store.YES) {
                doc.add(new StoredField(name, value))
              }
            case (name: String, value: Integer, options: List[(String, Any) @unchecked]) =>
              val map         = options.collect { case t @ (_: String, _: Any) => t }.toMap
              val doubleValue = value.doubleValue
              doc.add(new DoublePoint(name, doubleValue))
              dvs.put((name, 'ddv), new DoubleDocValuesField(name, doubleValue))
              if (toStore(map) == Store.YES) {
                doc.add(new StoredField(name, doubleValue))
              }
            case (name: String, value: Float, options: List[(String, Any) @unchecked]) =>
              val map         = options.collect { case t @ (_: String, _: Any) => t }.toMap
              val doubleValue = value.doubleValue
              doc.add(new DoublePoint(name, doubleValue))
              dvs.put((name, 'ddv), new DoubleDocValuesField(name, doubleValue))
              if (toStore(map) == Store.YES) {
                doc.add(new StoredField(name, doubleValue))
              }
            case (name: String, value: Long, options: List[(String, Any) @unchecked]) =>
              val map         = options.collect { case t @ (_: String, _: Any) => t }.toMap
              val doubleValue = value.doubleValue
              doc.add(new DoublePoint(name, doubleValue))
              dvs.put((name, 'ddv), new DoubleDocValuesField(name, doubleValue))
              if (toStore(map) == Store.YES) {
                doc.add(new StoredField(name, doubleValue))
              }
            case (name: String, value: BigInt, options: List[(String, Any) @unchecked]) =>
              val map         = options.collect { case t @ (_: String, _: Any) => t }.toMap
              val doubleValue = value.doubleValue
              doc.add(new DoublePoint(name, doubleValue))
              dvs.put((name, 'ddv), new DoubleDocValuesField(name, doubleValue))
              if (toStore(map) == Store.YES) {
                doc.add(new StoredField(name, doubleValue))
              }
          }
        }
        // Insert _last_ indexed value for DV's for backward compatibility
        for (f <- dvs.values) {
          doc.add(f)
        }
        Some(UpdateDocMsg(id.asString, facetsConfig.build(doc)))
      }
      case ETuple(EAtom("open"), peer, path: EBinary, options) =>
        AnalyzerOptions
          .from(adapter.toScala(options))
          .flatMap(options => Some(OpenIndexMsg(peer.asInstanceOf[EPid], path.asString, options)))
      case ETuple(EAtom("rename"), dbName: EBinary) =>
        Some(RenamePathMsg(dbName.asString))
      case ETuple(EAtom("search"), options: EList) =>
        Some(SearchRequest(options.map(adapter.toScala).asInstanceOf[List[(Symbol, Any)]].toMap))
      case ETuple(EAtom("set_purge_seq"), seq: ENumber) =>
        seq.toLong.map(SetPurgeSeqMsg)
      case ETuple(EAtom("set_update_seq"), seq: ENumber) =>
        seq.toLong.map(SetUpdateSeqMsg)
      // most of the messages would be matching here so we can handle them elsewhere
      case other => None
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

  def toIndex(options: Map[String, Any]): Boolean = {
    options.getOrElse("index", "analyzed") match {
      case true => true
      case false => false
      case str: String =>
        str.toUpperCase match {
          case "ANALYZED" => true
          case "ANALYZED_NO_NORMS" => true
          case "NO" => false
          case "NOT_ANALYZED" => false
          case "NOT_ANALYZED_NO_NORMS" => false
          case _ => true
        }
      case _ => true
    }
  }

  def toScala(term: ETerm): Option[Any] = {
    term match {
      // case tuple: ETuple => Some(toScala(tuple))
      case pid: EPid     => Some(Pid.toScala(pid))
      case ref: ERef     => Some(Reference.toScala(ref))
      case byte: ENumber => byte.toInt
      case ETuple(EAtom("committed"), newUpdateSeq: ENumber, newPurgeSeq: ENumber) => {
        (newUpdateSeq.toLong, newPurgeSeq.toLong) match {
          case (Some(newUpdateSeq), Some(newPurgeSeq)) =>
            Some((Symbol("committed"), newUpdateSeq, newPurgeSeq))
          case _ =>
            None
        }
      }
      case ETuple(from: EPid, EListImproper(EAtom("alias"), ref: ERef)) =>
        Some((Pid.toScala(from), List(Symbol("alias"), Reference.toScala(ref))))
      case ETuple(from: EPid, ref: ERef) =>
        Some((Pid.toScala(from), Reference.toScala(ref)))
      case _ => None
    }
  }

  def fromScala(term: Any): Option[ETerm] = {
    term match {
      case pid: Pid           => Some(pid.fromScala)
      case ref: Reference     => Some(ref.fromScala)
      case bytesRef: BytesRef => Some(EBinary(bytesRef.utf8ToString()))
      case (from: Pid, List(Symbol("alias"), reference: Reference)) =>
        val ref = reference.fromScala
        Some(ETuple(from.fromScala, EListImproper(EAtom("alias"), ref), ref))
      case (from: Pid, reference: Reference) =>
        val ref = reference.fromScala
        Some(ETuple(from.fromScala, ref, ref))
      case _ => None
    }
  }
}
