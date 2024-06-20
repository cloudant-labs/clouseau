package com.cloudant.ziose.otp

import com.cloudant.ziose.macros.checkEnv
import zio.Config
import zio.config.magnolia.deriveConfig

final case class OTPNodeConfig(name: String, domain: String, cookie: Option[String]) {
  val cookieVal: String = cookie.getOrElse(OTPCookie.findOrGenerateCookie)

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"name=$name",
    s"domain=$domain",
    s"cookie=****"
  )
}

object OTPNodeConfig {
  val config: Config[OTPNodeConfig] = deriveConfig[OTPNodeConfig]
}
