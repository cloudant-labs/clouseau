package com.cloudant.ziose.core

import zio._

trait ForwardWithId[I, M] {
  type Type = M
  val id: I

  def forward(a: M)(implicit trace: Trace): UIO[Boolean]
  def shutdown(implicit trace: Trace): UIO[Unit]
}
