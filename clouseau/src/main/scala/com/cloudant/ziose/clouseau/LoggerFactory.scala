package com.cloudant.ziose.clouseau

import com.cloudant.ziose.scalang.Adapter
import zio.{Cause, Runtime, LogLevel, Trace}
import zio.ZIO.{logDebug, logError, logErrorCause, logInfo, logWarning, logWarningCause}
import zio.logging.{
  loggerName,
  consoleLogger,
  consoleJsonLogger,
  ConsoleLoggerConfig,
  LogFormat,
  LogFilter,
  LogGroup,
  LoggerNameExtractor
}
import zio.logging.LogFormat._
import zio.logging.slf4j.bridge.Slf4jBridge
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

object LoggerFactory {
  case class NamedLogger(id: String) extends ZioSupport {
    def debug(msg: String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      (logDebug(msg) @@ loggerName(id)).unsafeRun
    }

    def info(msg: String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      (logInfo(msg) @@ loggerName(id)).unsafeRun
    }

    def warn(msg: String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      (logWarning(msg) @@ loggerName(id)).unsafeRun
    }
    def warn(msg: String, e: Throwable)(implicit adapter: Adapter[_, _]): Unit = {
      (logWarningCause(msg, Cause.die(e)) @@ loggerName(id)).unsafeRun
    }

    def error(msg: String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      (logError(msg) @@ loggerName(id)).unsafeRun
    }
    def error(msg: String, e: Throwable)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      (logErrorCause(msg, Cause.die(e)) @@ loggerName(id)).unsafeRun
    }
  }

  private val slf4jLogger = org.slf4j.LoggerFactory.getLogger("SLF4J-LOGGER")

  private val logFilterConfig = LogFilter.LogLevelByNameConfig(
    LogLevel.Debug,
    "zio.logging.slf4j" -> LogLevel.Debug,
    "SLF4J-LOGGER"      -> LogLevel.Debug
  )

  private val logFormatPlainText = {
    val timeStampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:MM:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    (
      timestamp(timeStampFormat).fixed(25) +
        level.fixed(7) +
        fiberId.fixed(21) + space +
        (enclosingClass + text(":") + traceLine).fixed(32) + space +
        (text("[") + LogFormat.loggerName(LoggerNameExtractor.annotation("logger_name")) + text("]") + space) +
        line +
        (space + cause).filter(LogFilter.causeNonEmpty)
    ).highlight
  }

  private val logFormatJSON = {
    val extractAnnotations: LogGroup[Any, Map[String, String]] = {
      LogGroup.apply((_, _, _, _, _, _, _, annotations) => annotations)
    }
    val annotationsNonEmpty: LogFilter[Any] = {
      LogFilter.apply[Any, Map[String, String]](extractAnnotations, !_.isEmpty)
    }

    label("timestamp", timestamp) |-|
      label("level", level) |-|
      label("fiberId", fiberId) |-|
      (label("annotations", annotations)).filter(annotationsNonEmpty) |-|
      label("location", label("module", enclosingClass) |-| label("line", traceLine)) |-|
      label("message", quoted(line)) + (space + label("cause", cause)).filter(LogFilter.causeNonEmpty)
  }

  private def loggerForOutput(format: LogOutput) = {
    val (config, logger) = format match {
      case LogOutput.PlainText =>
        val config = ConsoleLoggerConfig(logFormatPlainText, logFilterConfig)
        (config, consoleLogger(config))
      case LogOutput.JSON =>
        val config = ConsoleLoggerConfig(logFormatJSON, logFilterConfig)
        (config, consoleJsonLogger(config))
    }
    logger >+> Slf4jBridge.init(config.toFilter)
  }

  def loggerDefault(format: LogOutput) = {
    Runtime.removeDefaultLoggers >>> loggerForOutput(format)
  }

  def getLogger(id: String): NamedLogger = NamedLogger(id)
}
