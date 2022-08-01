package com.cloudant.zio.actors

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, File, ObjectInputStream, ObjectOutputStream }
import java.nio.ByteBuffer

import zio.Console

import zio.{ Chunk, ZIO, Promise, RIO, Ref, Task, UIO }
import Actor.{ AbstractStateful, Stateful }
import ActorSystemUtils._
import ActorsConfig._
import zio.Clock
import zio.nio.{ Buffer, InetAddress, SocketAddress }
import zio.nio.channels.{ AsynchronousServerSocketChannel, AsynchronousSocketChannel }

import com.ericsson.otp.erlang._;

import scala.io.Source

class Pid(
  override val node: String,
  override val id: Int,
  override val serial: Int,
  override val creation: Int = 4
) extends com.ericsson.otp.erlang.OtpErlangPid(
  node, id, serial, creation
) {
  override def toString(): String =
    "<" + id + "." + serial + "." + creation + ">"
}

/**
 * Object providing constructor for Actor System with optional remoting module.
 */
object ActorSystem {

  /**
   * Constructor for Actor System
   *
   * @param sysName    - Identifier for Actor System
   * @param configFile - Optional configuration for a remoting internal module.
   *                     If not provided the actor system will only handle local actors in terms of actor selection.
   *                     When provided - remote messaging and remote actor selection is possible
   * @return instantiated actor system
   */
  def apply(sysName: String, configFile: Option[File] = None): Task[ActorSystem] =
    for {
      initActorRefMap <- Ref.make(Map.empty[Pid, Any])
      config          <- retrieveConfig(configFile)
      remoteConfig    <- retrieveRemoteConfig(sysName, config).debug("remoteConfig")
      actorSystem     <- ZIO.attempt(new ActorSystem(sysName, 1, 0, config, remoteConfig, initActorRefMap, parentActor = None))
      _               <- ZIO
                           .succeed(remoteConfig)
                           .flatMap(_.fold[Task[Unit]](ZIO.unit)(c => actorSystem.startNode(c.node, c.cookie)))
    } yield actorSystem
}

/**
 * Context for actor used inside Stateful which provides self actor reference and actor creation/selection API
 */
final class Context private[actors] (
  private val pid: Pid,
  private val actorSystem: ActorSystem,
  private val childrenRef: Ref[Set[ActorRef[Any]]]
) {

  /**
   * Accessor for self actor reference
   *
   * @return actor reference in a task
   */
  def self[F[+_]]: Task[ActorRef[F]] = actorSystem.select(pid)

  /**
   * Creates actor and registers it to dependent actor system
   *
   * @param actorName name of the actor
   * @param sup       - supervision strategy
   * @param init      - initial state
   * @param stateful  - actor's behavior description
   * @tparam S  - state type
   * @tparam F1 - DSL type
   * @return reference to the created actor in effect that can't fail
   */
  def make[R, S, F1[+_]](
    actorName: String,
    sup: Supervisor[R],
    init: S,
    stateful: Stateful[R, S, F1]
  ): ZIO[R with Clock, Throwable, ActorRef[F1]] =
    for {
      actorRef <- actorSystem.make(actorName, sup, init, stateful)
      children <- childrenRef.get
      _        <- childrenRef.set(children + actorRef.asInstanceOf[ActorRef[Any]])
    } yield actorRef

  /**
   * Looks up for actor on local actor system, and in case of its absence - delegates it to remote internal module.
   * If remote configuration was not provided for ActorSystem (so the remoting is disabled) the search will
   * fail with ActorNotFoundException.
   * Otherwise it will always create remote actor stub internally and return ActorRef as if it was found.   *
   *
   * @param pid - pid of the actor
   * @tparam F1 - actor's DSL type
   * @return task if actor reference. Selection process might fail with "Actor not found error"
   */
  def select[F1[+_]](pid: Pid): Task[ActorRef[F1]] =
    actorSystem.select(pid)

  /* INTERNAL API */

  private[actors] def actorSystemName = actorSystem.actorSystemName

  private[actors] def actorSystemConfig = actorSystem.config

}

/**
 * Type representing running instance of actor system provisioning actor herding,
 * remoting and actor creation and selection.
 */
