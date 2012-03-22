package com.cloudant.clouseau

import java.nio.ByteBuffer
import java.nio.charset.Charset

object Utils {

  def toString(buf: ByteBuffer): String = {
    val charset = Charset.forName("UTF-8")
    val decoder = charset.newDecoder();
    decoder.decode(buf).toString
  }

}
