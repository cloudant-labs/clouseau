package com.cloudant.ziose.otp

import _root_.zio.Config

final case class Node(value: String)   extends AnyVal
final case class Domain(value: String) extends AnyVal
final case class Cookie(value: String) extends AnyVal
final case class OTPNodeConfig(node: Node, domain: Domain, cookie: Cookie)

object OTPNodeConfig {
  val config: Config[OTPNodeConfig] = {
    (Config.string("name") ++ Config.string("domain") ++ Config.string("cookie")).map { case (node, domain, cookie) =>
      OTPNodeConfig(Node(node), Domain(domain), Cookie(cookie))
    }
  }
}
