package com.cloudant.ziose.otp

import zio._
import zio.stream.ZStream

import com.cloudant.ziose.core.Mailbox

import com.ericsson.otp.erlang.OtpMbox

import com.ericsson.otp.erlang.OtpMsg
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
 *   - stream of events (encoded as ETerm) received from remote node
 *   - queue of events (encoded as ETerm) received internally
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
 *   _ <- actor.stream.runDrain.forever.forkScoped
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
 * otpETermStream.
 * We can use `.collect` on a stream. With this approach we would effectively
 * kill two birds with one stone. We would remove the event from the queue
 * and resolve the promise.
 *
 * 2. Change otpMsgStream to take up to configured maximum number of messages
 * from the mbox.
 */

class OTPMailbox private (
  val id: Address,
  private val queue: Queue[MessageEnvelope],
  private val buffer: Queue[MessageEnvelope],
  private val remoteStream: ZStream[Any, Throwable, MessageEnvelope],
  val mbox: OtpMbox,
  // TODO: Make it private and make `run` public instead in the trait
  val stream: ZStream[Any, Throwable, MessageEnvelope]
) extends Mailbox {
  def capacity: Int = queue.capacity
  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    queue.awaitShutdown <&> buffer.awaitShutdown
  }
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    queue.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    queue.shutdown <&> buffer.shutdown
  }
  def offer(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    buffer.offer(msg)
  }
  def offerAll[A1 <: MessageEnvelope](as: Iterable[A1])(implicit trace: zio.Trace): UIO[zio.Chunk[A1]] = {
    buffer.offerAll(as)
  }
  def size(implicit trace: zio.Trace): UIO[Int] = {
    queue.size
  }

  /*
   * TODO
   * [ ] - exit
   * [ ] - unlink
   * [ ] - link
   * [ ] - monitor
   * [ ] - demonitor
   * [ ] - makeRef
   */
  def exit(reason: Codec.ETerm)               = ???
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
  def call(msg: MessageEnvelope.Call)(implicit trace: zio.Trace): UIO[MessageEnvelope.Response] = {
    // TODO implement Request/Response model here
    // offer(msg)
    // TODO returning dummy value for now
    ZIO.succeed(MessageEnvelope.Response(msg.from, msg.to, msg.tag, msg.payload, msg.workerId))
  }
  def cast(msg: MessageEnvelope.Cast)(implicit trace: zio.Trace): UIO[Unit] = {
    ZIO.succeed(offer(msg))
  }
  def send(msg: MessageEnvelope.Send)(implicit trace: zio.Trace): UIO[Unit] = {
    // println(s"OTPMailbox.send($msg)")
    ZIO.succeed(msg.to match {
      case PID(pid, _workerId) =>
        mbox.send(pid.toOtpErlangObject, msg.payload.toOtpErlangObject) // TODO bypass jinterface for local
      case Name(name, _workerId) =>
        mbox.send(name.toString(), msg.payload.toOtpErlangObject) // TODO bypass jinterface for local
      case NameOnNode(name, node, _workerId) =>
        mbox.send(name.toString(), node.toString(), msg.payload.toOtpErlangObject)
    })
  }

  def start(scope: Scope) = for {
    _ <- ZIO.addFinalizer(ZIO.succeed(shutdown))
    _ <- ZStream.fromQueue(buffer).mapZIO(queue.offer(_)).runDrain.forever.forkIn(scope)
    _ <- remoteStream.mapZIO(queue.offer(_)).runDrain.forever.forkIn(scope)
  } yield ()
}

object OTPMailbox {
  def make(ctx_builder: OTPProcessContext.Ready): UIO[OTPMailbox] = {
    // It is safe to use .get because we require Ready state
    val mbox     = ctx_builder.getMbox()
    val workerId = ctx_builder.getWorkerId()
    val capacity = ctx_builder.getCapacity()
    val pid      = Codec.fromErlang(mbox.self).asInstanceOf[Codec.EPid]
    val address  = Address.fromPid(pid, workerId)
    capacity match {
      case None =>
        for {
          queue  <- Queue.unbounded[MessageEnvelope]
          buffer <- Queue.unbounded[MessageEnvelope]
          remoteStream     = otpETermStream(mbox, workerId)
          aggregatedStream = ZStream.fromQueue(queue)
        } yield new OTPMailbox(address, queue, buffer, remoteStream, mbox, aggregatedStream)
      case Some(capacity) =>
        for {
          queue  <- Queue.bounded[MessageEnvelope](capacity)
          buffer <- Queue.bounded[MessageEnvelope](capacity)
          remoteStream     = otpETermStream(mbox, workerId)
          aggregatedStream = ZStream.fromQueue(queue)
        } yield new OTPMailbox(address, queue, buffer, remoteStream, mbox, aggregatedStream)
    }
  }

  private def otpETermStream(mbox: OtpMbox, workerId: Engine.WorkerId): ZStream[Any, Throwable, MessageEnvelope] = {
    otpMsgStream(mbox).map(msg => {
      MessageEnvelope.fromOtpMsg(msg, workerId)
    }) // .tap(x => printLine(s"mailbox ETerm stream: $x"))
  }
  // Here I tried to parse events in parallel fibers turned out it is a bit slower (BUT not by much).
  // otpMsgStream(mbox).mapZIOPar(100)(msg => ZIO.succeed(MessageEnvelope.fromOtpMsg(msg, workerId)))

  // TODO 1. set timeout 0 to leave attemptBlocking section as fast as we can
  // TODO 2. return up to chunk size messages from attemptBlocking and flatten outside
  // TODO 3. put a rate limit on consumer side
  private def otpMsgStream(mbox: OtpMbox): ZStream[Any, Throwable, OtpMsg] = {
    ZStream
      .fromZIO(readMsg(mbox))
      // .tap(x => printLine(s"mailbox stream: $x"))
      .collect { case Some(msg) => msg }
  }
  // .tap(x => printLine(s"mailbox stream: $x"))

  private def readMsg(mbox: OtpMbox) = {
    ZIO.attemptBlocking {
      try {
        // TODO: Ignore "net_kernel" events
        Some(mbox.receiveMsg(1)) // very small timeout so we leave the blocking section sooner
      } catch {
        case e: java.lang.InterruptedException => None
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
