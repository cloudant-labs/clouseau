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
import scalang._

import _root_.com.cloudant.ziose.core
import core.ProcessContext
import core.ActorConstructor
import core.ActorBuilder

trait AnalyzerServiceBuilder {}

object AnalyzerServiceBuilder extends ActorConstructor[AnalyzerService] {
  def make(node: SNode, service_ctx: ServiceContext[ConfigurationArgs]) = {
    def maker[PContext <: ProcessContext](process_context: PContext): AnalyzerService = {
      new AnalyzerService(service_ctx)(new Adapter(process_context, node, ClouseauTypeFactory))
    }

    ActorBuilder()
      // TODO get capacity from config
      .withCapacity(16)
      .withName("analyzer")
      .withMaker(maker)
      .build(this)
  }
  /*
    This function is called from ClouseauSupervisor and return
        `{ok, Pid}` or `error`
   */
  def start(node: SNode, config: Configuration)(implicit adapter: Adapter[_, _]): Any = {
    val ctx = new ServiceContext[ConfigurationArgs] { val args = ConfigurationArgs(config) }
    node.spawnService[AnalyzerService, ConfigurationArgs](make(node, ctx)) match {
      case core.Success(actor)  => (Symbol("ok"), Pid.toScala(actor.self.pid))
      case core.Failure(reason) => reason
    }
  }
}
