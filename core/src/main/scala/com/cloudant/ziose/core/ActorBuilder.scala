package com.cloudant.ziose.core

import com.cloudant.ziose.macros.checkEnv

trait ActorBuilder[A <: Actor]

object ActorBuilder {
  type Sealed[A <: Actor] = ActorBuilder.Builder[A, ActorBuilder.State.Spawnable]
  sealed trait State
  object State {
    sealed trait Initial     extends State
    sealed trait Maker       extends State
    sealed trait Constructor extends State
    type Ready     = Initial with Maker
    type Spawnable = Ready with Constructor
  }

  case class Builder[A <: Actor, S <: State] private (
    val capacity: Option[Int] = None,
    val name: Option[String] = None,
    val constructor: Option[ActorConstructor[A]] = None,
    // We erase the type here. But since the only way to set this field is via
    // withMaker it is good enough
    val maker: Option[(Any) => A] = None
  ) {
    /*
     * Specify the size of the message queue of an actor.
     * When actor would reach the configured threshold
     * the sender of a new message would be blocked.
     */
    def withCapacity(capacity: Int): Builder[A, S] = {
      this.copy(capacity = Some(capacity))
    }

    /*
     * Specify the name of a registered actor.
     */
    def withName(name: String): Builder[A, S] = {
      this.copy(name = Some(name))
    }

    /*
     * This function must be called from the actor constructor to specify a
     * signature of the actor class.
     *
     * Let's say we have following class to be used as an Actor.
     *
     * ```scala
     * case class MyActor(foo: String, bar: Int)(implicit ctx: ProcessContext) extends Actor {
     *   def onMessage(msg: MessageEnvelope): UIO[Unit] =
     *     ZIO.succeed(())
     *   def onTermination(reason: Codec.ETerm): UIO[Unit] =
     *     ZIO.succeed(())
     * }
     * ```
     *
     * Since the number and type of parameters in the class is unique for each actor type we need
     * to pass a callback function which would create an actor of desired type.
     * This callback function for our particular example is specified as
     * ```scala
     * def make(foo: String, bar: Int) = {
     *    def maker[PContext <: ProcessContext](ctx: PContext): MyActor =
     *         MyActor(foo, bar)(ctx)
     *
     *     ActorBuilder()
     *       ...
     *       .withMaker(maker)
     *       ...
     * ```
     *
     * The complete constructor might look like
     *
     * ```scala
     * object MyActor extends ActorConstructor[MyActor] {
     *   def make(foo: String, bar: Int) = {
     *     def maker[PContext <: ProcessContext](ctx: PContext): MyActor =
     *       MyActor(foo, bar)(ctx)
     *
     *     ActorBuilder()
     *       .withCapacity(16)
     *       .withMaker(maker)
     *       .build(this)
     *   }
     * }
     * ```
     */
    def withMaker[C <: ProcessContext](maker: (C) => A): Builder[A, S with State.Maker] = {
      this.copy(maker = Some(maker.asInstanceOf[Any => A]))
    }

    /*
     * This method is used to finish the chain of parameters in the constructor of an actor.
     *
     * Example of using:
     * ```scala
     * object MyActor extends ActorConstructor[MyActor] {
     *   def make(name: String, config: Configuration) = {
     *     def maker[PContext <: ProcessContext](ctx: PContext): MyActor =
     *       MyActor(foo, bar)(ctx)

     *     ActorBuilder()
     *      .withCapacity(config.capacity)
     *      .withName(name)
     *      .withMaker(maker)
     *      .build(this) // <<<<< This method
     * ```
     */

    def build(implicit constructor: ActorConstructor[A]): Builder[A, S with State.Constructor] = {
      this.copy(constructor = Some(constructor))
    }

    /*
     * Given an `ActorBuilder` (in `Sealed` state) creates the object of a class which
     * extends the `Actor`.
     * This function is not supposed to be called directly.
     * It is used by classes which extend `ActorFactory` trait.
     */
    def toActor[C <: ProcessContext](ctx: C)(implicit ev: S =:= State.Spawnable): AddressableActor[A, C] = {
      val constructor = maker.get.asInstanceOf[C => A]
      AddressableActor(constructor(ctx), ctx)
    }

    @checkEnv(System.getProperty("env"))
    def toStringMacro: List[String] = List(
      s"${getClass.getSimpleName}",
      s"capacity=$capacity",
      s"name=$name",
      s"constructor=$constructor",
      s"maker=$maker"
    )
  }

  object Builder {
    def apply[A <: Actor]() = new Builder[A, State.Initial]()
  }

  def apply[A <: Actor]() = Builder[A]()
}
