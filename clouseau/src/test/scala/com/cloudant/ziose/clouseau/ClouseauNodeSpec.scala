/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ClouseauNodeSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.{Spec, assertTrue}

import com.cloudant.ziose.core
import com.cloudant.ziose.scalang.{Adapter, Pid, Service, ServiceContext, SNode, PidSend}
import com.cloudant.ziose.otp.OTPProcessContext
import zio.test.TestAspect

class PingPongService(ctx: ServiceContext[None.type])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  var calledArgs: List[Product2[String, Any]] = List()
  override def handleInfo(request: Any): Any = {
    request match {
      case (Symbol("ping"), from: Pid, payload) => {
        calledArgs = ("handleInfo", payload) :: calledArgs
        send(from, Symbol("pong"))
      }
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("ping"), payload) =>
        calledArgs = ("handleCall", payload) :: calledArgs
        (Symbol("reply"), Symbol("pong"))
      case Symbol("reset") => {
        calledArgs = List()
        (Symbol("reply"), Symbol("ok"))
      }
      case Symbol("collect") => (Symbol("reply"), calledArgs)
    }
  }
}

private object PingPongService extends core.ActorConstructor[PingPongService] {
  private def make(
    node: SNode,
    name: String,
    service_context: ServiceContext[None.type]
  ): core.ActorBuilder.Builder[PingPongService, core.ActorBuilder.State.Spawnable] = {
    def maker[PContext <: core.ProcessContext](process_context: PContext): PingPongService = {
      new PingPongService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
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
      new ServiceContext[None.type] { val args: None.type = None }
    }
    node.spawnServiceZIO[PingPongService, None.type](make(node, name, ctx))
  }

  def history(actor: core.AddressableActor[_, _]) = {
    val historyMessage = core.MessageEnvelope.makeCall(
      core.Codec.EAtom("$gen_call"),
      actor.self.pid,
      actor.id,
      core.Codec.EAtom("collect"),
      Some(3.seconds),
      actor.id
    )
    actor.ctx
      .asInstanceOf[OTPProcessContext]
      .call(historyMessage)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => core.Codec.toScala(result.payload.get))
      .timeout(3.seconds)
  }
}

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauNodeSpec extends JUnitRunnableSpec {
  def dummyCaller(testName: String) = core.Name(core.Codec.EAtom("test"), 1, Symbol(testName))
  val TIMEOUT                       = 2.seconds
  val WAIT_DURATION                 = 500.milliseconds
  val serviceSpawnSuite: Spec[Any, Throwable] = {
    suite("serviceSpawn")(
      test("Start Echo")(
        for {
          node <- Utils.clouseauNode
          cfg  <- Utils.defaultConfig
          zio  <- EchoService.startZIO(node, "echo", cfg)
        } yield assertTrue(zio.isInstanceOf[core.AddressableActor[_, _]])
      )
    ).provideLayer(Utils.testEnvironment(1, 1, "serviceSpawn"))
  }

  val serviceCommunicationSuite: Spec[Any, Throwable] = {
    suite("service communication")(
      // This is a foundational test which uses somewhat low level access
      // to the internals. Please refrain from DRYing it.
      test("Call into service using actor reference - basic")(
        for {
          node  <- Utils.clouseauNode
          cfg   <- Utils.defaultConfig
          actor <- PingPongService.startZIO(node, "serviceCommunication.Call")
          ctx     = actor.ctx.asInstanceOf[OTPProcessContext]
          tag     = core.Codec.EAtom("$gen_call")
          payload = core.Codec.ETuple(core.Codec.EAtom("ping"), core.Codec.EAtom("something"))
          callMsg = core.MessageEnvelope.makeCall(
            tag,
            actor.self.pid,
            actor.id,
            payload,
            Some(TIMEOUT),
            dummyCaller("serviceCommunication.Call")
          )
          result <- ctx.call(callMsg)
        } yield assertTrue(
          result.isSuccess,
          result.from == Some(actor.id.asInstanceOf[core.PID].pid),
          result.tag == tag,
          result.payload.get == core.Codec.EAtom("pong"),
          result.workerId == 1
        )
      ),
      test("Call into service using actor reference - state is updated")(
        for {
          node  <- Utils.clouseauNode
          cfg   <- Utils.defaultConfig
          actor <- PingPongService.startZIO(node, "serviceCommunication.Call")
          ctx     = actor.ctx.asInstanceOf[OTPProcessContext]
          tag     = core.Codec.EAtom("$gen_call")
          payload = core.Codec.ETuple(core.Codec.EAtom("ping"), core.Codec.EAtom("something"))
          callMsg = core.MessageEnvelope.makeCall(
            tag,
            actor.self.pid,
            actor.id,
            payload,
            Some(TIMEOUT),
            dummyCaller("serviceCommunication.Call")
          )
          result  <- ctx.call(callMsg)
          history <- PingPongService.history(actor)
        } yield assertTrue(
          result.isSuccess,
          result.from == Some(actor.id.asInstanceOf[core.PID].pid),
          result.tag == tag,
          result.payload.get == core.Codec.EAtom("pong"),
          result.workerId == 1
        ) && assertTrue(
          history.isDefined,
          history.get == List(
            ("handleCall", Symbol("something"))
          )
        )
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "serviceCommunication")
    ) @@ TestAspect.withLiveClock
  }

  val processSpawnSuite: Spec[Any, Throwable] = {
    suite("processSpawn")(
      test("no longer registered after termination")(
        for {
          node            <- Utils.clouseauNode
          cfg             <- Utils.defaultConfig
          worker          <- ZIO.service[core.EngineWorker]
          actor           <- EchoService.startZIO(node, "echo", cfg)
          knownAfterStart <- worker.exchange.isKnown(actor.id)
          ctx = actor.ctx.asInstanceOf[OTPProcessContext]
          _ <- ctx.exit(core.Codec.EAtom("kill it"))
          _ <- ZIO.sleep(WAIT_DURATION)
          knownAfterKill <- worker.exchange
            .isKnown(actor.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
        } yield assertTrue(
          knownAfterStart == true,
          knownAfterKill.isDefined,
          knownAfterKill.get == false
        )
      ),
      test("spawn closure")(
        for {
          node   <- Utils.clouseauNode
          cfg    <- Utils.defaultConfig
          worker <- ZIO.service[core.EngineWorker]
          actor  <- PingPongService.startZIO(node, "processSpawn.Closure")
          _ <- ZIO.succeed(node.spawn(process => {
            // this is needed to enable `actor ! message` syntax
            // this shouldn't be required in clouseau code because
            // we only use this syntax from the service classes
            // where it would be enabled automatically
            implicit def pid2sendable(pid: core.PID): PidSend = new PidSend(pid, process)
            val actorPID                                      = actor.self
            actorPID ! core.Codec.ETuple(
              core.Codec.EAtom("ping"),
              process.self.pid,
              core.Codec.EAtom("processSpawn.Closure")
            )
          }))
          history <- PingPongService.history(actor)
        } yield assertTrue(
          history.isDefined,
          history.get == List(
            ("handleInfo", Symbol("processSpawn.Closure"))
          )
        )
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "serviceCommunication")
    ) @@ TestAspect.withLiveClock
  }

  def spec: Spec[Any, Throwable] = {
    suite("ClouseauNodeSpec")(
      serviceSpawnSuite,
      serviceCommunicationSuite,
      processSpawnSuite
    )
  }
}
