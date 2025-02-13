package com.cloudant.ziose.experiments.node_stream

// format: off
/**
  * Running from sbt `experiments/runMain com.cloudant.ziose.experiments.node_stream.Main`
  *
  * # Goals of the experiment
  *
  * 1. Learn how to create ZStream out of a generic `receive` function
  * 2. Establish a pattern for implementing an actor pattern (a main loop which handles events received via message queue)
  * 3. Find an approach to return either result or an error.
  * 4. Learn how we can bind a response type to request type.
  * 5. Find a decent way to start multiple zio effects in parallel (main loop and stream receiver)
  *
  * # Context
  *
  * In the EchoExperiment we've learned how to use low level functions of jinterface to communicate with erlang node.
  * However we don't know the thread safety guaranties of jinterface abstractions. Therefore we should manage access to mbox.
  * There are two common ways of managing access.
  * 1. Using locking primitives
  * 2. Dedicated message receiver
  * We choose second option since it provides better safety.
  *
  * Building of the results achieved in EchoExperiment we want to abstract away the calls to `mbox.receiveMessage`.
  * In this particular experiment we are trying to create a ZIO stream (ZStream) of received messages.
  *
  * # Constrains
  *
  * 1. To avoid additional complexity do not attempt to convert `OtpErlangObject` events.
  * 2. Assume every command is expressed using its own dedicated type and that it has a corresponding response type.
  * 3. Allow returning either the result or an error.
  * 4. Try to rely on pattern matching in the main loop of an actor.
  *
  * # Solutions
  *
  * 1. Use the following to create stream (pay attention to `.collect { ... }`)
  * ```scala
  * ZStream
  *   .fromZIO(ZIO.attemptBlocking {
  *     try {
  *       Some(mbox.receiveMsg().getMsg()) ///<-------- our receive function
  *     } catch {
  *       case e: java.lang.InterruptedException => {
  *         None
  *       }
  *     }
  *   })
  *   .collect { case Some(msg) => msg }
  * ```
  * 2. To implement actor pattern use combination of
  * - case classes for Request and Response
  * - `promise` to return the result
  * - use `Queue` as a message box
  * 3. To return either result or error introduce new Result[Error, Response] type
  * 4. Use generics and associative types to bind response type to the request
  * ```scala
  * trait NodeCommand[R <: NodeResponse] {
  *   type Response = R
  * }
  *
  * case class MyFirstCommand()  extends NodeCommand[MyFirstCommandResponse]
  *
  * trait NodeResponse {}
  *
  * case class MyFirstCommandResponse(result: Unit) extends NodeResponse
  * ```
  * 5. In this experiment we start multiple concurrent effects using the following
  * ```scala
  *     for {
  *    _ <- ((nodeFiber.fork *> connect) zipPar (for {
  *      a <- node.spawn(Some("mbox")).debug("actor")
  *      _ <- actor(a, 16).forever
  *    } yield ())).fork
  *    _ <- ZIO.never
  *  } yield ()
  * ```
  * This is not great and we need to find better way
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
  *    sbt:ziose> experiments/runMain com.cloudant.ziose.experiments.node_stream.Main
  *    ```
  * 6. On Erlang side send message
  *     ```
  *     {mbox, 'ziose@127.0.0.1'} ! {echo, self(), 1}.
  *     ```
  * ```
  * 7. Observe messages printed in sbt console
  **/

import zio._
import zio.logging._
import zio.Console._
import _root_.com.ericsson.otp.erlang._
import zio.stream.ZStream

import scala.concurrent.duration.Duration

object Main extends ZIOAppDefault {
  sealed abstract class Result[+E, +T] extends Product {
    def isOk: Boolean = this match {
      case Err(_) => false
      case Ok(_)  => true
    }
    def isErr: Boolean = this match {
      case Err(_) => true
      case Ok(_)  => false
    }
  }
  final case class Ok[E, +T](value: T)   extends Result[E, T] {}
  final case class Err[+E, T](reason: E) extends Result[E, T] {}

  trait NodeCommand[R <: NodeResponse] {
    type Response = R
  }

