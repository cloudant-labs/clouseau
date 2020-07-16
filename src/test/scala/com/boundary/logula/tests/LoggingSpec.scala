package com.boundary.logula.tests

import org.junit.Test
import com.boundary.logula.{Logging, Log}
import com.simple.simplespec.Spec

class LoggingExample extends Logging {
  def getLog = log
}

class LoggingSpec extends Spec {
  class `A class which extends Logging` {
    val example = new LoggingExample

    @Test def `has a Log instance` = {
      example.getLog.must(beA[Log])
    }
  }
}
