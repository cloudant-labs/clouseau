package com.cloudant.ziose.otp

import java.util.concurrent.atomic.AtomicBoolean
import collection.mutable.HashMap
import com.cloudant.ziose.core.Mailbox

import com.ericsson.otp.erlang.{
  OtpMbox,
  OtpMboxListener,
  OtpErlangException,
  OtpErlangExit,
  OtpErlangConnectionException,
  OtpErlangAtom
}

import com.cloudant.ziose.core.Codec
import com.cloudant.ziose.core.Address
import com.cloudant.ziose.core.MessageEnvelope
import com.cloudant.ziose.core.PID
import com.cloudant.ziose.core.Name
import com.cloudant.ziose.core.NameOnNode
import com.cloudant.ziose.core.Node
import com.cloudant.ziose.macros.CheckEnv
import zio._
import zio.stream.ZStream
import zio.Exit

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
 * a ForwardWithId where Address is used as an Id type.
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
 *     _ <- ZIO.logDebug(s"Creating new actor (${builder.name})")
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
  val id: PID,
  val mbox: OtpMbox,
  private val compositeMailbox: Queue[MessageEnvelope],
  private val internalMailbox: Queue[MessageEnvelope],
  private val externalMailbox: Queue[MessageEnvelope]
) extends Mailbox
    with OtpMboxListener {

  private val inProgressCalls: HashMap[Codec.ERef, Codec.EPid] = HashMap()
  private val callResults: HashMap[Codec.ERef, Codec.ETerm]    = HashMap()
  private var isFinalized: AtomicBoolean                       = new AtomicBoolean(false)

  def nextEvent = compositeMailbox.take.flatMap(e => ZIO.succeed(handleCall(e)))

  def handleCall(envelope: MessageEnvelope): Option[MessageEnvelope] = {
    def maybeConstructResult(ref: Codec.ERef, term: Codec.ETerm) = {
      if (inProgressCalls.contains(ref)) {
        inProgressCalls.remove(ref).map(pid => callResults.put(ref, term))
        None
      } else {
        Some(envelope)
      }
    }
    envelope match {
      case MessageEnvelope.Send(from, to, Codec.ETuple(ref: Codec.ERef, term: Codec.ETerm), workerId) =>
        maybeConstructResult(ref, term)
      case MessageEnvelope.Send(
            from,
            to,
            Codec.ETuple(Codec.EListImproper(Codec.EAtom("alias"), ref: Codec.ERef), term: Codec.ETerm),
            workerId
          ) =>
        maybeConstructResult(ref, term)
      case _ => Some(envelope)
    }
  }

  def capacity: Int = compositeMailbox.capacity
  def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    compositeMailbox.awaitShutdown <&> internalMailbox.awaitShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    ZIO.unit
  }
  def forward(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    internalMailbox.offer(msg)
  }

  // There is no easy way to account for externalMailbox
  // without consuming messages
  def size(implicit trace: zio.Trace): UIO[Int] = for {
    composite <- compositeMailboxSize
    internal  <- internalMailboxSize
  } yield composite + internal

  def compositeMailboxSize(implicit trace: Trace): UIO[Int] = {
    // there is a bug in zio.Queue it can return negative
    // size if there are no consumers
    for {
      size <- compositeMailbox.size
    } yield 0 max size
  }

  def internalMailboxSize(implicit trace: Trace): UIO[Int] = {
    // there is a bug in zio.Queue it can return negative
    // size if there are no consumers
    for {
      size <- internalMailbox.size
    } yield 0 max size
  }

  def exit(message: MessageEnvelope.Exit): UIO[Unit] = {
    for {
      _ <- forward(message)
    } yield ()
  }

  private def attempt[A](code: => A) = {
    ZIO.attempt(code).refineOrDie {
      case e: OtpErlangExit =>
        e.reason match {
          case atom: OtpErlangAtom =>
            atom.atomValue match {
              case "noproc"       => Node.Error.NoSuchActor()
              case "noconnection" => Node.Error.Disconnected()
              case _              => Node.Error.Unknown(e)
            }
          case _ =>
            Node.Error.Unknown(e)
        }
      case e: OtpErlangConnectionException =>
        Node.Error.ConnectionError(e)
      case e =>
        Node.Error.Unknown(e)
    }
  }

  def unlink(to: Codec.EPid): UIO[Unit] = {
    ZIO.succeed(mbox.unlink(to.toOtpErlangObject))
  }

  def link(to: Codec.EPid): ZIO[Any, _ <: Node.Error, Unit] = {
    attempt(mbox.link(to.toOtpErlangObject))
  }

  def monitor(monitored: Address): ZIO[Node, _ <: Node.Error, Codec.ERef] = {
    ZIO.blocking(for {
      node <- ZIO.service[Node]
      ref <- attempt(monitored match {
        case PID(pid, _workerId, _workerName) =>
          mbox.monitor(pid.toOtpErlangObject)
        case Name(name, _workerId, _workerName) =>
          mbox.monitorNamed(name.asString)
        case NameOnNode(name, node, _workerId, _workerName) =>
          mbox.monitorNamed(name.asString, node.asString)
      })
    } yield Codec.ERef(ref))
  }

  def demonitor(ref: Codec.ERef): UIO[Unit] = {
    ZIO.succeed(mbox.demonitor(ref.toOtpErlangObject))
  }

  /**
   * @param msg
   * @param trace
   * @return
   *   a result of the call in the form of a Eterm
   */

  def call(
    message: MessageEnvelope.Call
  )(implicit trace: zio.Trace): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response] = for {
    node <- ZIO.service[Node]
    ref  <- node.makeRef()
    _    <- ZIO.succeed(inProgressCalls += Tuple2(ref, message.from.get))
    _    <- forward(toSend(message, ref))
    result <- message.timeout match {
      case Some(duration) =>
        ZIO
          .succeed(callResults.remove(ref))
          // wait a bit before next try
          .delay(10.millis)
          .repeatUntil(o => o.nonEmpty)
          .map(o => o.get)
          .timeout(duration)
      case None =>
        ZIO
          .succeed(callResults.remove(ref))
          .repeatUntil(o => o.nonEmpty)
    }
  } yield message.toResponse(result)

  def cast(message: MessageEnvelope.Cast)(implicit trace: zio.Trace): UIO[Unit] = for {
    _ <- forward(message)
  } yield ()

  def send(message: MessageEnvelope.Send)(implicit trace: zio.Trace): UIO[Unit] = {
    // println(s"OTPMailbox.send($msg)")
    ZIO.succeedBlocking(message.to match {
      case PID(pid, _workerId, _workerNodeName) =>
        mbox.send(
          pid.toOtpErlangObject,
          message.payload.toOtpErlangObject
        ) // TODO bypass jinterface for local
      case Name(name, _workerId, _workerNodeName) =>
        mbox.send(name.toString, message.payload.toOtpErlangObject) // TODO bypass jinterface for local
      case NameOnNode(name, node, _workerId, _workerNodeName) =>
        mbox.send(name.toString, node.toString, message.payload.toOtpErlangObject)
    })
  }

  def start(scope: Scope.Closeable) = for {
    _ <- scope.addFinalizerExit(onExit)
    internalMailboxFiber <- ZStream
      .fromQueueWithShutdown(internalMailbox)
      .mapZIO(compositeMailbox.offer)
      .runDrain
      // Make sure we terminate the scope on Interruption
      .onTermination(cause => scope.close(Exit.failCause(cause)))
      .forkIn(scope)
    externalMailboxFiber <- ZStream
      .fromQueueWithShutdown(externalMailbox)
      .mapZIO(compositeMailbox.offer)
      .runDrain
      // Make sure we terminate the scope on Interruption
      .onTermination(cause => scope.close(Exit.failCause(cause)))
      .forkIn(scope)
    _ <- ZIO.succeed(mbox.subscribe(this))
  } yield Map(
    Symbol("internalMailboxConsumerFiber") -> internalMailboxFiber,
    Symbol("externalMailboxConsumerFiber") -> externalMailboxFiber
  )

  def onExit(exit: Exit[_, _]): UIO[Unit] = {
    if (!isFinalized.getAndSet(true)) {
      val reason = OTPError.fromExit(exit)
      if (reason != Codec.EAtom("shutdown")) {
        ZIO.succeedBlocking(mbox.exit(reason.toOtpErlangObject))
      } else { ZIO.unit }
    } else {
      ZIO.unit
    }
  }

  private def readMessage = {
    try {
      // TODO: Ignore "net_kernel" events
      val message = mbox.receiveMsg
      MessageEnvelope.fromOtpMsg(message, id)
    } catch {
      case otpException: OtpErlangException => {
        val pid = Codec.fromErlang(mbox.self).asInstanceOf[Codec.EPid]
        MessageEnvelope.fromOtpException(otpException, pid, id)
      }
    }
  }

  def onMessageReceived = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(for {
        message <- ZIO.succeed(readMessage)
        _       <- externalMailbox.offer(message)
      } yield ())
    }
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"id=$id",
    s"mbox=$mbox",
    s"compositeMailbox=$compositeMailbox",
    s"internalMailbox=$internalMailbox",
    s"externalMailbox=$externalMailbox",
    s"compositeMailbox.capacity=$capacity",
    s"compositeMailbox.size=$size"
  )

  def toSend(message: MessageEnvelope.Call, ref: Codec.ERef) = {
    val msg = Codec.ETuple(
      message.tag,
      Codec.ETuple(message.from.get, Codec.EListImproper(Codec.EAtom("alias"), ref)),
      message.payload
    )
    message.toSend(_ => msg)
  }
}

