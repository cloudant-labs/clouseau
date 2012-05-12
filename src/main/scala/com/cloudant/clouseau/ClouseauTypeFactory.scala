package com.cloudant.clouseau

import java.nio.ByteBuffer
import java.nio.charset.Charset
import scalang._

object ClouseauTypeFactory extends TypeFactory {

  def createType(name: Symbol, arity: Int, reader: TermReader) : Option[Any] = (name, arity) match {
    case ('utf8, 2) =>
      val buf = reader.readAs[ByteBuffer]
      Some(utf8.decode(buf).toString)
    case _ =>
      None
  }

  val utf8 = Charset.forName("UTF-8")
}
