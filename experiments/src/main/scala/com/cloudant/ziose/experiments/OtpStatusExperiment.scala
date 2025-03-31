/**
 * Running from sbt experiments/runMain com.cloudant.ziose.experiments.otp_status.Main
 *
 * ```
 * # Goals of the experiment
 *
 * 1. Test how inheritance from Java class works
 * 2. Test how we can implement a metric on connection attempts
 *
 *
 * # Results
 *
 * This experiment failed. The code compiles, but I don't observe any debugging printouts
 * when attempting to ping the node or send messages to it.
 * ```
 */

package com.cloudant.ziose.experiments.otp_status

import zio._
import zio.logging._
import zio.Console._
import _root_.com.ericsson.otp.erlang._
import zio.stream.ZStream

import zio.stream.SubscriptionRef

object Main extends ZIOAppDefault {
  sealed trait EMessage {}
  case class OTPMessage(msg: OtpErlangObject) extends EMessage
  case class Error(message: String)           extends EMessage

  case class OTPNodeStatus(var connAttempts: Int) {
    def incConnAttempts(node: String, incomming: Boolean) = {
      this.copy(connAttempts = this.connAttempts + 1)
    }
  }

  class OTPNodeStatusUpdater(ref: SubscriptionRef[OTPNodeStatus]) extends OtpNodeStatus {
    def connAttempt​(node: String, incomming: Boolean, info: java.lang.Object): Unit = {
      // ref.update(status => status.incConnAttempts(node, incomming))
      println(s"connAttempt from node: $node")
      ref.update(status => status.copy(connAttempts = status.connAttempts + 1)).forever
      ()
    }
    def localStatus​(node: String, up: Boolean, info: java.lang.Object): Unit = {
      println(s"localStatus of node: $node")
      ()
    }
    def remoteStatus​(node: String, up: Boolean, info: java.lang.Object): Unit = {
      println(s"remoteStatus of node: $node")
      ()
    }
  }
  class OTPNodeStatusUpdaterSimple() extends OtpNodeStatus {
    def connAttempt​(node: String, incomming: Boolean, info: Any): Unit = {
      println(s"connAttempt from node: $node")
      ()
    }
    def localStatus​(node: String, up: Boolean, info: Any): Unit = {
      println(s"localStatus of node: $node")
      ()
    }
    def remoteln​(node: String, up: Boolean, info: Any): Unit = {
      println(s"remoteStatus of node: $node")
      ()
    }
  }

  class OTPNode(private val node: OtpNode, private val statusRef: SubscriptionRef[OTPNodeStatus]) {
    def createMbox(name: String) = node.createMbox(name)
  }

  object OTPNode {
    def make(
      name: String,
      cookie: String,
      statusRef: SubscriptionRef[OTPNodeStatus]
    ): ZIO[Any, Throwable, OTPNode] = ZIO.attemptBlocking {
      var node = new OtpNode(name, cookie)
      println(s"$name node: $node")
      // node.registerStatusHandler(new OTPNodeStatusUpdater(statusRef))
      // val status = new OTPNodeStatusUpdaterSimple()
      val status = new OTPNodeStatusUpdater(statusRef)
      println(s"status of node: $status")
      node.registerStatusHandler(status)
      println(s"ping of node: ${node.ping("remsh@127.0.0.1", 2000)}")
      new OTPNode(node, statusRef)
    }
  }

  case class Node(node: OTPNode, queue: Queue[EMessage], statusRef: SubscriptionRef[OTPNodeStatus]) {
    def createMbox(name: String) = node.createMbox(name)
  }

  object Node {
    def create: ZIO[Any with Scope, Throwable, Node] = {
      for {
        queue     <- Queue.unbounded[EMessage].withFinalizer(_.shutdown)
        statusRef <- SubscriptionRef.make(new OTPNodeStatus(0))
        node      <- OTPNode.make("ziose@127.0.0.1", "secret_cookie", statusRef).debug("node")
      } yield Node(node, queue, statusRef)
    }
  }

  def parse(msg: OtpErlangObject): EMessage = OTPMessage(msg).asInstanceOf[EMessage]
  def receiveMessage(mbox: OtpMbox): EMessage = {
    try {
      Console.printLine("called receiveMessage")
      // TODO: Ignore "net_kernel" events
      parse(mbox.receiveMsg().getMsg())
    } catch {
      case e: java.lang.InterruptedException => {
        Error("")
      }
    }
  }

  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)

  def client(changes: ZStream[Any, java.io.IOException, OTPNodeStatus]) = {
    for {
      chunk <- changes
        .map(status => status.connAttempts)
        .tap(x => printLine(s"event: $x"))
        .take(1)
        .runCollect
    } yield ()
  }

  def server(node: Node) = {
    val mbox = node.createMbox("mbox")
    for {
      message <- ZIO.succeed(receiveMessage(mbox)).debug("message")
      msg     <- node.queue.offer(message)
    } yield msg
  }

  val program = ZIO.scoped(Node.create.flatMap { node =>
    server(node).forever.fork.unit // *> client(node.statusRef.changes)
  })

  def run: Task[Unit] = {
    // The warning here is by design
    ZIO
      .scoped(program)
      .provide(
        logger,
        ZLayer.Debug.tree
      )
  }
}
