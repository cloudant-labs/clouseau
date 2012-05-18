package com.cloudant.clouseau

import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.log4j.Logger
import org.apache.lucene.document.Field._
import org.apache.lucene.document._
import scalang._

object ClouseauTypeFactory extends TypeFactory {

  def createType(name: Symbol, arity: Int, reader: TermReader) : Option[Any] = (name, arity) match {
    case ('utf8, 2) =>
      val buf = reader.readAs[ByteBuffer]
      Some(utf8.decode(buf).toString)
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

  private def toFieldable(field: Any): Option[Fieldable] = field match {
    case (name: String, value: Int, store: Boolean) =>
      Some(new NumericField(name, if (store) Store.YES else Store.NO, true).setLongValue(value))
    case (name: String, value: Long, store: Boolean) =>
      Some(new NumericField(name, if (store) Store.YES else Store.NO, true).setLongValue(value))
    case (name: String, value: Float, store: Boolean) =>
      Some(new NumericField(name, if (store) Store.YES else Store.NO, true).setDoubleValue(value))
    case (name: String, value: Double, store: Boolean) =>
      Some(new NumericField(name, if (store) Store.YES else Store.NO, true).setDoubleValue(value))
    case (name: String, value: Boolean, store: Boolean) =>
      Some(new Field(name, value.toString, if (store) Store.YES else Store.NO, Index.NOT_ANALYZED))
    case (name: String, value: String, store: String, index: String) =>
      Some(new Field(name, value, toStore(store), toIndex(index)))
    case _ =>
      logger.warn("Unrecognized field: ".format(field))
      None
  }

  private def toStore(store: String): Store = {
    Store.valueOf(store toUpperCase)
  }

  private def toIndex(index: String): Index = {
    Index.valueOf(index toUpperCase)
  }

  private def long(a: Any) : Long = {
    a match {
      case v: java.lang.Integer => v.intValue
      case v: java.lang.Long => v.longValue
    }
  }

  val utf8 = Charset.forName("UTF-8")
  val logger = Logger.getLogger("clouseau.tf")
}
