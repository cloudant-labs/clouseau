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

import com.yammer.metrics.scala._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.lucene.analysis.tokenattributes._
import scala.collection.immutable.List
import scalang._
import org.apache.lucene.analysis.Analyzer

class AnalyzerService(ctx: ServiceContext[ConfigurationArgs]) extends Service(ctx) with Instrumented {

  val logger = LoggerFactory.getLogger("clouseau.analyzer")

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('analyze, analyzerConfig: Any, text: String) =>
      SupportedAnalyzers.createAnalyzer(analyzerConfig) match {
        case Some(analyzer) =>
          ('ok, tokenize(text, analyzer))
        case None =>
          ('error, 'no_such_analyzer)
      }
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
