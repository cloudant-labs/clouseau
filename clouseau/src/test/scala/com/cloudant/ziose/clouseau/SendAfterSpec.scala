/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.SendAfterSpec'
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
import com.cloudant.ziose.test.helpers.Asserts.containsShapeOption
import com.cloudant.ziose.test.helpers.TestRunner

class SendAfterService(ctx: ServiceContext[None.type])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  var calledArgs: List[Product2[String, Any]] = List()

  override def handleInfo(request: Any): Unit = {
    request match {
      case Symbol("notify") =>
        calledArgs ::= ("handleInfo", "notified")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("after"), delay: Long) =>
        calledArgs ::= ("handleCall", ("after", delay))
        sendAfter(self.pid, Symbol("notify"), delay)
        (Symbol("reply"), Symbol("ok"))
      case Symbol("collect") =>
        (Symbol("reply"), calledArgs)
    }
  }
}

private object SendAfterService extends core.ActorConstructor[SendAfterService] {
  private def make(
    node: SNode,
    name: String,
    service_context: ServiceContext[None.type]
  ): core.ActorBuilder.Builder[SendAfterService, core.ActorBuilder.State.Spawnable] = {
    def maker[PContext <: core.ProcessContext](process_context: PContext): SendAfterService = {
      new SendAfterService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
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

    node.spawnServiceZIO[SendAfterService, None.type](make(node, name, ctx))
  }

  def history(
    actor: core.AddressableActor[_, _],
    duration: Duration
  ): ZIO[core.Node, _ <: core.Node.Error, Option[List[Any]]] = {
    actor
      .doTestCallTimeout(core.Codec.EAtom("collect"), 3.seconds)
      .delay(duration)
      .repeatUntil(_.isSuccess)
      .map(result => core.Codec.toScala(result.payload.get).asInstanceOf[List[Any]])
      .timeout(3.seconds)
  }

  def scheduleNotificationAfter(actor: core.AddressableActor[_, _], delay: Duration) = {
    val payload = core.Codec.ETuple(core.Codec.EAtom("after"), core.Codec.ENumber(delay.toMillis))
    actor
      .doTestCallTimeout(payload, 3.seconds)
      .unit
  }
}

@RunWith(classOf[ZTestJUnitRunner])
class SendAfterSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val TIMEOUT_TEST  = 1.seconds

  def spec: Spec[Any, Throwable] = {
    suite("SendAfterSpec")(
      test("Notification should happen after a delay")(
        for {
          node <- Utils.clouseauNode
          actorName = "SendAfterSpec.Notification"
          actor   <- SendAfterService.startZIO(node, actorName)
          _       <- SendAfterService.scheduleNotificationAfter(actor, 100.millis)
          history <- SendAfterService
            .history(actor, 150.millis)
            .repeatUntil(h => h.isDefined && h.get.nonEmpty)
            .timeout(TIMEOUT_TEST)
            .map(_.flatten)
        } yield assert(history)(isSome) ?? "history should be available"
          && assert(history)(
            containsShapeOption { case ("handleInfo", "notified") => true }
          ) ?? "has to contain elements of expected shape"
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "SendAfterHandleInfoSuite")
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.SendAfterSpecMain
 * ```
 */
object SendAfterSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("SendAfterSpec", new SendAfterSpec().spec)
  }
}
