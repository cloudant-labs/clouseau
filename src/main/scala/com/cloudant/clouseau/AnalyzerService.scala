/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import com.yammer.metrics.scala._
import java.io.StringReader
import org.apache.log4j.Logger
import org.apache.lucene.analysis.tokenattributes._
import scala.collection.immutable.List
import scalang._

class AnalyzerService(ctx : ServiceContext[NoArgs]) extends Service(ctx) with Instrumented {

  val logger = Logger.getLogger("clouseau.analyzer")

  override def handleCall(tag : (Pid, Reference), msg : Any) : Any = msg match {
    case ('analyze, analyzerConfig : Any, text : String) =>
      SupportedAnalyzers.createAnalyzer(analyzerConfig) match {
        case Some(analyzer) =>
          var result : List[String] = List()
          val tokenStream = analyzer.tokenStream("default", new StringReader(text))
          tokenStream.reset
          while (tokenStream.incrementToken) {
            val term = tokenStream.getAttribute(classOf[CharTermAttribute])
            result = term.toString +: result
          }
          tokenStream.end
          tokenStream.close
          ('ok, result.reverse)
        case None =>
          ('error, 'no_such_analyzer)
      }
  }

}
