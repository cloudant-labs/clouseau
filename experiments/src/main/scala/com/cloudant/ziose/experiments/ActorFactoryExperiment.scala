package com.cloudant.ziose.experiments.afe // afe - ActorFactorExperiment

// format: off
/**
 * Running from sbt experiments/runMain com.cloudant.ziose.experiments.afe.ActorFactoryExperiment
 *
 * # Goals of the experiment
 *
 *   1. Test out the use of a builder pattern to construct an instance of a class with arbitrary parameters.
 *   2. Make sure we can swap the implementation from OTP to GRPC (dummy classes).
 *   3. Learn how `ZIO.environmentWithZIO` and `ZLayer` can be used for dependency injection pattern.
 *   4. Learn how ZIO finalizers work.
 *   5. Run two nodes of different kind (OTP and GRPC) in parallel.
 *
 * # Context
 *
 * We develop the modernization project with anticipation that eventually jinterface might go away and will be replaced
 * with something else. It will be very convenient during deployment to support a mix mode where the search component
 * can be configured to support both protocols at the same time. In order to do that we need an additional indirection.
 * Typically similar problems are solved with dependency injection pattern. The standard approach for dependency
 * injection in ZIO is service pattern. Which is a combination of a `ZIO.environmentWithZIO` (or `ZIO.environment`) with
 * `ZLayer`.
 *
 * # Constrains
 *
 *   1. The core has to be generic and be able to work transparently with any underlying communication library.
 *   2. The constructed classes are arbitrary in a sense that they receive unknown arguments which
 *      makes instantiation of them harder. Since instantiation is triggered by the node on actor spawn.
 *   3. There is a need to support multiple transports simultaneously.
 *
 * # Instructions
 *
 * 1. Start sbt
 * ```
 * > sbt
 * sbt:ziose>
 * ```
 * 2. Run experiment in sbt
 * ```
 * sbt:ziose> experiments/runMain com.cloudant.ziose.experiments.afe.ActorFactoryExperiment
 * ```
 */

import zio._
import zio.logging._
import zio.stream.ZStream

trait Actor {
  def handle_event(event: Event): UIO[Unit]
}

trait ActorConstructor[A <: Actor] {
  type AType <: A
}

case class MyActor private (foo: String, counter: Int = 0)(implicit mbox: Mailbox) extends Actor {
  def handle_event(event: Event): UIO[Unit] = {
    ZIO.debug(s"MyActor($foo, ${mbox.name}) event: $event")
  }
}

object MyActor extends ActorConstructor[MyActor] {
  def make(foo: String, capacity: Int, name: String): ActorBuilder.Sealed[MyActor] = {
    ActorBuilder[MyActor]()
      .withCapacity(capacity)
      .withName(name)
      .withMaker(MyActor(foo)(_))
      .build(this)
  }
}

case class Event()

trait Mailbox {
  val name: Option[String]
}
case class OTPMailbox(val name: Option[String] = None)  extends Mailbox
case class GRPCMailbox(val name: Option[String] = None) extends Mailbox

case class OTPActor() extends Actor {
  def handle_event(event: Event): UIO[Unit] = {
    ZIO.debug(s"OTPActor event: $event")
  }
}
case class GRPCActor() extends Actor {
  def handle_event(event: Event): UIO[Unit] = {
    ZIO.debug(s"GRPCActor event: $event")
  }
}

trait EngineWorker {
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[EngineWorker, Node.Error, A]
  def kind: ZIO[EngineWorker, Nothing, String]
}

object EngineWorker {
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[EngineWorker, Node.Error, A] = {
    ZIO.environmentWithZIO[EngineWorker](_.get.spawn(builder))
  }
  def kind: ZIO[EngineWorker, Nothing, String] = {
    ZIO.environmentWithZIO[EngineWorker](_.get.kind)
  }
}

final case class OTPEngineWorker(name: String, node: OTPNode) extends EngineWorker {
  def acquire: UIO[Unit] = {
    ZIO.debug(s"Acquired OTPEngineWorker")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released OTPEngineWorker")
  }
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[EngineWorker, Node.Error, A] = {
    node.spawn(builder)
  }
  def kind = ZIO.succeed("OTP")
}

