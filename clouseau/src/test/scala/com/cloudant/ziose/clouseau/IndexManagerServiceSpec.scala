/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.IndexManagerServiceSpec'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.Codec.{EAtom, ETuple}
import com.cloudant.ziose.core.MessageEnvelope.makeCall
import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import java.io.File
import com.cloudant.ziose.core._
import com.cloudant.ziose.otp.OTPNodeConfig
import com.cloudant.ziose.scalang.ProcessLike.NodeName
import com.cloudant.ziose.scalang.{Adapter, Pid, ServiceContext}
import zio.test._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner
import zio.test.Assertion.{anything, equalTo, forall, hasSameElements, isSome, isSubtype}

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

  type TestContext = EngineWorker & Node & ActorFactory & OTPNodeConfig

  val startIndexManager: ZIO[TestContext, Throwable, AddressableActor[IndexManagerService, _ <: ProcessContext]] = {
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

  def callIndexManager(manager: AddressableActor[_, _], msg: Any): ZIO[Node, Node.Error, Option[Unit]] = {
    val getFirstResponse = (_: MessageEnvelope.Response) => Some(())
    callIndexManager(manager, msg, getFirstResponse)
  }

  def callIndexManager[A](
    manager: AddressableActor[_, _],
    msg: Any,
    selector: MessageEnvelope.Response => Option[A]
  ): ZIO[Node, Node.Error, Option[A]] = {
    for {
      result <- manager
        .doTestCallTimeout(adapter.fromScala(msg), 3.seconds)
        .delay(100.millis)
        .map(selector)
        .repeatUntil(_.isDefined)
        .map(_.get)
        .timeout(3.seconds)
    } yield result
  }

  def parseOpenIndexResponse(response: MessageEnvelope.Response): Option[Pid] = {
    for {
      payload <- response.payload
      pid     <- adapter.toScala(payload) match {
        case (Symbol("ok"), pid: Pid) => Some(pid)
        case _                        => None
      }
    } yield pid
  }

  def parseDeleteIndexResponse(response: MessageEnvelope.Response): Option[Symbol] = {
    for {
      payload <- response.payload
      result  <- adapter.toScala(payload) match {
        case sym @ Symbol("ok") => Some(sym)
        case _                  => None
      }
    } yield result
  }

  def stopIndexManager(manager: AddressableActor[_, _]): ZIO[Node, Node.Error, Unit] = {
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
  ): ZIO[Node, Node.Error, Option[Pid]] =
    callIndexManager(manager, ('open, peer.self.pid, path, options.toMap), parseOpenIndexResponse)

  def diskSize(
    path: String
  ): ZIO[EngineWorker & Node & ActorFactory & OTPNodeConfig, Throwable, Option[List[(NodeName, Long)]]] = {
    def parseSizeInfo(response: MessageEnvelope.Response): Option[List[(Symbol, Long)]] =
      adapter.toScala(response.payload.get) match {
        case ('ok, sizeInfo: List[_]) => Some(sizeInfo.asInstanceOf[List[(NodeName, Long)]])
        case _                        => None
      }
    for {
      manager <- startIndexManager
      result  <- callIndexManager(manager, ('disk_size, path), parseSizeInfo)
      _       <- stopIndexManager(manager)
    } yield result
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

  def openMessage(sender: PID): Codec.ETerm =
    ETuple(EAtom("open"), sender.pid, Codec.fromScala("SOMEPATH"), Codec.fromScala("standard"))

  val analyzerOptions = AnalyzerOptions.fromAnalyzerName("standard")

  val indexManagerSuite: Spec[Any, Throwable] = {
    suite("index manager")(
      test("opens an index when asked")(
        for {
          peer    <- startPeer
          manager <- startIndexManager
          pid     <- openIndex(manager, peer, "foo", analyzerOptions)
          _       <- stopActor(peer)
          _       <- stopIndexManager(manager)
        } yield assertTrue(pid.isDefined)
      ),
      test("opens an index atomically")(
        for {
          peer   <- startPeer
          node   <- ZIO.service[Node]
          engine <- ZIO.service[EngineWorker]
          _      <- engine.unregister(peer.self)
          collector = new MessageCollector(peer.self)
          _       <- engine.register(collector)
          manager <- startIndexManager
          newRef  <- node.makeRef()
          Some(openCall) = makeCall(
            manager.self,
            ETuple(peer.self.pid, newRef),
            openMessage(peer.self),
            None
          )
          _ <- manager.onMessage(openCall)
          _ <- manager.onMessage(openCall)
          _ <- stopActor(peer)
          _ <- stopIndexManager(manager)
        } yield {
          val messages = collector.capturedMessages
          assertTrue(messages.size == 2) &&
          assert(messages)(forall(isSubtype[MessageEnvelope.Response](anything))) &&
          assert(messages.map(m => parseOpenIndexResponse(m.asInstanceOf[MessageEnvelope.Response])))(forall(isSome)) &&
          assert(messages.tail)(forall(equalTo(messages.head)))
        }
      ),
      test("returns the same index if it's already open")(
        for {
          peer    <- startPeer
          manager <- startIndexManager
          pid1    <- openIndex(manager, peer, "foo", analyzerOptions)
          pid2    <- openIndex(manager, peer, "foo", analyzerOptions)
          _       <- stopActor(peer)
          _       <- stopIndexManager(manager)
        } yield assertTrue(pid1.isDefined, pid2.isDefined) &&
          assert(pid1)(equalTo(pid2))
      ),
      test("correctly reports index is open when asked to delete it") {
        def pidOrOk(resp: MessageEnvelope.Response): Option[String] =
          parseOpenIndexResponse(resp).map(_ => "pid").orElse(parseDeleteIndexResponse(resp).map(_ => "ok"))
        for {
          peer   <- startPeer
          node   <- ZIO.service[Node]
          engine <- ZIO.service[EngineWorker]
          _      <- engine.unregister(peer.self)
          collector = new MessageCollector(peer.self)
          _       <- engine.register(collector)
          manager <- startIndexManager
          newRef  <- node.makeRef()
          Some(openCall) = makeCall(
            manager.self,
            ETuple(peer.self.pid, newRef),
            openMessage(peer.self),
            None
          )
          _ <- manager.onMessage(openCall)
          Some(deleteCall) = makeCall(
            manager.self,
            ETuple(peer.self.pid, newRef),
            ETuple(EAtom("delete"), Codec.fromScala("SOMEPATH")),
            None
          )
          _ <- manager.onMessage(deleteCall)
          _ <- stopActor(peer)
          _ <- stopIndexManager(manager)
        } yield {
          val messages = collector.capturedMessages
          assertTrue(messages.size == 2) &&
          assert(messages)(forall(isSubtype[MessageEnvelope.Response](anything))) &&
          assert(messages.map(m => pidOrOk(m.asInstanceOf[MessageEnvelope.Response])))(
            hasSameElements(List(Some("pid"), Some("ok")))
          )
        }
      }
    ).provideLayer(environment) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  val diskSizeSuite: Spec[Any, Throwable] = {
    suite("disk size service")(
      test("return 0 for (db) when index directory is missing")(
        for {
          result <- diskSize("foo.1234567890")
        } yield assertTrue(
          result.isDefined,
          result.get.equals(List(('disk_size, 0L)))
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
