package com.cloudant.clouseau

import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.log4j.Logger
import org.apache.lucene.document.Field._
import org.apache.lucene.document._
import scalang._

case class OpenIndexMsg(path : String, analyzer : String)
case class SearchMsg(query : String, limit : Int, refresh : Boolean)
case class UpdateDocMsg(id : String, doc : Document)
case class DeleteDocMsg(id : String)
case class CommitMsg(seq : Long)

object ClouseauTypeFactory extends TypeFactory {

  def createType(name : Symbol, arity : Int, reader : TermReader) : Option[Any] = (name, arity) match {
    case ('open, 3) =>
      Some(OpenIndexMsg(reader.readAs[ByteBuffer], reader.readAs[ByteBuffer]))
    case ('search, 4) =>
      Some(SearchMsg(reader.readAs[ByteBuffer], reader.readAs[Int], reader.readAs[Boolean]))
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
    case (name : ByteBuffer, value : ByteBuffer, store : ByteBuffer, index : ByteBuffer, termvector : ByteBuffer) =>
      Some(new Field(name, value, toStore(store), toIndex(index), toTermVector(termvector)))
    case (name : String, value : Boolean, store : ByteBuffer, index : ByteBuffer, termvector : ByteBuffer) =>
      Some(new Field(name, value.toString, toStore(store), Index.NOT_ANALYZED, toTermVector(termvector)))
    case (name : ByteBuffer, value : Any, store : ByteBuffer, index : ByteBuffer, termvector : ByteBuffer) =>
      toDouble(value) match {
        case Some(doubleValue) =>
          Some(new NumericField(name, 8, toStore(store), true).setDoubleValue(doubleValue))
        case None =>
          logger.warn("Unrecognized value: %s".format(value))
          None
      }
  }

  def toDouble(a : Any) : Option[Double] = a match {
    case v : java.lang.Double  => Some(v)
    case v : java.lang.Float   => Some(v.doubleValue)
    case v : java.lang.Integer => Some(v.doubleValue)
    case v : java.lang.Long    => Some(v.doubleValue)
    case _                     => None
  }

  def toLong(a : Any) : Long = a match {
    case v : java.lang.Integer => v.longValue
    case v : java.lang.Long    => v.longValue
  }

  def toStore(store : String) : Store = {
    Store.valueOf(store toUpperCase)
  }

  def toIndex(index : String) : Index = {
    Index.valueOf(index toUpperCase)
  }

  def toTermVector(tv : String) : TermVector = {
    TermVector.valueOf(tv toUpperCase)
  }

  implicit def toString(buf : ByteBuffer) : String = {
    utf8.decode(buf).toString
  }

  val utf8 = Charset.forName("UTF-8")
  val logger = Logger.getLogger("clouseau.tf")
}
