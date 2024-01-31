package com.cloudant.ziose.otp

import zio._
import zio.stream.ZStream

import com.cloudant.ziose.core.Mailbox

import com.ericsson.otp.erlang.{OtpMbox, OtpErlangException}

import com.cloudant.ziose.core.Codec
import com.cloudant.ziose.core.Address
import com.cloudant.ziose.core.MessageEnvelope
import com.cloudant.ziose.core.Engine
import com.cloudant.ziose.core.PID
import com.cloudant.ziose.core.Name
import com.cloudant.ziose.core.NameOnNode

/*
 * - def stream: - is used by Actor to retrieve messages
 *
 * If we want to call handleMessage for each event we can use
 * the following
 *
 * ```
 * for {
 *   actor <- ...
 *   actorLoop = actor.mbox.stream.mapZIO(handleMessage).runDrain.forever
 *   _ <- actorLoop.forkScoped
 * } yield ()
 * ```
 *
 * To send messages to mailbox just use functions provided by `Enqueue[_]` trait
 *
 * ```
 * mbox.offer(msg)
 * ```
 *
 * The OTPMailbox is a stream resulting from merging of two sources
 *   - stream of events (encoded as `MessageEnvelope`) received from remote node
 *   - queue of events (encoded as `MessageEnvelope`) received internally
 *
 * OTPMailbox
 *   ---> erlang.OtpMBox -----Stream--\
 *                                     |--> Stream
 *   ---> Queue ----------------------/
 *
 * The main reason to bypass the jInterface managed mbox is to quickly
 * send messages originating internally. The ordering guaranties are
 * the same as for OTP.
 *
 * The OTPMailbox extends from Mailbox which requires implementation of
 * a EnqueueWithId where Address is used as an Id type.
 * As a result OTPMailbox look like a queue to any external actor.
 * The aggregated stream of messages consumed by OTPActor using
 * ```scala
 * mailbox.stream.tap(x => printLine(s"node event: $x")).mapZIO(onEvent)
 * ```
 * Where `onEvent` is
 * ```scala
 * def onEvent(event: MessageEnvelope) = for {
 *   _ <- ....
 * } yield ()
 * ```
 *
 * An OTPMailbox is created out of OTPProcessContextBuilder
 * which holds arguments needed to construct an OTPMailbox.
 *
 * Here is a relevant logic from OTPNode:
 * ```scala
 * for {
 *   scope <- ZIO.scope
 *   _ <- actor.start(scope)
 *   _ <- actor.stream.runForeachWhile { ... }.forkScoped
 *   _ <- event.succeed(Response.StartActor(actor.self.pid))
 * } yield ()
 *
 * private def startActor[A <: Actor](actor: AddressableActor[A, _ <: ProcessContext]): ZIO[Node with Scope, _ <: Node.Error, AddressableActor[_, _]] = for {
 *   _ <- call(StartActor(actor))
 * } yield actor
 *
 * def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[Node with Scope, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] =
 *       for {
 *         mbox <- createMbox(builder.name)
 *         context <- ctx
 *           .withOtpMbox(mbox)
 *           .withBuilder(builder)
 *           .build()
 *         addressable  <- f.create[A, OTPProcessContext](builder, context).mapError(e => Error.Constructor(e)).foldZIO(
 *           e => ZIO.fail(e),
 *           actor => startActor[A](actor)
 *         )
 *       } yield addressable.asInstanceOf[AddressableActor[A, C]]
 *   }
 * ```
 *
 * The OTPActorFactory does the following on `create`
 *
 * ```scala
 * def create[A <: Actor, C <: ProcessContext](builder: ActorBuilder.Sealed[A], ctx: C): ZIO[Any, Node.Error, AddressableActor[A, _ <: ProcessContext]] =
 *   for {
 *     _ <- ZIO.debug(s"OTPActorFactory creating new actor (${builder.name})")
 *   } yield builder.toActor(ctx)
 * ```
 *
 * The roles of components involved in creation of a mailbox
 * - ActorBuilder - configures properties of the actor such as
 *   - capacity of a mailbox
 *   - name of an actor/process
 *   - specify actor's constructor
 *   - call actor's constructor to create actor object
 * - OTPActorFactory - builds OTPProcessContext and calls ActorBuilder.toActor
 * - OTPProcessContext - internal state of an actor/process, such as
 *   - mailbox
 *   - engineId
 *   - workerId
 *   - nodeName
 * - OTPNode - interface to jinterface.OtpNode
 *   - create jinterface.OtpMbox
 *   - optionally register name
 *   - prepare arguments to create OTPProcessContext
 *     - populate jinterface.OtpMbox
 *     - populate ActorBuilder
 *   - call OTPActorFactory.create(builder, ctx)
 *
 * TODO
 *
 * 1. To implement Service we would need to implement Request/Response
 * model. In order to do it we would need to ba able to match reference
 * extracted from the incoming event `{ref(), term()}` and match it
 * against the callers awaiting for response.
 *
 * One way of doing it is to add `inFlight` field to the OTPMailbox
 * This field could be of `Map[ERef, Promise[Eterm]]` type.
 * We would create the Promise when we do `mailbox.call` and resolve from
 * `messageEnvelopeStream`.
 * We can use `.collect` on a stream. With this approach we would effectively
 * kill two birds with one stone. We would remove the event from the queue
 * and resolve the promise.
 *
 * 2. Change otpMsgStream to take up to configured maximum number of messages
 * from the mbox.
 */