  case class CloseNode()                                                  extends NodeCommand[CloseNodeResponse]
  case class PingNode(nodeName: String, timeout: Option[Duration] = None) extends NodeCommand[PingNodeResponse]
  case class CreateMbox(name: Option[String] = None)                      extends NodeCommand[CreateMboxResponse]
  case class Register(mbox: OtpMbox, name: String)                        extends NodeCommand[RegisterResponse]
  case class ListNames()                                                  extends NodeCommand[ListNamesResponse]
  case class LookUpName(name: String)                                     extends NodeCommand[LookUpNameResponse]
  case class CloseMbox(mbox: OtpMbox, reason: Option[OtpErlangObject])    extends NodeCommand[CloseMboxResponse]

  trait NodeResponse {}

  case class CloseNodeResponse(result: Unit)                  extends NodeResponse
  case class PingNodeResponse(result: Boolean)                extends NodeResponse
  case class CreateMboxResponse(result: OtpMbox)              extends NodeResponse
  case class RegisterResponse(result: Boolean)                extends NodeResponse
  case class ListNamesResponse(result: List[String])          extends NodeResponse
  case class LookUpNameResponse(result: Option[OtpErlangPid]) extends NodeResponse
  case class CloseMboxResponse(result: Unit)                  extends NodeResponse

  trait NodeError                         extends Throwable {}
  case class CloseNodeError()             extends NodeError
  case class NameInUseError(name: String) extends NodeError

  class CommandEnvelope[+C <: NodeCommand[R], +E <: NodeError, R <: NodeResponse](
    promise: Promise[E, R],
    val command: C
  ) {
    def succeed(result: NodeResponse)         = promise.succeed(result.asInstanceOf[R])
    def fail(reason: NodeError): UIO[Boolean] = promise.fail(reason.asInstanceOf[E])
    def await                                 = promise.await
  }

  object CommandEnvelope {
    def apply[C <: NodeCommand[R], E <: NodeError, R <: NodeResponse](command: C) = {
      for {
        promise <- Promise.make[E, R]
      } yield new CommandEnvelope[C, E, R](promise, command)
    }
  }

  /*
  OTPNode is a class which implements the Fiber owning the erlang.OtpNode
  it receive NodeCommands from Node via queue
   */
  class OTPNode(private val node: OtpNode, private val queue: Queue[CommandEnvelope[NodeCommand[_], _, _]]) {
    val DEFAULT_PING_TIMEOUT     = 1000
    def createMbox(name: String) = node.createMbox(name)
    def close()                  = node.close
    def handleCommand(command: NodeCommand[_]): Result[NodeError, NodeResponse] = {
      command match {
        case cmd @ CloseNode() => {
          node.close()
          Ok(CloseNodeResponse(()))
        }
        case cmd @ PingNode(nodeName, None) => Ok(PingNodeResponse(node.ping(nodeName, DEFAULT_PING_TIMEOUT)))
        case cmd @ PingNode(nodeName, Some(timeout)) =>
          Ok(PingNodeResponse(node.ping(nodeName, timeout.toMillis)))
        case CreateMbox(None) => Ok(CreateMboxResponse(node.createMbox()))
        case CreateMbox(Some(name)) => {
          node.createMbox(name) match {
            case null          => Err(NameInUseError(name))
            case mbox: OtpMbox => Ok(CreateMboxResponse(mbox))
          }
        }
        case Register(mbox: OtpMbox, name: String) => Ok(RegisterResponse(node.registerName(name, mbox)))
        case ListNames()                           => Ok(ListNamesResponse(node.getNames().toList))
        case LookUpName(name: String) => {
          node.whereis(name) match {
            case null => Ok(LookUpNameResponse(None))
            case pid  => Ok(LookUpNameResponse(Some(pid)))
          }
        }
        case CloseMbox(mbox, None) => {
          node.closeMbox(mbox)
          Ok(CloseMboxResponse(()))
        }
        case CloseMbox(mbox, Some(reason)) => {
          node.closeMbox(mbox, reason)
          Ok(CloseMboxResponse(()))
        }
      }
    }
    val commandLoop = for {
      _     <- ZIO.debug("commandLoop")
      event <- queue.take.debug("command")
      _ <- handleCommand(event.command) match {
        case Ok(response) => event.succeed(response)
        case Err(reason)  => event.fail(reason)
      }
    } yield ()
  }