final class ActorSystem private[actors] (
  private[actors] val actorSystemName: String,
  private var pidCount: Int = 0,
  private var serial: Int = 0,
  private[actors] val config: Option[String],
  private val remoteConfig: Option[RemoteConfig],
  private val refActorMap: Ref[Map[Pid, Any]],
  private val parentActor: Option[Pid]
) {
  def creation: Int =
      0

  def newPid(): ZIO[Any, Throwable, Pid] = ZIO.attemptBlocking {
    val pid: Pid = new Pid(actorSystemName, pidCount, serial, creation)
    // https://github.com/erlang/otp/blob/7e332d22c4cd2a1da9f7207c4e978d3c5ccb944e/lib/jinterface/java_src/com/ericsson/otp/erlang/OtpLocalNode.java#L119:L127'
    pidCount += 1
    if (pidCount > 0x7fff) {
      pidCount = 0

      serial += 1
      if (serial > 0x1fff) { /* 13 bits */
        serial = 0
      }
    }
    pid
  }

  /**
   * Creates actor and registers it to dependent actor system
   *
   * @param actorName name of the actor
   * @param sup       - supervision strategy
   * @param init      - initial state
   * @param stateful  - actor's behavior description
   * @tparam S - state type
   * @tparam F - DSL type
   * @return reference to the created actor in effect that can't fail
   */
  def make[R, S, F[+_]](
    actorName: String,
    sup: Supervisor[R],
    init: S,
    stateful: AbstractStateful[R, S, F]
  ): RIO[R with Clock, ActorRef[F]] =
    for {
      map          <- refActorMap.get
      pid          <- newPid().debug("newPid")
      _            <- if (map.contains(pid)) ZIO.fail(new Exception(s"Actor $pid already exists")) else ZIO.unit
      derivedSystem = new ActorSystem(actorSystemName, pidCount, serial, config, remoteConfig, refActorMap, Some(pid))
      childrenSet  <- Ref.make(Set.empty[ActorRef[Any]])
      actor        <- stateful.makeActor(
                        sup,
                        new Context(pid, derivedSystem, childrenSet),
                        () => dropFromActorMap(pid, childrenSet)
                      )(init)
      _            <- refActorMap.set(map + (pid -> actor))
    } yield new ActorRefLocal[F](pid, actor)

  /**
   * Looks up for actor on local actor system, and in case of its absence - delegates it to remote internal module.
   * If remote configuration was not provided for ActorSystem (so the remoting is disabled) the search will
   * fail with ActorNotFoundException.
   * Otherwise it will always create remote actor stub internally and return ActorRef as if it was found.   *
   *
   * @param pid - pid of the actor
   * @tparam F - actor's DSL type
   * @return task if actor reference. Selection process might fail with "Actor not found error"
   */
  def select[F[+_]](pid: Pid): Task[ActorRef[F]] =
    for {
      actorMap <- refActorMap.get

      actorRef <-
        for {
          actorRef <- actorMap.get(pid) match {
            case Some(value) =>
              for {
                actor <- ZIO.succeed(value.asInstanceOf[Actor[F]])
              } yield new ActorRefLocal(pid, actor)
            case None        =>
              ZIO.fail(new Exception(s"No such actor $pid in local ActorSystem."))
          }
        } yield actorRef
    } yield actorRef

  /**
   * Stops all actors within this ActorSystem.
   *
   * @return all actors' unprocessed messages
   */
  def shutdown: Task[List[_]] =
    for {
      systemActors <- refActorMap.get
      actorsDump   <- ZIO.foreach(systemActors.values.toList)(_.asInstanceOf[Actor[Any]].stop)
    } yield actorsDump.flatten

  /* INTERNAL API */

  private[actors] def dropFromActorMap(pid: Pid, childrenRef: Ref[Set[ActorRef[Any]]]): Task[Unit] =
    for {
      _                   <- refActorMap.update(_ - pid)
      children            <- childrenRef.get
      _                   <- ZIO.foreachDiscard(children)(_.stop)
      _                   <- childrenRef.set(Set.empty)
    } yield ()

  def createNode(
      name: String,
      cookie: String
  ): ZIO[Any, Throwable, OtpNode] = ZIO.attemptBlocking {
    var node = new OtpNode(name, cookie)
    node.ping("ziose@127.0.0.1", 2000)
    node
  }

  private def startNode(node: ActorsConfig.Node, cookie: ActorsConfig.Cookie): Task[Unit] =
    for {
      // TODO: use ZIO supervisor to restart node with exponencial delay in case of errors
      node <- createNode(node.value, cookie.value)
      // _ <- receiveLoop(node).fold(
      //         e =>
      //           Console.printLine(s"Execution failed with error $e") *> ZIO.succeed(
      //             1
      //           ),
      //         _ => ZIO.succeed(0)
      //       )
    } yield ()

  private def receiveLoop(
        node: OtpNode
    ): ZIO[Any with Console, Throwable, Unit] =
      for {
        p <- Promise.make[Nothing, Unit]
        // loopEffect = for {
        // } yield ()
        // _ <- loopEffect
        //   .onTermination(_ => ZIO.unit)
        //   .fork
        _ <- p.await
      } yield ()
}

/* INTERNAL API */

private[actors] object ActorSystemUtils {
  def retrieveConfig(configFile: Option[File]): Task[Option[String]] =
    // configFile.fold[Task[Option[String]]](Task.none) { file =>
    //   IO(Source.fromFile(file)).toManaged(f => UIO(f.close())).use(s => IO.some(s.mkString))
    // }
    ZIO.none

  def retrieveRemoteConfig(sysName: String, configStr: Option[String]): Task[Option[RemoteConfig]] =
    ZIO.some(new RemoteConfig(
      new ActorsConfig.Node("mynode@127.0.0.1"),
      new ActorsConfig.Cookie("monster")
    ))
}
