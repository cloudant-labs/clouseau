package com.cloudant.ziose.otp

import com.cloudant.ziose.core.{
  Actor,
  ActorBuilder,
  ActorFactory,
  AddressableActor,
  Codec,
  Engine,
  Failure,
  Node,
  ProcessContext,
  Result,
  Success
}
import com.cloudant.ziose.macros.CheckEnv
import com.ericsson.otp.erlang.{OtpErlangPid, OtpErlangRef, OtpMbox, OtpNode}
import zio.stream.{UStream, ZStream}
import zio.{&, Duration, IO, Promise, Queue, RIO, RLayer, Schedule, Scope, Trace, UIO, URIO, ZIO, ZLayer, durationInt}
import com.cloudant.ziose.core.EngineWorker
import zio.Fiber

abstract class OTPNode extends Node {
  def acquire: UIO[Unit]
  def release: UIO[Unit]
}

object OTPNode {
  class AccessKey
  object AccessKey {
    private[OTPNode] def create(): AccessKey = new AccessKey
  }

  def live(
    name: String,
    engineId: Engine.EngineId,
    workerId: Engine.WorkerId,
    cfg: OTPNodeConfig
  ): RLayer[ActorFactory, Node] = ZLayer.scoped {
    // TODO: Add name format validation (for example '.' is not allowed)
    for {
      _       <- ZIO.logDebug("Constructing")
      factory <- ZIO.service[ActorFactory]
      ctx = OTPProcessContext.builder(Symbol(name))
      queue <- Queue.unbounded[Envelope[Command[_], _, _]].withFinalizer(_.shutdown)
      accessKey = AccessKey.create()
      cookie    = cfg.cookieVal
      service <- for {
        _           <- ZIO.logDebug(s"Creating OtpNode($name, ****)")
        nodeProcess <- NodeProcess.make(name, cookie, queue, accessKey)
        fiber       <- nodeProcess.stream.runDrain.fork
        nodeScope   <- ZIO.scope
        service = unsafeMake(fiber, queue, nodeProcess, nodeScope, factory, ctx)
        _ <- service.acquire
        _ <- ZIO.addFinalizer(service.release)
        _ <- ZIO.logDebug("Adding to the environment")
      } yield service
    } yield service
  }

  protected class Envelope[+C <: Command[R], E <: Node.Error, R <: Response](
    promise: Promise[E, R],
    val command: C
  ) {
    def succeed(result: Response): UIO[Boolean] = promise.succeed(result.asInstanceOf[R])
    def fail(reason: Node.Error): UIO[Boolean]  = promise.fail(reason.asInstanceOf[E])
    def await: IO[E, R]                         = promise.await
    override def toString: String               = s"OTPNode.Envelope($command)"
  }

  protected object Envelope {
    def apply[C <: Command[R], E <: Node.Error, R <: Response](command: C): UIO[Envelope[C, E, R]] = {
      for {
        promise <- Promise.make[E, R]
      } yield new Envelope[C, E, R](promise, command)
    }
  }

  protected trait Command[R <: Response] {
    type Response = R
  }

  case class CloseNode()                                                  extends Command[Response.CloseNode]
  case class PingNode(nodeName: String, timeout: Option[Duration] = None) extends Command[Response.PingNode]
  case class CreateMbox(name: Option[String] = None)                      extends Command[Response.CreateMbox]
  case class MakeRef()                                                    extends Command[Response.MakeRef]
  case class Register(mbox: OtpMbox, name: String)                        extends Command[Response.Register]
  case class ListNames()                                                  extends Command[Response.ListNames]
  case class LookUpName(name: String)                                     extends Command[Response.LookUpName]
  // case class CloseMbox(mbox: OtpMbox, reason: Option[OtpErlangObject])    extends Command[CloseMboxResponse]
  case class StartActor[A <: Actor](actor: AddressableActor[A, _], continue: Promise[Nothing, Unit])
      extends Command[Response.StartActor]
  case class StopActor[A <: Actor](actor: AddressableActor[A, OTPProcessContext], reason: Option[Codec.ETerm] = None)
      extends Command[Response.StopActor]
  // case class MonitorNode(name: String, timeout: Option[Duration] = None) extends Command[Response.MonitorNode]

  protected trait Response

