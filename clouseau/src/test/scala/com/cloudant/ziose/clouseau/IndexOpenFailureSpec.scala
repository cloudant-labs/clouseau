/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.IndexOpenFailureSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import com.cloudant.ziose.core._
import com.cloudant.ziose.scalang.{Adapter, ServiceContext}
import com.cloudant.ziose.test.helpers.Asserts.hasShape
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class IndexOpenFailureSpec extends JUnitRunnableSpec {
  val environment = Utils.testEnvironment(1, 1, "IndexOpenFailure") ++ Utils.logger
  val adapter     = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  val badDirClassConfig = Utils.mkConfig(
    ClouseauConfiguration(dir_class = Some("com.cloudant.ziose.clouseau.NoSuchDirectoryClassForRepro"))
  )

  def startManager(cfg: Configuration) = {
    for {
      node <- Utils.clouseauNode
      _    <- ZIO.service[EngineWorker]
      ctx = new ServiceContext[ConfigurationArgs] { val args = ConfigurationArgs(cfg) }
      manager <- node.spawnServiceZIO[IndexManagerService, ConfigurationArgs](
        IndexManagerServiceBuilder.make(node, ctx)
      )
      peer <- TestService.start(node, "peer_open_failure")
    } yield (manager, peer.actor)
  }

  def openCall(manager: AddressableActor[_, _], peer: AddressableActor[_, _], path: String) = {
    manager
      .doTestCallTimeout(adapter.fromScala(('open, peer.self.pid, path, Map("name" -> "standard"))), 3.seconds)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(_.payload)
      .timeout(5.seconds)
  }

  val openFailureSuite: Spec[Any, Throwable] = {
    suite("IndexOpenFailureSpec suite")(
      test("an open whose directory build throws must reply an error, not strand the caller")(
        for {
          cfg <- badDirClassConfig
          mp  <- startManager(cfg)
          (manager, peer) = mp
          reply <- openCall(manager, peer, "open_failure_path")
          _     <- manager.exit(adapter.fromScala('normal))
        } yield assert(reply)(hasShape { case Some(Some(value)) =>
          adapter.toScala(value.asInstanceOf[Codec.ETerm]) match {
            case ('error, _) => true
            case _           => false
          }
        }) ?? "a failed open must reply ('error, _) within the timeout instead of leaving the caller with no reply"
      )
    ).provideLayer(environment) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds) @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("IndexOpenFailureSpec")(
      openFailureSuite
    ) @@ TestAspect.timeout(5.minutes)
  }
}

object IndexOpenFailureSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("IndexOpenFailureSpec", new IndexOpenFailureSpec().spec)
  }
}