object OTPEngineWorker {
  def live(name: String): ZLayer[OTPNode, Throwable, EngineWorker] = ZLayer {
    for {
      node <- ZIO.service[OTPNode]
    } yield new OTPEngineWorker(name, node)
  }
}

final case class GRPCEngineWorker(name: String, node: GRPCNode) extends EngineWorker {
  def acquire: UIO[Unit] = {
    ZIO.debug(s"Acquired OTPEngineWorker")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released OTPEngineWorker")
  }
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[EngineWorker, Node.Error, A] = {
    node.spawn(builder)
  }
  def kind = ZIO.succeed("GRPC")
}

object GRPCEngineWorker {
  def live(name: String): ZLayer[GRPCNode, Throwable, EngineWorker] = ZLayer {
    for {
      node <- ZIO.service[GRPCNode]
    } yield new GRPCEngineWorker(name, node)
  }
}

trait Node {
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[Node, Node.Error, A]
  def factory(): ZIO[ActorFactory, Throwable, ActorFactory]
}

object Node {
  trait Error                    extends Throwable
  case class DisconnectedError() extends Error

  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[Node, Node.Error, A] = {
    ZIO.environmentWithZIO[Node](_.get.spawn(builder))
  }
}

final case class OTPNode(name: String, f: OTPActorFactory) extends Node {
  def factory() = ZIO.succeed(f)
  def acquire: UIO[Unit] = {
    ZIO.debug(s"Acquired OTPEngineWorker")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released OTPEngineWorker")
  }
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[Any, Node.Error, A] = for {
    _     <- ZIO.debug(s"[OTPNode] spawning new actor...")
    actor <- f.create[A](builder)
  } yield actor
}

object OTPNode {
  def live(name: String): ZLayer[OTPActorFactory, Throwable, OTPNode] = ZLayer.scoped {
    for {
      _       <- ZIO.debug("Constructing OTPNode")
      factory <- ZIO.service[OTPActorFactory]
      service = new OTPNode(name, factory)
      _ <- service.acquire
      _ <- ZIO.addFinalizer(service.release)
    } yield service
  }
}

final case class GRPCNode(name: String, f: GRPCActorFactory) extends Node {
  def factory() = ZIO.succeed(f)
  def acquire: UIO[Unit] = {
    ZIO.debug(s"Acquired OTPEngineWorker")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released OTPEngineWorker")
  }
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[Any, Node.Error, A] = for {
    _     <- ZIO.debug("[GRPCNode] spawning new actor...")
    actor <- f.create[A](builder)
  } yield actor
}

object GRPCNode {
  def live(name: String): ZLayer[GRPCActorFactory, Throwable, GRPCNode] = ZLayer.scoped {
    for {
      _       <- ZIO.debug("Constructing GRPCNode")
      factory <- ZIO.service[GRPCActorFactory]
      service = new GRPCNode(name, factory)
      _ <- service.acquire
      _ <- ZIO.addFinalizer(service.release)
    } yield service
  }
}

case class ActorFactoryOptions(
  val capacity: Option[Int] = None,
  val name: Option[String] = None
) {}

