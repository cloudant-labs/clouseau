/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.Main'
 */
package com.cloudant.ziose.clouseau

import zio.config.magnolia.deriveConfig
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.{&, ConfigProvider, Console, IO, Scope, ZIO, ZIOAppArgs, ZIOAppDefault}

import java.io.FileNotFoundException

object Main extends ZIOAppDefault {
  final case class NodeCfg(config: List[AppConfiguration])

  def getConfig(path: String): IO[FileNotFoundException, NodeCfg] = {
    ConfigProvider
      .fromHoconFilePath(path)
      .load(deriveConfig[NodeCfg])
      .orElseFail(new FileNotFoundException(s"File Not Found: $path"))
  }

  def run: ZIO[Any & ZIOAppArgs & Scope, Serializable, Unit] = {
    for {
      nodes <- getConfig("app.conf")
      node1 = nodes.config.head
      node2 = nodes.config(1)
      _ <- Console.printLine(s"node2 node config: ${node2.node}")
      _ <- Console.printLine(s"node2 clouseau config: ${node2.clouseau}")
      _ <- Console.printLine(node1.clouseau.get.close_if_idle)
      _ <- Console.printLine(node1.clouseau.get.max_indexes_open)
      _ <- Console.printLine(node2.clouseau.get.dir.get)
    } yield ()
  }
}
