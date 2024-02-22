package com.cloudant.ziose.clouseau

import zio.Config
import _root_.com.cloudant.ziose.otp
import otp.OTPNodeConfig
import zio.config.magnolia.deriveConfig

final case class AppConfiguration(node: OTPNodeConfig, clouseau: Option[ClouseauConfiguration])

object AppConfiguration {
  val config: Config[AppConfiguration] = deriveConfig[AppConfiguration]
}

final case class RootDir(value: String) extends AnyVal

final case class ClouseauConfiguration(
  dir: Option[RootDir],
  search_allowed_timeout_msecs: Option[Long],
  count_fields: Option[Boolean],
  close_if_idle: Option[Boolean],
  idle_check_interval_secs: Option[Int],
  max_indexes_open: Option[Int],
  field_cache_metrics: Option[Boolean],
  commit_interval_secs: Option[Int]
) {
  def getString(key: String, default: String) = key match {
    case "clouseau.dir" => dir.getOrElse(default).asInstanceOf[String]
    case _ => throw new Exception(s"Unexpected String key '$key'")
  }
  def getInt(key: String, default: Int) = key match {
    case "clouseau.idle_check_interval_secs" => idle_check_interval_secs.getOrElse(default)
    case "clouseau.max_indexes_open" => max_indexes_open.getOrElse(default)
    case "commit_interval_secs" => commit_interval_secs.getOrElse(default)
    case _ => throw new Exception(s"Unexpected Int key '$key'")
  }
  def getLong(key: String, default: Long) = key match {
    case "clouseau.search_allowed_timeout_msecs" => search_allowed_timeout_msecs.getOrElse(default)
    case _ => throw new Exception(s"Unexpected Long key '$key'")
  }
  def getBoolean(key: String, default: Boolean) = key match {
    case "clouseau.count_fields" => count_fields.getOrElse(default)
    case "clouseau.count_locks" => count_fields.getOrElse(default)
    case "clouseau.close_if_idle" => close_if_idle.getOrElse(default)
    case "field_cache_metrics" => field_cache_metrics.getOrElse(default)
    case _ => throw new Exception(s"Unexpected Boolean key '$key'")
  }
}

object ClouseauConfiguration {
  val config: Config[ClouseauConfiguration] = deriveConfig[ClouseauConfiguration]
}

case class Configuration(clouseau: ClouseauConfiguration, workers: OTPNodeConfig) {
  // these getters are only for compatibility with old clouseau and shouldn't be used in new code
  def getString(key: String, default: String) = clouseau.getString(key, default)
  def getInt(key: String, default: Int) = clouseau.getInt(key, default)
  def getLong(key: String, default: Long) = clouseau.getLong(key, default)
  def getBoolean(key: String, default: Boolean) = clouseau.getBoolean(key, default)
}

final case class ConfigurationArgs(config: Configuration)
