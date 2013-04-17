package com.cloudant.clouseau

import scalang.ServiceContext
import scalang.node.ProcessAdapter

class FakeServiceContext[A <: Product](serviceArgs: A) extends ServiceContext[A] {
  val pid = null
  val referenceCounter = null
  val node = null
  val fiber = null
  val replyRegistry = null
  val args = serviceArgs
  var adapter : ProcessAdapter = null
}
