package com.cloudant.ziose.scalang

import _root_.com.cloudant.ziose.core.Codec.ETerm

trait TypeFactory {
  type T
  def parse(term: ETerm)(implicit adapter: Adapter[_, _]): Option[T]
  def toScala(term: ETerm): Option[Any]
  def fromScala(term: Any): Option[ETerm]
}
