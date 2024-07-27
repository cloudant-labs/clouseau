/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ClouseauNodeSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.{Spec, assertTrue}

import com.cloudant.ziose.core
import com.cloudant.ziose.scalang.{Adapter, Pid, Reference, Service, ServiceContext, SNode, PidSend}
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

  def history(actor: core.AddressableActor[_, _]): ZIO[core.Node, _ <: core.Node.Error, Option[List[Any]]] = {
    val historyMessage = core.MessageEnvelope.makeCall(
      core.Codec.EAtom("$gen_call"),
      actor.self.pid,
      actor.id,
      core.Codec.EAtom("collect"),
      Some(3.seconds),
      actor.id
    )
    actor.ctx
      .asInstanceOf[core.ProcessContext]
      .call(historyMessage)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => core.Codec.toScala(result.payload.get).asInstanceOf[List[Any]])
      .timeout(3.seconds)
  }
}

class MonitorService(ctx: ServiceContext[None.type])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  var downPids: List[Product3[Pid, Reference, Symbol]] = List()

  override def handleInfo(request: Any): Any = {
    request match {
      case (Symbol("DOWN"), ref: Reference, Symbol("process"), pid: Pid, reason: Symbol) =>
        downPids = (pid, ref, reason) :: downPids
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("monitor"), target: Any) =>
        val ref = {
          try {
            monitor(target)
          } catch {
            case e: core.Node.Error.NoSuchActor  => Symbol("noproc")
            case e: core.Node.Error.Disconnected => Symbol("noconnection")
            case e: core.Node.Error.Unknown      => Symbol("unknown")
          }
        }
        (Symbol("reply"), ref)
      case (Symbol("demonitor"), ref: Reference) =>
        demonitor(ref)
        (Symbol("reply"), Symbol("ok"))
      case (Symbol("link"), pid: Pid) =>
        val result = {
          try {
            link(pid)
            Symbol("ok")
          } catch {
            case e: core.Node.Error.NoSuchActor  => Symbol("noproc")
            case e: core.Node.Error.Disconnected => Symbol("noconnection")
            case e: core.Node.Error.Unknown      => Symbol("unknown")
          }
        }
        (Symbol("reply"), result)
      case (Symbol("unlink"), pid: Pid) =>
        unlink(pid)
        (Symbol("reply"), Symbol("ok"))
      case Symbol("down_pids") =>
        (Symbol("reply"), downPids)
    }
  }
}

private object MonitorService extends core.ActorConstructor[MonitorService] {
  private def make(
    node: SNode,
    name: String,
    service_context: ServiceContext[None.type]
  ): core.ActorBuilder.Builder[MonitorService, core.ActorBuilder.State.Spawnable] = {
    def maker[PContext <: core.ProcessContext](process_context: PContext): MonitorService = {
      new MonitorService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
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
    node.spawnServiceZIO[MonitorService, None.type](make(node, name, ctx))
  }

  def monitor(
    actor: core.AddressableActor[_, _],
    target: core.Codec.ETerm
  ): ZIO[core.Node, _ <: core.Node.Error, Either[Symbol, Reference]] = {
    val monitorMessage = core.MessageEnvelope.makeCall(
      core.Codec.EAtom("$gen_call"),
      actor.self.pid,
      actor.id,
      core.Codec.ETuple(core.Codec.EAtom("monitor"), target),
      Some(3.seconds),
      actor.id
    )
    for {
      result <- actor.ctx
        .asInstanceOf[core.ProcessContext]
        .call(monitorMessage)
        .delay(100.millis)
        .repeatUntil(_.isSuccess)
        .map(result => result.payload.get)
        .timeout(3.seconds)
    } yield (result match {
      case None                         => Left(Symbol("timeout"))
      case Some(atom: core.Codec.EAtom) => Left(Symbol(atom.asString))
      case Some(ref: core.Codec.ERef)   => Right(Reference.toScala(ref))
      case Some(value)                  => Left(Symbol("unknown"))
    })
  }

  def demonitor(
    actor: core.AddressableActor[_, _],
    ref: Reference
  ): ZIO[core.Node, _ <: core.Node.Error, Symbol] = {
    val monitorMessage = core.MessageEnvelope.makeCall(
      core.Codec.EAtom("$gen_call"),
      actor.self.pid,
      actor.id,
      core.Codec.ETuple(core.Codec.EAtom("demonitor"), ref.fromScala),
      Some(3.seconds),
      actor.id
    )
    actor.ctx
      .asInstanceOf[core.ProcessContext]
      .call(monitorMessage)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => core.Codec.toScala(result.payload.get))
      .timeout(3.seconds)
      .map(result => result.get.asInstanceOf[Symbol])
  }

