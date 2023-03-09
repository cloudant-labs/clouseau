package com.cloudant.zio.actors

import _root_.zio.config._
import _root_.zio.Config
import _root_.zio.config.typesafe._

private[actors] object ActorsConfig {
  final case class Node(value: String) extends AnyVal
  final case class Cookie(value: String)    extends AnyVal
  final case class RemoteConfig(node: Node, cookie: Cookie)
}
