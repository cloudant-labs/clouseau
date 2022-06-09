package com.cloudant.ziose.experiments

import zio._

object Hello extends ZIOAppDefault {
  def run =
    for {
      _ <- Console.printLine("Hello! What is your name?")
      n <- divide(4, 2)
      _ <- Console.printLine("Hello, foo, good to meet you " + n + "!")
    } yield ()

  def divide(a: Int, b: Int): Task[Int] = ZIO.attempt(a / b)
}
