/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.IndexManagerServiceSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import java.io.File

import com.cloudant.ziose.core._
import com.cloudant.ziose.scalang.{Adapter, ServiceContext}
import zio.test._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class IndexManagerServiceSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val environment   = Utils.testEnvironment(1, 1, "IndexManager") ++ Utils.logger
  val adapter       = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  val indexDir = new File("target", "indexes")

  if (indexDir.exists) {
    for (f <- indexDir.listFiles) {
      f.delete
    }
  }

  val foodir = new File(indexDir, "foo.1234567890")

  if (!foodir.exists) {
    foodir.mkdirs
  }

  val foo2dir = new File(indexDir, "foo.0987654321")

  if (!foo2dir.exists) {
    foo2dir.mkdirs
  }

  val foo2indexDir = new File(new File(indexDir, "foo.0987654321"), "5838a59330e52227a58019dc1b9edd6e")

  if (!foo2indexDir.exists) {
    foo2indexDir.mkdirs
  }

  val startIndexManager = {
    for {
      node   <- Utils.clouseauNode
      worker <- ZIO.service[EngineWorker]
      cfg    <- Utils.defaultConfig
      val ctx = new ServiceContext[ConfigurationArgs] { val args = ConfigurationArgs(cfg) }
      service <- node.spawnServiceZIO[IndexManagerService, ConfigurationArgs](
        IndexManagerServiceBuilder.make(node, ctx)
      )
    } yield service
  }

  def callIndexManager(manager: AddressableActor[_, _], msg: Any) = {
    for {
      result <- manager
        .doTestCallTimeout(adapter.fromScala(msg), 3.seconds)
        .delay(100.millis)
        .repeatUntil(_.isSuccess)
        .map(result => result.payload.get)
        .timeout(3.seconds)
    } yield result
  }

  def stopIndexManager(manager: AddressableActor[_, _]) = {
    for {
      _ <- callIndexManager(manager, 'close_lru)
      _ <- manager.exit(adapter.fromScala('normal))
    } yield ()
  }

  def openIndex(
    manager: AddressableActor[_, _],
    peer: AddressableActor[_, _],
    path: String,
    options: AnalyzerOptions
  ) = {
    for {
      result <- callIndexManager(manager, ('open, peer.self.pid, path, options.toMap))
    } yield (result match {
      case Some(value) =>
        adapter.toScala(value) match {
          case ('ok, pid) => Some(pid)
          case _          => None
        }
      case _ => None
    })
  }

  def diskSize(path: String) = {
    for {
      manager <- startIndexManager
      result  <- callIndexManager(manager, ('disk_size, path))
      _       <- stopIndexManager(manager)
    } yield (result match {
      case Some(value) =>
        adapter.toScala(value) match {
          case ('ok, sizeInfo) => Some(sizeInfo)
          case _               => None
        }
      case _ => None
    })
  }

  val startPeer = {
    for {
      node <- Utils.clouseauNode
      peer <- TestService.start(node, "dummy")
    } yield peer.actor
  }

  def stopActor(actor: AddressableActor[_, _]) = {
    for {
      _ <- actor.exit(adapter.fromScala('normal))
    } yield ()
  }

  val analyzerOptions = AnalyzerOptions.fromAnalyzerName("standard")

  val indexManagerSuite: Spec[Any, Throwable] = {
    suite("index manager")(
      test("open an index when asked")(
        for {
          peer    <- startPeer
          manager <- startIndexManager
          pid     <- openIndex(manager, peer, "foo", analyzerOptions)
          _       <- stopActor(peer)
          _       <- stopIndexManager(manager)
        } yield assertTrue(pid.isDefined)
      ),
      test("return the same index if it's already open")(
        for {
          peer    <- startPeer
          manager <- startIndexManager
          pid1    <- openIndex(manager, peer, "foo", analyzerOptions)
          pid2    <- openIndex(manager, peer, "foo", analyzerOptions)
          _       <- stopActor(peer)
          _       <- stopIndexManager(manager)
        } yield assertTrue(
          pid1.isDefined,
          pid2.isDefined,
          pid1.get == pid2.get
        )
      )
    ).provideLayer(environment) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  val diskSizeSuite: Spec[Any, Throwable] = {
    suite("disk size service")(
      test("return 0 for (db) when index directory is missing")(
        for {
          result <- diskSize("foo.1234567890")
        } yield assertTrue(
          result.isDefined,
          result.get.equals(List(('disk_size, 0)))
        )
      ),
      test("return 0 for (db/index) when index directory is missing")(
        for {
          result <- diskSize("foo.1234567890/5838a59330e52227a58019dc1b9edd6e")
        } yield assertTrue(
          result.isDefined,
          result.get.equals(List(('disk_size, 0)))
        )
      ),
      test("should not return 0 for (db) when index directory is not missing")(
        for {
          result <- diskSize("foo.0987654321")
        } yield assertTrue(
          result.isDefined,
          !result.get.equals(List(('disk_size, 0)))
        )
      ),
      test("return 0 for (db/index) when index directory is not missing but empty")(
        for {
          result <- diskSize("foo.0987654321/5838a59330e52227a58019dc1b9edd6e")
        } yield assertTrue(
          result.isDefined,
          result.get.equals(List(('disk_size, 0)))
        )
      )
    ).provideLayer(environment) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("IndexManagerServiceSpec")(
      indexManagerSuite,
      diskSizeSuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.IndexManagerServiceSpecMain
 * ```
 */
object IndexManagerServiceSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("IndexManagerServiceSpec", new IndexManagerServiceSpec().spec)
  }
}
