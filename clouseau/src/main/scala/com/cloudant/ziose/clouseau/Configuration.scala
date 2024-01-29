package com.cloudant.ziose.clouseau

import zio.Config
import com.cloudant.ziose.otp.OTPNodeConfig
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
)

object ClouseauConfiguration {
  val config: Config[ClouseauConfiguration] = deriveConfig[ClouseauConfiguration]
}

final case class Configuration(clouseau: ClouseauConfiguration, workers: OTPNodeConfig)

final case class ConfigurationArgs(config: Configuration)
