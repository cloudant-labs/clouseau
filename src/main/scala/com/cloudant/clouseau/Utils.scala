package com.cloudant.clouseau

import java.nio.ByteBuffer
import java.nio.charset.Charset

import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.NumericUtils

object Utils {

  val utf8 = Charset.forName("UTF-8")

  def toMap(options : List[(ByteBuffer, Any)]) : Map[String, Any] = {
    val b = Map.newBuilder[String, Any]
    for (option <- options)
      option match {
        case (name : ByteBuffer, value : ByteBuffer) =>
          b += new Tuple2(name, byteBufferToString(value))
        case (name : ByteBuffer, value : List[ByteBuffer]) =>
          b += new Tuple2(name, byteBufferListToStringList(value))
        case (name : ByteBuffer, value : Any) =>
          b += new Tuple2(name, value)
        case _ =>
            'ok
      }
    b.result
  }

  implicit def byteBufferToString(buf : ByteBuffer) : String = {
    utf8.decode(buf.duplicate).toString
  }

  implicit def byteBufferListToStringList(list : List[ByteBuffer]) : List[String] = {
    for (buf <- list) yield { byteBufferToString(buf) }
  }

  def findOrElse[A](options : List[(Symbol, Any)], key : Symbol, default : A) : A = {
    options find { e => e._1 == key } match {
      case None                  => default
      case Some((_, result : A)) => result
    }
  }

  def doubleToTerm(field : String, value : Double) : Term = {
    val bytesRef = new BytesRef
    val asLong = NumericUtils.doubleToSortableLong(value)
    NumericUtils.longToPrefixCoded(asLong, 0, bytesRef)
    new Term(field, bytesRef)
  }

}
