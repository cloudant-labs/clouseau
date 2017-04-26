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