object OTPMailbox {
  def make(ctx_builder: OTPProcessContext.Ready): UIO[OTPMailbox] = {
    // It is safe to use .get because we require Ready state
    val mbox     = ctx_builder.getMbox()
    val workerId = ctx_builder.getWorkerId()
    val capacity = ctx_builder.getCapacity()
    val nodeName = ctx_builder.getNodeName()
    val pid      = Codec.fromErlang(mbox.self).asInstanceOf[Codec.EPid]
    val address  = Address.fromPid(pid, workerId, nodeName)
    def createMailbox(
      compositeMailbox: Queue[MessageEnvelope],
      internalMailbox: Queue[MessageEnvelope],
      externalMailbox: Queue[MessageEnvelope]
    ): OTPMailbox = {
      new OTPMailbox(address, mbox, compositeMailbox, internalMailbox, externalMailbox)
    }
    capacity match {
      case None =>
        for {
          compositeMailbox <- Queue.unbounded[MessageEnvelope]
          internalMailbox  <- Queue.unbounded[MessageEnvelope]
          externalMailbox  <- Queue.unbounded[MessageEnvelope]
        } yield createMailbox(compositeMailbox, internalMailbox, externalMailbox)
      case Some(capacity) =>
        for {
          compositeMailbox <- Queue.bounded[MessageEnvelope](capacity)
          internalMailbox  <- Queue.bounded[MessageEnvelope](capacity)
          externalMailbox  <- Queue.bounded[MessageEnvelope](capacity)
        } yield createMailbox(compositeMailbox, internalMailbox, externalMailbox)
    }
  }
}
