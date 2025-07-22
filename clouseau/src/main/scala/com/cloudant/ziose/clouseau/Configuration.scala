package com.cloudant.ziose.clouseau

import _root_.com.cloudant.ziose.core.Exponent
import _root_.com.cloudant.ziose.macros.CheckEnv
import _root_.com.cloudant.ziose.otp.OTPNodeConfig
import zio.Config.Error
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.{Config, ConfigProvider, IO, LogLevel, ZIOAppArgs, ZLayer}

sealed abstract class LogOutput
sealed abstract class LogFormat

object LogOutput {
  final case object Stdout extends LogOutput
  final case object Syslog extends LogOutput
}

object LogFormat {
  final case object Raw  extends LogFormat
  final case object Text extends LogFormat
  final case object JSON extends LogFormat
}

final case class WorkerConfiguration(
  node: OTPNodeConfig,
  clouseau: Option[ClouseauConfiguration],
  capacity: Option[CapacityConfiguration]
)
final case class LogConfiguration(
  output: Option[LogOutput],
  format: Option[LogFormat],
  level: Option[LogLevel],
  syslog: Option[SyslogConfiguration]
)

object LogConfiguration {
  def readLogLevel(value: String): Either[Error, LogLevel] = {
    value.trim().toUpperCase match {
      case "ALL"     => Right(LogLevel.All)
      case "FATAL"   => Right(LogLevel.Fatal)
      case "ERROR"   => Right(LogLevel.Error)
      case "WARNING" => Right(LogLevel.Warning)
      case "INFO"    => Right(LogLevel.Info)
      case "DEBUG"   => Right(LogLevel.Debug)
      case "TRACE"   => Right(LogLevel.Trace)
      case "NONE"    => Right(LogLevel.None)
      case _ =>
        Left(
          Error.InvalidData(message = {
            s"LogLevel must be one of (case insensitive) ALL|FATAL|ERROR|WARNING|INFO|DEBUG|TRACE|NONE (got '${value}')"
          })
        )
    }
  }
}

final case class RootDir(value: String) extends AnyVal

final case class ClouseauConfiguration(
  dir: Option[RootDir] = None,
  search_allowed_timeout_msecs: Option[Long] = None,
  count_fields: Option[Boolean] = None,
  count_locks: Option[Boolean] = None,
  close_if_idle: Option[Boolean] = None,
  idle_check_interval_secs: Option[Int] = None,
  lru_update_interval_msecs: Option[Int] = None,
  max_indexes_open: Option[Int] = None,
  field_cache_metrics: Option[Boolean] = None,
  commit_interval_secs: Option[Int] = None,
  lock_class: Option[String] = None,
  dir_class: Option[String] = None,
  concurrent_search_enabled: Option[Boolean] = None
) {
  def getString(key: String, default: String) = key match {
    case "clouseau.dir" =>
      dir match {
        case Some(RootDir(value)) => value
        case None                 => default
      }
    case "clouseau.lock_class" => lock_class.getOrElse(default).asInstanceOf[String]
    case "clouseau.dir_class"  => dir_class.getOrElse(default).asInstanceOf[String]
    case _                     => throw new Exception(s"Unexpected String key '$key'")
  }
  def getInt(key: String, default: Int) = key match {
    case "clouseau.idle_check_interval_secs"  => idle_check_interval_secs.getOrElse(default)
    case "clouseau.lru_update_interval_msecs" => lru_update_interval_msecs.getOrElse(default)
    case "clouseau.max_indexes_open"          => max_indexes_open.getOrElse(default)
    case "commit_interval_secs"               => commit_interval_secs.getOrElse(default)
    case _                                    => throw new Exception(s"Unexpected Int key '$key'")
  }
  def getLong(key: String, default: Long) = key match {
    case "clouseau.search_allowed_timeout_msecs" => search_allowed_timeout_msecs.getOrElse(default)
    case _                                       => throw new Exception(s"Unexpected Long key '$key'")
  }
  def getBoolean(key: String, default: Boolean) = key match {
    case "clouseau.count_fields"              => count_fields.getOrElse(default)
    case "clouseau.count_locks"               => count_locks.getOrElse(default)
    case "clouseau.close_if_idle"             => close_if_idle.getOrElse(default)
    case "field_cache_metrics"                => field_cache_metrics.getOrElse(default)
    case "clouseau.concurrent_search_enabled" => concurrent_search_enabled.getOrElse(default)
    case _                                    => throw new Exception(s"Unexpected Boolean key '$key'")
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"dir=$dir",
    s"search_allowed_timeout_msecs=$search_allowed_timeout_msecs",
    s"count_fields=$count_fields",
    s"count_locks=$count_locks",
    s"close_if_idle=$close_if_idle",
    s"idle_check_interval_secs=$idle_check_interval_secs",
    s"lru_update_interval_msecs=$lru_update_interval_msecs",
    s"max_indexes_open=$max_indexes_open",
    s"field_cache_metrics=$field_cache_metrics",
    s"commit_interval_secs=$commit_interval_secs"
  )
}

