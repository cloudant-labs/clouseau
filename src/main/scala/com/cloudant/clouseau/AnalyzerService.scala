/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import com.yammer.metrics.scala._
import org.apache.log4j.Logger
import org.apache.lucene.analysis.tokenattributes._
import scala.collection.immutable.List
import scalang._
import org.apache.lucene.analysis.Analyzer

class AnalyzerService(ctx: ServiceContext[ConfigurationArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.analyzer")

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
