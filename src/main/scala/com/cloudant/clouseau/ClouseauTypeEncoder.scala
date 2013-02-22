/*
 * Copyright 2013 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import scalang.TypeEncoder
import org.jboss.netty.buffer.ChannelBuffer
import org.apache.lucene.util.BytesRef

object ClouseauTypeEncoder extends TypeEncoder {

  def unapply(obj: Any): Option[Any] = obj match {
    case bytesRef : BytesRef =>
      Some(bytesRef)
    case string : String =>
      Some(string)
    case _ =>
      None
  }

  def encode(obj: Any, buffer: ChannelBuffer) = obj match {
    case bytesRef : BytesRef =>
      buffer.writeByte(109)
      buffer.writeInt(bytesRef.length)
      buffer.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length)
    case string : String =>
      val bytes = string.getBytes("UTF-8")
      buffer.writeByte(109)
      buffer.writeInt(bytes.length)
      buffer.writeBytes(bytes)
  }

}
