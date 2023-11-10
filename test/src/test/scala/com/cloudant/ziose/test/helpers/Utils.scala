package com.cloudant.ziose.test.helpers

import zio._

object Utils {
  def flag(name: String): Boolean = {
    (sys.env.getOrElse(name, "false").toBoolean) || (sys.props.getOrElse(name, "false").toBoolean)
  }

  val logger = if (flag("ZIOSE_TEST_DEBUG")) {
    Runtime.addLogger(ZLogger.default.map(println))
  } else {
    Runtime.addLogger(ZLogger.default.map(_ => null))
  }
}
