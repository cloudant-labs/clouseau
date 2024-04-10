package com.cloudant.ziose.scalang

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter
import io.micrometer.jmx.{JmxConfig, JmxMeterRegistry}
import io.micrometer.core.instrument.util.HierarchicalNameMapper
import io.micrometer.core.instrument.Clock
import com.codahale.metrics.MetricFilter
import io.micrometer.core.instrument.config.NamingConvention
import java.util.concurrent.TimeUnit

class ScalangMeterRegistry(registry: MetricRegistry, durationUnit: TimeUnit, rateUnit: TimeUnit, reporter: JmxReporter)
    extends JmxMeterRegistry(
      JmxConfig.DEFAULT,
      Clock.SYSTEM,
      HierarchicalNameMapper.DEFAULT,
      registry,
      reporter
    ) {
  def getDurationUnit = durationUnit
  def getRateUnit     = rateUnit
  def rateUnitName(unit: TimeUnit) = {
    unit match {
      case TimeUnit.DAYS         => "events/day"
      case TimeUnit.HOURS        => "events/hour"
      case TimeUnit.MICROSECONDS => "events/microsecond"
      case TimeUnit.MILLISECONDS => "events/millisecond"
      case TimeUnit.MINUTES      => "events/minute"
      case TimeUnit.NANOSECONDS  => "events/nanosecond"
      case TimeUnit.SECONDS      => "events/second"
    }
  }

}

object ScalangMeterRegistry {
  def jmxReporter(
    registry: MetricRegistry,
    domain: String,
    filter: MetricFilter,
    nameTransformer: (JmxObjectNameComponents) => JmxObjectNameComponents,
    durationUnit: TimeUnit,
    rateUnit: TimeUnit
  ): JmxReporter = {
    JmxReporter
      .forRegistry(registry)
      .inDomain(domain)
      .createsObjectNamesWith(new JmxObjectNameFactory(nameTransformer))
      .convertDurationsTo(durationUnit)
      .convertRatesTo(rateUnit)
      .filter(filter)
      .build()
  }

  def make(
    domain: String,
    filter: MetricFilter,
    nameTransformer: (JmxObjectNameComponents) => JmxObjectNameComponents,
    durationUnit: TimeUnit,
    rateUnit: TimeUnit
  ): ScalangMeterRegistry = {
    val metricRegistry = new MetricRegistry()
    val registry = {
      new ScalangMeterRegistry(
        metricRegistry,
        durationUnit,
        rateUnit,
        jmxReporter(metricRegistry, domain, filter, nameTransformer, durationUnit, rateUnit)
      )
    }
    registry.config().namingConvention(NamingConvention.dot)
    registry
  }
}
