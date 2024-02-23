/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.Main'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.{ActorFactory, AddressableActor, EngineWorker, Node}
import com.cloudant.ziose.otp.{OTPActorFactory, OTPEngineWorker, OTPNode, OTPNodeConfig}
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, consoleLogger}
import zio.{&, ConfigProvider, IO, RIO, Runtime, System, Task, ZIO, ZIOAppDefault}

import java.io.FileNotFoundException

object Main extends ZIOAppDefault {
  final case class NodeCfg(config: List[AppConfiguration])

  def getConfig(path: String): IO[FileNotFoundException, NodeCfg] = {
    ConfigProvider
      .fromHoconFilePath(path)
      .load(deriveConfig[NodeCfg])
      .orElseFail(new FileNotFoundException(s"File Not Found: $path"))
  }

  def getNodeIdx: Task[Int] = {
    for {
      prop <- System.property("node")
      lastChar = prop.getOrElse("1").last
      index = {
        if (('1' to '3').contains(lastChar)) {
          lastChar - '1'
        } else {
          0
        }
      }
    } yield index
  }

  private def startCoordinator(
    node: ClouseauNode,
    config: AppConfiguration
  ): RIO[EngineWorker & Node & ActorFactory, AddressableActor[_, _]] = {
    val clouseauCfg: ClouseauConfiguration = config.clouseau.get
    val nodeCfg: OTPNodeConfig             = config.node

    EchoService.start(node, "coordinator", Configuration(clouseauCfg, nodeCfg))
  }

  private def main(nodesCfg: NodeCfg): RIO[EngineWorker & Node & ActorFactory, Unit] = {
    for {
      runtime  <- ZIO.runtime[EngineWorker & Node & ActorFactory]
      otp_node <- ZIO.service[Node]
      nodeCfg     = nodesCfg.config.head
      remote_node = s"node${nodeCfg.node.name.last}@${nodeCfg.node.domain}"
      _      <- otp_node.monitorRemoteNode(remote_node)
      worker <- ZIO.service[EngineWorker]
      node   <- ZIO.succeed(new ClouseauNode(worker)(runtime))
      _      <- startCoordinator(node, nodeCfg)
      _      <- worker.awaitShutdown
    } yield ()
  }

  private val workerId: Int = 1
  private val engineId: Int = 1

  private val app: Task[Unit] = {
    for {
      nodeIdx  <- getNodeIdx
      nodesCfg <- getConfig("app.conf")
      node = nodesCfg.config(nodeIdx).node
      name = s"${node.name}@${node.domain}"
      _ <- ZIO
        .scoped(main(nodesCfg))
        .provide(
          OTPActorFactory.live(name, node),
          OTPNode.live(name, engineId, workerId, node),
          OTPEngineWorker.live(engineId, workerId, name, node)
        )
    } yield ()
  }

  private val logger = Runtime.removeDefaultLoggers >>>
    consoleLogger(ConsoleLoggerConfig(LogFormat.colored, LogFilter.acceptAll))

  def run: IO[Any, Unit] = ZIO.scoped(app).provide(logger)
}
