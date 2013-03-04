/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import org.apache.log4j.Logger
import org.apache.lucene.document.Field._
import org.apache.lucene.document._
import org.apache.lucene.search._
import scala.collection.immutable.Map
import scalang._
import org.jboss.netty.buffer.ChannelBuffer
import org.apache.lucene.util.BytesRef

case class OpenIndexMsg(peer : Pid, path : String, options : Any)
case class CleanupPathMsg(path : String)
case class CleanupDbMsg(dbName : String, activeSigs : List[String])

case class SearchMsg(query : String, limit : Int, refresh : Boolean, after : Option[ScoreDoc], sort : Any)

case class Group1Msg(query: String, field: String, refresh: Boolean, groupSort: Any, groupOffset: Int,
                     groupLimit: Int)

case class Group2Msg(query: String, field: String, refresh: Boolean, groups: Any, groupSort: Any,
                     docSort: Any, docLimit: Int)

case class UpdateDocMsg(id : String, doc : Document)
case class DeleteDocMsg(id : String)
case class CommitMsg(seq : Long)

object ClouseauTypeFactory extends TypeFactory {

  val logger = Logger.getLogger("clouseau.tf")

  def createType(name : Symbol, arity : Int, reader : TermReader) : Option[Any] = (name, arity) match {
    case ('open, 4) =>
      Some(OpenIndexMsg(reader.readAs[Pid], reader.readAs[String], reader.readTerm))
    case ('cleanup, 2) =>
      Some(CleanupPathMsg(reader.readAs[String]))
    case ('cleanup, 3) =>
      Some(CleanupDbMsg(reader.readAs[String], reader.readAs[List[String]]))
    case ('search, 6) =>
      Some(SearchMsg(reader.readAs[String], reader.readAs[Int], reader.readAs[Boolean], readScoreDoc(reader),
        reader.readTerm))
    case ('group1, 7) =>
      Some(Group1Msg(reader.readAs[String], reader.readAs[String], reader.readAs[Boolean], reader.readTerm,
        reader.readAs[Int], reader.readAs[Int]))
    case ('group2, 8) =>
      Some(Group2Msg(reader.readAs[String], reader.readAs[String], reader.readAs[Boolean],
        reader.readTerm, reader.readTerm, reader.readTerm, reader.readAs[Int]))
    case ('update, 3) =>
      val doc = readDoc(reader)
      val id = doc.getField("_id").stringValue
      Some(UpdateDocMsg(id, doc))
    case ('delete, 2) =>
      Some(DeleteDocMsg(reader.readAs[String]))
    case ('commit, 2) =>
      Some(CommitMsg(toLong(reader.readTerm)))
    case _ =>
      None
  }

  protected def readScoreDoc(reader : TermReader) : Option[ScoreDoc] = reader.readTerm match {
    case 'nil =>
      None
    case (score : Any, doc : Any) =>
      Some(new ScoreDoc(toInteger(doc), toFloat(score)))
    case list : List[Object] =>
      val doc = list.last
      val fields = list dropRight(1)
      Some(new FieldDoc(toInteger(doc), Float.NaN, fields map {
        case str: String =>
          Utils.stringToBytesRef(str)
        case field =>
           field
      } toArray))
  }

  protected def readDoc(reader : TermReader) : Document = {
    val result = new Document()
    result.add(new Field("_id", reader.readAs[String], Store.YES, Index.NOT_ANALYZED))
    val fields = reader.readAs[List[Any]]
    for (field <- fields) {
      toField(field) match {
        case Some(field) =>
          result.add(field)
        case None =>
          'ok
      }
    }
    result
  }

  private def toField(field : Any) : Option[Field] = field match {
    case (name : String, value : String, options : List[(String, Any)]) =>
      val map = options.toMap
      constructField(name, value, toStore(map), toIndex(map), toTermVector(map)) match {
        case Some(field) =>
          map.get("boost") match {
            case Some(boost : Number) =>
              field.setBoost(toFloat(boost))
            case None =>
              'ok
          }
          Some(field)
        case None =>
          None
      }
    case (name : String, value : Boolean, options : List[(String, Any)]) =>
      val map = options.toMap
      constructField(name, value.toString, toStore(map), Index.NOT_ANALYZED, toTermVector(map))
    case (name : String, value : Any, options : List[(String, Any)]) =>
      val map = options.toMap
      toDouble(value) match {
        case Some(doubleValue) =>
          Some(new DoubleField(name, doubleValue, toStore(map)))
        case None =>
          logger.warn("Unrecognized value: %s".format(value))
          None
      }
  }

  private def constructField(name : String, value : String, store : Store, index : Index, tv : TermVector) : Option[Field] = {
    try {
      Some(new Field(name, value, store, index, tv))
    } catch {
      case e : IllegalArgumentException =>
        logger.error("Failed to construct field '%s' with reason '%s'".format(name, e.getMessage))
        None
      case e : NullPointerException =>
        logger.error("Failed to construct field '%s' with reason '%s'".format(name, e.getMessage))
        None
    }
  }

  // These to* methods are stupid.

  def toFloat(a : Any) : Float = a match {
    case v : java.lang.Double  => v.floatValue
    case v : java.lang.Float   => v
    case v : java.lang.Integer => v.floatValue
    case v : java.lang.Long    => v.floatValue
  }

  def toDouble(a : Any) : Option[Double] = a match {
    case v : java.lang.Double  => Some(v)
    case v : java.lang.Float   => Some(v.doubleValue)
    case v : java.lang.Integer => Some(v.doubleValue)
    case v : java.lang.Long    => Some(v.doubleValue)
    case v : scala.math.BigInt => Some(v.doubleValue())
    case _ => None
  }

  def toLong(a : Any) : Long = a match {
    case v : java.lang.Integer => v.longValue
    case v : java.lang.Long    => v
  }

  def toInteger(a : Any) : Integer = a match {
    case v : java.lang.Integer => v
    case v : java.lang.Long    => v.intValue
  }

  def toStore(options : Map[String, Any]) : Store = {
    val store = options.getOrElse("store", "no").asInstanceOf[String]
    Store.valueOf(store toUpperCase)
  }

  def toIndex(options : Map[String, Any]) : Index = {
    val index = options.getOrElse("index", "analyzed").asInstanceOf[String]
    Index.valueOf(index toUpperCase)
  }

  def toTermVector(options : Map[String, Any]) : TermVector = {
    val termVector = options.getOrElse("termvector", "no").asInstanceOf[String]
    TermVector.valueOf(termVector toUpperCase)
  }

}

object ClouseauTypeEncoder extends TypeEncoder {

  def unapply(obj: Any): Option[Any] = obj match {
    case bytesRef : BytesRef =>
      Some(bytesRef)
    case string : String =>
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
    case 109 =>
      Some(typeOrdinal)
    case _ =>
      None
  }

  def decode(typeOrdinal : Int, buffer: ChannelBuffer) : Any = typeOrdinal match {
    case 109 =>
      val length = buffer.readInt
      val bytes = new Array[Byte](length)
      buffer.readBytes(bytes)
      new String(bytes, "UTF-8")
  }

}
