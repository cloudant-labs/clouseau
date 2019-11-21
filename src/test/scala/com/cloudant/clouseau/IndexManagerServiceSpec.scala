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
import java.io.File
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

  "the index disk size service" should {

    "return 0 for (db) when index directory is missing" in new manager_service {
      node.call(service, DiskSizeMsg("foo.1234567890")) must be equalTo ('ok, List(('disk_size, 0)))
    }

    "return 0 for (db/index) when index directory is missing" in new manager_service {
      node.call(service, DiskSizeMsg("foo.1234567890/5838a59330e52227a58019dc1b9edd6e")) must be equalTo ('ok, List(('disk_size, 0)))
    }

    "should not return 0 for (db) when index directory is not missing" in new manager_service {
      node.call(service, DiskSizeMsg("foo.0987654321")) must not be equalTo('ok, List(('disk_size, 0)))
    }

    "return 0 for (db/index) when index directory is not missing but empty" in new manager_service {
      node.call(service, DiskSizeMsg("foo.0987654321/5838a59330e52227a58019dc1b9edd6e")) must be equalTo ('ok, List(('disk_size, 0)))
    }

  }

  "the index soft deletion service" should {

    "softly delete index when database is deleted" in new manager_service {
      node.call(service, SoftDeleteMsg("foo.5432109876"))
      Thread.sleep(1000)
      val indexdir = new File("target", "indexes")
      var subdirlist = List[String]()

      for (file <- indexdir.listFiles if file.getName contains ".deleted") {
        subdirlist = file.getName() +: subdirlist
      }
      subdirlist.length > 0 must be equalTo true
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

  val dir = new File("target", "indexes")
  if (dir.exists) {
    for (f <- dir.listFiles) {
      f.delete
    }
  }

  val foodir = new File(new File("target", "indexes"), "foo.1234567890")
  if (!foodir.exists) {
    foodir.mkdirs
  }

  val foo2dir = new File(new File("target", "indexes"), "foo.0987654321")
  if (!foo2dir.exists) {
    foo2dir.mkdirs
  }

  val foo2indexdir = new File(new File(new File("target", "indexes"), "foo.0987654321"), "5838a59330e52227a58019dc1b9edd6e")
  if (!foo2indexdir.exists) {
    foo2indexdir.mkdirs
  }

  val foo3dir = new File(new File("target", "indexes"), "foo.5432109876")
  if (!foo3dir.exists) {
    foo3dir.mkdirs
  }
}
