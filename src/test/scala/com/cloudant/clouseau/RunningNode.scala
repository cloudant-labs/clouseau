package com.cloudant.clouseau

import scalang.Node
import org.specs2.mutable.After

trait RunningNode extends After {

  val cookie = "test"
  val epmd = EpmdCmd()
  val node = Node(Symbol("test@localhost"), cookie)

  def after {
    epmd.destroy()
    epmd.waitFor
    node.shutdown
  }

}

object EpmdCmd {
  def apply(): Process = {
    val builder = new ProcessBuilder("epmd")
    builder.start
  }
}