  object Response {
    case class CloseNode(result: Unit)                  extends Response
    case class PingNode(result: Boolean)                extends Response
    case class CreateMbox(result: OtpMbox)              extends Response
    case class MakeRef(result: OtpErlangRef)            extends Response
    case class Register(result: Boolean)                extends Response
    case class ListNames(result: List[String])          extends Response
    case class LookUpName(result: Option[OtpErlangPid]) extends Response
    // case class CloseMbox(result: Unit)                  extends Response
    case class StartActor(result: Codec.EPid) extends Response
    case class StopActor(result: Unit)        extends Response
    // case class MonitorNode(result: Unit)                extends Response
  }

  class NodeProcess private (
    private val node: OtpNode,
    private val queue: Queue[Envelope[Command[_], _, _]],
    accessKey: AccessKey
  ) {
    val DEFAULT_PING_TIMEOUT: Duration                 = 61.seconds
    val DEFAULT_REMOTE_NODE_MONITOR_INTERVAL: Duration = 61.seconds
    def release(): Unit                                = close()
    def createMbox(name: String): OtpMbox              = node.createMbox(name)
    def close(): Unit                                  = node.close()
    def stream: ZStream[Scope, Nothing, Unit]          = ZStream.fromQueueWithShutdown(queue).mapZIO(loop)
    def monitorRemoteNode(name: String, timeout: Option[Duration]): UStream[Boolean] = {
      ZStream((name, timeout))
        .schedule(Schedule.spaced(DEFAULT_REMOTE_NODE_MONITOR_INTERVAL))
        .mapZIO(checkRemoteNode)
    }
    def checkRemoteNode(spec: (String, Option[Duration])): UIO[Boolean] = {
      ZIO.succeedBlocking(node.ping(spec._1, spec._2.getOrElse(DEFAULT_PING_TIMEOUT).toMillis))
    }
    def loop(event: Envelope[Command[_], _, _]): URIO[Scope, Unit] = for {
      _ <- event.command match {
        case StartActor(actor: AddressableActor[_, _], continue) =>
          for {
            _ <- actor.start(continue)
            _ <- event.succeed(Response.StartActor(actor.self.pid))
          } yield ()
        case _ =>
          handleCommand(event.command) match {
            case Success(response) => event.succeed(response)
            case Failure(reason)   => event.fail(reason)
          }
      }
    } yield ()

    def handleCommand(command: Command[_]): Result[_ <: Node.Error, Response] = {
      command match {
        case cmd @ CloseNode() =>
          node.close()
          Success(Response.CloseNode(()))
        case cmd @ PingNode(nodeName, None) =>
          Success(Response.PingNode(node.ping(nodeName, DEFAULT_PING_TIMEOUT.toMillis)))
        case cmd @ PingNode(nodeName, Some(timeout)) =>
          Success(Response.PingNode(node.ping(nodeName, timeout.toMillis)))
        case CreateMbox(None) => Success(Response.CreateMbox(node.createMbox()))
        case CreateMbox(Some(name)) =>
          node.createMbox(name) match {
            case null          => Failure(Node.Error.NameInUse(name))
            case mbox: OtpMbox => Success(Response.CreateMbox(mbox))
          }
        case MakeRef()                             => Success(Response.MakeRef(node.createRef()))
        case Register(mbox: OtpMbox, name: String) => Success(Response.Register(node.registerName(name, mbox)))
        case ListNames()                           => Success(Response.ListNames(node.getNames.toList))
        case LookUpName(name: String) =>
          node.whereis(name) match {
            case null => Success(Response.LookUpName(None))
            case pid  => Success(Response.LookUpName(Some(pid)))
          }
      }
    }

    @CheckEnv(System.getProperty("env"))
    def toStringMacro: List[String] = List(
      s"${getClass.getSimpleName}",
      s"node=$node",
      s"queue=$queue",
      s"accessKey=$accessKey"
    )
  }

  object NodeProcess {
    def make(
      name: String,
      cookie: String,
      queue: Queue[Envelope[Command[_], _, _]],
      accessKey: AccessKey
    )(implicit trace: Trace): RIO[Scope, NodeProcess] = {
      ZIO.acquireRelease(
        ZIO.attemptBlocking(
          new NodeProcess(new OtpNode(name, cookie), queue, accessKey)
        )
      )(node => ZIO.attemptBlockingIO(node.close()).orDie.unit) // TODO orDie could be too harsh
    }
  }

