package com.cloudant.ziose.core

final case class Exponent(private val value: Int) extends AnyVal {
  def toInt             = 1 << value
  override def toString = toInt.toString
}
