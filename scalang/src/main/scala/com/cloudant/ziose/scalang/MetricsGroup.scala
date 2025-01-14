package com.cloudant.ziose.scalang

import scala.collection.JavaConverters._

import zio.metrics.Metric
import zio.metrics.MetricState
import com.cloudant.ziose.core.ZioSupport
import com.cloudant.ziose.macros.CheckEnv
import com.codahale.metrics.MetricFilter
import scala.collection.immutable.HashMap
import com.codahale.metrics
import java.util.concurrent.Callable

/**
 * A helper class for creating and registering metrics.
 *
 * Notes: Only Counter type has a default value and therefore can be initialized and reported to JMX on registration.
 * All other types would be available after first update.
 */
case class MetricsGroup(klass: Class[_], metricsRegistry: ScalangMeterRegistry) extends ZioSupport {
  private val rateUnit       = metricsRegistry.getRateUnit
  private val durationUnit   = metricsRegistry.getDurationUnit
  private val durationFactor = 1.0 / durationUnit.toNanos(1)
  final class Counter(zioCounter: Metric.Counter[Long]) {
    def +=(delta: Int): Unit = (
      for {
        _ <- zioCounter.update(delta)
      } yield ()
    ).unsafeRun
    def -=(delta: Int): Unit       = zioCounter.update(-delta.abs).unsafeRun
    def count: MetricState.Counter = zioCounter.value.unsafeRun
    def clear                      = zioCounter.fromConst(0)

    def get: Metric.Counter[Long] = this.zioCounter
  }

  final class Gauge[T](zioGauge: Metric.Gauge[Double])(f: => T) {
    def value(): T = f

    def get: Metric.Gauge[Double] = this.zioGauge
  }

  final class Timer(codahaleTimer: metrics.Timer) {
    def time[A](fun: => A): A = codahaleTimer.time(new Callable[A] { def call = fun })
    def getCount              = codahaleTimer.getCount()
    def getMeanRate           = codahaleTimer.getMeanRate()
  }

  def counter(name: String): Counter = {
    val zioCounter = Metric.counter(constructName(name))
    // register to JMX
    zioCounter.update(0).unsafeRun
    new Counter(zioCounter)
  }

  def gauge[T](name: String)(f: => T): Gauge[T] = {
    val zioGauge: Metric.Gauge[Double] = Metric.gauge(constructName(name))
    new Gauge[T](zioGauge)(f)
  }

  def timer(name: String): Timer = {
    val codahaleTimer = metricsRegistry.getDropwizardRegistry().timer(constructName(name))
    new Timer(codahaleTimer)
  }

  def constructName(name: String) = {
    klass.getCanonicalName() + '|' + name
  }

  private def getMeter(name: String): Option[com.codahale.metrics.Meter] = {
    val id = constructName(name)
    Option(
      metricsRegistry
        .getDropwizardRegistry()
        .getMeters(MetricFilter.contains(id))
        .get(id)
    )
  }

  private def getTimer(name: String): Option[com.codahale.metrics.Timer] = {
    val id = constructName(name)
    Option(
      metricsRegistry
        .getDropwizardRegistry()
        .getTimers(MetricFilter.contains(id))
        .get(id)
    )
  }

  private def getMeters() = {
    val prefix = klass.getCanonicalName() + '|'
    metricsRegistry
      .getDropwizardRegistry()
      .getMeters(MetricFilter.startsWith(prefix))
      .asScala
      .map { case (key: String, metric: com.codahale.metrics.Meter) =>
        // split by '|' and take second part
        // there is no way we get a name without '|'
        // because prefix used in the filter includes '|'
        (key.split('|')(1), metric)
      }
  }

  private def getTimers() = {
    val prefix = klass.getCanonicalName() + '|'
    metricsRegistry
      .getDropwizardRegistry()
      .getTimers(MetricFilter.startsWith(prefix))
      .asScala
      .map { case (key: String, timer: com.codahale.metrics.Timer) =>
        // split by '|' and take second part
        // there is no way we get a name without '|'
        // because prefix used in the filter includes '|'
        (key.split('|')(1), timer)
      }
  }

  def dump() = {
    // Counters are implemented as codahale.Metrics
    val meters = getMeters().map { case (key: String, metric: com.codahale.metrics.Meter) =>
      (key, metric.getCount)
    }
    // Timers are implemented as codahale.Timer
    val timers = getTimers().map { case (key: String, timer: com.codahale.metrics.Timer) =>
      (key, timerToMap(timer))
    }
    meters ++ timers
  }

  private def timerToMap(timer: com.codahale.metrics.Timer) = {
    val snapshot = timer.getSnapshot()
    HashMap(
      "p75"                -> snapshot.get75thPercentile() * durationFactor,
      "p95"                -> snapshot.get95thPercentile() * durationFactor,
      "p98"                -> snapshot.get98thPercentile() * durationFactor,
      "p99"                -> snapshot.get99thPercentile() * durationFactor,
      "p999"               -> snapshot.get999thPercentile() * durationFactor,
      "max"                -> snapshot.getMax() * durationFactor,
      "mean"               -> snapshot.getMean() * durationFactor,
      "median"             -> snapshot.getMedian() * durationFactor,
      "min"                -> snapshot.getMin() * durationFactor,
      "stddev"             -> snapshot.getStdDev() * durationFactor,
      "oneMinuteRate"      -> timer.getOneMinuteRate() * durationFactor,
      "fiveMinutesRate"    -> timer.getFiveMinuteRate() * durationFactor,
      "fifteenMinutesRate" -> timer.getFifteenMinuteRate() * durationFactor,
      "durationUnit"       -> durationUnit.name().toLowerCase(),
      "rateUnit"           -> metricsRegistry.rateUnitName(rateUnit)
    )
  }

  private def timerToSymbolMap(timer: com.codahale.metrics.Timer) = {
    val snapshot = timer.getSnapshot()
    HashMap(
      Symbol("p75")                -> snapshot.get75thPercentile() * durationFactor,
      Symbol("p95")                -> snapshot.get95thPercentile() * durationFactor,
      Symbol("p98")                -> snapshot.get98thPercentile() * durationFactor,
      Symbol("p99")                -> snapshot.get99thPercentile() * durationFactor,
      Symbol("p999")               -> snapshot.get999thPercentile() * durationFactor,
      Symbol("max")                -> snapshot.getMax() * durationFactor,
      Symbol("mean")               -> snapshot.getMean() * durationFactor,
      Symbol("median")             -> snapshot.getMedian() * durationFactor,
      Symbol("min")                -> snapshot.getMin() * durationFactor,
      Symbol("stddev")             -> snapshot.getStdDev() * durationFactor,
      Symbol("oneMinuteRate")      -> timer.getOneMinuteRate() * durationFactor,
      Symbol("fiveMinutesRate")    -> timer.getFiveMinuteRate() * durationFactor,
      Symbol("fifteenMinutesRate") -> timer.getFifteenMinuteRate() * durationFactor,
      Symbol("durationUnit")       -> durationUnit.name().toLowerCase(),
      Symbol("rateUnit")           -> metricsRegistry.rateUnitName(rateUnit)
    )
  }

  def dumpAsSymbolValuePairs() = {
    // Counters are implemented as codahale.Metrics
    val meters = getMeters().map { case (key: String, metric: com.codahale.metrics.Meter) =>
      (Symbol(key), metric.getCount)
    }
    // Timers are implemented as codahale.Histograms
    val timers = getTimers().map { case (key: String, timer: com.codahale.metrics.Timer) =>
      (Symbol(key), timerToSymbolMap(timer))
    }
    meters ++ timers
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"klass=$klass",
    s"metricsRegistry=$metricsRegistry",
    s"rateUnit=$rateUnit",
    s"durationUnit=$durationUnit",
    s"durationFactor=$durationFactor"
  )
}