  object OTPNode {
    def make(
      name: String,
      cookie: String,
      queue: Queue[CommandEnvelope[NodeCommand[_], _, _]]
    )(implicit trace: Trace): ZIO[Any with Scope, Throwable, OTPNode] = {
      ZIO.acquireRelease(
        ZIO.attemptBlocking(
          new OTPNode(new OtpNode(name, cookie), queue)
        )
      )(node => ZIO.attempt(node.close()).orDie.unit) // TODO orDie could be too harsh
    }
  }

  /*
   * Node is the class which interacts with OTPNode class by sending NodeCommands to it via queue.
   */
  case class Node(
    private val queue: Queue[CommandEnvelope[NodeCommand[_], _, _]],
    val node: OTPNode
  ) { self =>
    def run = for {
      fiber <- node.commandLoop.onTermination(_ => ZIO.unit).fork
      _     <- fiber.join
    } yield ()
    def close                                                            = call(CloseNode()).ignore
    def ping(nodeName: String, timeout: Option[Duration] = None)         = call(PingNode(nodeName, timeout))
    def createMbox(name: Option[String] = None)                          = call(CreateMbox(name))
    def register(mbox: OtpMbox, name: String)                            = call(Register(mbox, name))
    def listNames()                                                      = call(ListNames())
    def lookUpName(name: String)                                         = call(LookUpName(name))
    def closeMbox(mbox: OtpMbox, reason: Option[OtpErlangObject] = None) = call(CloseMbox(mbox, reason))
    def spawn(name: Option[String] = None) = {
      for {
        response <- createMbox(name)
        actor    <- Actor(response.result, 16)
      } yield actor
    }

    def call[R <: NodeResponse](command: NodeCommand[R]) = for {
      _        <- ZIO.debug(s"command $command")
      envelope <- CommandEnvelope[NodeCommand[R], NodeError, R](command)
      _        <- queue.offer(envelope)
      result   <- envelope.await
    } yield result
  }

  object Node {
    def create(name: String, cookie: String)(implicit trace: Trace): ZIO[Any with Scope, Throwable, Node] = {
      for {
        queue <- Queue.unbounded[CommandEnvelope[NodeCommand[_], _, _]].withFinalizer(_.shutdown)
        node  <- OTPNode.make(name, cookie, queue).debug("node")
      } yield Node(queue, node)
    }
  }

  case class Actor(mbox: OtpMbox, queue: Queue[OtpErlangObject]) {
    def stream(capacity: Int) = ZStream.fromQueue(queue).mergeHaltEither(otpMsgStream(mbox).buffer(capacity))
    private def otpMsgStream(mbox: OtpMbox): ZStream[Any, Throwable, OtpErlangObject] = {
      ZStream
        .fromZIO(ZIO.attemptBlocking {
          try {
            // TODO: Ignore "net_kernel" events
            Some(mbox.receiveMsg().getMsg())
          } catch {
            case e: java.lang.InterruptedException => {
              None
            }
          }
        })
        .collect { case Some(msg) => msg }
        .tap(x => printLine(s"stream: $x"))
    }
  }
  object Actor {
    def apply(mbox: OtpMbox, capacity: Int) = {
      for {
        queue <-
          if (capacity == 0) {
            Queue.unbounded[OtpErlangObject]
          } else {
            Queue.bounded[OtpErlangObject](capacity)
          }
      } yield new Actor(mbox, queue)
    }
  }

  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)
  val program = ZIO.scoped(Node.create("ziose@127.0.0.1", "secret_cookie").flatMap { node =>
    val nodeFiber                      = node.node.commandLoop.forever
    val connect                        = ZIO.succeed(node.ping("remsh@127.0.0.1").debug("ping"))
    def actor(a: Actor, capacity: Int) = a.stream(capacity).runDrain.debug("drain")
    for {
      _ <- ((nodeFiber.fork *> connect) zipPar (for {
        a <- node.spawn(Some("mbox")).debug("actor")
        _ <- actor(a, 16).forever
      } yield ())).fork
      _ <- ZIO.never
    } yield ()
  })

  def run: Task[Unit] = {
    // The warning here is by design
    ZIO
      .scoped(program.mapError(reason => new RuntimeException(s"failed with: $reason")))
      .provide(
        logger,
        ZLayer.Debug.tree
      )
  }
}
