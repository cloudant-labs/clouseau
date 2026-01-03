/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.IndexCleanupServiceSpec'
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
class IndexCleanupServiceSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val environment   = Utils.testEnvironment(1, 1, "IndexCleanup") ++ Utils.logger
  val adapter       = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  val WAIT_DURATION = 1.second

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

  def runCleanupService(msg: Any) = {
    for {
      node   <- Utils.clouseauNode
      worker <- ZIO.service[EngineWorker]
      cfg    <- Utils.defaultConfig
      val ctx = new ServiceContext[ConfigurationArgs] { val args = ConfigurationArgs(cfg) }
      cleanup <- node.spawnServiceZIO[IndexCleanupService, ConfigurationArgs](
        IndexCleanupServiceBuilder.make(node, ctx)
      )
      _ <- cleanup.doTestCast(adapter.fromScala(msg))
      _ <- ZIO.sleep(WAIT_DURATION)
      _ <- cleanup.exit(adapter.fromScala('normal))
    } yield ()
  }

  val indexCleanupSuite: Spec[Any, Throwable] = {
    suite("index cleanup service")(
      test("rename index when database is deleted")(
        for {
          _ <- runCleanupService('rename, "foo.1234567890")
          subdirList <- ZIO.attempt(
            indexDir.listFiles.filter(file => file.getName contains ".deleted")
          )
        } yield assertTrue(subdirList.length > 0)
      )
    ).provideLayer(environment) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("IndexCleanupServiceSpec")(
      indexCleanupSuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.IndexCleanupServiceSpecMain
 * ```
 */
object IndexCleanupServiceSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("IndexCleanupServiceSpec", new IndexCleanupServiceSpec().spec)
  }
}
