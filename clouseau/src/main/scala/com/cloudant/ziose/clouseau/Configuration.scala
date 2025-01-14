package com.cloudant.ziose.clouseau

import com.cloudant.ziose.macros.CheckEnv
import zio.{Config, LogLevel}
import _root_.com.cloudant.ziose.otp
import otp.OTPNodeConfig
import zio.config.magnolia.deriveConfig
import zio.Config.Error
import zio.config.magnolia.DeriveConfig

sealed abstract class LogOutput
sealed abstract class LogFormat

object LogOutput {
  final case object Stdout extends LogOutput
  final case object Syslog extends LogOutput
}

object LogFormat {
  final case object PlainText extends LogFormat
  final case object JSON      extends LogFormat
}

final case class WorkerConfiguration(node: OTPNodeConfig, clouseau: Option[ClouseauConfiguration])
final case class LogConfiguration(
  output: Option[LogOutput],
  format: Option[LogFormat],
  level: Option[LogLevel],
  syslog: Option[SyslogConfiguration]
)

object LogConfiguration {
  implicit val logLevelDescriptor: DeriveConfig[LogLevel] = {
    DeriveConfig[String].mapOrFail(readLogLevel)
  }

  def readLogLevel(value: String): Either[Error, LogLevel] = {
    value.toUpperCase match {
      case "ALL"     => Right(LogLevel.All)
      case "FATAL"   => Right(LogLevel.Fatal)
      case "ERROR"   => Right(LogLevel.Error)
      case "WARNING" => Right(LogLevel.Warning)
      case "INFO"    => Right(LogLevel.Info)
      case "DEBUG"   => Right(LogLevel.Debug)
      case "TRACE"   => Right(LogLevel.Trace)
      case "NONE"    => Right(LogLevel.None)
      case _ =>
        Left(Error.InvalidData(message = "LogLevel must be one of ALL|FATAL|ERROR|WARNING|INFO|DEBUG|TRACE|NONE"))
    }
  }
}

object AppConfiguration {
  val config: Config[WorkerConfiguration] = deriveConfig[WorkerConfiguration]
  val logger: Config[LogConfiguration]    = deriveConfig[LogConfiguration]
}

final case class RootDir(value: String) extends AnyVal

final case class ClouseauConfiguration(
  dir: Option[RootDir] = None,
  search_allowed_timeout_msecs: Option[Long] = None,
  count_fields: Option[Boolean] = None,
  count_locks: Option[Boolean] = None,
  close_if_idle: Option[Boolean] = None,
  idle_check_interval_secs: Option[Int] = None,
  max_indexes_open: Option[Int] = None,
  field_cache_metrics: Option[Boolean] = None,
  commit_interval_secs: Option[Int] = None,
  lock_class: Option[String] = None,
  dir_class: Option[String] = None
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
    case "clouseau.idle_check_interval_secs" => idle_check_interval_secs.getOrElse(default)
    case "clouseau.max_indexes_open"         => max_indexes_open.getOrElse(default)
    case "commit_interval_secs"              => commit_interval_secs.getOrElse(default)
    case _                                   => throw new Exception(s"Unexpected Int key '$key'")
  }
  def getLong(key: String, default: Long) = key match {
    case "clouseau.search_allowed_timeout_msecs" => search_allowed_timeout_msecs.getOrElse(default)
    case _                                       => throw new Exception(s"Unexpected Long key '$key'")
  }
  def getBoolean(key: String, default: Boolean) = key match {
    case "clouseau.count_fields"  => count_fields.getOrElse(default)
    case "clouseau.count_locks"   => count_locks.getOrElse(default)
    case "clouseau.close_if_idle" => close_if_idle.getOrElse(default)
    case "field_cache_metrics"    => field_cache_metrics.getOrElse(default)
    case _                        => throw new Exception(s"Unexpected Boolean key '$key'")
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
    s"max_indexes_open=$max_indexes_open",
    s"field_cache_metrics=$field_cache_metrics",
    s"commit_interval_secs=$commit_interval_secs"
  )
}

object ClouseauConfiguration {
  val config: Config[ClouseauConfiguration] = deriveConfig[ClouseauConfiguration]
}

case class Configuration(clouseau: ClouseauConfiguration, workers: OTPNodeConfig) {
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

object SyslogConfiguration {
  val config: Config[SyslogConfiguration] = deriveConfig[SyslogConfiguration]
}
