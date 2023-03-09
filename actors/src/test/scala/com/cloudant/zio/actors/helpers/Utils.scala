package com.cloudant.zio.actors.helpers

object Utils {
    def flag(name: String): Boolean =
      (sys.env.getOrElse(name, "false").toBoolean) || (sys.props.getOrElse(name, "false").toBoolean)
}
