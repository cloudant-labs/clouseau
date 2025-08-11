package com.cloudant.ziose.core

import zio._

trait WithProcessInfo[I] {
  val id: I
  def messageQueueLength()(implicit trace: Trace): UIO[Int]
}
