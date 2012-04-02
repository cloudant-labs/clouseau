package com.cloudant.clouseau

import java.nio._
import java.nio.charset.Charset

object Conversions {

  def byteBufferAsString(buf: ByteBuffer) : String = {
    val decoder = Charset.forName("UTF-8").newDecoder
    decoder.decode(buf).toString
  }

}
