package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.Exponent
import com.cloudant.ziose.macros.CheckEnv
import com.cloudant.ziose.otp.OTPNodeConfig
import zio.Config.Error
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.{Config, ConfigProvider, IO, LogLevel, ZIOAppArgs, ZLayer}

sealed abstract class LogOutput
sealed abstract class LogFormat

object LogOutput {
  case object Stdout extends LogOutput
  case object Syslog extends LogOutput
}

object LogFormat {
  case object Raw  extends LogFormat
  case object Text extends LogFormat
  case object JSON extends LogFormat
}

final case class LogConfiguration(
  output: LogOutput = LogOutput.Stdout,
  format: LogFormat = LogFormat.Raw,
  level: LogLevel = LogLevel.Debug,
  syslog: SyslogConfiguration = SyslogConfiguration()
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
      case _         =>
        Left(
          Error.InvalidData(message = {
            s"LogLevel must be one of (case insensitive) ALL|FATAL|ERROR|WARNING|INFO|DEBUG|TRACE|NONE (got '${value}')"
          })
        )
    }
  }
}

final case class ClouseauConfiguration(
  dir: String = "target/indexes",
  search_allowed_timeout_msecs: Long = 5000,
  count_fields: Boolean = false,
  count_locks: Boolean = false,
  close_if_idle: Boolean = false,
  idle_check_interval_secs: Int = 300,
  lru_update_interval_msecs: Int = 1000,
  max_indexes_open: Int = 100,
  field_count_warn_threshold: Int = 5000,
  commit_interval_secs: Int = 30,
  lock_class: String = "org.apache.lucene.store.NativeFSLockFactory",
  dir_class: String = "org.apache.lucene.store.NIOFSDirectory",
  concurrent_search_enabled: Boolean = false,
  concurrent_search_limit: Int = 1000,
  track_index_atimes: Boolean = false
)

final case class Configuration(
  node: OTPNodeConfig,
  clouseau: ClouseauConfiguration = ClouseauConfiguration(),
  capacity: CapacityConfiguration = CapacityConfiguration()
)

sealed abstract class SyslogProtocol

object SyslogProtocol {
  case object TCP extends SyslogProtocol
  case object UDP extends SyslogProtocol
}

final case class SyslogConfiguration(
  protocol: SyslogProtocol = SyslogProtocol.UDP,
  host: String = "localhost",
  port: Int = 514,
  facility: String = "CONSOLE",
  level: String = "debug",
  tag: String = ""
)

/**
 * A data type to hold configured capacity exponent values. Exponent must be greater than 0. If not specified
 * backpressure wouldn't be applied.
 * @param analyzer_exponent
 *   An exponent to calculate capacity of the message queue used for ''AnalyzerService''.
 * @param cleanup_exponent
 *   An exponent to calculate capacity of the message queue used for ''CleanupService''.
 * @param index_exponent
 *   An exponent to calculate capacity of the message queue used for ''IndexService''.
 * @param init_exponent
 *   An exponent to calculate capacity of the message queue used for ''InitService''.
 * @param main_exponent
 *   An exponent to calculate capacity of the message queue used for ''InitService''.
 */
final case class CapacityConfiguration(
  analyzer_exponent: Option[Exponent] = None,
  cleanup_exponent: Option[Exponent] = None,
  index_exponent: Option[Exponent] = None,
  init_exponent: Option[Exponent] = None,
  main_exponent: Option[Exponent] = None
)

object CapacityConfiguration {
  def readExponent(value: Int): Either[Error, Exponent] =
    if (1 <= value && value <= 16)
      Right(Exponent(value))
    else
      Left(
        Error.InvalidData(
          message = s"Exponent must be greater than 0 and less than or equal to 16 (got '$value')"
        )
      )
}

final case class AppCfg(config: List[Configuration], logger: LogConfiguration)

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

  private val DEFAULT_CFG: String = "clouseau.conf"

  def layer: ZLayer[ZIOAppArgs, Config.Error, AppCfg] = {
    ZLayer {
      for {
        args   <- ZIOAppArgs.getArgs
        config <- fromHoconFilePath(args.headOption.getOrElse(DEFAULT_CFG))
      } yield config
    }
  }
}
