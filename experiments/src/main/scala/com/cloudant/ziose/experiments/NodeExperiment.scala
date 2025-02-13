package com.cloudant.ziose.experiments.node

// format: off
/**
  * Running from sbt `experiments/runMain com.cloudant.ziose.experiments.node.Main`
  *
  * # Goals of the experiment
  *
  * 1. Learn how to implement Event-Driven programming pattern using ZIO
  * 2. Learn how to define commands and responses in strongly typed language.
  * 3. Understand how to drive the main loop of the effect.
  * 4. Figure out how to return error to the caller.
  * 5. Understand how finalizer works
  *
  * # Context
  *
  * It is unknown how reliable jinterface node class is in multithreaded setting.
  * To protect us from concurrency problems we want to prevent concurrent access to jinterface's `node` object.
  * There are two ways of doing it. First we can use mutex and second we can use event driven programming pattern.
  * In event driven programming pattern the main loop receives and processes incoming events in order,
  * taking appropriate actions based on their type and content. The `dbcore` team is very familiar with
  * this pattern since it is the main idea of Erlang and Scalang.
  *
  * # Constrains
  *
  * 1. Attempt to simplify commands matching using `match` statement.
  * 2. Call `node.close()` from finalizer to allow `epmd` to remove the node from its dictionary of known nodes.
  *
  * # Solution
  *
  * 1. Event driven programming pattern is implemented using effect defining `commandLoop` which handles commands
  *    received from the queue.
  * 2. The commands and responses are implemented as case classes extending from dedicated NodeCommand and NodeResponse trait.
  *    Each NodeCommand specify the expected response type as a generic type argument.
  * 3. The main loop is driven by `consumer(a).forever zipPar a.loop.forever`
  * 4. We return errors using new `Result[E, T]` type which implements `Success` and `Failure` alternatives using `case class` which
  * has unapply and therefore can be used in pattern matching.
  * 5. We connect finalizer using `ZIO.acquireRelease(...)(node => ZIO.attempt(node.close()).orDie.unit)`
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
  *    sbt:ziose> experiments/runMain com.cloudant.ziose.experiments.node.Main
  *    ```
  * 6. On Erlang side send message
  *    ```
  *    (remsh@127.0.0.1)6> {mbox, 'ziose@127.0.0.1'} ! hello.
  *    ```
  * 7. Expected result is to see "received message $message" event in sbt console
  **/

import zio._
import zio.logging._
import _root_.com.ericsson.otp.erlang._

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

  case class CloseNode()                                                  extends NodeCommand[CloseNodeResponse] {}
  case class PingNode(nodeName: String, timeout: Option[Duration] = None) extends NodeCommand[PingNodeResponse]  {}
  case class CreateMbox(name: Option[String] = None)                      extends NodeCommand[CreateMboxResponse]
  case class Register(mbox: OtpMbox, name: String)                        extends NodeCommand[RegisterResponse]
  case class ListNames()                                                  extends NodeCommand[ListNamesResponse]
  case class LookUpName(name: String)                                     extends NodeCommand[LookUpNameResponse]
  case class CloseMbox(mbox: OtpMbox, reason: Option[OtpErlangObject])    extends NodeCommand[CloseMboxResponse]

  trait NodeResponse {}

  case class CloseNodeResponse(result: Unit)                  extends NodeResponse {}
  case class PingNodeResponse(result: Boolean)                extends NodeResponse {}
  case class CreateMboxResponse(result: OtpMbox)              extends NodeResponse {}
  case class RegisterResponse(result: Boolean)                extends NodeResponse {}
  case class ListNamesResponse(result: List[String])          extends NodeResponse {}
  case class LookUpNameResponse(result: Option[OtpErlangPid]) extends NodeResponse {}
  case class CloseMboxResponse(result: Unit)                  extends NodeResponse {}

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
        actor    <- Actor.make(response.result)
      } yield actor
    }

    def call[R <: NodeResponse](command: NodeCommand[R]) = for {
      _        <- ZIO.debug(s"command $command")
      envelope <- CommandEnvelope[NodeCommand[R], NodeError, R](command)
      _        <- queue.offer(envelope)
      result   <- envelope.await
    } yield result
    // def scoped = ZIO.acquireRelease(ZIO.succeed(self))(ZIO.attempt((node: OTPNode): Unit => node.node.close).orDie)
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
    def loop = {
      for {
        _      <- ZIO.debug("actorLoop")
        result <- ZIO.succeed(receiveMessage()).debug("message")
        _ <- {
          result match {
            case Ok(message) => {
              queue.offer(message).debug("adding to queue")
            }
            case Err(err) => {
              println(s"Err: $err")
              ZIO.succeed(())
            }
          }
        }
      } yield ()
    }

    def receiveMessage(): Result[String, OtpErlangObject] = {
      try {
        Console.printLine("called receiveMessage")
        // TODO: Ignore "net_kernel" events
        Ok(mbox.receiveMsg().getMsg())
      } catch {
        case e: java.lang.InterruptedException => {
          Err("")
        }
      }
    }
  }
  object Actor {
    def make(mbox: OtpMbox) = for {
      queue <- Queue.unbounded[OtpErlangObject].withFinalizer(_.shutdown)
    } yield Actor(mbox, queue)
  }

  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)
  val program = ZIO.scoped(Node.create("ziose@127.0.0.1", "secret_cookie").flatMap { node =>
    val nodeFiber = node.node.commandLoop.forever
    val connect   = ZIO.succeed(node.ping("remsh@127.0.0.1").debug("ping"))
    def consumer(actor: Actor) = for {
      _       <- ZIO.debug("clientLoop")
      message <- actor.queue.take
      _       <- ZIO.debug(s"received message $message")
    } yield ()
    def actor(a: Actor) = consumer(a).forever zipPar a.loop.forever
    for {
      _ <- ((nodeFiber.fork *> connect) zipPar (for {
        a <- node.spawn(Some("mbox")).debug("actor")
        // _ <- consumer(a).forever zipPar a.loop.forever
        _ <- actor(a).forever
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
