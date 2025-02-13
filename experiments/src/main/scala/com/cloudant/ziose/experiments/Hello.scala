package com.cloudant.ziose.experiments.hello

// format: off
/**
  * Running from sbt `experiments/runMain com.cloudant.ziose.experiments.hello.Main`
  *
  * # Goals of the experiment
  *
  * 1. demonstrate that building infrastructure is provisioned correctly
  * 2. demonstrate how we can integrate metrics into stack
  * 3. demonstrate how to use logging
  *
  * # Context
  *
  * The metrics and logging libraries used by current version of Clouseau are outdated and cannot be run on modern scala.
  * So we need to find an approach how to replace them.
  *
  * # Constrains
  *
  * 1. We need to avoid modifications of the call sites because we don't want to touch the Clouseau source code.
  * 2. The metrics should report values in the same format as metrics in Clouseau.
  *
  * # Solution
  *
  * 1. Use the accompanying test suite to test how logging works
  * 2. Use `zio.metrics.dropwizard` package
  **/

import com.codahale.metrics.MetricRegistry
import zio.Console.printLine
import zio._
import zio.logging._
import zio.metrics.dropwizard.{DropwizardExtractor, Registry}
import zio.metrics.dropwizard.helpers.{counter, getCurrentRegistry, jmx, timer}
import zio.metrics.dropwizard.reporters.Reporters

object Main extends ZIOAppDefault {
  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)

  private val metricName = "Dropwizard"
  val counterName        = MetricRegistry.name(metricName, Array("test", "counter"): _*)
  val timerName          = MetricRegistry.name(metricName, Array("test", "timer"): _*)

  val app: RIO[Registry with Reporters, MetricRegistry] = {
    for {
      r <- getCurrentRegistry()
      _ <- jmx(r)
      // _ <- helpers.console(r, 2, TimeUnit.SECONDS)
      c      <- counter.register(metricName, Array("test", "counter"))
      t      <- timer.register(metricName, Array("test", "timer"))
      _      <- Console.printLine("What is your name?")
      name   <- ZIO.succeed("Ziose")
      n      <- divide(4, 2)
      _      <- Console.printLine(s"Hello, $name! Nice to meet you $n!")
      ctx    <- t.start()
      _      <- divide(4, 2)
      _      <- t.stop(ctx)
      _      <- c.inc(2)
      _      <- ZIO.log(s"name: $name")
      _      <- ZIO.logError(s"n: $n")
      _      <- ZIO.logWarning(s"Counter: ${r.getCounters.get(counterName).getCount}")
      _      <- ZIO.logWarning(s"Timer Count: ${r.getTimers.get(timerName).getCount}")
      _      <- ZIO.logWarning(s"Timer MeanRate: ${r.getTimers.get(timerName).getMeanRate}")
      ctxnew <- t.start()
      _ = Thread.sleep(1000)
      _ <- t.stop(ctxnew)
      _ <- ZIO.logWarning(s"Timer Count (after sleep): ${r.getTimers.get(timerName).getCount}")
      _ <- ZIO.logWarning(s"Timer MeanRate (after sleep): ${r.getTimers.get(timerName).getMeanRate}")
    } yield r
  }

  def run = {
    (for {
      json <- app.flatMap(DropwizardExtractor.writeJson(_)(None))
      _    <- Clock.sleep(30.seconds)
      _    <- printLine(json).exitCode
    } yield ()).provideSomeLayer(logger ++ Registry.live ++ Reporters.live)
  }

  def divide(a: Int, b: Int): Task[Int] = ZIO.attempt(a / b)
}
