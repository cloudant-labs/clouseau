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
import org.specs2.mutable.SpecificationWithJUnit
import java.io.File
import concurrent._

class IndexCleanupServiceSpec extends SpecificationWithJUnit {
  sequential

  "the index clean-up service" should {

    "move index when database is deleted" in new cleanup_service {
      node.cast(service, MovePathMsg("foo")) must be equalTo 'ok
      Thread.sleep(1000)
      val indexdir = new File(new File(new File("target", "indexes"), ".recovery"), "foo")
      indexdir.exists must be equalTo true
    }

  }

}

trait cleanup_service extends RunningNode {
  val config = new SystemConfiguration()
  val args = new ConfigurationArgs(config)
  val service = node.spawnService[IndexCleanupService, ConfigurationArgs](args)
  val mbox = node.spawnMbox

  val dir = new File(new File("target", "indexes"), ".recovery")
  if (dir.exists) {
    for (f <- dir.listFiles) {
      f.delete
    }
  }

  override def after {
    super.after
  }

}