trait ActorFactory {
  def create[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[ActorFactory, Node.Error, A]
}

object ActorFactory {
  def create[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[ActorFactory, Node.Error, A] = {
    ZIO.environmentWithZIO[ActorFactory](_.get.create(builder))
  }
}

final case class OTPActorFactory(name: String) extends ActorFactory {
  def acquire: UIO[Unit] = {
    ZIO.debug(s"Acquired OTPActorFactory")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released OTPActorFactory")
  }
  def create[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[Any, Node.Error, A] = {
    for {
      _ <- ZIO.debug(s"OTPActorFactory creating new actor (${builder.name})")
      mbox  = OTPMailbox(builder.name)
      actor = builder.toActor(mbox)
    } yield actor
  }
}

object OTPActorFactory {
  def live(name: String): ZLayer[Any, Nothing, OTPActorFactory] = ZLayer.scoped {
    for {
      _ <- ZIO.debug("Constructing OTPActorFactory")
      service = new OTPActorFactory(name)
      _ <- service.acquire
      _ <- ZIO.addFinalizer(service.release)
    } yield service
  }
}

final case class GRPCActorFactory(name: String) extends ActorFactory {
  def acquire: UIO[Unit] = {
    ZIO.debug(s"Acquired GRPCActorFactory")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released GRPCctorFactory")
  }
  def create[A <: Actor](builder: ActorBuilder.Sealed[A]): ZIO[Any, Node.Error, A] = {
    for {
      _ <- ZIO.debug(s"GRPCActorFactory creating new actor (${builder.name})")
      mbox  = GRPCMailbox(builder.name)
      actor = builder.toActor(mbox)
    } yield actor
  }
}

object GRPCActorFactory {
  def live(name: String): ZLayer[Any, Nothing, GRPCActorFactory] = ZLayer.scoped {
    for {
      _ <- ZIO.debug("Constructing OTPActorFactory")
      service = new GRPCActorFactory(name)
      _ <- service.acquire
      _ <- ZIO.addFinalizer(service.release)
    } yield service
  }
}

sealed trait BuilderState
object BuilderState {
  sealed trait Initial extends BuilderState
  sealed trait Mailbox extends BuilderState
  type Spawnable = Initial with Mailbox
}

case class ActorBuilder[A <: Actor, S <: BuilderState] private (
  val capacity: Option[Int] = None,
  val name: Option[String] = None,
  val fromMailbox: Option[ActorConstructor[A]] = None,
  val maker: Option[(Mailbox) => A] = None
) {
  def withCapacity(capacity: Int): ActorBuilder[A, S] = {
    this.copy(capacity = Some(capacity))
  }

  def withName(name: String): ActorBuilder[A, S] = {
    this.copy(name = Some(name))
  }

  def withMaker(maker: (Mailbox) => A): ActorBuilder[A, S] = {
    this.copy(maker = Some(maker))
  }

  def build(implicit constructor: ActorConstructor[A]): ActorBuilder[A, S with BuilderState.Mailbox] = {
    this.copy(fromMailbox = Some(constructor))
  }

  def toActor[M <: Mailbox](mbox: M)(implicit ev: S =:= BuilderState.Spawnable) = {
    val constructor = maker.get
    constructor(mbox)
  }
}

object ActorBuilder {
  type Sealed[A <: Actor] = ActorBuilder[A, BuilderState.Spawnable]

  def apply[A <: Actor]() = new ActorBuilder[A, BuilderState.Initial]()
}

object NodeLoop {
  val live = ZLayer.fromZIO(ZStream.range(0, 255).runDrain.forever)
}

object ActorFactoryExperiment extends ZIOAppDefault {
  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)
  val otp = OTPEngineWorker.live("OTP") ++ OTPNode.live("OTP") ++ OTPActorFactory.live("OTP") ++ ZLayer.Debug.tree
  val grpc = {
    GRPCEngineWorker.live("GRPC") ++ GRPCNode.live("GRPC") ++ GRPCActorFactory.live("GRPC") ++ ZLayer.Debug.tree
  }

  val effect: ZIO[EngineWorker, Throwable, Unit] = for {
    name <- EngineWorker.kind
    _    <- ZIO.debug(s"Worker $name")
    actor <- EngineWorker
      .spawn(
        MyActor.make(s"Foo for $name Actor", 16, s"$name Actor").withName("new name")
      )
      .debug("actor")
    result <- actor.handle_event(Event())
  } yield result

  val program: ZIO[Scope, Throwable, Unit] = {
    for {
      // The warning here is by design
      _ <- ZIO
        .scoped(effect)
        .provide(
          OTPEngineWorker.live("OTP"),
          OTPNode.live("OTP"),
          OTPActorFactory.live("OTP"),
          ZLayer.Debug.tree
        )
      // The warning here is by design
      _ <- ZIO
        .scoped(effect)
        .provide(
          GRPCEngineWorker.live("GRPC"),
          GRPCNode.live("GRPC"),
          GRPCActorFactory.live("GRPC"),
          ZLayer.Debug.tree
        )
    } yield ()
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