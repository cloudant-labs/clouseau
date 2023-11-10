package com.cloudant.ziose.core

import zio._

trait EnqueueWithId[I, M] extends Enqueue[M] {
  type Type = M
  val id: I
}
