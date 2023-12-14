package com.cloudant.ziose.otp

import zio.Config
import zio.config.magnolia.deriveConfig

final case class OTPNodeConfig(name: String, domain: String, cookie: String)

object OTPNodeConfig {
  val config: Config[OTPNodeConfig] = deriveConfig[OTPNodeConfig]
}
