package com.cloudant.ziose.clouseau

import zio._
import zio.logging.backend.SLF4J

object LoggerFactory {
  case class NamedLogger(id: String) {
    def debug(msg: String) = {
      ZIO.logDebug(msg) @@ zio.logging.loggerName(id)
    }
    def info(msg: String) = {
      ZIO.logInfo(msg) @@ zio.logging.loggerName(id)
    }
    def warn(msg: String) = {
      ZIO.logWarning(msg) @@ zio.logging.loggerName(id)
    }
    def warn(msg: String, e: Throwable) = {
      ZIO.logWarningCause(msg, Cause.die(e)) @@ zio.logging.loggerName(id)
    }
    def error(msg: String) = {
      ZIO.logError(msg) @@ zio.logging.loggerName(id)
    }
    def error(msg: String, e: Throwable) = {
      ZIO.logErrorCause(msg, Cause.die(e)) @@ zio.logging.loggerName(id)
    }
  }

  val loggerDefault: ZLayer[Any, Nothing, Unit] = {
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j
  }

  def getLogger(id: String) = NamedLogger(id)
}
