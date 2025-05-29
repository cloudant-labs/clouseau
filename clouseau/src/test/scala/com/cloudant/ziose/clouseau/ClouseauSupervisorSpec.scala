/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ClouseauSupervisorSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.Assertion._

import com.cloudant.ziose.core
import zio.test._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.Asserts._

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauSupervisorSpec extends JUnitRunnableSpec {
  def getChild(
    supervisor: core.AddressableActor[_, _],
    childName: String
  ): ZIO[core.Node, Throwable, core.Address] = {
    for {
      res <- supervisor.lookUpName(childName).repeatUntil(_.isDefined).map(_.get).timeout(3.seconds)
      pid <- res match {
        case Some(pid) => ZIO.succeed(pid)
        case None      => ZIO.fail(new Throwable(s"Cannot get address of ${childName}"))
      }
    } yield pid
  }

  def isResponsive(supervisor: core.AddressableActor[_, _], childName: String) = for {
    response <- supervisor.doTestCallTimeout(core.Codec.fromScala((Symbol("isAlive"), Symbol(childName))), 3.seconds)
    result   <- ZIO.succeed(response.payload)
  } yield result.isDefined

  val clouseauSupervisorSpecSuite: Spec[Any, Throwable] = {
    suite("ClouseauSupervisorSpec suite")(
      test("ClouseauSupervisorSpec init is restarted on child exit signal")(
        for {
          node             <- Utils.clouseauNode
          worker           <- ZIO.service[core.EngineWorker]
          cfg              <- Utils.defaultConfig
          supervisor       <- ClouseauSupervisor.start(node, cfg)
          responsiveBefore <- isResponsive(supervisor, "init")
          address          <- getChild(supervisor, "init")
          _                <- ZIO.debug("The log message about 'init' crashing below is expected ----vvvv")
          _                <- node.terminateNamedWithExit("init", Symbol("normal"))
          _                <- assertNotAlive(address)
          newAddress       <- getChild(supervisor, "init")
          _                <- assertAlive(newAddress)
          responsiveAfter  <- isResponsive(supervisor, "init")
          _                <- supervisor.exit(core.Codec.EAtom("normal"))
        } yield assert(address)(!equalTo(newAddress))
          ?? "new instance of 'init' should be started" &&
          assertTrue(responsiveBefore)
          ?? "The actor should be responsive before we kill it" &&
          assertTrue(responsiveAfter)
          ?? "The new instance of actor should be responsive"
      ),
      test("ClouseauSupervisorSpec main is restarted on child exit signal")(
        for {
          node             <- Utils.clouseauNode
          worker           <- ZIO.service[core.EngineWorker]
          cfg              <- Utils.defaultConfig
          supervisor       <- ClouseauSupervisor.start(node, cfg)
          responsiveBefore <- isResponsive(supervisor, "main")
          address          <- getChild(supervisor, "main")
          _                <- ZIO.debug("The log message about 'manager' crashing below is expected ----vvvv")
          _                <- node.terminateNamedWithExit("main", Symbol("normal"))
          _                <- assertNotAlive(address)
          newAddress       <- getChild(supervisor, "main")
          _                <- assertAlive(newAddress)
          responsiveAfter  <- isResponsive(supervisor, "main")
          _                <- supervisor.exit(core.Codec.EAtom("normal"))
        } yield assert(address)(!equalTo(newAddress))
          ?? "new instance of 'main' should be started" &&
          assertTrue(responsiveBefore)
          ?? "The actor should be responsive before we kill it" &&
          assertTrue(responsiveAfter)
          ?? "The new instance of actor should be responsive"
      ),
      test("ClouseauSupervisorSpec analyzer is restarted on child exit signal")(
        for {
          node             <- Utils.clouseauNode
          worker           <- ZIO.service[core.EngineWorker]
          cfg              <- Utils.defaultConfig
          supervisor       <- ClouseauSupervisor.start(node, cfg)
          responsiveBefore <- isResponsive(supervisor, "analyzer")
          address          <- getChild(supervisor, "analyzer")
          _                <- ZIO.debug("The log message about 'analyzer' crashing below is expected ----vvvv")
          _                <- node.terminateNamedWithExit("analyzer", Symbol("normal"))
          _                <- assertNotAlive(address)
          newAddress       <- getChild(supervisor, "analyzer")
          _                <- assertAlive(newAddress)
          responsiveAfter  <- isResponsive(supervisor, "analyzer")
          _                <- supervisor.exit(core.Codec.EAtom("normal"))
        } yield assert(address)(!equalTo(newAddress))
          ?? "new instance of 'analyzer' should be started" &&
          assertTrue(responsiveBefore)
          ?? "The actor should be responsive before we kill it" &&
          assertTrue(responsiveAfter)
          ?? "The new instance of actor should be responsive"
      ),
      test("ClouseauSupervisorSpec cleanup is restarted on child exit signal")(
        for {
          node             <- Utils.clouseauNode
          worker           <- ZIO.service[core.EngineWorker]
          cfg              <- Utils.defaultConfig
          supervisor       <- ClouseauSupervisor.start(node, cfg)
          responsiveBefore <- isResponsive(supervisor, "cleanup")
          address          <- getChild(supervisor, "cleanup")
          _                <- ZIO.debug("The log message about 'cleanup' crashing below is expected ----vvvv")
          _                <- node.terminateNamedWithExit("cleanup", Symbol("normal"))
          _                <- assertNotAlive(address)
          newAddress       <- getChild(supervisor, "cleanup")
          _                <- assertAlive(newAddress)
          responsiveAfter  <- isResponsive(supervisor, "cleanup")
          _                <- supervisor.exit(core.Codec.EAtom("normal"))
        } yield assert(address)(!equalTo(newAddress))
          ?? "new instance of 'cleanup' should be started" &&
          assertTrue(responsiveBefore)
          ?? "The actor should be responsive before we kill it" &&
          assertTrue(responsiveAfter)
          ?? "The new instance of actor should be responsive"
      )
    ).provideLayer(
      Utils.testEnvironment(1, 1, "ClouseauSupervisorSpecSuite")
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds) @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("ServiceSpec")(
      clouseauSupervisorSpecSuite
    ) @@ TestAspect.timeout(15.minutes)
  }
}
