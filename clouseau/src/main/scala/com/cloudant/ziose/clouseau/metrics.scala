/*
com.yammer.metrics.scala compatible API

This is a temporary approach until we get comfortable updating core clouseau classes
 */

package com.yammer.metrics.scala

object metrics {
  case class Timer(_name: String, metric: Unit) {
    def time[A](f: => A): A = {
      f
    }
  }

  case class Counter(name: String, counter: Unit) {
    def +=(v: Double): Counter = {
      // Q: How to get correct runtime here the one which has the MeterRegistry service
      //   Unsafe.unsafe { implicit unsafe =>
      //     Runtime.default.unsafe.run(counter.update(1))
      //   }
      this.copy()
    }
    def -=(v: Double): Counter = {
      //   Unsafe.unsafe { implicit unsafe =>
      //     Runtime.default.unsafe.run(counter.update(1))
      //   }
      this.copy()
    }
  }

  case class Gauge[T](name: String) {
    def value(): T = ???
  }

  def timer(name: String): Timer     = new Timer(name, ())
  def counter(name: String): Counter = new Counter(name, ())
  def gauge[T](name: String)(f: => T): Gauge[T] = new Gauge[T](name) {
    override def value(): T = f
  }
}
