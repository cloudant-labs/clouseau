/*
 * $ sbt "otp/testOnly com.cloudant.ziose.otp.ActorSpec"
 */

package com.cloudant.ziose.otp

import _root_.com.cloudant.ziose.core
import _root_.com.cloudant.ziose.otp.Utils.testEnvironment
import _root_.com.cloudant.ziose.test.helpers
import core._
import core.Codec._
import helpers.{LogHistory, Utils}
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.junit._

class TestActor()(implicit ctx: ProcessContext) extends Actor {
  override def onInit[C <: ProcessContext](ctx: C): Task[_ <: ActorResult] = {
    ZIO.logTrace(s"onInit ${ctx.name}").as(ActorResult.Continue())
  }

  override def onMessage[C <: ProcessContext](msg: MessageEnvelope, ctx: C)(implicit
    trace: Trace
  ): Task[_ <: ActorResult] = {
    val result = msg.getPayload match {
      case Some(ETuple(EAtom("interrupt"), fiberName: EAtom)) =>
        ctx.interruptNamedFiber(fiberName.atom).as(ActorResult.Continue())
      case Some(ETuple(EAtom("throw"), EString(reason))) =>
        throw new Throwable(reason)
      case Some(ETuple(EAtom("error"), EString(reason))) =>
        ZIO.succeed(ActorResult.StopWithReasonTerm(EString(reason)))
      case _ =>
        ZIO.succeed(ActorResult.Continue())
    }
    ZIO.logTrace(s"onMessage ${ctx.name} -> ${msg.to}") *> result
  }

  override def onTermination[C <: ProcessContext](reason: ETerm, ctx: C): Task[Unit] = {
    ZIO.logTrace(s"onTermination ${ctx.name}: ${reason.getClass.getSimpleName} -> ${reason}")
  }
}

private object TestActor extends ActorConstructor[TestActor] {
  private def make(
    name: String
  ): ActorBuilder.Builder[TestActor, ActorBuilder.State.Spawnable] = {
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
  ): ZIO[EngineWorker & Node & ActorFactory, Node.Error, AddressableActor[_, _]] = for {
    worker <- ZIO.service[EngineWorker]
    actor  <- worker.spawn[TestActor](make(name))
  } yield actor
}

@RunWith(classOf[ZTestJUnitRunner])
class ActorSpec extends JUnitRunnableSpec {
  val WAIT_DURATION = 500.milliseconds
  val logger        = Utils.logger
  val environment   = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  val onMessageSuite = suite("Actor onMessage callback")(
    test("test onMessage terminate properly when throw error")(
      for {
        actor      <- TestActor.startZIO("testActor")
        _          <- actor.status()
        _          <- actor.send(ETuple(EAtom("throw"), EString("throw => Die")))
        _          <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        statusDone <- actor.status()
        output     <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory
          .withLogLevel(LogLevel.Trace) && logHistory
          .withActorCallback("TestActor", ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, reason: String, "TestActor") =>
        reason.contains("throw => Die")
      }) ?? "log should contain messages from 'TestActor.onTermination' callback"
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Trace) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 1
        ) ?? "'TestService.onTermination' callback should be only called once"
        && assertTrue(
          statusDone(Symbol("actorLoop")) == Fiber.Status.Done
        ) ?? "'actorLoop' should have Done status"
        && assertTrue(
          statusDone(Symbol("internalMailboxConsumerFiber")) == Fiber.Status.Done
        ) ?? "'internalMailboxConsumerFiber' should have Done status"
        && assertTrue(
          statusDone(Symbol("externalMailboxConsumerFiber")) == Fiber.Status.Done
        ) ?? "'externalMailboxConsumerFiber' should have Done status"
    ),
    test("testing onMessage send error with StopWithReasonTerm")(
      for {
        actor      <- TestActor.startZIO("testActor")
        _          <- actor.status()
        _          <- actor.send(ETuple(EAtom("error"), EString("error => StopWithReasonTerm")))
        _          <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        statusDone <- actor.status()
        output     <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory.withLogLevel(LogLevel.Trace) &&
          logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, reason: String, "TestActor") =>
        reason.contains("error => StopWithReasonTerm")
      }) ?? "log should contain messages from 'TestActor.onTermination' callback"
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Trace) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 1
        ) ?? "'TestService.onTermination' callback should be only called once"
        && assertTrue(
          statusDone(Symbol("actorLoop")) == Fiber.Status.Done
        ) ?? "'actorLoop' should have Done status"
        && assertTrue(
          statusDone(Symbol("internalMailboxConsumerFiber")) == Fiber.Status.Done
        ) ?? "'internalMailboxConsumerFiber' should have Done status"
        && assertTrue(
          statusDone(Symbol("externalMailboxConsumerFiber")) == Fiber.Status.Done
        ) ?? "'externalMailboxConsumerFiber' should have Done status"
    )
  ).provideLayer(
    testEnvironment(1, 1, "ActorSpec")
  ) @@ TestAspect.withLiveClock

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
    )
  ).provideLayer(
    testEnvironment(1, 1, "ActorSpec")
  ) @@ TestAspect.withLiveClock

  // def spec = suite("Actor callbacks")(onMessageSuite, onTerminateSuite).provideLayer(environment)
  def spec = suite("Actor callbacks")(onMessageSuite).provideLayer(environment)
}
