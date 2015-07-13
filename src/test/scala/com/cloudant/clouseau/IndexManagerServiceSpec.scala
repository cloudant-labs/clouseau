// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.


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
