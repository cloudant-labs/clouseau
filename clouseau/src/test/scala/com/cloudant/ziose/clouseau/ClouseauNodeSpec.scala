/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ClouseauNodeSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import com.cloudant.ziose.core
import com.cloudant.ziose.scalang.{Adapter, Pid, Reference, Service, ServiceContext, SNode, PidSend}
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect
import com.cloudant.ziose.clouseau.helpers.Asserts._
import com.cloudant.ziose.clouseau.helpers.LogHistory

class MonitorService(ctx: ServiceContext[None.type])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  var downPids: List[Product3[Pid, Reference, Any]] = List()

  override def trapMonitorExit(monitored: Any, ref: Reference, reason: Any): Unit = {
    val pid = Pid.toScala(monitored.asInstanceOf[core.Codec.EPid])
    downPids = (pid, ref, reason) :: downPids
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
  ): ZIO[core.Node, _ <: core.Node.Error, Either[Symbol, Reference]] = for {
    result <- actor
      .doTestCallTimeout(core.Codec.ETuple(core.Codec.EAtom("monitor"), target), 3.seconds)
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

  def demonitor(
    actor: core.AddressableActor[_, _],
    ref: Reference
  ): ZIO[core.Node, _ <: core.Node.Error, Symbol] = {
    actor
      .doTestCallTimeout(core.Codec.ETuple(core.Codec.EAtom("demonitor"), ref.fromScala), 3.seconds)
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
  ): ZIO[core.Node, _ <: core.Node.Error, Symbol] = for {
    result <- actor
      .doTestCallTimeout(core.Codec.ETuple(core.Codec.EAtom(action), pid), 3.seconds)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => core.Codec.toScala(result.payload.get))
      .timeout(3.seconds)
  } yield (result match {
    case None              => Symbol("timeout")
    case Some(sym: Symbol) => sym
    case _                 => Symbol("unknown")
  })

  def link(actor: core.AddressableActor[_, _], pid: core.Codec.EPid)   = linkShared(actor, "link", pid)
  def unlink(actor: core.AddressableActor[_, _], pid: core.Codec.EPid) = linkShared(actor, "unlink", pid)

  def history(actor: core.AddressableActor[_, _]) = {
    def toScala(term: core.Codec.ETerm): Option[Any] = term match {
      case ref: core.Codec.ERef => Some(Reference.toScala(ref))
      case pid: core.Codec.EPid => Some(Pid.toScala(pid))
      case _                    => None
    }
    actor
      .doTestCallTimeout(core.Codec.EAtom("down_pids"), 3.seconds)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => {
        core.Codec
          .toScala(result.payload.get, toScala)
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
          node   <- Utils.clouseauNode
          handle <- TestService.start(node, "echo")
        } yield assertTrue(handle.actor.isInstanceOf[core.AddressableActor[_, _]])
      )
    ).provideLayer(Utils.testEnvironment(1, 1, "serviceSpawn")) @@ TestAspect.withLiveClock
  }

  val serviceCommunicationSuite: Spec[Any, Throwable] = {
    suite("service communication")(
      // This is a foundational test which uses somewhat low level access
      // to the internals. Please refrain from DRYing it.
      test("Call into service using actor reference - basic")(
        for {
          node  <- Utils.clouseauNode
          actor <- TestService.start(node, "ServiceCommunication.Call")
          ctx     = actor.ctx.asInstanceOf[core.ProcessContext]
          tag     = core.Codec.EAtom("$gen_call")
          payload = core.Codec.ETuple(core.Codec.EAtom("echo"), core.Codec.EAtom("something"))
          callMsg = core.MessageEnvelope.makeCall(
            tag,
            actor.self.pid,
            actor.id,
            payload,
            Some(TIMEOUT),
            dummyCaller("ServiceCommunication.Call")
          )
          result <- ctx.call(callMsg)
        } yield assertTrue(
          result.isSuccess,
          result.from == Some(actor.id.asInstanceOf[core.PID].pid),
          result.tag == tag,
          result.payload.get == core.Codec.ETuple(core.Codec.EAtom("echo"), core.Codec.EAtom("something")),
          result.workerId == 1
        )
      ),
      test("Call into service using actor reference - state is updated")(
        for {
          node  <- Utils.clouseauNode
          actor <- TestService.start(node, "ServiceCommunication.Call")
          ctx     = actor.ctx.asInstanceOf[core.ProcessContext]
          tag     = core.Codec.EAtom("$gen_call")
          payload = core.Codec.ETuple(core.Codec.EAtom("echo"), core.Codec.EAtom("something"))
          callMsg = core.MessageEnvelope.makeCall(
            tag,
            actor.self.pid,
            actor.id,
            payload,
            Some(TIMEOUT),
            dummyCaller("ServiceCommunication.Call")
          )
          result       <- ctx.call(callMsg)
          actorHistory <- actor.history
        } yield assertTrue(
          result.isSuccess,
          result.from == Some(actor.id.asInstanceOf[core.PID].pid),
          result.tag == tag,
          result.payload.get == core.Codec.ETuple(core.Codec.EAtom("echo"), core.Codec.EAtom("something")),
          result.workerId == 1
        ) && assert(actorHistory)(containsShape { case ("handleCall", (Symbol("echo"), Symbol("something"))) =>
          true
        }) ?? "has to contain elements of expected shape"
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "ServiceCommunication")
    ) @@ TestAspect.withLiveClock
  }

  val processSpawnSuite: Spec[Any, Throwable] = {
    suite("processSpawn")(
      test("no longer registered after termination")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          actor <- TestService.start(node, "echo")
          _     <- assertAlive(actor.id)
          _     <- actor.exit(core.Codec.EAtom("kill it"))
          _     <- assertNotAlive(actor.id)
        } yield assertTrue(true)
      ),
      test("spawn closure")(
        for {
          node           <- Utils.clouseauNode
          worker         <- ZIO.service[core.EngineWorker]
          addressChannel <- Queue.bounded[core.PID](1)
          unleashChannel <- Queue.bounded[Unit](1)

          actor <- TestService.start(node, "ProcessSpawn.Closure")
          _ <- ZIO.succeed(node.spawn(process => {
            // this is needed to enable `actor ! message` syntax
            // this shouldn't be required in clouseau code because
            // we only use this syntax from the service classes
            // where it would be enabled automatically
            implicit def pid2sendable(pid: core.PID): PidSend = new PidSend(pid, process)
            val actorPID                                      = actor.self
            actorPID ! core.Codec.ETuple(
              core.Codec.EAtom("echo"),
              process.self.pid,    // from
              core.Codec.EInt(0L), // ts
              core.Codec.EInt(1L)  // seq
            )
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.run(addressChannel.offer(process.self))
            }
            Unsafe.unsafe { implicit unsafe =>
              runtime.unsafe.run(unleashChannel.take)
            }
          }))
          address <- addressChannel.take
          _       <- assertAlive(address)
          _       <- unleashChannel.offer(())
          _       <- assertNotAlive(address)

          actorHistory <- actor.history
        } yield assert(actorHistory)(containsShape { case ("handleInfo", (Symbol("echo"), _pid, 0, 1)) =>
          true
        }) ?? "has to contain elements of expected shape"
      ),
      test("spawn closure - ensure the actor stops at the end of the closure")(
        for {
          node           <- Utils.clouseauNode
          worker         <- ZIO.service[core.EngineWorker]
          monitorerActor <- MonitorService.startZIO(node, "ProcessSpawn.Monitorer.ActorStops")
          addressChannel <- Queue.bounded[core.PID](1)
          unleashChannel <- Queue.bounded[Unit](1)
          _ <- ZIO
            .attemptBlocking(node.spawn(process => {
              Unsafe.unsafe { implicit unsafe =>
                runtime.unsafe.run(addressChannel.offer(process.self))
              }
              Unsafe.unsafe { implicit unsafe =>
                runtime.unsafe.run(unleashChannel.take)
              }
            }))
            .fork
          address    <- addressChannel.take
          monitorRef <- MonitorService.monitor(monitorerActor, address.pid).map(_.right.get)
          _          <- assertAlive(address)
          _          <- unleashChannel.offer(())
          _          <- assertNotAlive(address)
        } yield assertTrue(true)
      ),
      test("spawn closure - ensure the execution of a closure doesn't block the caller")(
        // The fact that the closure doesn't block the caller is confirmed by lack of a timeout.
        // If it were blocking we would have a deadlock and fail with timeout eventually
        for {
          node           <- Utils.clouseauNode
          worker         <- ZIO.service[core.EngineWorker]
          monitorerActor <- MonitorService.startZIO(node, "ProcessClosure.Monitorer.NonBlocking")
          addressChannel <- Queue.bounded[core.PID](1)
          unleashChannel <- Queue.bounded[Unit](1)
          _ <- ZIO
            .attemptBlocking(node.spawn(process => {
              Unsafe.unsafe { implicit unsafe =>
                runtime.unsafe.run(addressChannel.offer(process.self))
              }
              Unsafe.unsafe { implicit unsafe =>
                runtime.unsafe.run(unleashChannel.take)
              }
            }))
            .fork
          address        <- addressChannel.take
          monitorRef     <- MonitorService.monitor(monitorerActor, address.pid).map(_.right.get)
          _              <- assertAlive(address)
          _              <- unleashChannel.offer(())
          _              <- assertNotAlive(address)
          _              <- assertAlive(monitorerActor.id)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref, Symbol("normal")) =>
            pid == Pid.toScala(address.pid) && monitorRef == ref
          }) ?? "has to contain elements of expected shape"
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "ProcessSpawn")
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds) @@ TestAspect.sequential
  }

  val monitorsSuite: Spec[Any, Throwable] = {
    suite("monitor")(
      test("monitor process by identifier - killed by calling exit")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echo           <- TestService.start(node, "MonitorSuite.Echo.KillByExit")
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.KillByExit")
          echoPid = echo.self.pid
          echoRef <- MonitorService.monitor(monitorerActor, echoPid).map(_.right.get)
          _       <- ZIO.sleep(WAIT_DURATION)
          _       <- echo.exit(core.Codec.EAtom("reason"))
          _       <- assertNotAlive(echo.id)
          _       <- ZIO.sleep(WAIT_DURATION)
          output  <- ZTestLogger.logOutput
          logHistory = LogHistory(output)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref, Symbol("reason")) =>
            pid == Pid.toScala(echoPid) && echoRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
          )(containsShape { case (_, "onTermination", "TestService") =>
            true
          }) ?? "log should contain messages from 'TestService.onTermination' callback"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
              .size
          )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
      ),
      test("monitor process by identifier - killed by exception")(
        for {
          node           <- Utils.clouseauNode
          worker         <- ZIO.service[core.EngineWorker]
          echo           <- TestService.start(node, "MonitorSuite.Echo.KillByException")
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.KillByException")
          echoPid = echo.self.pid
          echoRef <- MonitorService.monitor(monitorerActor, echoPid).map(_.right.get)
          _       <- ZIO.debug("The stack trace bellow is expected =====vvvvvv")
          _       <- echo.crashWithReason("myCrashReason")
          _       <- assertNotAlive(echo.id)
          output  <- ZTestLogger.logOutput
          logHistory = LogHistory(output)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref: Reference, reason: String) =>
            pid == Pid.toScala(echoPid) && echoRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(monitorHistory)(containsShapeOption { case (_, _, reason: String) =>
            reason.contains("OnMessage")
          }) ?? "reason has to contain 'OnMessageResult'"
          && assert(monitorHistory)(containsShapeOption { case (_, _, reason: String) =>
            reason.contains("myCrashReason")
          }) ?? "reason has to contain 'myCrashReason'"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) &&
              logHistory.withActor("TestService") &&
              logHistory.withActorAddress(echo.self))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
          )(containsShape { case (_, "onTermination", "TestService") =>
            true
          }) ?? "log should contain messages from 'TestService.onTermination' callback"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
              .size
          )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
      ),
      test("monitor process by identifier - killed by stop")(
        for {
          node           <- Utils.clouseauNode
          worker         <- ZIO.service[core.EngineWorker]
          echo           <- TestService.start(node, "MonitorSuite.Echo.KillByStop")
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.KillByStop")
          echoPid = echo.self.pid
          echoRef <- MonitorService.monitor(monitorerActor, echoPid).map(_.right.get)
          _       <- echo.stopWithReason("myReason")
          _       <- assertNotAlive(echo.id)
          _       <- ZIO.sleep(WAIT_DURATION)
          output  <- ZTestLogger.logOutput
          logHistory = LogHistory(output)
          _ <- assertAlive(monitorerActor.id)

          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref: Reference, reason: String) =>
            pid == Pid.toScala(echoPid) && echoRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(monitorHistory)(
            containsShapeOption { case (_, _, "myReason") => true }
          ) ?? "reason must be 'myReason'"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
          )(containsShape { case (_, "onTermination", "TestService") =>
            true
          }) ?? "log should contain messages from 'TestService.onTermination' callback"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
              .size
          )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
      ),
      test("monitor process by name - killed by calling exit")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echoName = "MonitorSuite.Echo.MonitorByName.KillByExit"
          echo           <- TestService.start(node, echoName)
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.MonitorByName.KillByExit")
          echoPid = echo.self.pid
          echoRef <- MonitorService.monitor(monitorerActor, core.Codec.EAtom(echoName)).map(_.right.get)
          _       <- ZIO.sleep(WAIT_DURATION)
          _       <- echo.exit(core.Codec.EAtom("reason"))
          _       <- assertNotAlive(echo.id)
          _       <- ZIO.sleep(WAIT_DURATION)
          output  <- ZTestLogger.logOutput
          logHistory = LogHistory(output)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref, Symbol("reason")) =>
            pid == Pid.toScala(echoPid) && echoRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
          )(containsShape { case (_, "onTermination", "TestService") =>
            true
          }) ?? "log should contain messages from 'TestService.onTermination' callback"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
              .size
          )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
      ),
      test("monitor process by name - killed by exception")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echoName = "MonitorSuite.Echo.MonitorByName.KillByException"
          echo           <- TestService.start(node, echoName)
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.MonitorByName.KillByException")
          echoPid = echo.self.pid
          echoRef <- MonitorService.monitor(monitorerActor, core.Codec.EAtom(echoName)).map(_.right.get)
          _       <- ZIO.debug("The stack trace below is expected =====vvvvvv")
          _       <- echo.crashWithReason("myCrashReason")
          _       <- assertNotAlive(echo.id)
          output  <- ZTestLogger.logOutput
          logHistory = LogHistory(output)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref: Reference, reason: String) =>
            pid == Pid.toScala(echoPid) && echoRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(monitorHistory)(containsShapeOption { case (_, _, reason: String) =>
            reason.contains("OnMessage") && reason.contains("HandleCallCBError")
          }) ?? "reason has to contain 'OnMessageResult' and 'HandleCallCBError'"
          && assert(monitorHistory)(containsShapeOption { case (_, _, reason: String) =>
            reason.contains("myCrashReason")
          }) ?? "reason has to contain 'myCrashReason'"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) &&
              logHistory.withActor("TestService") &&
              logHistory.withActorAddress(echo.self))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
          )(containsShape { case (_, "onTermination", "TestService") =>
            true
          }) ?? "log should contain messages from 'TestService.onTermination' callback"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
              .size
          )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
      ),
      test("monitor process by name - killed by stop")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echoName = "MonitorSuite.Echo.MonitorByName.KillByStop"
          echo           <- TestService.start(node, echoName)
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.MonitorByName.KillByStop")
          echoPid = echo.self.pid
          echoRef <- MonitorService.monitor(monitorerActor, core.Codec.EAtom(echoName)).map(_.right.get)
          _       <- echo.stopWithReason("myReason")
          _       <- assertNotAlive(echo.id)
          _       <- ZIO.sleep(WAIT_DURATION)
          output  <- ZTestLogger.logOutput
          logHistory = LogHistory(output)
          _ <- assertAlive(monitorerActor.id)

          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref: Reference, reason: String) =>
            pid == Pid.toScala(echoPid) && echoRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(monitorHistory)(
            containsShapeOption { case (_, _, "myReason") => true }
          ) ?? "reason must be 'myReason'"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
          )(containsShape { case (_, "onTermination", "TestService") =>
            true
          }) ?? "log should contain messages from 'TestService.onTermination' callback"
          && assert(
            (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActor("TestService"))
              .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
              .size
          )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
      ),
      test("monitor remote process by name")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echoName = "MonitorSuite.Echo.MonitorRemoteByName"
          echo           <- TestService.start(node, echoName)
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.MonitorRemoteByName")
          echoPid = echo.self.pid
          // not exactly remote but localhost, but it exercises the same path
          target = core.Codec.ETuple(core.Codec.EAtom(echoName), core.Codec.EAtom("MonitorSuite"))
          echoRef        <- MonitorService.monitor(monitorerActor, target).map(_.right.get)
          _              <- ZIO.sleep(WAIT_DURATION)
          _              <- echo.exit(core.Codec.EAtom("reason"))
          _              <- assertNotAlive(echo.id)
          _              <- ZIO.sleep(WAIT_DURATION)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case (pid: Pid, ref, Symbol("reason")) =>
            pid == Pid.toScala(echoPid) && echoRef == ref
          }) ?? "has to contain elements of expected shape"
      ),
      test("demonitor")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echo           <- TestService.start(node, "MonitorSuite.Echo.Demonitor")
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.Demonitor")
          echoPid = echo.self.pid
          ref             <- MonitorService.monitor(monitorerActor, echoPid).map(_.right.get)
          demonitorResult <- MonitorService.demonitor(monitorerActor, ref)
          _               <- ZIO.sleep(WAIT_DURATION)
          _               <- echo.exit(core.Codec.EAtom("reason"))
          _               <- assertNotAlive(echo.id)
          _               <- ZIO.sleep(WAIT_DURATION)
          monitorHistory  <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          demonitorResult == Symbol("ok")
        ) && assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory.get)(isEmpty) ?? "history should be empty"
      ),
      test("fail to monitor non-existent process by identifier")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echo           <- TestService.start(node, "MonitorSuite.Echo.MonitorNoProc")
          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.MonitorNoProc")
          echoPid = echo.self.pid
          _ <- ZIO.sleep(WAIT_DURATION)
          // make the process exit to obtain a valid PID but without an active instance
          _              <- echo.exit(core.Codec.EAtom("normal"))
          _              <- assertNotAlive(echo.id)
          ref            <- MonitorService.monitor(monitorerActor, echoPid)
          _              <- ZIO.sleep(WAIT_DURATION)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          ref == Left(Symbol("noproc"))
        ) && assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory.get)(isEmpty) ?? "history should be empty"
      ),
      test("fail to monitor non-existent process by name")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.MonitorNonExistent")
          ref            <- MonitorService.monitor(monitorerActor, core.Codec.EAtom("non_existent"))
          _              <- ZIO.sleep(WAIT_DURATION)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          ref == Left(Symbol("noproc"))
        ) && assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory.get)(isEmpty) ?? "history should be empty"
          && assert(ref)(isLeft) ?? "Call to monitor should return error"
          && assert(ref)(isLeft(equalTo(Symbol("noproc")))) ?? "Should get `noproc` error"
      ),
      test("fail to monitor process on non-existent remote node")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          monitorerActor <- MonitorService.startZIO(node, "MonitorSuite.Monitorer.MonitorNoConnection")
          target = core.Codec.ETuple(core.Codec.EAtom("non_existent"), core.Codec.EAtom("non_existent"))
          ref            <- MonitorService.monitor(monitorerActor, target)
          _              <- ZIO.sleep(WAIT_DURATION)
          monitorHistory <- MonitorService.history(monitorerActor)
        } yield assertTrue(
          ref == Left(Symbol("noconnection"))
        ) && assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory.get)(isEmpty) ?? "history should be empty"
      ),
      test("link")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echo   <- TestService.start(node, "MonitorSuite.echo_link")
          linked <- MonitorService.startZIO(node, "MonitorSuite.linked")
          echoPid = echo.self.pid
          linkResult <- MonitorService.link(linked, echoPid)
          _          <- ZIO.sleep(WAIT_DURATION)
          _          <- echo.exit(core.Codec.EAtom("reason"))
          _          <- assertNotAlive(echo.id)
          _          <- assertNotAlive(linked.id)
        } yield assertTrue(
          linkResult == Symbol("ok")
        )
      ),
      test("unlink")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echo   <- TestService.start(node, "MonitorSuite.echo_unlink")
          linked <- MonitorService.startZIO(node, "MonitorSuite.unlinked")
          echoPid = echo.self.pid
          linkResult   <- MonitorService.link(linked, echoPid)
          _            <- ZIO.sleep(WAIT_DURATION)
          unlinkResult <- MonitorService.unlink(linked, echoPid)
          _            <- echo.exit(core.Codec.EAtom("reason"))
          _            <- assertNotAlive(echo.id)
          _            <- assertAlive(linked.id)
        } yield assertTrue(
          linkResult == Symbol("ok"),
          unlinkResult == Symbol("ok")
        )
      ),
      test("fail to link to non-existent process")(
        for {
          node   <- Utils.clouseauNode
          worker <- ZIO.service[core.EngineWorker]

          echo   <- TestService.start(node, "MonitorSuite.echo_link_noproc")
          linked <- MonitorService.startZIO(node, "MonitorSuite.linked_noproc")
          echoPid = echo.self.pid
          _ <- ZIO.sleep(WAIT_DURATION)
          // make the process exit to obtain a valid PID but without an active instance
          _          <- echo.exit(core.Codec.EAtom("normal"))
          _          <- assertNotAlive(echo.id)
          linkResult <- MonitorService.link(linked, echoPid)
        } yield assertTrue(
          linkResult == Symbol("noproc")
        )
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "MonitorSuite")
    ) @@ TestAspect.withLiveClock
  }

  def spec: Spec[Any, Throwable] = {
    suite("ClouseauNodeSpec")(
      serviceSpawnSuite,
      serviceCommunicationSuite,
      processSpawnSuite,
      monitorsSuite
    ) @@ TestAspect.timeout(15.minutes)
  }
}
