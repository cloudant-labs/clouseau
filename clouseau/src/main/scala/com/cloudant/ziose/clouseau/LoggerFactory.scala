package com.cloudant.ziose.clouseau

import com.cloudant.ziose.scalang.Adapter
import zio.{Cause, FiberId, FiberRefs, LogLevel, LogSpan, Runtime, Trace, UIO, ULayer, ZLogger}
import zio.ZIO.{logDebug, logError, logErrorCause, logInfo, logWarning, logWarningCause}
import zio.logging.loggerName

object LoggerFactory {
  case class NamedLogger(id: String) extends ZioSupport {
    def debug(msg: String)(implicit adapter: Adapter[_, _]): Unit = (logDebug(msg) @@ loggerName(id)).unsafeRun

    def info(msg: String)(implicit adapter: Adapter[_, _]): Unit = (logInfo(msg) @@ loggerName(id)).unsafeRun

    def warn(msg: String)(implicit adapter: Adapter[_, _]): Unit = (logWarning(msg) @@ loggerName(id)).unsafeRun
    def warn(msg: String, e: Throwable)(implicit adapter: Adapter[_, _]): Unit = {
      (logWarningCause(msg, Cause.die(e)) @@ loggerName(id)).unsafeRun
    }

    def error(msg: String)(implicit adapter: Adapter[_, _]): Unit = (logError(msg) @@ loggerName(id)).unsafeRun
    def error(msg: String, e: Throwable)(implicit adapter: Adapter[_, _]): Unit = {
      (logErrorCause(msg, Cause.die(e)) @@ loggerName(id)).unsafeRun
    }
  }

  case class NamedZioLogger(id: String) {
    def debug(msg: String): UIO[Unit] = logDebug(msg) @@ loggerName(id)

    def info(msg: String): UIO[Unit] = logInfo(msg) @@ loggerName(id)

    def warn(msg: String): UIO[Unit] = logWarning(msg) @@ loggerName(id)
    def warn(msg: String, e: Throwable): UIO[Unit] = {
      logWarningCause(msg, Cause.die(e)) @@ loggerName(id)
    }

    def error(msg: String): UIO[Unit] = logError(msg) @@ loggerName(id)
    def error(msg: String, e: Throwable): UIO[Unit] = {
      logErrorCause(msg, Cause.die(e)) @@ loggerName(id)
    }
  }

  val logger: ZLogger[String, Unit] = (
    _trace: Trace,
    fiberId: FiberId,
    logLevel: LogLevel,
    message: () => String,
    _cause: Cause[Any],
    _context: FiberRefs,
    _spans: List[LogSpan],
    annotations: Map[String, String]
  ) => {
    val from = annotations.getOrElse("logger_name", "[unknown]")
    println(s"[${fiberId.threadName}] ${logLevel.label} ${from} - ${message()}")
  }

  val loggerDefault: ULayer[Unit] = Runtime.removeDefaultLoggers ++ Runtime.addLogger(logger)

  def getLogger(id: String): NamedLogger       = NamedLogger(id)
  def getZioLogger(id: String): NamedZioLogger = NamedZioLogger(id)
}