case class Configuration(clouseau: ClouseauConfiguration, workers: OTPNodeConfig, capacity: CapacityConfiguration) {
  // these getters are only for compatibility with old clouseau and shouldn't be used in new code
  def getString(key: String, default: String)   = clouseau.getString(key, default)
  def getInt(key: String, default: Int)         = clouseau.getInt(key, default)
  def getLong(key: String, default: Long)       = clouseau.getLong(key, default)
  def getBoolean(key: String, default: Boolean) = clouseau.getBoolean(key, default)
}

final case class ConfigurationArgs(config: Configuration)

sealed abstract class SyslogProtocol

object SyslogProtocol {
  final case object TCP extends SyslogProtocol
  final case object UDP extends SyslogProtocol
}

final case class SyslogConfiguration(
  protocol: Option[SyslogProtocol] = None,
  host: Option[String] = None,
  port: Option[Int] = None,
  facility: Option[String] = None,
  level: Option[String] = None,
  tag: Option[String] = None
)

/**
 * A data type to hold configured capacity exponent values
 * @param analyzer_exponent
 *   An exponent to calculate capacity of the message queue used for `AnalyzerService`. Exponent must be greater than 0.
 *   If not specified backpressure wouldn't be applied.
 * @param cleanup_exponent
 *   An exponent to calculate capacity of the message queue used for `CleanupService`. Exponent must be greater than 0.
 *   If not specified backpressure wouldn't be applied.
 * @param index_exponent
 *   An exponent to calculate capacity of the message queue used for `IndexService`. Exponent must be greater than 0. If
 *   not specified backpressure wouldn't be applied.
 * @param init_exponent
 *   An exponent to calculate capacity of the message queue used for `InitService`. Exponent must be greater than 0. If
 *   not specified backpressure wouldn't be applied.
 * @param main_exponent
 *   An exponent to calculate capacity of the message queue used for `IndexManagerService`. Exponent must be greater
 *   than 0. If not specified backpressure wouldn't be applied.
 */
final case class CapacityConfiguration(
  analyzer_exponent: Option[Exponent] = None,
  cleanup_exponent: Option[Exponent] = None,
  index_exponent: Option[Exponent] = None,
  init_exponent: Option[Exponent] = None,
  main_exponent: Option[Exponent] = None
)

object CapacityConfiguration {
  def readExponent(value: Int): Either[Error, Exponent] = {
    value match {
      case 0 =>
        Left(Error.InvalidData(message = s"Exponent cannot be 0 (got '${value}')"))
      case v if v < 0 =>
        Left(Error.InvalidData(message = s"Exponent cannot be negative (got '${value}')"))
      case v if v > 16 =>
        Left(Error.InvalidData(message = s"Exponent cannot be greater than 16 (got '${value}')"))
      case _ =>
        Right(Exponent(value))
    }
  }
}

final case class AppCfg(config: List[WorkerConfiguration], logger: LogConfiguration)
object AppCfg {
  implicit val exponentDescriptor: DeriveConfig[Exponent] = {
    DeriveConfig[Int].mapOrFail(CapacityConfiguration.readExponent)
  }

  implicit val logLevelDescriptor: DeriveConfig[LogLevel] = {
    DeriveConfig[String].mapOrFail(LogConfiguration.readLogLevel)
  }

  val config: Config[AppCfg] = deriveConfig[AppCfg]

  def fromHoconFilePath(pathToCfgFile: String): IO[Config.Error, AppCfg] = {
    ConfigProvider.fromHoconFilePath(pathToCfgFile).load(config)
  }

  def fromHoconString(input: String): IO[Config.Error, AppCfg] = {
    ConfigProvider.fromHoconString(input).load(config)
  }

  private val DEFAULT_CFG: String = "app.conf"

  def layer: ZLayer[ZIOAppArgs, Config.Error, AppCfg] = {
    ZLayer {
      for {
        config <- ZIOAppArgs.getArgs
          .map(_.headOption.getOrElse(DEFAULT_CFG))
          .map(fromHoconFilePath)
        cfg <- config
      } yield cfg
    }
  }
}
