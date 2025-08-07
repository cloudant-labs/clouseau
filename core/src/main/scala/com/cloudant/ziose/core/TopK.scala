package com.cloudant.ziose.core

import scala.collection.mutable.PriorityQueue

class TopK[K](k: Int, queue: PriorityQueue[(K, Long)]) {
  def add(key: K, value: Long): Unit = {
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
  def apply[K](k: Int) = {
    val ordering: Ordering[(K, Long)] = Ordering.by(_._2)
    new TopK[K](k, PriorityQueue[(K, Long)]()(ordering.reverse))
  }
}
