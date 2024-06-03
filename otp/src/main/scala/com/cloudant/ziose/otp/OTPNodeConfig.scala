package com.cloudant.ziose.otp

import com.cloudant.ziose.macros.checkEnv
import zio.Config
import zio.config.magnolia.deriveConfig

final case class OTPNodeConfig(name: String, domain: String, cookie: String) {
  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"name=$name",
    s"domain=$domain",
    s"cookie=$cookie"
  )
}

object OTPNodeConfig {
  val config: Config[OTPNodeConfig] = deriveConfig[OTPNodeConfig]
}
