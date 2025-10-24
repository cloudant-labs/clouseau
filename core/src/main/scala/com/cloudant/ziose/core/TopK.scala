package com.cloudant.ziose.core

import scala.collection.mutable.PriorityQueue
import Ordering.Implicits._

class TopK[K, V: Ordering](k: Int, queue: PriorityQueue[(K, V)]) {
  def add(key: K, value: V): Unit = {
    if (queue.size < k) {
      queue.enqueue((key, value))
    } else {
      if (queue.head._2 <= value) {
        queue.dequeue()
        queue.enqueue((key, value))
      }
    }
  }
  def asList = {
    queue.dequeueAll.reverse.toList
  }
}

object TopK {
  def apply[K, V: Ordering](k: Int) = {
    val ordering: Ordering[(K, V)] = Ordering.by(_._2)
    new TopK[K, V](k, PriorityQueue[(K, V)]()(ordering.reverse))
  }
}
