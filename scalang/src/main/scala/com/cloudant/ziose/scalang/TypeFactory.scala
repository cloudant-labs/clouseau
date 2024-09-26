package com.cloudant.ziose.scalang

import _root_.com.cloudant.ziose.core.Codec.ETerm

trait TypeFactory {
  type T
  def parse(term: ETerm)(implicit adapter: Adapter[_, _]): Option[T]
  val bottomRules: PartialFunction[Any, ETerm]
}
