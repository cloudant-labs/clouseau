package com.cloudant.ziose.otp

import com.cloudant.ziose.core.Node.Error
import java.time.Duration
import com.cloudant.ziose.core.ProcessContext
import com.cloudant.ziose.core.ActorBuilder
import com.cloudant.ziose.core.Actor
import com.cloudant.ziose.core.Engine
import com.cloudant.ziose.core.Codec
import com.cloudant.ziose.core.Node
import com.ericsson.otp.erlang.{OtpNode, OtpMbox, OtpErlangPid, OtpErlangRef}
import com.cloudant.ziose.core.Success
import com.cloudant.ziose.core.Failure
import com.cloudant.ziose.core.Result
import com.cloudant.ziose.core.AddressableActor
import com.cloudant.ziose.core.ActorFactory
import com.cloudant.ziose.core.MessageEnvelope
import com.cloudant.ziose.macros.checkEnv
import zio.stream.ZStream
import zio.{Promise, Queue, Schedule, Scope, Trace, UIO, ZIO, ZLayer, durationInt}

abstract class OTPNode() extends Node {
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
  ): ZLayer[ActorFactory, Throwable, Node] = ZLayer.scoped {
    // TODO: Add name format validation (for example '.' is not allowed)
    for {
      _       <- ZIO.debug("Constructing OTPNode")
      factory <- ZIO.service[ActorFactory]
      ctx = OTPProcessContext.builder(name, engineId, workerId)
      queue <- Queue.unbounded[Envelope[Command[_], _, _]].withFinalizer(_.shutdown)
      accessKey = AccessKey.create()
      cookie    = cfg.cookieVal
      service <- for {
        _           <- ZIO.debug(s"Creating OtpNode(${name}, ${cookie})") // TODO remove cookie
        nodeProcess <- NodeProcess.make(name, cookie, queue, accessKey)
        _           <- nodeProcess.stream.runDrain.fork
        scope       <- ZIO.scope
        service = unsafeMake(queue, nodeProcess, scope, factory, ctx)
        _ <- service.acquire
        _ <- ZIO.addFinalizer(service.release)
        _ <- ZIO.debug("Adding OTPNode to the environment")
      } yield service
    } yield service
  }

  protected class Envelope[+C <: Command[R], E <: Node.Error, R <: Response](
    promise: Promise[E, R],
    val command: C
  ) {
    def succeed(result: Response)              = promise.succeed(result.asInstanceOf[R])
    def fail(reason: Node.Error): UIO[Boolean] = promise.fail(reason.asInstanceOf[E])
    def await                                  = promise.await
    override def toString: String              = s"OTPNode.Envelope($command)"
  }

  protected object Envelope {
    def apply[C <: Command[R], E <: Node.Error, R <: Response](command: C) = {
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
  case class StartActor[A <: Actor](actor: AddressableActor[A, _]) extends Command[Response.StartActor]
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
    val DEFAULT_PING_TIMEOUT                 = 61.seconds
    val DEFAULT_REMOTE_NODE_MONITOR_INTERVAL = 61.seconds
    def release()                            = close()
    def createMbox(name: String)             = node.createMbox(name)
    def close()                              = node.close
    def stream                               = ZStream.fromQueueWithShutdown(queue).mapZIO(loop)
    def monitorRemoteNode(name: String, timeout: Option[Duration]) = ZStream((name, timeout))
      .schedule(Schedule.spaced(DEFAULT_REMOTE_NODE_MONITOR_INTERVAL))
      .mapZIO(checkRemoteNode)
    def checkRemoteNode(spec: (String, Option[Duration])) = {
      ZIO.succeedBlocking(node.ping(spec._1, spec._2.getOrElse(DEFAULT_PING_TIMEOUT).toMillis()))
    }
    def loop(event: Envelope[Command[_], _, _]): ZIO[Scope, Nothing, Unit] = for {
      _ <- event.command match {
        case StartActor(actor: AddressableActor[_, _]) => {
          for {
            // TODO I don't like the fact we use `asInstanceOf` here
            // _ <- ZIO.addFinalizer(
            //   ZIO.succeed(stopActor(actor.asInstanceOf[AddressableActor[_ <: Actor, OTPProcessContext]], None)))
            scope <- ZIO.scope
            _ <- actor.start(
              scope
            ) // .withFinalizer(_ => ZIO.succeed(stopActor(actor.asInstanceOf[AddressableActor[_ <: Actor, OTPProcessContext]], None)))
            _ <- actor.stream.runForeachWhile {
              case MessageEnvelope.Exit(_from, _to, reason, _workerId) =>
                for {
                  // _ <- Console.printLine(s"exit: $reason")
                  _ <- stopActor(actor.asInstanceOf[AddressableActor[_ <: Actor, OTPProcessContext]], Some(reason))
                } yield false
              case MessageEnvelope.Monitor(monitorer, monitored, ref, workerId) =>
                for {
                  // _ <- Console.printLine(s"monitor: monitorer=$monitorer, monitored=$monitored, ref=$ref, worker=$workerId")
                  _ <- actor
                    .asInstanceOf[AddressableActor[_ <: Actor, OTPProcessContext]]
                    .ctx
                    .addMonitorer(monitorer, ref)
                } yield true
              case MessageEnvelope.Demonitor(monitorer, monitored, ref, workerId) =>
                for {
                  // _ <- Console.printLine(s"demonitor: monitorer=$monitorer, monitored=$monitored, ref=$ref, worker=$workerId")
                  _ <- actor
                    .asInstanceOf[AddressableActor[_ <: Actor, OTPProcessContext]]
                    .ctx
                    .removeMonitorer(monitorer, ref)
                } yield true
              case message =>
                for {
                  // _ <- Console.printLine(s"message: $message")
                  _ <- actor.onMessage(message)
                } yield true
            }
              // .ensuring {
              //  ZIO.succeed(stopActor(actor.asInstanceOf[AddressableActor[_ <: Actor, OTPProcessContext]], None))
              // }
              .forkScoped
            // _ <- actor.stream.runDrain.forever.forkScoped
            _ <- event.succeed(Response.StartActor(actor.self.pid))
          } yield ()
        }
        case _ => {
          handleCommand(event.command) match {
            case Success(response) => event.succeed(response)
            case Failure(reason)   => event.fail(reason)
          }
        }
      }
    } yield ()

    def stopActor[A <: Actor, C <: ProcessContext](
      actor: AddressableActor[A, OTPProcessContext],
      reason: Option[Codec.ETerm]
    ): UIO[Unit] = for {
      _ <- ZIO.debug(s"stopping actor ${actor.id.toString()}")
      mbox = actor.ctx.mailbox(accessKey)
      _ <- reason match {
        case Some(term) =>
          for {
            _ <- actor.onTermination(term).catchAll(_ => ZIO.unit)
            _ <- actor.ctx.shutdown
            _ <- ZIO.succeedBlocking {
              actor.ctx.notifyMonitorers(term)
              node.closeMbox(mbox, term.toOtpErlangObject)
            }
          } yield ()
        case None =>
          val term = Codec.EAtom("normal")
          for {
            _ <- actor.onTermination(term).catchAll(_ => ZIO.unit)
            _ <- actor.ctx.shutdown
            _ <- ZIO.succeedBlocking {
              actor.ctx.notifyMonitorers(term)
              node.closeMbox(mbox)
            }
          } yield ()
      }
    } yield ()

    def handleCommand(command: Command[_]): Result[_ <: Node.Error, Response] = {
      command match {
        case cmd @ CloseNode() => {
          node.close()
          Success(Response.CloseNode(()))
        }
        case cmd @ PingNode(nodeName, None) =>
          Success(Response.PingNode(node.ping(nodeName, DEFAULT_PING_TIMEOUT.toMillis())))
        case cmd @ PingNode(nodeName, Some(timeout)) =>
          Success(Response.PingNode(node.ping(nodeName, timeout.toMillis)))
        case CreateMbox(None) => Success(Response.CreateMbox(node.createMbox()))
        case CreateMbox(Some(name)) => {
          node.createMbox(name) match {
            case null          => Failure(Node.Error.NameInUse(name))
            case mbox: OtpMbox => Success(Response.CreateMbox(mbox))
          }
        }
        case MakeRef()                             => Success(Response.MakeRef(node.createRef()))
        case Register(mbox: OtpMbox, name: String) => Success(Response.Register(node.registerName(name, mbox)))
        case ListNames()                           => Success(Response.ListNames(node.getNames().toList))
        case LookUpName(name: String) => {
          node.whereis(name) match {
            case null => Success(Response.LookUpName(None))
            case pid  => Success(Response.LookUpName(Some(pid)))
          }
        }
        case StopActor(actor, reason: Option[Codec.ETerm]) => {
          stopActor(actor, reason)
          Success(Response.StopActor(()))
        }
      }
    }

    @checkEnv(System.getProperty("env"))
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
    )(implicit trace: Trace): ZIO[Scope, Throwable, NodeProcess] = {
      ZIO.acquireRelease(
        ZIO.attemptBlocking(
          new NodeProcess(new OtpNode(name, cookie), queue, accessKey)
        )
      )(node => ZIO.attempt(node.close()).orDie.unit) // TODO orDie could be too harsh
    }
  }

  private def unsafeMake[C <: ProcessContext](
    queue: Queue[Envelope[Command[_], _, _]],
    process: NodeProcess,
    scope: Scope,
    f: ActorFactory,
    ctx: OTPProcessContext.Seeded
  ): OTPNode = {
    new OTPNode {
      def call[E <: Node.Error, R <: Response](
        command: Command[R]
      )(implicit trace: Trace): ZIO[Any, _ <: Node.Error, R] = {
        for {
          envelope <- Envelope[Command[R], Node.Error, R](command)
          _        <- queue.offer(envelope)
          response <- envelope.await.foldZIO(
            failure => ZIO.fail(failure),
            success => ZIO.succeed(success.asInstanceOf[R])
          )
        } yield response
      }

      def acquire: UIO[Unit] = {
        ZIO.debug(s"Acquired OTPNode")
      }
      def release: UIO[Unit] = {
        ZIO.debug(s"Released OTPNode")
      }

      override def close = {
        for {
          response <- call(CloseNode()).map(v => v.result)
        } yield response
      }

      override def ping(nodeName: String, timeout: Option[Duration] = None): ZIO[Any, _ <: Node.Error, Boolean] = {
        for {
          response <- call(PingNode(nodeName, timeout)).map(v => v.result)
        } yield response
      }

      private def createMbox(name: Option[String] = None): ZIO[Any, _ <: Node.Error, OtpMbox] = {
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

      def stopActor(actor: AddressableActor[_ <: Actor, _ <: ProcessContext], reason: Option[Codec.ETerm]) = {
        for {
          // TODO I don't like the fact we use `asInstanceOf` here
          _ <- call(StopActor(actor.asInstanceOf[AddressableActor[_ <: Actor, OTPProcessContext]], reason))
            .map(v => v.result)
            .debug("stopActor is finished")
        } yield ()
      }

      // TODO prevent attempts to run multiple monitors for the same node
      def monitorRemoteNode(name: String, timeout: Option[Duration] = None) = {
        val loop = process.monitorRemoteNode(name, timeout).runDrain.forever
        for {
          _ <- (for {
            _ <- loop.forkIn(scope)
          } yield ()).fork
        } yield ()
      }

      private def startActor[A <: Actor](
        actor: AddressableActor[A, _ <: ProcessContext]
      ): ZIO[Node with Scope, _ <: Node.Error, AddressableActor[_, _]] = for {
        _ <- call(StartActor(actor))
      } yield actor

      /*
       * We do it in two steps because we want to do construction
       * in the caller process to avoid blocking node's command loop.
       * Since we cannot predict the complexity of user provided
       * constructor.
       */
      def spawn[A <: Actor](
        builder: ActorBuilder.Sealed[A]
      ): ZIO[Node with Scope, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
        for {
          mbox <- createMbox(builder.name)
          context <- ctx
            .withOtpMbox(mbox)
            .withBuilder(builder)
            .build()
          // TODO: Consider removing builder argument, since it is available from the context and builder can use it
          addressable <- f
            .create[A, OTPProcessContext](builder, context)
            .mapError(e => Error.Constructor(e))
            .foldZIO(
              e => ZIO.fail(e),
              actor => startActor[A](actor)
            )
        } yield addressable.asInstanceOf[AddressableActor[A, C]] // TODO: I don't like that we have to cast here
      }
    }
  }
}
