package com.cloudant.ziose.otp

import com.cloudant.ziose.macros.CheckEnv
import zio.{Config, Duration, durationInt}
import zio.config.magnolia.deriveConfig

final case class OTPNodeConfig(
  name: String,
  domain: String,
  cookie: Option[String],
  ping_timeout: Option[Duration],
  ping_interval: Option[Duration]
) {
  val DEFAULT_PING_TIMEOUT: Duration  = 1.seconds
  val DEFAULT_PING_INTERVAL: Duration = 60.seconds

  val cookieVal: String         = cookie.getOrElse(OTPCookie.findOrGenerateCookie)
  val pingTimeoutVal: Duration  = ping_timeout.getOrElse(DEFAULT_PING_TIMEOUT)
  val pingIntervalVal: Duration = ping_interval.getOrElse(DEFAULT_PING_INTERVAL)

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"name=$name",
    s"domain=$domain",
    s"cookie=****",
    s"ping_timeout=$pingTimeoutVal",
    s"ping_interval=$pingIntervalVal"
  )
}

object OTPNodeConfig {
  val config: Config[OTPNodeConfig] = deriveConfig[OTPNodeConfig]
}
