package com.boundary.logula.examples

import com.boundary.logula.Logging

class ThingDoer extends Logging {
  def run() {
    log.trace("Contemplating doing a thing.")
    log.debug("About to do a thing.")
    log.info("Doing a thing")
    log.warn("This may get ugly.")

    try {
      sys.error("oh noes!")
    } catch {
      case e: Exception => log.error(e, "The thing has gone horribly wrong.")
    }
  }
}

object ExampleLoggingRun extends Logging {
  def main(args: Array[String]) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace")
    new ThingDoer().run()
  }
}
