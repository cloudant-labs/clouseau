/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import com.cloudant.clouseau.Utils._
import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.log4j.Logger
import org.apache.lucene.document.Field._
import org.apache.lucene.document._
import org.apache.lucene.search._
import scala.collection.immutable.Map
import scalang._

case class OpenIndexMsg(peer : Pid, path : String, options : Any)
case class CleanupPathMsg(path : String)
case class CleanupDbMsg(dbName : String, activeSigs : List[String])
case class SearchMsg(query: String, options: Map[Symbol, Any])
case class UpdateDocMsg(id : String, doc : Document)
case class DeleteDocMsg(id : String)
case class CommitMsg(seq : Long)

object ClouseauTypeFactory extends TypeFactory {

  val logger = Logger.getLogger("clouseau.tf")

  def createType(name : Symbol, arity : Int, reader : TermReader) : Option[Any] = (name, arity) match {
    case ('open, 4) =>
      Some(OpenIndexMsg(reader.readAs[Pid], reader.readAs[ByteBuffer], reader.readTerm))
    case ('cleanup, 2) =>
      Some(CleanupPathMsg(reader.readAs[ByteBuffer]))
    case ('cleanup, 3) =>
      Some(CleanupDbMsg(reader.readAs[ByteBuffer], reader.readAs[List[ByteBuffer]]))
    // legacy call when we update dreyfus
    case ('search, 6) => {
      Some(SearchMsg(reader.readAs[ByteBuffer], recordToOptionsMap(reader)))
    }
    case ('search, 3) => {
      Some(SearchMsg(reader.readAs[ByteBuffer], listToOptionsMap(reader)))
    }
    case ('update, 3) =>
      val doc = readDoc(reader)
      val id = doc.getField("_id").stringValue
      Some(UpdateDocMsg(id, doc))
    case ('delete, 2) =>
      Some(DeleteDocMsg(reader.readAs[ByteBuffer]))
    case ('commit, 2) =>
      Some(CommitMsg(toLong(reader.readTerm)))
    case _ =>
      None
  }

  protected def listToOptionsMap(reader : TermReader) : Map[Symbol, Any] = {
    reader.readAs[List[(Symbol,Any)]].toMap
  }

  protected def recordToOptionsMap(reader : TermReader) : Map[Symbol, Any] = {
    List('limit -> reader.readAs[Int],
         'refresh -> reader.readAs[Boolean]) ++
         extractOrEmpty('after, readScoreDoc(reader)) ++
         extractOrEmpty('sort, readSort(reader)) toMap
  }

  protected def extractOrEmpty[A,B](key : A, maybeValue : Option[B]) : List[(A, B)] = {
    maybeValue match {
      case Some(value) => List(key -> value)
      case None => List()
    }
  }

  protected def readSort(reader : TermReader) : Option[Any] = reader.readTerm match {
    case 'relevance =>
      None
    case sort =>
      Some(sort)
  }

  protected def readScoreDoc(reader : TermReader) : Option[ScoreDoc] = reader.readTerm match {
    case 'nil =>
      None
    case (score : Any, doc : Any) =>
      Some(new ScoreDoc(toInteger(doc), toFloat(score)))
    case list : List[Object] =>
      val doc = list last
      val fields = list dropRight(1)
      Some(new FieldDoc(toInteger(doc), Float.NaN, fields.toArray))
  }

  protected def readDoc(reader : TermReader) : Document = {
    val result = new Document()
    result.add(new Field("_id", reader.readAs[ByteBuffer], Store.YES, Index.NOT_ANALYZED))
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
    case (name : ByteBuffer, value : ByteBuffer, options : List[(ByteBuffer, Any)]) =>
      val map = toMap(options)
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
    case (name : ByteBuffer, value : Boolean, options : List[(ByteBuffer, Any)]) =>
      val map = toMap(options)
      constructField(name, value.toString, toStore(map), Index.NOT_ANALYZED, toTermVector(map))
    case (name : ByteBuffer, value : Any, options : List[(ByteBuffer, Any)]) =>
      val map = toMap(options)
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
    case v : scala.math.BigInt => Some(v.doubleValue)
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
