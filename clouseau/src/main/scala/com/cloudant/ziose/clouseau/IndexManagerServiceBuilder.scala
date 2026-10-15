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

package com.cloudant.ziose.clouseau

import _root_.com.cloudant.ziose.scalang
import scalang.{ServiceContext, SNode, Adapter}

import _root_.com.cloudant.ziose.core
import core.ProcessContext
import core.ActorConstructor
import core.ActorBuilder

object IndexManagerServiceBuilder extends ActorConstructor[IndexManagerService] {
  def make(node: SNode, service_ctx: ServiceContext[ConfigurationArgs]) = {
    def maker[PContext <: ProcessContext](process_context: PContext): IndexManagerService = {
      new IndexManagerService(service_ctx)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    val capacityExponent = service_ctx.args.config.capacity.main_exponent

    ActorBuilder()
      .withOptionalCapacityExponent(capacityExponent)
      .withName("main")
      .withMaker(maker)
      .build(this)
  }
}
