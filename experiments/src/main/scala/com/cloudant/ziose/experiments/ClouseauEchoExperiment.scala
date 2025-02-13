package com.cloudant.ziose.experiments.clouseau_echo

// format: off
/**
  * Running from sbt `experiments/runMain com.cloudant.ziose.experiments.clouseau_echo.Main`
  *
  * # Goals of the experiment
  *
  * The goal of the experiment is to make sure we can extend of a Service and appropriate handleInfo/handleCall
  * callbacks are called.
  *
  * 1. Be a testing ground to verify wire-up of zio services.
  * 2. Act as a coordinator for ziotest to do initial performance experiments.
  * 3. Proof the approach involving ActorBuilder works for purposes of constructing an arbitrary object.
  * 4. Serve as an illustration to explain how things work.
  *
  * # Context
  *
  * In order to satisfy the constraints of the project such as 1) avoid code changes in Clouseau and 2) preserve
  * the Lucene version we need to provide a Service class which is implementing the same API as a corresponding
  * class in scalang. There is a rather complex inheritance chain (although temporary) of abstractions to go from
  * AddressableActor to a class which extends the Service. The Service itself uses few hard to understand scala
  * features, such as:
  *  - implicit context
  *  - implicit type conversion functions
  *  - apply/unapply
  *  - function override
  *
  * The use of these advanced features was motivated by the need to satisfy the requirement to prevent modification
  * of Clouseau files. However as a result the understandability of the project was severely impacted. This particular
  * experiment is a bare bones service which can be used as an illustration during the knowledge transfer sessions.
  *
  * # Constrains
  *
  * 1. Avoid code changes in Clouseau
  * 2. Avoid complexity in the code of the experiment (current file)
  * 3. As a consequence of constraint #1 we have to mirror the API of the Service as closely as possible
  *
  * # Instructions
  *
  * 1. Kill all instances of `epmd`
  * 2. In one terminal tab start `epmd` as follows
  *    ```
  *    epmd -d -d -d -d 2>&1 | grep -v 'time in second'
  *    ```
  * 3. Start erlang using
  *    ```
  *    erl -name remsh@127.0.0.1 -setcookie secret_cookie -start_epmd false
  *    ```
  * 4. Start sbt
  *    ```
  *    > sbt
  *    sbt:ziose>
  *    ```
  * 5. Run experiment in sbt
  *    ```
  *    sbt:ziose> experiments/runMain com.cloudant.ziose.experiments.clouseau_echo.Main
  *    ```
  * 6. Test `handleCall` events
  * 6.1. On Erlang side send message
  *    ```
  *    (remsh@127.0.0.1)1> {coordinator, 'ziose1_1@127.0.0.1'} ! {echo, self(), erlang:system_time(microsecond), 1}.
  *    {echo,<0.87.0>,1698089208090139,1}
  *    (remsh@127.0.0.1)2> flush().
  *    Shell got {echo_reply,<0.87.0>,1698089208090139,<9566.1.0>,1698089208219590,1}
  *    ok
  *    ```
  * 7. Simulate crash if the actor to test finalizer
  * 7.1. On Erlang side send message
  *    ```
  *    (remsh@127.0.0.1)1> {coordinator, 'ziose1_1@127.0.0.1'} ! {crash, self()}.
  *    {crash,<0.87.0>}
  *    ```
  * 7.2. Expected result is to see `"AddressableActor.onTermination {die,"Some(java.lang.ArithmeticException: / by zero)"}"`
  * event in sbt console.
  * 8. Spawn new actor on ziose side
  * 8.1. On Erlang side send message
  *    ```
  *    (remsh@127.0.0.1)1> {coordinator, 'ziose1_1@127.0.0.1'} ! {spawn, self(), foo}.
  *    {spawn,<0.87.0>,foo}
  *    (remsh@127.0.0.1)2> {foo, 'ziose1_1@127.0.0.1'} ! {'$gen_call', {self(), erlang:make_ref()}, {echo, self(), 1}}.
  *    {'$gen_call',{<0.87.0>,#Ref<0.2355174934.3355967495.245145>},
  *         {echo,<0.87.0>,1}}
  *    (remsh@127.0.0.1)14> flush().
  *    Shell got {spawned,foo,<10846.2.0>}
  *    Shell got {#Ref<0.2355174934.3355967495.245145>,{echo,{echo,<0.87.0>,1}}}
  *    ok
  *    ```
  **/

import zio._
import zio.logging._
import zio.Console._
import com.cloudant.ziose.otp.OTPEngineWorker
import com.cloudant.ziose.core.MessageEnvelope
import com.cloudant.ziose.core.Codec

import java.util.concurrent.TimeUnit
import com.cloudant.ziose.clouseau.ClouseauNode
import com.cloudant.ziose.otp.OTPNodeConfig
import com.cloudant.ziose.clouseau.Configuration
import com.cloudant.ziose.clouseau.ClouseauConfiguration
import com.cloudant.ziose.core.EngineWorker
import com.cloudant.ziose.clouseau.AppConfiguration
import com.cloudant.ziose.otp.OTPActorFactory
import com.cloudant.ziose.otp.OTPNode
import com.cloudant.ziose.core.ActorFactory
import com.cloudant.ziose.core.Node
import com.cloudant.ziose.scalang.Pid
// TODO make a preamble or something to remove the need to do it explicitly
// import implicit convertors
import com.cloudant.ziose.scalang.Pid._
import com.cloudant.ziose.scalang.ServiceContext
import com.cloudant.ziose.scalang.Service
import com.cloudant.ziose.scalang.Adapter
import com.cloudant.ziose.scalang.{Node => SNode}
import com.cloudant.ziose.core.Actor
import com.cloudant.ziose.core.ProcessContext
import com.cloudant.ziose.core.ActorConstructor
import com.cloudant.ziose.core.ActorBuilder
import com.cloudant.ziose.clouseau.ConfigurationArgs
import com.cloudant.ziose.core.AddressableActor
import java.time.temporal.ChronoUnit
import java.time.Instant

