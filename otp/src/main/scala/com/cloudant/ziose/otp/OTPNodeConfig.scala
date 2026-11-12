package com.cloudant.ziose.otp

import com.cloudant.ziose.macros.CheckEnv
import zio.{Duration, durationInt}

final case class OTPNodeConfig(
  name: String = "clouseau1",
  domain: String = "127.0.0.1",
  cookie: String = OTPCookie.findOrGenerateCookie,
  ping_timeout: Duration = 1.seconds,
  ping_interval: Duration = 60.seconds
) {
  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"name=$name",
    s"domain=$domain",
    s"cookie=****",
    s"ping_timeout=$ping_timeout",
    s"ping_interval=$ping_interval"
  )
}
