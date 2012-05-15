package com.cloudant.clouseau

import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.lucene.document.Field._
import org.apache.lucene.document._
import scalang._

object ClouseauTypeFactory extends TypeFactory {

  def createType(name: Symbol, arity: Int, reader: TermReader) : Option[Any] = (name, arity) match {
    case ('utf8, 2) =>
      val buf = reader.readAs[ByteBuffer]
      Some(utf8.decode(buf).toString)
    case ('store, 2) =>
      Some(('store, Store.valueOf(reader.readAs[Symbol].name toUpperCase)))
    case ('index, 2) =>
      Some(('index, Index.valueOf(reader.readAs[Symbol].name toUpperCase)))
    case ('termvector, 2) =>
      Some(('termvector, TermVector.valueOf(reader.readAs[Symbol].name toUpperCase)))
    case ('doc, 3) =>
      Some(readDoc(reader))
    case ('commit, 2) =>
      Some(('commit, long(reader.readTerm)))
    case _ =>
      None
  }

  protected def readDoc(reader: TermReader): Document = {
    val result = new Document()
    result.add(new Field("_id", reader.readAs[String], Store.YES, Index.NOT_ANALYZED))
    val fields = reader.readAs[List[Any]]
    for (field <- fields) result add toFieldable(field)
    result
  }

  private def toFieldable(field: Any): Fieldable = field match {
    case (name: String, value: Int, options: List[(Symbol, Any)]) =>
      new NumericField(name, toStore(options), true).setIntValue(value)
    case (name: String, value: Long, options: List[(Symbol, Any)]) =>
      new NumericField(name, toStore(options), true).setLongValue(value)
    case (name: String, value: Float, options: List[(Symbol, Any)]) =>
      new NumericField(name, toStore(options), true).setFloatValue(value)
    case (name: String, value: Double, options: List[(Symbol, Any)]) =>
      new NumericField(name, toStore(options), true).setDoubleValue(value)
    case (name: String, value: Boolean, options: List[(Symbol, Any)]) =>
      new Field(name, value.toString, toStore(options), Index.NOT_ANALYZED)
    case (name: String, value: String, options: List[(Symbol, Any)]) =>
      new Field(name, value, toStore(options), toIndex(options), toTermVector(options))
  }

  private def toStore(options: List[(Symbol, Any)]) = {
    Utils.findOrElse(options, 'store, Store.NO)
  }

  private def toIndex(options: List[(Symbol, Any)]) = {
    Utils.findOrElse(options, 'index, Index.ANALYZED)
  }

  private def toTermVector(options: List[(Symbol, Any)]) = {
    Utils.findOrElse(options, 'termvector, TermVector.NO)
  }

  private def long(a: Any) : Long = {
    a match {
      case v: java.lang.Integer => v.intValue
      case v: java.lang.Long => v.longValue
    }
  }

  val utf8 = Charset.forName("UTF-8")
}