  def linkShared(
    actor: core.AddressableActor[_, _],
    action: String,
    pid: core.Codec.EPid
  ): ZIO[core.Node, _ <: core.Node.Error, Symbol] = {
    val linkMessage = core.MessageEnvelope.makeCall(
      core.Codec.EAtom("$gen_call"),
      actor.self.pid,
      actor.id,
      core.Codec.ETuple(core.Codec.EAtom(action), pid),
      Some(3.seconds),
      actor.id
    )
    for {
      result <- actor.ctx
        .asInstanceOf[core.ProcessContext]
        .call(linkMessage)
        .delay(100.millis)
        .repeatUntil(_.isSuccess)
        .map(result => core.Codec.toScala(result.payload.get))
        .timeout(3.seconds)
    } yield (result match {
      case None              => Symbol("timeout")
      case Some(sym: Symbol) => sym
      case _                 => Symbol("unknown")
    })
  }

  def link(actor: core.AddressableActor[_, _], pid: core.Codec.EPid)   = linkShared(actor, "link", pid)
  def unlink(actor: core.AddressableActor[_, _], pid: core.Codec.EPid) = linkShared(actor, "unlink", pid)

  def history(actor: core.AddressableActor[_, _]) = {
    val historyMessage = core.MessageEnvelope.makeCall(
      core.Codec.EAtom("$gen_call"),
      actor.self.pid,
      actor.id,
      core.Codec.EAtom("down_pids"),
      Some(3.seconds),
      actor.id
    )
    actor.ctx
      .asInstanceOf[core.ProcessContext]
      .call(historyMessage)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => {
        core.Codec
          .toScala(
            result.payload.get,
            {
              case ref: core.Codec.ERef => Some(Reference.toScala(ref))
              case pid: core.Codec.EPid => Some(Pid.toScala(pid))
            }
          )
          .asInstanceOf[List[Tuple3[Pid, Reference, Symbol]]]
      })
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
          ctx     = actor.ctx.asInstanceOf[core.ProcessContext]
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
          ctx     = actor.ctx.asInstanceOf[core.ProcessContext]
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
          ctx = actor.ctx.asInstanceOf[core.ProcessContext]
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
          history <- PingPongService
            .history(actor)
            .repeatUntil(h => h.isDefined && h.get.length > 0)
            .timeout(TIMEOUT)
            .map(_.flatten)
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

