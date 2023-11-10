package com.cloudant.ziose.experiments.echo

// format: off
/**
  * Running from sbt `experiments/runMain com.cloudant.ziose.experiments.echo.Main`
  *
  * # Goals of the experiment
  *
  * 1. Learn how to run concurrently multiple main loops (receiver and responder).
  * 2. To test end-to-end delivery of the message through ZIO stack.
  * 3. Use the example as a teaching aid to demonstrate how messages are flowing without unnecessary abstractions.
  *
  * # Context
  *
  * This is one of the very first experiments. With this experiment we would be able to prove to ourselves that ZIO
  * is capable of receiving messages asynchronously and provides sufficient abstractions to do it ergonomically.
  *
  * # Constrains
  *
  * Since the goal of the experiment is to demonstrate how things can be done in pure ZIO,
  * we need to stick to the basics and avoid unnecessary abstractions.
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
  *    sbt:ziose> experiments/runMain com.cloudant.ziose.experiments.echo.Main
  *    ```
  * 6. On Erlang side send message
  *     ```
  *     {mbox, 'ziose@127.0.0.1'} ! {echo, self(), 1}.
  *     ```
  * ```
  * 7. use `flush().` on erlang side to display echoed message.
  *
  * Here is an example of erl session
  *
  * ```
  * ❯ erl -name remsh@127.0.0.1 -setcookie secret_cookie -start_epmd false
  * Erlang/OTP 25 [erts-13.0.2] [source] [64-bit] [smp:10:10] [ds:10:10:10] [async-threads:1] [jit]
  *
  * Eshell V13.0.2  (abort with ^G)
  * (remsh@127.0.0.1)1> {mbox, 'ziose@127.0.0.1'} ! {echo, self(), 1}.
  * {echo,<0.86.0>,1}
  * (remsh@127.0.0.1)2> flush().
  * Shell got {<9397.1.0>,<0.86.0>,2}
  * ok
  * ```
  *
  */

import zio._
import zio.logging._
import _root_.com.ericsson.otp.erlang._

sealed trait EMessage {}

case class Echo(from: OtpErlangPid, counter: Int) extends EMessage
case class Error(message: String)                 extends EMessage

class ZENode(node: OtpNode, mbox: OtpMbox) {
  case class TupleMessage(command: String, from: OtpErlangPid, args: List[OtpErlangObject])
  case class TupleError(message: String)

  def parse(message: OtpErlangObject): EMessage = {
    parseTupleMessage(message) match {
      case TupleMessage(command, from, args) if command == "echo" => {
        val first = args.head
        if (first.isInstanceOf[OtpErlangLong]) {
          val counter = first.asInstanceOf[OtpErlangLong]
          Echo(from, counter.intValue.toInt).asInstanceOf[EMessage]
        } else {
          Error(s"Expected third element of a tuple to be integer()}, got: ${first.getClass()}")
        }
      }
      case TupleError(err) => Error(err)
      case None            => Error("Expected {echo, pid(), int()}")
    }
  }

  def parseTupleMessage(message: OtpErlangObject) = {
    if (message.isInstanceOf[OtpErlangTuple]) {
      val tuple = message.asInstanceOf[OtpErlangTuple]
      if (tuple.arity() > 2) {
        val first  = tuple.elementAt(0)
        val second = tuple.elementAt(1)
        if ((first.isInstanceOf[OtpErlangAtom]) && (second.isInstanceOf[OtpErlangPid])) {
          val command = first.asInstanceOf[OtpErlangAtom].atomValue
          val from    = second.asInstanceOf[OtpErlangPid]
          val args    = tuple.elements().toList.drop(2)
          TupleMessage(command, from, args)
        } else {
          TupleError("Expected {atom(), pid(), ....}")
        }
      } else {
        TupleError("Expected {atom(), pid(), ....}")
      }
    } else {
      TupleError("Expected {atom(), pid(), ....}")
    }
  }

  def receiveMessage(): EMessage = {
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

  def sendMessage(to: OtpErlangPid, msg: OtpErlangObject) = {
    mbox.send(to, msg)
  }

  def ownPid() = {
    mbox.self()
  }
}

object ZENode {
  def make(
    name: String,
    cookie: String
  ): ZIO[Any, Throwable, ZENode] = ZIO.attemptBlocking {
    var node = new OtpNode(name, cookie)
    node.ping(name, 2000)
    new ZENode(node, node.createMbox("mbox"))
  }
}

case class ENodeWrapper(node: ZENode, queue: Queue[EMessage])

object ENodeWrapper {
  def create: ZIO[Any with Scope, Throwable, ENodeWrapper] = {
    for {
      queue <- Queue.unbounded[EMessage].withFinalizer(_.shutdown)
      node  <- ZENode.make("ziose@127.0.0.1", "secret_cookie")
    } yield ENodeWrapper(node, queue)
  }
}

object Main extends ZIOAppDefault {
  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)

  val program1 = {
    ZIO.scoped(ENodeWrapper.create.flatMap { wrapper =>
      val receiveLoop = for {
        message <- ZIO.succeed(wrapper.node.receiveMessage()).debug("message")
        result  <- wrapper.queue.offer(message)
      } yield result
      val processCommands = for {
        event <- wrapper.queue.take.debug("event")
        result <- event match {
          case Echo(from, counter) => {
            val self = wrapper.node.ownPid.asInstanceOf[OtpErlangObject]
            val next = new OtpErlangInt(counter + 1).asInstanceOf[OtpErlangObject]
            val elements = { // {echo, self(), from, 1}.
              Array.empty[OtpErlangObject] :+ self :+ from.asInstanceOf[OtpErlangObject] :+ next
            }
            val msg = new OtpErlangTuple(elements)
            wrapper.node.sendMessage(from, msg)
            ZIO.succeed("else").debug("echo")
          }
          case _ => ZIO.succeed("any")
        }
      } yield event
      receiveLoop.forever.fork *> processCommands.forever
    })
  }

  def run: Task[Unit] = {
    // The warning here is by design
    ZIO
      .scoped(program1)
      .provide(
        logger,
        ZLayer.Debug.tree
      )
  }
}
