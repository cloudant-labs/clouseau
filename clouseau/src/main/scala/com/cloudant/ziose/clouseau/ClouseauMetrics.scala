package com.cloudant.ziose.clouseau

import com.cloudant.ziose.scalang.{JmxObjectNameComponents, ScalangMeterRegistry}
import com.codahale.metrics.MetricFilter
import io.micrometer.core.instrument.MeterRegistry
import zio.{ULayer, ZLayer}
import zio.metrics.connectors.micrometer.{MicrometerConfig, micrometerLayer}

import java.util.concurrent.TimeUnit

object ClouseauMetrics {
  val rateUnit     = TimeUnit.SECONDS
  val durationUnit = TimeUnit.MILLISECONDS

  class ClouseauMetricsFilter extends MetricFilter {
    def matches(name: String, metric: com.codahale.metrics.Metric) = {
      /*
       * We use histogram under the hood to implement timer,
       * so we want to prevent reporting of them.
       */
      val blackList = List(
        ".*InitService\\|spawned\\.timer\\.histogram.+".r
      )
      !blackList.exists(_.findFirstIn(name).isDefined)
    }
  }

  def makeRegistry: ScalangMeterRegistry = ScalangMeterRegistry.make(
    "com.cloudant.clouseau",
    new ClouseauMetricsFilter,
    clouseauObjectNameTransformer,
    durationUnit,
    rateUnit
  )

  def clouseauObjectNameTransformer = (components: JmxObjectNameComponents) => {
    val domain = if (components.packageName == "com.cloudant.ziose.clouseau") {
      components.domain
    } else {
      components.packageName
    }
    JmxObjectNameComponents(domain, components.packageName, components.service, components.name)
  }

  def makeLayer(metricsRegistry: MeterRegistry): ULayer[Unit] = {
    ZLayer.succeed(MicrometerConfig.default) ++
      ZLayer.succeed(metricsRegistry) >>>
      micrometerLayer
  }
}
