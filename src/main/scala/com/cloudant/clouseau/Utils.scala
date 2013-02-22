/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau


import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.NumericUtils

object Utils {

  def findOrElse[A](options : List[(Symbol, Any)], key : Symbol, default : A) : A = {
    options find { e => e._1 == key } match {
      case None                  => default
      case Some((_, result : A)) => result
    }
  }

  def doubleToTerm(field : String, value : Double) : Term = {
    val bytesRef = new BytesRef
    val asLong = NumericUtils.doubleToSortableLong(value)
    NumericUtils.longToPrefixCoded(asLong, 0, bytesRef)
    new Term(field, bytesRef)
  }

}
