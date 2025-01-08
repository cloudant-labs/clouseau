/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.SendEverySpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.Assertion._

import com.cloudant.ziose.core
import com.cloudant.ziose.scalang.{Adapter, Pid, Service, ServiceContext, SNode, PidSend}
import zio.test._
import zio.test.TestAspect
import com.cloudant.ziose.clouseau.helpers.Asserts.containsShapeOption

class SendEveryService(ctx: ServiceContext[None.type])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  var calledArgs: List[Product2[String, Any]] = List()
  var counter: Int                            = 0

  override def handleInfo(request: Any): Unit = {
    request match {
      case Symbol("increment") =>
        val counterBefore = counter
        counter += 1
        val counterAfter = counter
        calledArgs ::= ("handleInfo", (counterBefore, counterAfter))
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case Symbol("counter") =>
        calledArgs ::= ("handleCall", "counter")
        (Symbol("reply"), adapter.fromScala(counter))
      case (Symbol("start"), interval: Int) =>
        calledArgs ::= ("handleCall", ("start", interval))
        sendEvery(self.pid, Symbol("increment"), interval.toLong)
        (Symbol("reply"), Symbol("ok"))
      case Symbol("kill") =>
        calledArgs ::= ("handleCall", "kill")
        exit("normal")
      case Symbol("collect") =>
        (Symbol("reply"), calledArgs)
    }
  }
}

private object SendEveryService extends core.ActorConstructor[SendEveryService] {
  private def make(
    node: SNode,
    name: String,
    service_context: ServiceContext[None.type]
  ): core.ActorBuilder.Builder[SendEveryService, core.ActorBuilder.State.Spawnable] = {
    def maker[PContext <: core.ProcessContext](process_context: PContext): SendEveryService = {
      new SendEveryService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    core
      .ActorBuilder()
      // TODO get capacity from config
      .withCapacity(16)
      .withName(name)
      .withMaker(maker)
      .build(this)
  }

  def startZIO(
    node: SNode,
    name: String
  ): ZIO[core.EngineWorker & core.Node & core.ActorFactory, core.Node.Error, core.AddressableActor[_, _]] = {
    val ctx: ServiceContext[None.type] = {
      new ServiceContext[None.type] {
        val args: None.type = None
      }
    }

    node.spawnServiceZIO[SendEveryService, None.type](make(node, name, ctx))
  }

  def history(actor: core.AddressableActor[_, _]): ZIO[core.Node, _ <: core.Node.Error, Option[List[Any]]] = {
    actor
      .doTestCallTimeout(core.Codec.EAtom("collect"), 3.seconds)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => core.Codec.toScala(result.payload.get).asInstanceOf[List[Any]])
      .timeout(3.seconds)
  }

  def waitNEvents(
    actor: core.AddressableActor[_, _],
    n: Int
  ): ZIO[core.Node, _ <: core.Node.Error, Option[List[Any]]] = {
    history(actor)
      .repeatUntil(h => h.isDefined && h.get.length >= n)
      .timeout(3.seconds)
      .map(_.flatten)
  }

  def getCounter(actor: core.AddressableActor[_, _]) = {
    val payload = core.Codec.EAtom("counter")
    actor
      .doTestCallTimeout(payload, 3.seconds)
      .map(response => core.Codec.toScala(response.payload.get))
      .asInstanceOf[UIO[Int]]
  }

  def startCounting(actor: core.AddressableActor[_, _]) = {
    val payload = core.Codec.ETuple(core.Codec.EAtom("start"), core.Codec.ENumber(1))
    actor
      .doTestCallTimeout(payload, 3.seconds)
      .unit
  }

  def killActor(actor: core.AddressableActor[_, _]) = {
    val payload = core.Codec.EAtom("kill")
    actor
      .doTestCallTimeout(payload, 3.seconds)
      .unit
  }
}

@RunWith(classOf[ZTestJUnitRunner])
class SendEverySpec extends JUnitRunnableSpec {
  def dummyCaller(actorName: String) = core.Name(core.Codec.EAtom("test"), 1, Symbol(actorName))
  val TIMEOUT                        = 2.seconds
  val WAIT_DURATION                  = 500.milliseconds