  val monitorsSuite: Spec[Any, Throwable] = {
    suite("monitor")(
      test("monitor process by identifier")(
        for {
          node           <- Utils.clouseauNode
          cfg            <- Utils.defaultConfig
          worker         <- ZIO.service[core.EngineWorker]
          echoActor      <- EchoService.startZIO(node, "echo_monitor_pid", cfg)
          monitorerActor <- MonitorService.startZIO(node, "monitorer_pid")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          ref <- MonitorService.monitor(monitorerActor, echoPid).map(_.right.get)
          _   <- ZIO.sleep(WAIT_DURATION)
          _   <- echo.exit(core.Codec.EAtom("reason"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          _       <- ZIO.sleep(WAIT_DURATION)
          history <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          echoIsAlive.get == false,
          history.isDefined,
          history.get == List(
            (echoPid: Pid, ref, Symbol("reason"))
          )
        )
      ),
      test("monitor process by name")(
        for {
          node   <- Utils.clouseauNode
          cfg    <- Utils.defaultConfig
          worker <- ZIO.service[core.EngineWorker]
          echoName = "echo_monitor_name"
          echoActor      <- EchoService.startZIO(node, echoName, cfg)
          monitorerActor <- MonitorService.startZIO(node, "monitorer_name")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          ref <- MonitorService.monitor(monitorerActor, core.Codec.EAtom(echoName)).map(_.right.get)
          _   <- ZIO.sleep(WAIT_DURATION)
          _   <- echo.exit(core.Codec.EAtom("reason"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          _       <- ZIO.sleep(WAIT_DURATION)
          history <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          echoIsAlive.get == false,
          history.isDefined,
          history.get == List(
            (echoPid: Pid, ref, Symbol("reason"))
          )
        )
      ),
      test("monitor remote process by name")(
        for {
          node   <- Utils.clouseauNode
          cfg    <- Utils.defaultConfig
          worker <- ZIO.service[core.EngineWorker]
          echoName = "echo_monitor_remote_name"
          echoActor      <- EchoService.startZIO(node, echoName, cfg)
          monitorerActor <- MonitorService.startZIO(node, "monitorer_remote_name")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          // not exactly remote but localhost, but it exercises the same path
          target = core.Codec.ETuple(core.Codec.EAtom(echoName), core.Codec.EAtom("monitors"))
          ref <- MonitorService.monitor(monitorerActor, target).map(_.right.get)
          _   <- ZIO.sleep(WAIT_DURATION)
          _   <- echo.exit(core.Codec.EAtom("reason"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          _       <- ZIO.sleep(WAIT_DURATION)
          history <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          echoIsAlive.get == false,
          history.isDefined,
          history.get == List(
            (echoPid: Pid, ref, Symbol("reason"))
          )
        )
      ),
      test("demonitor")(
        for {
          node           <- Utils.clouseauNode
          cfg            <- Utils.defaultConfig
          worker         <- ZIO.service[core.EngineWorker]
          echoActor      <- EchoService.startZIO(node, "echo_demonitor_pid", cfg)
          monitorerActor <- MonitorService.startZIO(node, "demonitorer")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          ref             <- MonitorService.monitor(monitorerActor, echoPid).map(_.right.get)
          demonitorResult <- MonitorService.demonitor(monitorerActor, ref)
          _               <- ZIO.sleep(WAIT_DURATION)
          _               <- echo.exit(core.Codec.EAtom("reason"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          _       <- ZIO.sleep(WAIT_DURATION)
          history <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          echoIsAlive.get == false,
          demonitorResult == Symbol("ok"),
          history.isDefined,
          history.get == List()
        )
      ),
      test("fail to monitor non-existent process by identifier")(
        for {
          node           <- Utils.clouseauNode
          cfg            <- Utils.defaultConfig
          worker         <- ZIO.service[core.EngineWorker]
          echoActor      <- EchoService.startZIO(node, "echo_monitor_pid_noproc", cfg)
          monitorerActor <- MonitorService.startZIO(node, "monitorer_pid_noproc")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          _ <- ZIO.sleep(WAIT_DURATION)
          // make the process exit to obtain a valid PID but without an active instance
          _ <- echo.exit(core.Codec.EAtom("normal"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          ref     <- MonitorService.monitor(monitorerActor, echoPid)
          _       <- ZIO.sleep(WAIT_DURATION)
          history <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          echoIsAlive.get == false,
          history.isDefined,
          history.get == List(),
          ref == Left(Symbol("noproc"))
        )
      ),
      test("fail to monitor non-existent process by name")(
        for {
          node           <- Utils.clouseauNode
          cfg            <- Utils.defaultConfig
          worker         <- ZIO.service[core.EngineWorker]
          monitorerActor <- MonitorService.startZIO(node, "monitorer_name_noproc")
          ref            <- MonitorService.monitor(monitorerActor, core.Codec.EAtom("non_existent"))
          _              <- ZIO.sleep(WAIT_DURATION)
          history        <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          history.isDefined,
          history.get == List(),
          ref == Left(Symbol("noproc"))
        )
      ),
      test("fail to monitor process on non-existent remote node")(
        for {
          node           <- Utils.clouseauNode
          cfg            <- Utils.defaultConfig
          worker         <- ZIO.service[core.EngineWorker]
          monitorerActor <- MonitorService.startZIO(node, "monitorer_name_noconnection")
          target = core.Codec.ETuple(core.Codec.EAtom("non_existent"), core.Codec.EAtom("non_existent"))
          ref     <- MonitorService.monitor(monitorerActor, target)
          _       <- ZIO.sleep(WAIT_DURATION)
          history <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          history.isDefined,
          history.get == List(),
          ref == Left(Symbol("noconnection"))
        )
      ),
      test("link")(
        for {
          node        <- Utils.clouseauNode
          cfg         <- Utils.defaultConfig
          worker      <- ZIO.service[core.EngineWorker]
          echoActor   <- EchoService.startZIO(node, "echo_link", cfg)
          linkedActor <- MonitorService.startZIO(node, "linked")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          linked  = linkedActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          linkResult <- MonitorService.link(linkedActor, echoPid)
          _          <- ZIO.sleep(WAIT_DURATION)
          _          <- echo.exit(core.Codec.EAtom("reason"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          linkedIsAlive <- worker.exchange
            .isKnown(linked.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
        } yield assertTrue(
          echoIsAlive.get == false,
          linkedIsAlive.get == false,
          linkResult == Symbol("ok")
        )
      ),
      test("unlink")(
        for {
          node        <- Utils.clouseauNode
          cfg         <- Utils.defaultConfig
          worker      <- ZIO.service[core.EngineWorker]
          echoActor   <- EchoService.startZIO(node, "echo_unlink", cfg)
          linkedActor <- MonitorService.startZIO(node, "unlinked")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          linked  = linkedActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          linkResult   <- MonitorService.link(linkedActor, echoPid)
          _            <- ZIO.sleep(WAIT_DURATION)
          unlinkResult <- MonitorService.unlink(linkedActor, echoPid)
          _            <- echo.exit(core.Codec.EAtom("reason"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          linkedIsAlive <- worker.exchange
            .isKnown(linked.id)
            .repeatWhile(_ == false)
            .timeout(TIMEOUT)
        } yield assertTrue(
          echoIsAlive.get == false,
          linkedIsAlive.get == true,
          linkResult == Symbol("ok"),
          unlinkResult == Symbol("ok")
        )
      ),
      test("fail to link to non-existent process")(
        for {
          node        <- Utils.clouseauNode
          cfg         <- Utils.defaultConfig
          worker      <- ZIO.service[core.EngineWorker]
          echoActor   <- EchoService.startZIO(node, "echo_link_noproc", cfg)
          linkedActor <- MonitorService.startZIO(node, "linked_noproc")
          echo    = echoActor.ctx.asInstanceOf[core.ProcessContext]
          echoPid = echo.self.pid
          _ <- ZIO.sleep(WAIT_DURATION)
          // make the process exit to obtain a valid PID but without an active instance
          _ <- echo.exit(core.Codec.EAtom("normal"))
          echoIsAlive <- worker.exchange
            .isKnown(echo.id)
            .repeatWhile(_ == true)
            .timeout(TIMEOUT)
          linkResult <- MonitorService.link(linkedActor, echoPid)
        } yield assertTrue(
          echoIsAlive.get == false,
          linkResult == Symbol("noproc")
        )
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "monitors")
    ) @@ TestAspect.withLiveClock
  }

  def spec: Spec[Any, Throwable] = {
    suite("ClouseauNodeSpec")(
      serviceSpawnSuite,
      serviceCommunicationSuite,
      processSpawnSuite,
      monitorsSuite
    )
  }
}
