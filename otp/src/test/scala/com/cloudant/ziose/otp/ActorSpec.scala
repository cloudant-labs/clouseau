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
import com.cloudant.ziose.test.helpers.TestRunner

class TestActor()(implicit ctx: ProcessContext) extends Actor {
  var throwFromTerminate: Option[String] = None
  var exitFromTerminate: Option[String]  = None

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
      case Some(ETuple(EAtom("exit"), reason: ETerm)) =>
        ActorResult.exit(reason)
        ZIO.succeed(ActorResult.Continue())
      case Some(ETuple(EAtom("exit"), EString(reason))) =>
        ActorResult.exit(reason)
        ZIO.succeed(ActorResult.Continue())
      case Some(ETuple(EAtom("setThrowFromTerminate"), EString(reason))) =>
        throwFromTerminate = Some(reason)
        ZIO.succeed(ActorResult.Continue())
      case Some(ETuple(EAtom("setExitFromTerminate"), EString(reason))) =>
        exitFromTerminate = Some(reason)
        ZIO.succeed(ActorResult.Continue())
      case _ =>
        ZIO.succeed(ActorResult.Continue())
    }
    ZIO.logTrace(s"onMessage ${ctx.name} -> ${msg.to}") *> result
  }

  override def onTermination[C <: ProcessContext](reason: ETerm, ctx: C): Task[Unit] = {
    ZIO.logTrace(s"onTermination ${ctx.name}: ${reason.getClass.getSimpleName} -> ${reason}") *>
      ZIO.succeed(exitFromTerminate.map(reason => ActorResult.exit(reason))) *>
      ZIO.succeed(throwFromTerminate.map(reason => throw new Throwable(reason)))
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
        _          <- ZIO.debug("The log message about 'onMessage' crashing below is expected ----vvvv")
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
        && assert(
          (logHistory
            .withLogLevel(LogLevel.Error) && logHistory
            .withActorCallback("TestActor", ActorCallback.OnMessage))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
        )(helpers.Asserts.containsShape { case (_, reason: String, "TestActor") =>
          // This is what our onMessage callback throws
          reason.contains("throw => Die") && reason.contains("StopWithCause(OnMessage,Fail")
        }) ?? "log should contain error message pointing to failure in onMessage"
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
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Error) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnMessage))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "'TestService.onMessage' callback should not log any errors"
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Error) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "'TestService.onTermination' callback should not log any errors"
    ),
    test("test onMessage terminate properly when exit is called with string reason")(
      for {
        actor      <- TestActor.startZIO("testActor")
        _          <- actor.status()
        _          <- actor.send(ETuple(EAtom("exit"), EString("exit => Die")))
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
        reason.contains("exit => Die")
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
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Error) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnMessage))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "'TestService.onMessage' callback should not log any errors"
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Error) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "'TestService.onTermination' callback should not log any errors"
    ),
    test("test onMessage terminate properly when exit is called with term reason")(
      for {
        actor      <- TestActor.startZIO("testActor")
        _          <- actor.status()
        _          <- actor.send(ETuple(EAtom("exit"), EAtom("die")))
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
        reason.contains("EAtom -> die")
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
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Error) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnMessage))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "'TestService.onMessage' callback should not log any errors"
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Error) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "'TestService.onTermination' callback should not log any errors"
    )
  ).provideLayer(
    testEnvironment(1, 1, "ActorSpec")
  ) @@ TestAspect.withLiveClock

  val onTerminateSuite = suite("Actor onTerminate callback")(
    test("test the throw from onTerminate")(
      for {
        actor  <- TestActor.startZIO("testActor")
        _      <- actor.send(ETuple(EAtom("setThrowFromTerminate"), EString("throw => Die")))
        _      <- ZIO.debug("The log message about 'onTermination' crashing below is expected ----vvvv")
        _      <- actor.exit(core.Codec.EAtom("terminate"))
        _      <- helpers.Asserts.assertNotAlive(actor.id)
        _      <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        output <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory
          .withLogLevel(LogLevel.Trace) && logHistory
          .withActorCallback("TestActor", ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, reason: String, "TestActor") =>
        // This is what our onTermination callback receives
        reason.contains("EAtom -> terminate")
      }) ?? "log should contain message with original cause"
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Trace) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 1
        ) ?? "'TestService.onTermination' callback should be only called once"
        && assert(
          (logHistory
            .withLogLevel(LogLevel.Error) && logHistory
            .withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
        )(helpers.Asserts.containsShape { case (_, reason: String, "TestActor") =>
          // This is what our onTermination callback throws
          reason.contains("throw => Die") && reason.contains("StopWithCause(OnTermination,Die")
        }) ?? "log should contain error message pointing to failure in onTermination"
    ),
    test("test the call of exit from onTerminate")(
      for {
        actor  <- TestActor.startZIO("testActor")
        _      <- actor.send(ETuple(EAtom("setExitFromTerminate"), EString("exit => Die")))
        _      <- actor.exit(core.Codec.EAtom("normal"))
        _      <- helpers.Asserts.assertNotAlive(actor.id)
        _      <- actor.isStoppedZIO.repeatUntil(_ == true).unit
        output <- ZTestLogger.logOutput
        logHistory = LogHistory(output)
      } yield assert(
        (logHistory
          .withLogLevel(LogLevel.Trace) && logHistory
          .withActorCallback("TestActor", ActorCallback.OnTermination))
          .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
      )(helpers.Asserts.containsShape { case (_, reason: String, "TestActor") =>
        reason.contains("EAtom -> normal")
      }) ?? "log should contain messages from 'TestActor.onTermination' callback"
        && assertTrue(
          (logHistory.withLogLevel(LogLevel.Trace) &&
            logHistory.withActorCallback("TestActor", ActorCallback.OnTermination))
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 1
        ) ?? "'TestService.onTermination' callback should be only called once"
        && assertTrue(
          logHistory
            .withLogLevel(LogLevel.Error)
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "log should not contain errors"
    ),
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
        && assertTrue(
          logHistory
            .withLogLevel(LogLevel.Error)
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "log should not contain errors"
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
        && assertTrue(
          logHistory
            .withLogLevel(LogLevel.Error)
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "log should not contain errors"
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
        && assertTrue(
          logHistory
            .withLogLevel(LogLevel.Error)
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "log should not contain errors"
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
        && assertTrue(
          logHistory
            .withLogLevel(LogLevel.Error)
            .asIndexedMessageAnnotationTuples(AddressableActor.actorTypeLogAnnotation)
            .size == 0
        ) ?? "log should not contain errors"
    )
  ).provideLayer(
    testEnvironment(1, 1, "ActorSpec")
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

  def spec = suite("Actor callbacks")(
    onMessageSuite,
    onTerminateSuite
  ).provideLayer(environment)
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.otp.ActorSpecMain
 * ```
 */
object ActorSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("ActorSpec", new ActorSpec().spec)
  }
}