  val handleCallSuite: Spec[Any, Throwable] = {
    suite("sendEvery handleCall suite")(
      test("Send `counter` should return 0")(
        for {
          node <- Utils.clouseauNode
          actorName = "SendEveryHandleCallSuite.SendCounter"
          actor   <- SendEveryService.startZIO(node, actorName)
          counter <- SendEveryService.getCounter(actor)
        } yield assert(counter)(equalTo(0))
          ?? "initial value of the counter suppose to be 0"
      ),
      test("Send `increment` counter should change by 1")(
        for {
          node <- Utils.clouseauNode
          actorName = "SendEveryHandleCallSuite.SendIncrement"
          actor         <- SendEveryService.startZIO(node, actorName)
          counterBefore <- SendEveryService.getCounter(actor)
          _ <- ZIO.succeed(
            node.spawn(process => {
              implicit def pid2sendable(pid: core.PID): PidSend = new PidSend(pid, process)

              val actorPID: core.PID = actor.self
              actorPID ! core.Codec.EAtom("increment")
            })
          )
          _            <- SendEveryService.waitNEvents(actor, 2)
          counterAfter <- SendEveryService.getCounter(actor)
        } yield assert(counterBefore)(equalTo(0))
          ?? "initial value of the counter suppose to be 0"
          && assert(counterAfter)(equalTo(1))
          ?? "value of a counter should increment and be equal to 1"
      ),
      test("Send `start`, `counter` should greater than 0")(
        for {
          node <- Utils.clouseauNode
          actorName = "SendEveryHandleCallSuite.SendStart"
          actor   <- SendEveryService.startZIO(node, actorName)
          _       <- SendEveryService.startCounting(actor)
          _       <- ZIO.sleep(200.milliseconds) // we count every millisecond, 200 should be enough
          counter <- SendEveryService.getCounter(actor)
        } yield assert(counter)(isGreaterThan(0))
          ?? "value of a counter should be incrementing"
      ),
      test("Send `kill` should not affect other fibers")(
        for {
          node <- Utils.clouseauNode
          actorName1 = "SendEveryService1"
          actorName2 = "SendEveryService2"
          actor1      <- SendEveryService.startZIO(node, actorName1)
          actor2      <- SendEveryService.startZIO(node, actorName2)
          _           <- SendEveryService.startCounting(actor1)
          _           <- SendEveryService.startCounting(actor2)
          _           <- ZIO.sleep(200.milliseconds) // we count every millisecond, 200 should be enough
          counter1    <- SendEveryService.getCounter(actor1)
          counter2    <- SendEveryService.getCounter(actor2)
          _           <- ZIO.sleep(200.milliseconds)
          _           <- SendEveryService.killActor(actor1)
          _           <- ZIO.sleep(200.milliseconds)
          counter2now <- SendEveryService.getCounter(actor2)
        } yield assert(counter1)(isGreaterThan(0))
          ?? "value of the counter1 should be incrementing"
          && assert(counter2)(isGreaterThan(0))
          ?? "value of the counter2 should be incrementing"
          && assert(counter2now)(isGreaterThan(counter2))
          ?? "value of a counter2 should continue incrementing"
      ),
      test("Kill parent fiber should only stop sendEvery()")(
        for {
          node <- Utils.clouseauNode
          actorName = "SendEveryHandleCallSuite.KillParent"
          actor          <- SendEveryService.startZIO(node, actorName)
          counter        <- SendEveryService.getCounter(actor)
          startChannel   <- Queue.bounded[Unit](1)
          unleashChannel <- Queue.bounded[Unit](1)
          _ <- ZIO.attemptBlocking(node.spawn(process => {
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.run(startChannel.take)
            }
            process.sendEvery(actor.self.pid, Symbol("increment"), 100)
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.run(unleashChannel.take)
            }
          }))
          _                 <- startChannel.offer(())
          _                 <- ZIO.sleep(1.second)
          counterAfterStart <- SendEveryService.getCounter(actor)
          _                 <- unleashChannel.offer(())
          _                 <- ZIO.sleep(1.second)
          counterAfterDelay <- SendEveryService.getCounter(actor)
          _                 <- ZIO.sleep(1.second)
          counterNow        <- SendEveryService.getCounter(actor)
        } yield assert(counter)(equalTo(0))
          ?? "initial value of the counter suppose to be 0"
          && assert(counterAfterStart)(isGreaterThan(0))
          ?? "current value of the counter suppose to be greater than 0"
          && assert(counterAfterDelay)(equalTo(counterNow))
          ?? "the counter should stop incrementing"
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "SendEveryHandleCallSuite")
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  val handleInfoSuite: Spec[Any, Throwable] = {
    suite("sendEvery handleInfo suite")(
      test("Send `increment` counter should change by 1")(
        for {
          node  <- Utils.clouseauNode
          actor <- SendEveryService.startZIO(node, "SendEveryHandleInfoSuite.SendIncrement")
          _ <- ZIO.succeed(node.spawn(process => {
            implicit def pid2sendable(pid: core.PID): PidSend = new PidSend(pid, process)

            val actorPID: core.PID = actor.self
            actorPID ! core.Codec.EAtom("increment")
          }))
          history <- SendEveryService
            .history(actor)
            .repeatUntil(h => h.isDefined && h.get.nonEmpty)
            .timeout(TIMEOUT)
            .map(_.flatten)
        } yield assert(history)(isSome) ?? "history should be available"
          && assert(history)(
            containsShapeOption { case ("handleInfo", (0, 1)) => true }
          ) ?? "has to contain elements of expected shape"
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "SendEveryHandleInfoSuite")
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("SendEverySpec")(
      handleCallSuite,
      handleInfoSuite
    ) @@ TestAspect.timeout(15.minutes)
  }
}
