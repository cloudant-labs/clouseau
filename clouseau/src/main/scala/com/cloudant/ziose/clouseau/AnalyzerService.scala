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

import org.apache.lucene.analysis.tokenattributes._
import scala.collection.immutable.List
import org.apache.lucene.analysis.Analyzer

import _root_.com.cloudant.ziose.scalang
import scalang._

class AnalyzerService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) with Instrumented {

  val logger = LoggerFactory.getLogger("clouseau.analyzer")

  override def handleInit(): Unit = {
    logger.debug(s"handleInit(capacity = ${adapter.capacity})")
  }

  override def handleCall(tag: (Pid, Any), msg: Any): Any = msg match {
    case ('analyze, analyzerConfig: Any, text: String) =>
      AnalyzerOptions.from(analyzerConfig).flatMap(SupportedAnalyzers.createAnalyzer) match {
        case Some(analyzer) =>
          ('ok, tokenize(text, analyzer))
        case None =>
          ('error, 'no_such_analyzer)
      }
    case other =>
      logger.info(s"[WARNING][handleCall] Unexpected message: $other ...")
  }

  def tokenize(text: String, analyzer: Analyzer): List[String] = {
    var result: List[String] = List()
    val tokenStream = analyzer.tokenStream("default", text)
    tokenStream.reset()
    while (tokenStream.incrementToken) {
      val term = tokenStream.getAttribute(classOf[CharTermAttribute])
      result = term.toString +: result
    }
    tokenStream.end()
    tokenStream.close()
    result.reverse
  }

}