object Main extends ZIOAppDefault {
  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)
  val engineId       = 1
  val workerId       = 1
  val clouseauConfigMap = Map(
    "close_if_idle" -> "false"
  )
  val configProvider = ConfigProvider.fromMap(
    Map(
      "clouseau.close_if_idle" -> "false",
      "node.name"              -> "ziose",
      "node.domain"            -> "127.0.0.1",
      "node.cookie"            -> "secret_cookie"
    )
  )

  class EchoService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_])
      extends Service(ctx)
      with Actor {
    override def handleInfo(request: Any) = {
      request match {
        case 'heartbeat => {
          println(s"[${adapter.ctx}] handle heartbeat")
        }
        case (Symbol("spawn"), from: Codec.EPid, name: Symbol) => {
          val process = spawnProcess(name.name)
          val reply = (Symbol("spawned"), name, process.self.pid)
          send(from, reply)
        }
        case (Symbol("crash"), from: Codec.EPid) => {
          println(s"got order to crash from $from")
          5 / 0
        }
        case any => {
          println(s"unexpected message: $any")
        }
      }
    }

    override def handleCall(tag: (Pid, Any), request: Any): Any = {
      println(s"handleCall(tag: $tag, $request)")
      (Symbol("echo"), request)
    }

    def spawnProcess(name: String) = {
      val process = node.spawnService[EchoService, ConfigurationArgs](EchoService.make(node, ctx, name))
      process
    }

    def now(): BigInt = {
      ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())
    }
  }

  object EchoService extends ActorConstructor[EchoService] {
    def make(node: SNode, service_context: ServiceContext[ConfigurationArgs], name: String) = {
      def maker[PContext <: ProcessContext](process_context: PContext): EchoService = {
        new EchoService(service_context)(Adapter(process_context, node))
      }

      ActorBuilder()
        // TODO get capacity from config
        .withCapacity(16)
        .withName(name)
        .withMaker(maker)
        .build(this)
    }

    def start(
      node: SNode,
      name: String,
      config: Configuration
    ): ZIO[EngineWorker & Node & ActorFactory, Throwable, AddressableActor[_, _]] = {
      val ctx = new ServiceContext[ConfigurationArgs] { val args = ConfigurationArgs(config) }
      node.spawnServiceZIO[EchoService, ConfigurationArgs](make(node, ctx, name))
    }
  }

  def startCoordinator(
    node: ClouseauNode
  ): ZIO[EngineWorker & Node & ActorFactory, Throwable, AddressableActor[_, _]] = {
    for {
      clouseauCfg <- configProvider.load(ClouseauConfiguration.config.nested("clouseau"))
      nodeCfg     <- configProvider.load(OTPNodeConfig.config.nested("node"))
      actor       <- EchoService.start(node, "coordinator", Configuration(clouseauCfg, nodeCfg))
    } yield actor
  }

  val effect: ZIO[EngineWorker & Node & ActorFactory, Throwable, Unit] = for {
    runtime  <- ZIO.runtime[Environment with EngineWorker & Node & ActorFactory]
    otp_node <- ZIO.service[Node]
    _        <- otp_node.monitorRemoteNode(s"remsh@127.0.0.1")
    worker   <- ZIO.service[EngineWorker]
    node     <- ZIO.succeed(new ClouseauNode()(runtime, worker))
    actor    <- startCoordinator(node)
    /*
      send message via core to make sure it doesn't crash anything
      TODO write unit tests
     */
    envelope = MessageEnvelope.makeSend(
      actor.id,
      Codec.EAtom(Symbol("please_ignore_it_is_just_testing_that_unknown_message_does_not_crash_the_handler)")),
      worker.id
    )
    _ <- actor.offer(envelope)
    _ <- worker.awaitShutdown
  } yield ()

  val program = {
    for {
      cfg <- configProvider
        .load(AppConfiguration.config)
        .orDieWith(e => new Exception(s"Cannot read configuration ${e.toString()}"))
        .cached(Duration(3, TimeUnit.MINUTES))
        .debug("config")
      nodeCfg <- cfg.map(_.node).debug("nodeCfg")
      name    <- ZIO.succeed(s"${nodeCfg.node.value}${engineId}_$workerId@${nodeCfg.domain.value}")
      // The warning here is by design
      _ <- ZIO
        .scoped(effect)
        .provide(
          OTPEngineWorker.live(engineId, workerId, name, nodeCfg),
          OTPActorFactory.live(name, nodeCfg),
          OTPNode.live(name, engineId, workerId, nodeCfg),
          ZLayer.Debug.tree
        )
    } yield ()
  }

  def run: Task[Unit] = {
    ZIO
      .scoped(program.mapError(reason => new RuntimeException(s"failed with: $reason")))
      .provide(
        logger
      )
      .onInterrupt(printLine("Stopped").orDie)
  }
}
