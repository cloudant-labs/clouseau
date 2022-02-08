package com.boundary.logula.tests

import org.junit.Test
import com.boundary.logula.Log
import com.simple.simplespec.Spec
import org.slf4j.Logger

class LogExample(log: Log) {
  def doTrace() { log.trace("One, two, %d", 3) }
  def doTrace(t: Throwable) { log.trace(t, "One, two, %d", 3) }
  def doDebug() { log.debug("One, two, %d", 3) }
  def doDebug(t: Throwable) { log.debug(t, "One, two, %d", 3) }
  def doInfo() { log.info("One, two, %d", 3) }
  def doInfo(t: Throwable) { log.info(t, "One, two, %d", 3) }
  def doWarn() { log.warn("One, two, %d", 3) }
  def doWarn(t: Throwable) { log.warn(t, "One, two, %d", 3) }
  def doError() { log.error("One, two, %d", 3) }
  def doError(t: Throwable) { log.error(t, "One, two, %d", 3) }
}

class LogSpec extends Spec {
  val logger = mock[Logger]
  val example = new LogExample(new Log(logger))

  class `Logging a TRACE message` {
    logger.isTraceEnabled.returns(true)

    @Test def `passes the message to the underlying logger` = {
      example.doTrace()
      verify.one(logger).trace("One, two, 3")
    }

    class `with an exception` {
      val t = mock[Throwable]

      @Test def `passes the message to the underlying logger` = {
        example.doTrace(t)
        verify.one(logger).trace("One, two, 3", t)
      }
    }

    class `when TRACE is disabled` {
      val t = mock[Throwable]
      logger.isTraceEnabled.returns(false)

      @Test def `doesn't pass the message to the underlying logger` = {
        example.doTrace()
        example.doTrace(t)
        verify.no(logger).trace(any)
        verify.no(logger).trace(any, isA[Throwable])
      }
    }
  }

  class `Logging a DEBUG message` {
    logger.isDebugEnabled.returns(true)

    @Test def `passes the message to the underlying logger` = {
      example.doDebug()
      verify.one(logger).debug("One, two, 3")
    }

    class `with an exception` {
      val t = mock[Throwable]

      @Test def `passes the message to the underlying logger` = {
        example.doDebug(t)
        verify.one(logger).debug("One, two, 3", t)
      }
    }

    class `when DEBUG is disabled` {
      val t = mock[Throwable]
      logger.isDebugEnabled.returns(false)

      @Test def `doesn't pass the message to the underlying logger` = {
        example.doDebug()
        example.doDebug(t)
        verify.no(logger).debug(any)
        verify.no(logger).debug(any, isA[Throwable])
      }
    }
  }

  class `Logging a INFO message` {
    logger.isInfoEnabled.returns(true)

    @Test def `passes the message to the underlying logger` = {
      example.doInfo()
      verify.one(logger).info("One, two, 3")
    }

    class `with an exception` {
      val t = mock[Throwable]

      @Test def `passes the message to the underlying logger` = {
        example.doInfo(t)
        verify.one(logger).info("One, two, 3", t)
      }
    }

    class `when INFO is disabled` {
      val t = mock[Throwable]
      logger.isInfoEnabled.returns(false)

      @Test def `doesn't pass the message to the underlying logger` = {
        example.doInfo()
        example.doInfo(t)
        verify.no(logger).info(any)
        verify.no(logger).info(any, isA[Throwable])
      }
    }
  }

  class `Logging a WARN message` {
    logger.isWarnEnabled.returns(true)

    @Test def `passes the message to the underlying logger` = {
      example.doWarn()
      verify.one(logger).warn("One, two, 3")
    }

    class `with an exception` {
      val t = mock[Throwable]

      @Test def `passes the message to the underlying logger` = {
        example.doWarn(t)
        verify.one(logger).warn("One, two, 3", t)
      }
    }

    class `when WARN is disabled` {
      val t = mock[Throwable]
      logger.isWarnEnabled.returns(false)

      @Test def `doesn't pass the message to the underlying logger` = {
        example.doWarn()
        example.doWarn(t)
        verify.no(logger).warn(any)
        verify.no(logger).warn(any, isA[Throwable])
      }
    }
  }

  class `Logging a ERROR message` {
    logger.isErrorEnabled.returns(true)

    @Test def `passes the message to the underlying logger` = {
      example.doError()
      verify.one(logger).error("One, two, 3")
    }

    class `with an exception` {
      val t = mock[Throwable]

      @Test def `passes the message to the underlying logger` = {
        example.doError(t)
        verify.one(logger).error("One, two, 3", t)
      }
    }

    class `when ERROR is disabled` {
      val t = mock[Throwable]
      logger.isErrorEnabled.returns(false)

      @Test def `doesn't pass the message to the underlying logger` = {
        example.doError()
        example.doError(t)
        verify.no(logger).error(any)
        verify.no(logger).error(any, isA[Throwable])
      }
    }
  }
}
