package com.cloudant.clouseau

import org.apache.commons.configuration.SystemConfiguration
import scalang.Pid
import org.specs2.mutable.SpecificationWithJUnit

class IndexManagerServiceSpec extends SpecificationWithJUnit {
  sequential

  "the index manager" should {

    "open an index when asked" in new manager_service {
      node.call(service, OpenIndexMsg(mbox.self, "foo", "standard")) must beLike { case ('ok, pid: Pid) => ok }
    }

    "return the same index if it's already open" in new manager_service {
      node.call(service, OpenIndexMsg(mbox.self, "foo", "standard")) match {
        case ('ok, pid) =>
          node.call(service, OpenIndexMsg(mbox.self, "foo", "standard")) must be equalTo ('ok, pid)
      }
    }

  }

}

trait manager_service extends RunningNode {
  val config = new SystemConfiguration()
  val args = new ConfigurationArgs(config)
  val service = node.spawnService[IndexManagerService, ConfigurationArgs](args)
  val mbox = node.spawnMbox

  override def after {
    node.call(service, 'close_lru)
    super.after
  }

}
