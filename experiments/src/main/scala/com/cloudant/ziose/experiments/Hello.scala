package com.cloudant.ziose.experiments

import zio._
import zio.logging._

object Hello extends ZIOAppDefault {
  private val logger =
    Runtime.removeDefaultLoggers >>> console(LogFormat.colored)

  def run =
    (for {
      _    <- Console.printLine("What is your name?")
      name <- ZIO.succeed("Ziose")
      _    <- ZIO.log(s"name: $name")
      n    <- divide(4, 2)
      _    <- ZIO.logError(s"n: $n")
      _    <- Console.printLine(s"Hello, $name! Nice to meet you $n!")
    } yield ExitCode.success).provide(logger)

  def divide(a: Int, b: Int): Task[Int] = ZIO.attempt(a / b)
}