class OTPMailbox private (
  val id: Address,
  private val compositeMailbox: Queue[MessageEnvelope],
  private val internalMailbox: Queue[MessageEnvelope],
  private val remoteStream: ZStream[Any, Throwable, MessageEnvelope],
  val externalMailbox: OtpMbox,
  // TODO: Make it private and make `run` public instead in the trait
  val stream: ZStream[Any, Throwable, MessageEnvelope]
) extends Mailbox {
  def capacity: Int = compositeMailbox.capacity
  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    compositeMailbox.awaitShutdown <&> internalMailbox.awaitShutdown
  }
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    compositeMailbox.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    compositeMailbox.shutdown <&> internalMailbox.shutdown
  }
  def offer(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    internalMailbox.offer(msg)
  }
  def offerAll[A1 <: MessageEnvelope](as: Iterable[A1])(implicit trace: zio.Trace): UIO[zio.Chunk[A1]] = {
    internalMailbox.offerAll(as)
  }
  def size(implicit trace: zio.Trace): UIO[Int] = {
    compositeMailbox.size
  }

  /*
   * TODO
   * [ ] - unlink
   * [ ] - link
   * [ ] - monitor
   * [ ] - demonitor
   * [ ] - makeRef
   */
  def exit(reason: Codec.ETerm): UIO[Unit] = {
    // println(s"OTPMailbox.exit($reason), id = $id")
    for {
      _ <- offer(MessageEnvelope.Exit(None, id, reason, 0))
    } yield ()
  }

  def unlink(to: Codec.EPid)                  = ???
  def link(to: Codec.EPid)                    = ???
  def monitor(monitored: Address): Codec.ERef = ???
  def demonitor(ref: Codec.ERef)              = ???
  def makeRef(): Codec.ERef                   = ???

  /**
   * @param msg
   * @param trace
   * @return
   *   a result of the call in the form of a Eterm
   */
  def call(message: MessageEnvelope.Call)(implicit trace: zio.Trace): UIO[MessageEnvelope.Response] = {
    // TODO implement Request/Response model here
    // offer(msg)
    // TODO returning dummy value for now
    ZIO.succeed(MessageEnvelope.Response(message.from, message.to, message.tag, message.payload, message.workerId))
  }

  def cast(message: MessageEnvelope.Cast)(implicit trace: zio.Trace): UIO[Unit] = for {
    _ <- offer(message)
  } yield ()

  def send(message: MessageEnvelope.Send)(implicit trace: zio.Trace): UIO[Unit] = {
    // println(s"OTPMailbox.send($msg)")
    ZIO.succeed(message.to match {
      case PID(pid, _workerId) =>
        externalMailbox.send(
          pid.toOtpErlangObject,
          message.payload.toOtpErlangObject
        ) // TODO bypass jinterface for local
      case Name(name, _workerId) =>
        externalMailbox.send(name.toString, message.payload.toOtpErlangObject) // TODO bypass jinterface for local
      case NameOnNode(name, node, _workerId) =>
        externalMailbox.send(name.toString, node.toString, message.payload.toOtpErlangObject)
    })
  }

  def start(scope: Scope) = for {
    _ <- ZIO.addFinalizer(shutdown)
    _ <- ZStream.fromQueueWithShutdown(internalMailbox).mapZIO(compositeMailbox.offer(_)).runDrain.forkIn(scope)
    _ <- remoteStream.mapZIO(compositeMailbox.offer(_)).runDrain.forkIn(scope)
  } yield ()
}

