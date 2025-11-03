package com.cloudant.ziose.core

import scala.jdk.CollectionConverters._

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.{Counter => MicrometerCounter}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.Measurement
import io.micrometer.core.instrument.{Meter => MicrometerMeter}
import io.micrometer.core.instrument.search.{Search => MicrometerSearch}

object Metrics {
  sealed trait Meter[M <: MicrometerMeter] {
    type Type = M
    protected val inner: M

    def getName() = Symbol(inner.getId().getName())
    def getTags() = inner.getId().getTags().asScala.map(tag => (tag.getKey(), tag.getValue())).toMap
    def getCount(): Option[Double] = inner match {
      case counter: MicrometerCounter => Some(counter.count())
      case _                          => None
    }
  }

  final class PlainCounter(protected val inner: MicrometerCounter) extends Meter[MicrometerCounter] {
    def +=(delta: Int): Unit         = inner.increment(delta)
    def -=(delta: Int): Unit         = inner.increment(-delta)
    def count: Iterable[Measurement] = inner.measure().asScala
  }

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

    def getMeters(): List[Metrics.Meter[_]] = {
      inner
        .getMeters()
        .asScala
        .map(meter => {
          meter match {
            case counter: MicrometerCounter => new PlainCounter(counter)
          }
        })
        .toList
    }
    def findMeter(name: String) = inner.find(name)
  }

  def simpleRegistry = Registry(new SimpleMeterRegistry())
}
