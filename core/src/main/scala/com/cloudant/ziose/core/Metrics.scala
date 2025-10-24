package com.cloudant.ziose.core

import scala.jdk.CollectionConverters._

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.{Counter => MicrometerCounter}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.Measurement
import io.micrometer.core.instrument.{Meter => MicrometerMeter}
import io.micrometer.core.instrument.search.{Search => MicrometerSearch}

object Metrics {
  type Meter  = MicrometerMeter
  type Search = MicrometerSearch
  case class Registry[R <: MeterRegistry](inner: R) {
    def plainCounter(klass: String, name: String) = {
      new PlainCounter(
        MicrometerCounter
          .builder(name)
          .tag("class", klass)
          .register(inner)
      )
    }

    def getMeters(): scala.collection.mutable.Buffer[Meter] = inner.getMeters().asScala
    def findMeter(name: String)                             = inner.find(name)
  }

  final class PlainCounter(inner: MicrometerCounter) {
    def +=(delta: Int): Unit         = inner.increment(delta)
    def -=(delta: Int): Unit         = inner.increment(-delta)
    def count: Iterable[Measurement] = inner.measure().asScala
  }

  def simpleRegistry = Registry(new SimpleMeterRegistry())
}
