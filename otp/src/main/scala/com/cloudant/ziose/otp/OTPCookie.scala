package com.cloudant.ziose.otp

import java.io.{BufferedReader, File, FileReader, FileWriter}
import java.nio.charset.StandardCharsets
import scala.util.Random

object OTPCookie {
  def findOrGenerateCookie: String = {
    val cookieProp: String = System.getProperty("cookie")
    if (cookieProp != null) {
      return cookieProp
    }

    val homeDir = System.getenv("HOME")
    if (homeDir == null) {
      throw new Exception("No erlang cookie set and cannot read ~/.erlang.cookie.")
    }
    val file = new File(homeDir, ".erlang.cookie")
    if (file.isFile) {
      readFile(file)
    } else {
      val cookie = randomCookie
      writeCookie(file, cookie)
      cookie
    }
  }

  private def readFile(file: File): String = {
    val in = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))
    try {
      in.readLine
    } finally {
      in.close()
    }
  }

  private def randomCookie: String = {
    (1 to 20).map(_ => Random.alphanumeric.filter(_.isUpper).head).mkString
  }

  private def writeCookie(file: File, cookie: String): Unit = {
    val out = new FileWriter(file, StandardCharsets.UTF_8)
    try {
      out.write(cookie)
      file.setReadOnly()
      file.setReadable(false, false)
      file.setReadable(true, true)
    } finally {
      out.close()
    }
  }
}
