/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ServiceSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.Assertion._

import com.cloudant.ziose.core
import com.cloudant.ziose.scalang.{Adapter, Pid, Service, ServiceContext, SNode}
import zio.test._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.Asserts._
import com.cloudant.ziose.scalang.Reference

class SupervisorService(ctx: ServiceContext[None.type])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  var calledArgs: List[Product2[String, Any]] = List()

  override def trapMonitorExit(monitored: Any, ref: Reference, reason: Any): Unit = {
    calledArgs ::= ("trapMonitorExit", (monitored, ref, reason))
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("monitor"), target: Any) =>
        (Symbol("reply"), monitor(target))
      case Symbol("history") =>
        (Symbol("reply"), calledArgs)
      case (Symbol("stop"), reason: Symbol) =>
        (Symbol("stop"), reason, adapter.fromScala(request))
    }
  }
}

private object SupervisorService extends core.ActorConstructor[SupervisorService] {
  val TIMEOUT = 2.seconds
  private def make(
    node: SNode,
    name: String,
    service_context: ServiceContext[None.type]
  ): core.ActorBuilder.Builder[SupervisorService, core.ActorBuilder.State.Spawnable] = {
    def maker[PContext <: core.ProcessContext](process_context: PContext): SupervisorService = {
      new SupervisorService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
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

    node.spawnServiceZIO[SupervisorService, None.type](make(node, name, ctx))
  }

  def monitor(
    actor: core.AddressableActor[_, _],
    target: core.Codec.ETerm
  ): ZIO[core.Node, _ <: core.Node.Error, Either[Symbol, Reference]] = for {
    result <- actor
      .doTestCallTimeout(core.Codec.ETuple(core.Codec.EAtom("monitor"), target), TIMEOUT)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => result.payload.get)
      .timeout(TIMEOUT)
  } yield (result match {
    case None                         => Left(Symbol("timeout"))
    case Some(atom: core.Codec.EAtom) => Left(Symbol(atom.asString))
    case Some(ref: core.Codec.ERef)   => Right(Reference.toScala(ref))
    case Some(value)                  => Left(Symbol("unknown"))
  })

  def history(actor: core.AddressableActor[_, _]): ZIO[core.Node, _ <: core.Node.Error, Option[List[Any]]] = {
    val adapter = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)
    actor
      .doTestCallTimeout(core.Codec.EAtom("history"), TIMEOUT)
      .delay(100.millis)
      .repeatUntil(_.isSuccess)
      .map(result => adapter.toScala(result.payload.get).asInstanceOf[List[Any]])
      .timeout(TIMEOUT)
  }
}

@RunWith(classOf[ZTestJUnitRunner])
class ServiceSpec extends JUnitRunnableSpec {
  val trapMonitorExitSuite: Spec[Any, Throwable] = {
    suite("trapMonitorExit suite")(
      test("trapMonitorExit is called when child stops")(
        for {
          node       <- Utils.clouseauNode
          worker     <- ZIO.service[core.EngineWorker]
          actor      <- TestService.start(node, "ServiceSpecTrapMonitorExitSuite.Actor.Stops")
          supervisor <- SupervisorService.startZIO(node, "ServiceSpecTrapMonitorExitSuite.Supervisor.Stops")
          address = actor.self
          monitorRef     <- SupervisorService.monitor(supervisor, address.pid).map(_.right.get)
          _              <- assertAlive(address)
          _              <- actor.stopWithReason(Symbol("myStopReason"))
          _              <- assertNotAlive(address)
          monitorHistory <- SupervisorService.history(supervisor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case ("trapMonitorExit", (pid: Pid, ref, reason: Symbol)) =>
            pid == Pid.toScala(address.pid) && monitorRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(monitorHistory)(containsShapeOption { case ("trapMonitorExit", (_, _, reason: Symbol)) =>
            reason.name.contains("myStopReason")
          }) ?? "reason has to contain 'myStopReason'"
      ),
      test("trapMonitorExit is called when child killed")(
        for {
          node       <- Utils.clouseauNode
          worker     <- ZIO.service[core.EngineWorker]
          actor      <- TestService.start(node, "ServiceSpecTrapMonitorExitSuite.Actor.Stops")
          supervisor <- SupervisorService.startZIO(node, "ServiceSpecTrapMonitorExitSuite.Supervisor.Stops")
          address = actor.self
          monitorRef     <- SupervisorService.monitor(supervisor, address.pid).map(_.right.get)
          _              <- assertAlive(address)
          _              <- actor.exitWithReason("myStopReason")
          _              <- assertNotAlive(address)
          monitorHistory <- SupervisorService.history(supervisor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption { case ("trapMonitorExit", (pid: Pid, ref, reason: String)) =>
            pid == Pid.toScala(address.pid) && monitorRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(monitorHistory)(containsShapeOption { case ("trapMonitorExit", (_, _, reason: String)) =>
            reason.contains("myStopReason")
          }) ?? "reason has to contain 'myStopReason'"
      ),
      test("trapMonitorExit is called when child crashes")(
        for {
          node       <- Utils.clouseauNode
          worker     <- ZIO.service[core.EngineWorker]
          actor      <- TestService.start(node, "ServiceSpecTrapMonitorExitSuite.Actor.crashByException")
          supervisor <- SupervisorService.startZIO(node, "ServiceSpecTrapMonitorExitSuite.Supervisor.crashByException")
          address = actor.self
          monitorRef     <- SupervisorService.monitor(supervisor, address.pid).map(_.right.get)
          _              <- assertAlive(address)
          _              <- ZIO.debug("The stack trace below is expected =====vvvvvv")
          _              <- actor.crashWithReason("myCrashReason")
          _              <- assertNotAlive(address)
          monitorHistory <- SupervisorService.history(supervisor)
        } yield assert(monitorHistory)(isSome) ?? "history should be available"
          && assert(monitorHistory)(containsShapeOption {
            case (
                  "trapMonitorExit",
                  (pid: Pid, ref, (Symbol("error"), "OnMessage", reason: String, stackTrace: String))
                ) =>
              pid == Pid.toScala(address.pid) && monitorRef == ref
          }) ?? "has to contain elements of expected shape"
          && assert(monitorHistory)(containsShapeOption {
            case ("trapMonitorExit", (_, _, (Symbol("error"), "OnMessage", reason: String, stackTrace: String))) =>
              reason.contains("HandleCallCBError")
          }) ?? "reason has to contain 'HandleCallCBError'"
          && assert(monitorHistory)(containsShapeOption {
            case ("trapMonitorExit", (_, _, (Symbol("error"), "OnMessage", reason: String, stackTrace: String))) =>
              reason.contains("myCrashReason")
          }) ?? "reason has to contain 'myCrashReason'"
          && assert(monitorHistory)(containsShapeOption {
            case ("trapMonitorExit", (_, _, (Symbol("error"), "OnMessage", reason: String, stackTrace: String))) =>
              stackTrace.contains("TestService.handleCall(TestService.scala:")
          }) ?? "reason has to contain 'TestService.handleCall'"
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "ServiceSpecTrapMonitorExitSuite")
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("ServiceSpec")(
      trapMonitorExitSuite
    ) @@ TestAspect.timeout(15.minutes)
  }
}
