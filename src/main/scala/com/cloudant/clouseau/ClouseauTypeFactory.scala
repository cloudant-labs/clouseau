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
case class SearchMsg(query : String, limit : Int, refresh : Boolean, after : Option[ScoreDoc], sort : Option[Sort])
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
    case ('search, 4) => // temporary upgrade clause
      Some(SearchMsg(reader.readAs[ByteBuffer], reader.readAs[Int], reader.readAs[Boolean], None, None))
    case ('search, 5) => // temporary upgrade clause
      Some(SearchMsg(reader.readAs[ByteBuffer], reader.readAs[Int], reader.readAs[Boolean], readScoreDoc(reader), None))
    case ('search, 6) => // temporary upgrade clause
      Some(SearchMsg(reader.readAs[ByteBuffer], reader.readAs[Int], reader.readAs[Boolean], readScoreDoc(reader), readSort(reader)))
    case ('update, 3) =>
      val doc = readDoc(reader)
      val id = doc.getFieldable("_id").stringValue
      Some(UpdateDocMsg(id, doc))
    case ('delete, 2) =>
      Some(DeleteDocMsg(reader.readAs[ByteBuffer]))
    case ('commit, 2) =>
      Some(CommitMsg(toLong(reader.readTerm)))
    case _ =>
      None
  }

  protected def readSort(reader : TermReader) : Option[Sort] = reader.readTerm match {
    case 'relevance =>
      None
    case field : ByteBuffer =>
      Some(new Sort(toSortField(field)))
    case fields : List[ByteBuffer] =>
      Some(new Sort(fields.map(toSortField(_)).toArray : _*))
  }

  protected def toSortField(field : String) : SortField = {
    if (field.startsWith("-"))
      new SortField(field.drop(1), SortField.DOUBLE, true)
    else
      new SortField(field, SortField.DOUBLE)
  }

  protected def readScoreDoc(reader : TermReader) : Option[ScoreDoc] = reader.readTerm match {
    case 'nil =>
      None
    case (score : Any, doc : Any) =>
      Some(new ScoreDoc(toInteger(doc), toFloat(score)))
  }

  protected def readDoc(reader : TermReader) : Document = {
    val result = new Document()
    result.add(new Field("_id", reader.readAs[ByteBuffer], Store.YES, Index.NOT_ANALYZED))
    val fields = reader.readAs[List[Any]]
    for (field <- fields) {
      toFieldable(field) match {
        case Some(fieldable) =>
          result.add(fieldable)
        case None =>
          'ok
      }
    }
    result
  }

  private def toFieldable(field : Any) : Option[Fieldable] = field match {
    case (name : ByteBuffer, value : ByteBuffer, options : List[(ByteBuffer, Any)]) =>
      val map = toMap(options)
      val field = new Field(name, value, toStore(map), toIndex(map), toTermVector(map))
      map.get("boost") match {
        case Some(boost : Number) =>
          field.setBoost(toFloat(boost))
        case None =>
          'ok
      }
      Some(field)
    case (name : ByteBuffer, value : Boolean, options : List[(ByteBuffer, Any)]) =>
      val map = toMap(options)
      Some(new Field(name, value.toString, toStore(map), Index.NOT_ANALYZED, toTermVector(map)))
    case (name : ByteBuffer, value : Any, options : List[(ByteBuffer, Any)]) =>
      val map = toMap(options)
      toDouble(value) match {
        case Some(doubleValue) =>
          Some(new NumericField(name, 8, toStore(map), true).setDoubleValue(doubleValue))
        case None =>
          logger.warn("Unrecognized value: %s".format(value))
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
