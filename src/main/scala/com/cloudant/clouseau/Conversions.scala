package com.cloudant.clouseau

import java.lang.Long
import java.nio.ByteBuffer
import java.nio.charset.Charset

object Conversions {

  implicit def asString(buf: ByteBuffer):String = {
    val decoder = Charset.forName("UTF-8").newDecoder
    decoder.decode(buf).toString
  }

  implicit def asLong(str: String):Long = {
    Long.parseLong(str)
  }

  implicit def asString(long: Long):String = {
    Long.toString(long)
  }

}
