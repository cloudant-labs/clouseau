package com.cloudant.clouseau

object Utils {

  def findOrElse[A](options : List[(Symbol, Any)], key : Symbol, default : A) : A = {
    options find { e => e._1 == key } match {
      case None                  => default
      case Some((_, result : A)) => result
    }
  }

}
