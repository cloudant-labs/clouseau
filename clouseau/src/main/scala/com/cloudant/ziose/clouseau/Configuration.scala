package com.cloudant.ziose.clouseau

import _root_.zio.Config
import com.cloudant.ziose.otp.OTPNodeConfig

final case class RootDir(value: String) extends AnyVal

case class AppConfiguration(node: OTPNodeConfig, clouseau: Option[ClouseauConfiguration])
object AppConfiguration {
  val config: Config[AppConfiguration] =
    (OTPNodeConfig.config.nested("node") ++ ClouseauConfiguration.config.nested("clouseau").optional).map { case (node, clouseau) => AppConfiguration(node, clouseau) }
}

case class ClouseauConfiguration(
  dir: Option[RootDir],
  search_allowed_timeout_msecs: Option[Int],
  count_fields: Option[Boolean],
  close_if_idle: Option[Boolean],
  idle_check_interval_secs: Option[Int],
  max_indexes_open: Option[Int],
  field_cache_metrics: Option[Boolean],
  commit_interval_secs: Option[Int]
)

object ClouseauConfiguration {
  val config: Config[ClouseauConfiguration] =
    (
      Config.string("dir").optional ++
      Config.int("search_allowed_timeout_msecs").optional ++
      Config.boolean("count_fields").optional ++
      Config.boolean("close_if_idle").optional ++
      Config.int("idle_check_interval_secs").optional ++
      Config.int("max_indexes_open").optional ++
      Config.boolean("field_cache_metrics").optional ++
      Config.int("commit_interval_secs").optional
    ).map {
      case (dir, timeout, count, close, idle, max, cache, commit) => ClouseauConfiguration(
        dir.map(RootDir), timeout, count, close, idle, max, cache, commit
      )
    }
 }

case class Configuration(clouseau: ClouseauConfiguration, workers: OTPNodeConfig)

case class ConfigurationArgs(config: Configuration)
