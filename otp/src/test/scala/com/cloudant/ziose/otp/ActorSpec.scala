/*
 * $ sbt "otp/testOnly com.cloudant.ziose.otp.ActorSpec"
 */

package com.cloudant.ziose.otp

import _root_.com.cloudant.ziose.test.helpers
import _root_.com.cloudant.ziose.core
import core.Actor
import core.ActorResult
import core.Codec._
import core.ProcessContext
import Utils.testEnvironment
import helpers.Utils
import helpers.Aspects._
import helpers.LogHistory
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.junit._

class TestActor()(implicit ctx: ProcessContext) extends Actor {
  override def onInit[C <: ProcessContext](ctx: C): ZIO[Any, Throwable, _ <: ActorResult] = {
    ZIO.logTrace(s"onInit ${ctx.name}") *> ZIO.succeed(ActorResult.Continue())
  }

  override def onMessage[C <: ProcessContext](msg: core.MessageEnvelope, ctx: C)(implicit
    trace: Trace
  ): ZIO[Any, Throwable, _ <: ActorResult] = {
    var result = msg.getPayload match {
      case Some(ETuple(EAtom("interrupt"), fiberName: EAtom)) =>
        ctx.interruptNamedFiber(fiberName.atom) *> ZIO.succeed(ActorResult.Continue())
      case _ => ZIO.succeed(ActorResult.Continue())
    }
    ZIO.logTrace(s"onMessage ${ctx.name} -> ${msg.to}") *> result
  }

  override def onTermination[C <: ProcessContext](reason: ETerm, ctx: C): ZIO[Any, Throwable, Unit] = {
    ZIO.logTrace(s"onTermination ${ctx.name}: ${reason.getClass.getSimpleName} -> ${reason}")
  }
}

private object TestActor extends core.ActorConstructor[TestActor] {
  private def make(
    name: String
  ): core.ActorBuilder.Builder[TestActor, core.ActorBuilder.State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): TestActor = {
      new TestActor()(process_context)
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
    name: String
  ): ZIO[core.EngineWorker & core.Node & core.ActorFactory, core.Node.Error, core.AddressableActor[_, _]] = for {
    worker <- ZIO.service[core.EngineWorker]
    actor  <- worker.spawn[TestActor](make(name))
  } yield actor
}

@RunWith(classOf[ZTestJUnitRunner])
class ActorSpec extends JUnitRunnableSpec {
  val WAIT_DURATION = 500.milliseconds
  val logger        = Utils.logger
  val environment   = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  val onMessageSuite = suite("Actor onMessage callback")(
    test("testing throwing onMessage") {
      ???
    } @@ needsTest
  )

