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

import org.apache.log4j.Logger
import org.apache.lucene.document.Field._
import org.apache.lucene.document._
import org.apache.lucene.search._
import scala.collection.immutable.Map
import scala.collection.JavaConversions._
import scalang._
import org.jboss.netty.buffer.ChannelBuffer
import org.apache.lucene.util.BytesRef
import org.apache.lucene.facet.params.FacetIndexingParams
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetFields
import org.apache.lucene.facet.taxonomy.CategoryPath
import scala.collection.mutable.ArrayBuffer

case class SearchRequest(options: Map[Symbol, Any])

case class OpenIndexMsg(peer: Pid, path: String, options: Any)
case class CleanupPathMsg(path: String)
case class CleanupDbMsg(dbName: String, activeSigs: List[String])
case class DiskSizeMsg(path: String)
case class CloseLRUByPathMsg(path: String)
case class SoftDeleteMsg(path: String)

case class Group1Msg(query: String, field: String, refresh: Boolean, groupSort: Any, groupOffset: Int,
                     groupLimit: Int)

case class Group2Msg(options: Map[Symbol, Any])

case class UpdateDocMsg(id: String, doc: Document)
case class DeleteDocMsg(id: String)
case class CommitMsg(seq: Long)
case class SetUpdateSeqMsg(seq: Long)
case class SetPurgeSeqMsg(seq: Long)

object ClouseauTypeFactory extends TypeFactory {

  val logger = Logger.getLogger("clouseau.tf")

