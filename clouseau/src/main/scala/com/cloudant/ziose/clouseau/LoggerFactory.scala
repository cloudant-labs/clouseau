package com.cloudant.ziose.clouseau

import com.cloudant.ziose.scalang.Adapter
import zio.{Cause, Runtime, LogLevel, Trace, ZIO, ZLayer, ZLogger, UIO, Unsafe}
import zio.ZIO.{logDebug, logError, logErrorCause, logInfo, logWarning, logWarningCause}
import zio.logging.{
  loggerName,
  consoleLogger,
  consoleJsonLogger,
  ConsoleLoggerConfig,
  LogFilter,
  LogGroup,
  LoggerNameExtractor,
  FilteredLogger
}
import zio.logging.LogFormat._
import zio.logging.slf4j.bridge.Slf4jBridge
import org.tinylog.Logger
import org.tinylog.configuration.Configuration
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

// ad-hoc extension of zio.logging.LoggerLayers, shall be backported to upstream
object LoggerLayers {
  def syslogLogger(
    config: ConsoleLoggerConfig,
    syslogConfig: Option[SyslogConfiguration]
  ): ZLayer[Any, Nothing, Unit] = {
    makeZLayer(config.format.toLogger, config.toFilter, syslogConfig)
  }

  def syslogJsonLogger(
    config: ConsoleLoggerConfig,
    syslogConfig: Option[SyslogConfiguration]
  ): ZLayer[Any, Nothing, Unit] = {
    makeZLayer(config.format.toJsonLogger, config.toFilter, syslogConfig)
  }

  private def makeZLayer(
    logger: ZLogger[String, String],
    filter: LogFilter[String],
    config: Option[SyslogConfiguration]
  ): ZLayer[Any, Nothing, Unit] = {
    ZLayer.scoped {
      ZIO
        .succeed(syslogLogger(logger, config))
        .map(FilteredLogger(_, filter))
        .flatMap(ZIO.withLoggerScoped(_))
    }
  }

  private def syslogLogger(
    logger: ZLogger[String, String],
    config: Option[SyslogConfiguration]
  ): ZLogger[String, Any] = {
    val protocol = config.flatMap(_.protocol).getOrElse(SyslogProtocol.UDP)
    val host     = config.flatMap(_.host).getOrElse("localhost")
    val port     = config.flatMap(_.port).getOrElse(514)
    val facility = config.flatMap(_.facility).getOrElse("CONSOLE")
    val level    = config.flatMap(_.level).getOrElse("debug")
    val tag      = config.flatMap(_.tag).getOrElse("")

    Configuration.set("writer", "syslog")
    Configuration.set("writer.format", s"${tag}{message}")
    Configuration.set("writer.protocol", protocol.toString)
    Configuration.set("writer.host", host)
    Configuration.set("writer.port", port.toString)
    Configuration.set("writer.facility", facility)
    Configuration.set("writer.level", level)

    val syslogLogger = logger.map { line =>
      try Logger.info(line.asInstanceOf[Object])
      catch {
        case t: VirtualMachineError => throw t
        case _: Throwable           => ()
      }
    }
    syslogLogger
  }
}

object LoggerFactory {
  case class NamedLogger(id: String) extends ZioSupport {
    def debug(msg: => String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      if (LogLevel.Debug >= adapter.logLevel) {
        log(logDebug(msg) @@ loggerName(id))
      }
    }

    def info(msg: => String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      if (LogLevel.Info >= adapter.logLevel) {
        log(logInfo(msg) @@ loggerName(id))
      }
    }

    def warn(msg: => String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      if (LogLevel.Warning >= adapter.logLevel) {
        log(logWarning(msg) @@ loggerName(id))
      }
    }
    def warn(msg: => String, e: Throwable)(implicit adapter: Adapter[_, _]): Unit = {
      if (LogLevel.Warning >= adapter.logLevel) {
        log(logWarningCause(msg, Cause.die(e)) @@ loggerName(id))
      }
    }

    def error(msg: => String)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      if (LogLevel.Error >= adapter.logLevel) {
        log(logError(msg) @@ loggerName(id))
      }
    }
    def error(msg: => String, e: Throwable)(implicit adapter: Adapter[_, _], trace: Trace): Unit = {
      if (LogLevel.Error >= adapter.logLevel) {
        log(logErrorCause(msg, Cause.die(e)) @@ loggerName(id))
      }
    }

    def log(event: UIO[Unit])(implicit adapter: Adapter[_, _]): Unit = {
      Unsafe.unsafe(implicit u => adapter.runtime.unsafe.run(event.fork.ignore))
    }
  }

  private val slf4jLogger = org.slf4j.LoggerFactory.getLogger("SLF4J-LOGGER")

  private def logFilterConfig(level: LogLevel) = LogFilter.LogLevelByNameConfig(
    level,
    "zio.logging.slf4j" -> level,
    "SLF4J-LOGGER"      -> level
  )

  private val timeStampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:MM:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  @inline
  private def bracket(content: zio.logging.LogFormat) = text("[") + content + text("]")

  private val logFormatText = {
    (
      timestamp(timeStampFormat).fixed(25) +
        level.fixed(7) +
        fiberId.fixed(21) + space +
        (enclosingClass + text(":") + traceLine).fixed(32) + space +
        bracket(zio.logging.LogFormat.loggerName(LoggerNameExtractor.annotation("logger_name"))) + space +
        line +
        (space + cause).filter(LogFilter.causeNonEmpty)
    ).highlight
  }

  private val logFormatRaw = {
    timestamp(timeStampFormat) + space +
      bracket(level) +
      bracket(fiberId) +
      bracket((enclosingClass + text(":") + traceLine)) +
      bracket(zio.logging.LogFormat.loggerName(LoggerNameExtractor.annotation("logger_name"))) + space +
      line +
      (space + cause).filter(LogFilter.causeNonEmpty)
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

  private def loggerForOutput(cfg: LogConfiguration) = {
    val output: LogOutput = cfg.output.getOrElse(LogOutput.Stdout)
    val format: LogFormat = cfg.format.getOrElse(LogFormat.Raw)
    val level: LogLevel   = cfg.level.getOrElse(LogLevel.Debug)
    val formatSpecifier = format match {
      case LogFormat.Raw  => logFormatRaw
      case LogFormat.Text => logFormatText
      case LogFormat.JSON => logFormatJSON
    }
    val config = ConsoleLoggerConfig(formatSpecifier, logFilterConfig(level))
    val logger = (output, format) match {
      case (LogOutput.Stdout, LogFormat.JSON) => consoleJsonLogger(config)
      case (LogOutput.Stdout, _)              => consoleLogger(config)
      case (LogOutput.Syslog, LogFormat.JSON) => LoggerLayers.syslogJsonLogger(config, cfg.syslog)
      case (LogOutput.Syslog, _)              => LoggerLayers.syslogLogger(config, cfg.syslog)
    }
    logger >+> Slf4jBridge.init(config.toFilter)
  }

  def loggerDefault(cfg: LogConfiguration) = {
    Runtime.removeDefaultLoggers >>> loggerForOutput(cfg)
  }

  def getLogger(id: String): NamedLogger = NamedLogger(id)
}