object OTPMailbox {
  def make(ctx_builder: OTPProcessContext.Ready): UIO[OTPMailbox] = {
    // It is safe to use .get because we require Ready state
    val externalMailbox = ctx_builder.getMbox()
    val workerId        = ctx_builder.getWorkerId()
    val capacity        = ctx_builder.getCapacity()
    val pid             = Codec.fromErlang(externalMailbox.self).asInstanceOf[Codec.EPid]
    val address         = Address.fromPid(pid, workerId)
    val remoteStream    = messageEnvelopeStream(externalMailbox, workerId)
    def createMailbox(compositeMailbox: Queue[MessageEnvelope], internalMailbox: Queue[MessageEnvelope]): OTPMailbox = {
      val aggregatedStream = ZStream.fromQueueWithShutdown(compositeMailbox)
      new OTPMailbox(address, compositeMailbox, internalMailbox, remoteStream, externalMailbox, aggregatedStream)
    }
    capacity match {
      case None =>
        for {
          compositeMailbox <- Queue.unbounded[MessageEnvelope]
          internalMailbox  <- Queue.unbounded[MessageEnvelope]
        } yield createMailbox(compositeMailbox, internalMailbox)
      case Some(capacity) =>
        for {
          compositeMailbox <- Queue.bounded[MessageEnvelope](capacity)
          internalMailbox  <- Queue.bounded[MessageEnvelope](capacity)
        } yield createMailbox(compositeMailbox, internalMailbox)
    }
  }

  private def messageEnvelopeStream(
    externalMailbox: OtpMbox,
    workerId: Engine.WorkerId
  ): ZStream[Any, Throwable, MessageEnvelope] = {
    ZStream
      .repeatZIO(readMessage(externalMailbox, workerId))
      .collect { case Some(message) => message }
    // .tap(x => Console.printLine(s"mailbox ETerm stream: $x"))
  }
  // Here I tried to parse events in parallel fibers turned out it is a bit slower (BUT not by much).
  // otpMsgStream(mbox).mapZIOPar(100)(msg => ZIO.succeed(MessageEnvelope.fromOtpMsg(msg, workerId)))

  // TODO 1. set timeout 0 to leave attemptBlocking section as fast as we can
  // TODO 2. return up to chunk size messages from attemptBlocking and flatten outside
  // TODO 3. put a rate limit on consumer side
  private def readMessage(
    externalMailbox: OtpMbox,
    workerId: Engine.WorkerId
  ): ZIO[Any, Throwable, Option[MessageEnvelope]] = {
    ZIO.attemptBlocking {
      try {
        // TODO: Ignore "net_kernel" events
        // very small timeout so we leave the blocking section sooner
        val message = externalMailbox.receiveMsg(1)
        Some(MessageEnvelope.fromOtpMsg(message, workerId))
      } catch {
        case _: java.lang.InterruptedException => None
        case otpException: OtpErlangException => {
          val pid = Codec.fromErlang(externalMailbox.self).asInstanceOf[Codec.EPid]
          Some(MessageEnvelope.fromOtpException(otpException, pid, workerId))
        }
      }
    }
  }

  private def mockMessage(workerId: Engine.WorkerId) = {
    MessageEnvelope.makeSend(
      Address.fromName(Codec.EAtom(Symbol("some")), workerId),
      Codec.EAtom(Symbol("hello")),
      workerId
    )
  }

}
