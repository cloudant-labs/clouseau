package com.cloudant.ziose.experiments

import zio._
import zio.test.TestAspect.silent
import zio.test._
import zio.test.junit.JUnitRunnableSpec

class ReadConfigSpec extends JUnitRunnableSpec {
  def spec =
    suite("ServerConfig Tests")(
      test("test ServerConfig") {
        for {
          config <- ZIO.service[ServerConfig]
        } yield assertTrue(
          config.host == "localhost" &&
            config.port == 5984
        )
      }.provide(ServerConfig.layer),
      test("test ReadConfig") {
        for {
          _      <- ReadConfig.run
          output <- TestConsole.output
        } yield assertTrue(
          output(0).contains("localhost") &&
            output(0).contains("5984")
        )
      } @@ silent
    )
}
