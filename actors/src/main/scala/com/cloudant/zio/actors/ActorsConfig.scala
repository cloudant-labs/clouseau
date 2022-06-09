package com.cloudant.zio.actors

import zio.{ Task, ZIO }
import zio.config._
import zio.config.ConfigDescriptor._
//import zio.config.typesafe.TypesafeConfig
import zio.config.typesafe._
import zio.Tag

private[actors] object ActorsConfig {
  final case class Addr(value: String) extends AnyVal
  final case class Port(value: Int)    extends AnyVal
  final case class RemoteConfig(addr: Addr, port: Port)
}
