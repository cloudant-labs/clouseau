package com.cloudant.ziose.clouseau

import com.cloudant.ziose.scalang.SNode
import com.cloudant.ziose.core
import core.ProcessContext
import com.cloudant.ziose.scalang.Service
import com.cloudant.ziose.scalang.Adapter
import zio._
import zio.Runtime
import core.Actor
import core.AddressableActor
import core.ActorBuilder

class ClouseauNode(implicit override val runtime: Runtime[core.EngineWorker & core.Node], worker: core.EngineWorker)
    extends SNode()(runtime) { self =>
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

  override def spawnService[TS <: Service[A] with Actor: Tag, A <: Product](
    builder: ActorBuilder.Sealed[TS]
  )(implicit adapter: Adapter[_, _]) = {
    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          spawnServiceZIO[TS, A](builder)
        )
        .getOrThrowFiberFailure()
    } // TODO: kill the caller
    result.asInstanceOf[AddressableActor[TS, ProcessContext]]
  }

  override def spawnService[TS <: Service[A] with Actor: Tag, A <: Product](
    builder: ActorBuilder.Sealed[TS],
    reentrant: Boolean
  )(implicit adapter: Adapter[_, _]) = {
    // TODO Handle reentrant argument
    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          spawnServiceZIO[TS, A](builder, reentrant)
        )
        .getOrThrowFiberFailure()
    } // TODO: kill the caller
    result.asInstanceOf[AddressableActor[TS, ProcessContext]]
  }

  override def spawnServiceZIO[TS <: Service[A] with Actor: Tag, A <: Product](builder: ActorBuilder.Sealed[TS]) = {
    for {
      addressable <- worker.spawn[TS](builder)
    } yield addressable
  }

  override def spawnServiceZIO[TS <: Service[A] with Actor: Tag, A <: Product](
    builder: ActorBuilder.Sealed[TS],
    reentrant: Boolean
  ) = {
    // TODO Handle reentrant argument
    for {
      addressable <- worker.spawn[TS](builder)
    } yield addressable
  }
}