  private def unsafeMake[C <: ProcessContext](
    fiber: Fiber.Runtime[_, _],
    queue: Queue[Envelope[Command[_], _, _]],
    process: NodeProcess,
    nodeScope: Scope,
    f: ActorFactory,
    ctx: OTPProcessContext.Seeded
  ): OTPNode = {
    new OTPNode {
      def call[E <: Node.Error, R <: Response](
        command: Command[R]
      )(implicit trace: Trace): IO[_ <: Node.Error, R] = {
        for {
          envelope <- Envelope[Command[R], Node.Error, R](command)
          _        <- queue.offer(envelope)
          response <- envelope.await.foldZIO(
            failure => ZIO.fail(failure),
            success => ZIO.succeed(success)
          )
        } yield response
      }

      def acquire: UIO[Unit] = ZIO.logDebug(s"Acquired")
      def release: UIO[Unit] = ZIO.logDebug(s"Released")

      override def shutdown(implicit trace: Trace): UIO[Unit] = {
        fiber.interrupt.unit
      }

      override def ping(nodeName: String, timeout: Option[Duration] = None): IO[_ <: Node.Error, Boolean] = {
        for {
          response <- call(PingNode(nodeName, timeout)).map(v => v.result)
        } yield response
      }

      private def createMbox(name: Option[String] = None): IO[_ <: Node.Error, OtpMbox] = {
        for {
          response <- call(CreateMbox(name)).map(v => v.result)
        } yield response
      }

      override def makeRef(): ZIO[Any, _ <: Node.Error, Codec.ERef] = {
        for {
          ref <- call(MakeRef()).map(v => v.result)
        } yield Codec.ERef(ref)
      }

      // this is problematic since our context is generic ProcessContext
      // def register(actor: Actor, name: String): ZIO[Any, Node.Error, Boolean] = {
      //   val mbox = actor
      //               .context     // OTPProcessContext
      //               .mailbox // OTPMailbox
      //               .mbox    // OtpMbox
      //   for {
      //     response <- call(Register(mbox, name)).map(v => v.result)
      //   } yield response
      // }

      // testing only
      def listNames(): ZIO[Node, _ <: Node.Error, List[String]] = {
        for {
          response <- call(ListNames()).map(v => v.result)
        } yield response
      }

      // testing only
      def lookUpName(name: String): ZIO[Node, _ <: Node.Error, Option[Codec.EPid]] = {
        for {
          response <- call(LookUpName(name)).map(v => Some(Codec.fromErlang(v.result).asInstanceOf[Codec.EPid]))
        } yield response
      }

      // TODO prevent attempts to run multiple monitors for the same node
      def monitorRemoteNode(name: String, timeout: Option[Duration] = None): UIO[Unit] = {
        val loop = process.monitorRemoteNode(name, timeout).runDrain.forever
        for {
          _ <- (for {
            _ <- loop.forkIn(nodeScope)
          } yield ()).fork
        } yield ()
      }

      private def startActor[A <: Actor](
        actor: AddressableActor[A, _ <: ProcessContext]
      ): ZIO[Scope & Node & EngineWorker, _ <: Node.Error, AddressableActor[_, _]] = for {
        continue <- Promise.make[Nothing, Unit]
        _        <- call(StartActor(actor, continue))
        _        <- continue.await
      } yield actor

      /*
       * We do it in two steps because we want to do construction
       * in the caller process to avoid blocking node's command loop.
       * Since we cannot predict the complexity of user provided
       * constructor.
       */
      def spawn[A <: Actor](
        builder: ActorBuilder.Sealed[A]
      ): ZIO[Scope & Node & EngineWorker, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
        for {
          mbox       <- createMbox(builder.name)
          worker     <- ZIO.service[EngineWorker]
          actorScope <- nodeScope.fork
          context <- ctx
            .withOtpMbox(mbox)
            .withWorker(worker.asInstanceOf[OTPEngineWorker])
            .withBuilder(builder)
            .withScope(actorScope)
            .build()
          // TODO: Consider removing builder argument, since it is available from the context and builder can use it
          addressable <- f
            .create[A, OTPProcessContext](builder, context)
            .mapError(e => Node.Error.Constructor(e))
            .foldZIO(
              e => ZIO.fail(e),
              actor => startActor[A](actor)
            )
        } yield addressable.asInstanceOf[AddressableActor[A, C]] // TODO: I don't like that we have to cast here
      }
    }
  }
}
