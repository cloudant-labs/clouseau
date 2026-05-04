package com.cloudant.ziose.clouseau

import com.cloudant.ziose.macros.CheckEnv
import com.cloudant.ziose.{core, scalang}
import core.{Actor, ActorBuilder, AddressableActor, EngineWorker, Node, ProcessContext, Result}
import scalang.{Adapter, Pid, Process, SNode, ScalangMeterRegistry, Service}
import zio.Exit.{Failure, Success}
import zio.{&, LogLevel, Runtime, Tag, ZIO}

class ClouseauNode(implicit
  override val runtime: Runtime[EngineWorker & Node],
  worker: EngineWorker,
  metricsRegistry: ScalangMeterRegistry,
  logLevel: LogLevel
) extends SNode(metricsRegistry, logLevel)(runtime) {
  /*
   * Each service would need to implement a constructor in the following form
   *
   * ```scala
   * import com.cloudant.ziose.scalang.{Node => SNode}
   *
   * object ClouseauSupervisor extends ActorConstructor[ClouseauSupervisor] {
   *   def make(node: SNode, service_ctx: ServiceContext[ConfigurationArgs]) = {
   *     def maker[PContext <: ProcessContext](process_context: PContext): ClouseauSupervisor =
   *       ClouseauSupervisor(service_ctx)(Adapter(process_context, node))
   *
   *     ActorBuilder()
   *       .withCapacity(16)
   *       .withName("ClouseauSupervisor")
   *       .withMaker(maker)
   *       .build(this)
   *     }
   *
   *   def start(node: SNode, config: Configuration) = {
   *     val ctx = new ServiceContext[ConfigurationArgs] {val args = ConfigurationArgs(config)}
   *     node.spawnServiceZIO[ClouseauSupervisor, ConfigurationArgs](make(node, ctx))
   *   }
   * }
   * ```
   */

  val workerId = worker.id

  override def spawn(fun: Process => Unit): Pid = {
    val addressableActor: AddressableActor[_ <: Actor, _ <: ProcessContext] = (
      for {
        addressable <- worker.spawn(SimpleProcess.make(this, fun))
      } yield addressable
    ).unsafeRunAndGetWith(runtime)
    addressableActor.self
  }

  override def spawnService[TS <: Service[A] & Actor: Tag, A <: Product](
    builder: ActorBuilder.Sealed[TS]
  )(implicit adapter: Adapter[_, _]): Result[Node.Error, AddressableActor[TS, ProcessContext]] = {
    val result = spawnServiceZIO[TS, A](builder).unsafeRunWith(runtime) // TODO: kill the caller
    result match {
      case Failure(cause) if cause.isFailure     => core.Failure(cause.failureOption.get)
      case Failure(cause) if cause.isDie         => core.Failure(Node.Error.Unknown(cause.dieOption.get))
      case Failure(cause) if cause.isInterrupted => core.Failure(Node.Error.Interrupt(cause.interruptOption.get))
      case Failure(cause: Node.Error)            => core.Failure(cause)
      case Failure(cause)                        => core.Failure(Node.Error.Unknown(new Throwable(cause.prettyPrint)))
      case Success(actor) => core.Success(actor.asInstanceOf[AddressableActor[TS, ProcessContext]])
    }
  }

  override def spawnService[TS <: Service[A] & Actor: Tag, A <: Product](
    builder: ActorBuilder.Sealed[TS],
    reentrant: Boolean
  )(implicit adapter: Adapter[_, _]): Result[Node.Error, AddressableActor[TS, ProcessContext]] = {
    // TODO Handle reentrant argument
    val result = spawnServiceZIO[TS, A](builder, reentrant).unsafeRunWith(runtime) // TODO: kill the caller
    result match {
      case Failure(cause) if cause.isFailure     => core.Failure(cause.failureOption.get)
      case Failure(cause) if cause.isDie         => core.Failure(Node.Error.Unknown(cause.dieOption.get))
      case Failure(cause) if cause.isInterrupted => core.Failure(Node.Error.Interrupt(cause.interruptOption.get))
      case Failure(cause: Node.Error)            => core.Failure(cause)
      case Failure(cause)                        => core.Failure(Node.Error.Unknown(new Throwable(cause.prettyPrint)))
      case Success(actor) => core.Success(actor.asInstanceOf[AddressableActor[TS, ProcessContext]])
    }
  }

  override def spawnServiceZIO[TS <: Service[A] & Actor: Tag, A <: Product](
    builder: ActorBuilder.Sealed[TS]
  ): ZIO[Node & EngineWorker, _ <: Node.Error, AddressableActor[TS, _ <: ProcessContext]] = {
    for {
      addressable <- worker.spawn[TS](builder)
    } yield addressable
  }

  override def spawnServiceZIO[TS <: Service[A] & Actor: Tag, A <: Product](
    builder: ActorBuilder.Sealed[TS],
    reentrant: Boolean
  ): ZIO[Node & EngineWorker, _ <: Node.Error, AddressableActor[TS, _ <: ProcessContext]] = {
    // TODO Handle reentrant argument
    for {
      addressable <- worker.spawn[TS](builder)
    } yield addressable
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"runtime=$runtime",
    s"worker=$worker",
    s"metricsRegistry=$metricsRegistry"
  )
}
