package com.cloudant.clouseau

import scalang.Node
import org.specs2.mutable.BeforeAfter

trait RunningNode extends BeforeAfter {

  val cookie = "test"
  val epmd = EpmdCmd()
  val node = Node(Symbol("test@localhost"), cookie)

  def before {
  }

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
