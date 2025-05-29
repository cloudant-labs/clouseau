package com.cloudant.ziose.experiments.receive

// format: off
/**
  * Running from sbt `experiments/runMain com.cloudant.ziose.experiments.receive.Main`
  *
  * # Goals of the experiment
  *
  * 1. Implement simplest ZIO effect to be able to receive messages from remote Erlang node
  *
  * # Context
  *
  * Before we would go to elaborate solution we should try the simplest possible structure to gain knowledge about:
  *
  * 1. ZIO effects
  * 2. jInterface `mbox` API
  * 3. Running multiple effects in parallel
  *
  * # Constrains
  *
  * 1. Make it simple and easy to understand
  * 2. Rely on jinterface library for communication with Erlang
  *
  * # Solution
  *
  * Chain together two effects producer and consumer.
  * The producer calls `node.receiveMessage()` in the loop and adds received messages to the queue.
  * The consumer reads the messages from the queue and output them to terminal using `.debug()`
  *
  * # Instructions
  *
  * Please kill all instances of `epmd` before testing and start it manually
  *
  * ```
  * epmd -d -d -d -d 2>&1 | grep -v 'time in second'
  * ```
  * ```
  * On erlang side do the following
  *   1. start erlang using
  *     ```
  *     erl -name remsh@127.0.0.1 -setcookie secret_cookie -start_epmd false
  *     ```
  *   2. send message
  *     ```
  *     {mbox, 'ziose@127.0.0.1'} ! hello.
  *     ```
  * ```
  *
  * # Limitations
  *
  * The provided simplified version does not include finalizers, meaning that `node.close()` is not called when node terminates.
  * Consequently, `epmd` cannot release the node name from its directory of known nodes.
  * This results in a failure to run subsequent attempts to execute this test.
  */

import zio._
import zio.logging._
import _root_.com.ericsson.otp.erlang._

class ZNode(node: OtpNode, mbox: OtpMbox) {
  def receiveMessage(): Option[OtpMsg] = {
    try {
      Console.printLine("called receiveMessage")
      // TODO: Ignore "net_kernel" events
      var msg = mbox.receiveMsg()
      Some(msg)
    } catch {
      case e: java.lang.InterruptedException => {
        None
      }
    }
  }

}

object ZNode {
  def make(
    name: String,
    cookie: String
  ): ZIO[Any, Throwable, ZNode] = ZIO.attemptBlocking {
    var node = new OtpNode(name, cookie)
    node.ping(name, 2000)
    new ZNode(node, node.createMbox("mbox"))
  }
}

private case class NodeWrapper(node: ZNode, queue: Queue[OtpErlangObject])

private object NodeWrapper {
  def create: ZIO[Any with Scope, Throwable, NodeWrapper] = {
    for {
      queue <- Queue.unbounded[OtpErlangObject].withFinalizer(_.shutdown)
      node  <- ZNode.make("ziose@127.0.0.1", "secret_cookie")
    } yield NodeWrapper(node, queue)
  }
}

object Main extends ZIOAppDefault {
  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)
  val program = {
    ZIO.scoped(NodeWrapper.create.flatMap { wrapper =>
      val receiveLoop = for {
        message <- ZIO.succeed(wrapper.node.receiveMessage()).debug("receiveLoop")
        _ <- message match {
          case Some(msg) => wrapper.queue.offer(msg.getMsg())
          case None      => ZIO.succeed(())
        }
      } yield ()
      val processCommands = for {
        message <- wrapper.queue.take.debug("message")
        result  <- ZIO.succeed(())
      } yield result
      receiveLoop.forever.fork *> processCommands.forever
    })
  }

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