  val onTerminateSuite = suite("Actor onTerminate callback")(
    test("test onTerminate is called on exit(normal)")(
      for {
        actor  <- TestActor.startZIO("testActor")
        _      <- actor.exit(core.Codec.EAtom("reason"))
        _      <- helpers.Asserts.assertNotAlive(actor.id)
        _      <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        output <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory
          .withLogLevel(LogLevel.Trace) && logHistory.withActorCallback("TestActor", core.ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, "onTermination Some(testActor): EAtom -> reason", "TestActor") =>
        true
      }) ?? "log should contain messages from 'TestActor.onTermination' callback"
        && assert(
          (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActorCallback(
            "TestActor",
            core.ActorCallback.OnTermination
          ))
            .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
            .size
        )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
    ),
    test("test onTerminate is called on interruption of internalMailboxConsumerFiber")(
      for {
        actor        <- TestActor.startZIO("testActor")
        statusActive <- actor.status()
        _            <- actor.send(ETuple(EAtom("interrupt"), EAtom("internalMailboxConsumerFiber")))
        _            <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        statusDone   <- actor.status()
        output       <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory
          .withLogLevel(LogLevel.Trace) && logHistory.withActorCallback("TestActor", core.ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, "onTermination Some(testActor): EAtom -> shutdown", "TestActor") =>
        true
      }) ?? "log should contain messages from 'TestActor.onTermination' callback"
        && assert(
          (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActorCallback(
            "TestActor",
            core.ActorCallback.OnTermination
          ))
            .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
            .size
        )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
        && assert(statusDone.get(Symbol("actorLoop")))(isSome) ?? "'actorLoop' should return status"
        && assert(statusDone.get(Symbol("actorLoop")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'actorLoop' should have Done status"
        && assert(statusDone.get(Symbol("internalMailboxConsumerFiber")))(
          isSome
        ) ?? "'internalMailboxConsumerFiber' should return status"
        && assert(statusDone.get(Symbol("internalMailboxConsumerFiber")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'internalMailboxConsumerFiber' should have Done status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")))(
          isSome
        ) ?? "'externalMailboxConsumerFiber' should return status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'externalMailboxConsumerFiber' should have Done status"
    ),
    test("test onTerminate is called on interruption of externalMailboxConsumerFiber")(
      for {
        actor        <- TestActor.startZIO("testActor")
        statusActive <- actor.status()
        _            <- actor.send(ETuple(EAtom("interrupt"), EAtom("externalMailboxConsumerFiber")))
        _            <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        statusDone   <- actor.status()
        output       <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory
          .withLogLevel(LogLevel.Trace) && logHistory
          .withActorCallback("TestActor", core.ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, "onTermination Some(testActor): EAtom -> shutdown", "TestActor") =>
        true
      }) ?? "log should contain messages from 'TestActor.onTermination' callback"
        && assert(
          (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActorCallback(
            "TestActor",
            core.ActorCallback.OnTermination
          ))
            .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
            .size
        )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
        && assert(statusDone.get(Symbol("actorLoop")))(isSome) ?? "'actorLoop' should return status"
        && assert(statusDone.get(Symbol("actorLoop")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'actorLoop' should have Done status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")))(
          isSome
        ) ?? "'externalMailboxConsumerFiber' should return status"
        && assert(statusDone.get(Symbol("internalMailboxConsumerFiber")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'internalMailboxConsumerFiber' should have Done status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")))(
          isSome
        ) ?? "'externalMailboxConsumerFiber' should return status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'externalMailboxConsumerFiber' should have Done status"
    ),
    test("test onTerminate is called on interruption of actorLoopFiber")(
      for {
        actor        <- TestActor.startZIO("testActor")
        statusActive <- actor.status()
        _            <- actor.send(ETuple(EAtom("interrupt"), EAtom("actorLoop")))
        _            <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        statusDone   <- actor.status()
        output       <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory
          .withLogLevel(LogLevel.Trace) && logHistory.withActorCallback("TestActor", core.ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, "onTermination Some(testActor): EAtom -> shutdown", "TestActor") =>
        true
      }) ?? "log should contain messages from 'TestActor.onTermination' callback"
        && assert(
          (logHistory.withLogLevel(LogLevel.Trace) && logHistory.withActorCallback(
            "TestActor",
            core.ActorCallback.OnTermination
          ))
            .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
            .size
        )(equalTo(1)) ?? "'TestService.onTermination' callback should be only called once"
        && assert(statusDone.get(Symbol("actorLoop")))(isSome) ?? "'actorLoop' should return status"
        && assert(statusDone.get(Symbol("actorLoop")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'actorLoop' should have Done status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")))(
          isSome
        ) ?? "'externalMailboxConsumerFiber' should return status"
        && assert(statusDone.get(Symbol("internalMailboxConsumerFiber")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'internalMailboxConsumerFiber' should have Done status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")))(
          isSome
        ) ?? "'externalMailboxConsumerFiber' should return status"
        && assert(statusDone.get(Symbol("externalMailboxConsumerFiber")).get)(
          equalTo(Fiber.Status.Done)
        ) ?? "'externalMailboxConsumerFiber' should have Done status"
    ),
    test("test catching of Throwable from throwing onMessage") {
      ???
    } @@ needsTest,
    test("do not brutally crash on throwing onTerminate") {
      ???
    } @@ needsTest
  ).provideLayer(
    testEnvironment(1, 1, "ActorSpec")
  ) @@ TestAspect.withLiveClock

  def spec = suite("Actor callbacks")(onMessageSuite, onTerminateSuite).provideLayer(environment)
}