  def createType(name: Symbol, arity: Int, reader: TermReader): Option[Any] = (name, arity) match {
    case ('open, 4) =>
      Some(OpenIndexMsg(reader.readAs[Pid], reader.readAs[String], reader.readTerm))
    case ('cleanup, 2) =>
      Some(CleanupPathMsg(reader.readAs[String]))
    case ('cleanup, 3) =>
      Some(CleanupDbMsg(reader.readAs[String], reader.readAs[List[String]]))
    case ('search, 2) =>
      val params = reader.readAs[List[(Symbol, Any)]].toMap
      Some(SearchRequest(params))
    case ('search, 6) => // legacy clause
      Some(SearchRequest(Map(
        'legacy -> true,
        'query -> reader.readTerm,
        'limit -> reader.readTerm,
        'refresh -> reader.readTerm,
        'after -> reader.readTerm,
        'sort -> reader.readTerm)))
    case ('group1, 7) =>
      Some(Group1Msg(reader.readAs[String], reader.readAs[String], reader.readAs[Boolean], reader.readTerm,
        reader.readAs[Int], reader.readAs[Int]))
    case ('group2, 2) =>
      val params = reader.readAs[List[(Symbol, Any)]].toMap
      Some(Group2Msg(params))
    case ('group2, 8) => //legacy clause
      Some(Group2Msg(Map(
        'query -> reader.readAs[String],
        'field -> reader.readAs[String],
        'refresh -> reader.readAs[Boolean],
        'groups -> reader.readTerm,
        'group_sort -> reader.readTerm,
        'sort -> reader.readTerm,
        'limit -> reader.readAs[Int])))
    case ('update, 3) =>
      val doc = readDoc(reader)
      val id = doc.getField("_id").stringValue
      Some(UpdateDocMsg(id, doc))
    case ('delete, 2) =>
      Some(DeleteDocMsg(reader.readAs[String]))
    case ('disk_size, 2) =>
      Some(DiskSizeMsg(reader.readAs[String]))
    case ('close_lru_by_path, 2) =>
      Some(CloseLRUByPathMsg(reader.readAs[String]))
    case ('soft_delete, 2) =>
      Some(SoftDeleteMsg(reader.readAs[String]))
    case ('commit, 2) =>
      Some(CommitMsg(toLong(reader.readTerm)))
    case ('set_update_seq, 2) =>
      Some(SetUpdateSeqMsg(toLong(reader.readTerm)))
    case ('set_purge_seq, 2) =>
      Some(SetPurgeSeqMsg(toLong(reader.readTerm)))
    case _ =>
      None
  }

  protected def readDoc(reader: TermReader): Document = {
    val result = new Document()
    result.add(new StringField("_id", reader.readAs[String], Store.YES))
    for (field <- reader.readAs[List[Any]]) {
      addFields(result, field)
    }
    result
  }

  private def addFields(doc: Document, field0: Any): Unit = field0 match {
    case (name: String, value: String, options: List[(String, Any)]) =>
      val map = options.toMap
      constructField(name, value, toStore(map), toIndex(map), toTermVector(map)) match {
        case Some(field) =>
          map.get("boost") match {
            case Some(boost: Number) =>
              field.setBoost(toFloat(boost))
            case None =>
              'ok
          }
          doc.add(field)
          if (isFacet(map) && value.nonEmpty) {
            val fp = FacetIndexingParams.DEFAULT
            val delim = fp.getFacetDelimChar
            if (!name.contains(delim) && !value.contains(delim)) {
              val facets = new SortedSetDocValuesFacetFields(fp)
              facets.addFields(doc, List(new CategoryPath(name, value)))
            }
          }
        case None =>
          'ok
      }
    case (name: String, value: Boolean, options: List[(String, Any)]) =>
      val map = options.toMap
      constructField(name, value.toString, toStore(map), Index.NOT_ANALYZED, toTermVector(map)) match {
        case Some(field) =>
          doc.add(field)
        case None =>
          'ok
      }
    case (name: String, value: Any, options: List[(String, Any)]) =>
      val map = options.toMap
      toDouble(value) match {
        case Some(doubleValue) =>
          doc.add(new DoubleField(name, doubleValue, toStore(map)))
          if (isFacet(map)) {
            doc.add(new DoubleDocValuesField(name, doubleValue))
          }
        case None =>
          logger.warn("Unrecognized value: %s".format(value))
          'ok
      }
  }

  private def constructField(name: String, value: String, store: Store, index: Index, tv: TermVector): Option[Field] = {
    try {
      Some(new Field(name, value, store, index, tv))
    } catch {
      case e: IllegalArgumentException =>
        logger.error("Failed to construct field '%s' with reason '%s'".format(name, e.getMessage))
        None
      case e: NullPointerException =>
        logger.error("Failed to construct field '%s' with reason '%s'".format(name, e.getMessage))
        None
    }
  }

  // These to* methods are stupid.

  def toFloat(a: Any): Float = a match {
    case v: java.lang.Double => v.floatValue
    case v: java.lang.Float => v
    case v: java.lang.Integer => v.floatValue
    case v: java.lang.Long => v.floatValue
  }

  def toDouble(a: Any): Option[Double] = a match {
    case v: java.lang.Double => Some(v)
    case v: java.lang.Float => Some(v.doubleValue)
    case v: java.lang.Integer => Some(v.doubleValue)
    case v: java.lang.Long => Some(v.doubleValue)
    case v: scala.math.BigInt => Some(v.doubleValue())
    case _ => None
  }

  def toLong(a: Any): Long = a match {
    case v: java.lang.Integer => v.longValue
    case v: java.lang.Long => v
  }

  def toInteger(a: Any): Integer = a match {
    case v: java.lang.Integer => v
    case v: java.lang.Long => v.intValue
  }

  def toStore(options: Map[String, Any]): Store = {
    options.getOrElse("store", "no") match {
      case true => Store.YES
      case false => Store.NO
      case str: String =>
        try {
          Store.valueOf(str toUpperCase)
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
      case true => Index.ANALYZED
      case false => Index.NO
      case str: String =>
        try {
          Index.valueOf(str toUpperCase)
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
    TermVector.valueOf(termVector toUpperCase)
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

object ClouseauTypeEncoder extends TypeEncoder {

  def unapply(obj: Any): Option[Any] = obj match {
    case bytesRef: BytesRef =>
      Some(bytesRef)
    case string: String =>
      Some(string)
    case null =>
      Some(null)
    case _ =>
      None
  }

  def encode(obj: Any, buffer: ChannelBuffer) = obj match {
    case bytesRef: BytesRef =>
      buffer.writeByte(109)
      buffer.writeInt(bytesRef.length)
      buffer.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length)
    case string: String =>
      val bytes = string.getBytes("UTF-8")
      buffer.writeByte(109)
      buffer.writeInt(bytes.length)
      buffer.writeBytes(bytes)
    case null =>
      buffer.writeByte(115)
      buffer.writeByte(4)
      buffer.writeByte(110)
      buffer.writeByte(117)
      buffer.writeByte(108)
      buffer.writeByte(108)
  }

}

object ClouseauTypeDecoder extends TypeDecoder {

  def unapply(typeOrdinal: Int): Option[Int] = typeOrdinal match {
    case 107 =>
      Some(typeOrdinal)
    case 109 =>
      Some(typeOrdinal)
    case _ =>
      None
  }

  def decode(typeOrdinal: Int, buffer: ChannelBuffer): Any = typeOrdinal match {
    case 107 =>
      val length = buffer.readUnsignedShort
      val b = new ArrayBuffer[Int](length)
      for (n <- 1 to length) {
        b += buffer.readByte
      }
      b.toList
    case 109 =>
      val length = buffer.readInt
      val bytes = new Array[Byte](length)
      buffer.readBytes(bytes)
      new String(bytes, "UTF-8")
  }

}
