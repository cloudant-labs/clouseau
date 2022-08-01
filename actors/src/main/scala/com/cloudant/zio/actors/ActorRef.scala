package com.cloudant.zio.actors

import java.io.{ IOException, ObjectInputStream, ObjectOutputStream, ObjectStreamException }

import zio.nio.{ InetAddress, InetSocketAddress, SocketAddress }
import zio.nio.channels.AsynchronousSocketChannel
import zio.{ZIO, IO, Runtime, Task, UIO }

/**
 * Reference to actor that might reside on local JVM instance or be available via remote communication
 *
 * @tparam F wrapper type constructing DSL
 */
sealed trait ActorRef[-F[+_]] extends Serializable {

  /**
   * Send a message to an actor as `ask` interaction pattern -
   * caller is blocked until the response is received
   *
   * @param fa message
   * @tparam A return type
   * @return effectful response
   */
  def ?[A](fa: F[A]): Task[A]

  /**
   * Send message to an actor as `fire-and-forget` -
   * caller is blocked until message is enqueued in stub's mailbox
   *
   * @param fa message
   * @return lifted unit
   */
  def !(fa: F[_]): Task[Unit]

  /**
   * Get referential actor pid
   * @return
   */
  val pid: UIO[Pid]

  /**
   * Stops actor and all its children
   */
  val stop: Task[List[_]]

}

/* INTERNAL API */

private[actors] object ActorRefSerial {
  private[actors] val runtimeForResolve = Runtime.default
}

private[actors] sealed abstract class ActorRefSerial[-F[+_]](private var actorPid: Pid)
    extends ActorRef[F]
    with Serializable {
  import ActorSystemUtils._

  override val pid: UIO[Pid] = ZIO.succeed(actorPid)
}

private[actors] final class ActorRefLocal[-F[+_]](
  private val actorPid: Pid,
  actor: Actor[F]
) extends ActorRefSerial[F](actorPid) {
  override def ?[A](fa: F[A]): Task[A] = actor ? fa

  override def !(fa: F[_]): Task[Unit] = actor ! fa

  override val stop: Task[List[_]] = actor.stop
}
