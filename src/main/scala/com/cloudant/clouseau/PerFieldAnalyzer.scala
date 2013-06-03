/*
 * Copyright 2013 Cloudant. All rights reserved.
 * The Scala equivalent of PerFieldAnalyzerWrapper with a
 * public getWrappedAnalyzer method.
 */

package com.cloudant.clouseau

import org.apache.lucene.analysis._
import org.apache.lucene.analysis.Analyzer._

class PerFieldAnalyzer(defaultAnalyzer: Analyzer,
                       map: Map[String, Analyzer]) extends AnalyzerWrapper {

  def getWrappedAnalyzer(fieldName: String): Analyzer = {
    map.getOrElse(fieldName, defaultAnalyzer)
  }

  override def wrapComponents(fieldName: String,
                              components: TokenStreamComponents): TokenStreamComponents = {
    components
  }

  override def toString = {
    "PerFieldAnalyzer(" + map + ", default=" + defaultAnalyzer + ")"
  }

}
