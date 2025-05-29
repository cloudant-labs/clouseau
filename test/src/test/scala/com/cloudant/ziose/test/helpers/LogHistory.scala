package com.cloudant.ziose.test.helpers

import zio.Chunk
import zio.test.ZTestLogger.LogEntry
import zio.LogLevel
import zio.logging.{LogAnnotation, logContext}
import com.cloudant.ziose.core.ActorCallback
import com.cloudant.ziose.core.Address
import com.cloudant.ziose.core.AddressableActor
import zio.ZIO

/**
 * This class implements a simple query language to find patterns in logs
 * {{{
 * for {
 *   output <- ZTestLogger.logOutput
 *   logHistory = LogHistory(output)
 * } yield (assert(
 *   (logHistory.withLogLevel(LogLevel.Debug) && logHistory.withActor("EchoService"))
 *     .asIndexedMessageAnnotationTuples(core.AddressableActor.actorTypeLogAnnotation)
 * )(containsShape { case (_, "onTermination", "EchoService") =>
 *   true
 * }) ?? "log should contain messages from 'EchoService.onTermination' callback")
 * }}}
 */
class LogHistory private (val entries: Chunk[(Int, LogEntry)]) {

  /**
   * Returns new instance of `LogHistory` which contains only messages of given `LogLevel`.
   */
  def withLogLevel(level: LogLevel) = {
    new LogHistory(entries.collect {
      case (idx, entry) if entry.logLevel == level => (idx, entry)
    })
  }

  /**
   * Returns new instance of `LogHistory` which contains only messages which have given annotation and that annotation
   * matches provided `PartialFunction[T, Boolean]` function.
   */
  def withContextAnnotation[T](annotation: LogAnnotation[T], pf: PartialFunction[T, Boolean]) = {
    new LogHistory(entries.collect {
      case (idx, entry) if entry.context.getOrDefault(logContext).get(annotation).collect(pf).getOrElse(false) =>
        (idx, entry)
    })
  }

  /**
   * Returns new instance of `LogHistory` which contains only messages which have given annotation and that annotation
   * matches provided `T => Boolean` function.
   */
  def withContextAnnotation[T](annotation: LogAnnotation[T], condition: T => Boolean) = {
    new LogHistory(entries.collect {
      case (idx, entry) if entry.context.getOrDefault(logContext).get(annotation).map(condition).getOrElse(false) =>
        (idx, entry)
    })
  }

  /**
   * Returns new instance of `LogHistory` which contains only messages from given `actorType: String`.
   */
  def withActor(actorType: String) = {
    withContextAnnotation(AddressableActor.actorTypeLogAnnotation, isEqual(actorType))
  }

  /**
   * Returns new instance of `LogHistory` which contains only messages of given `LogLevel` from given `actorType:
   * String`.
   */
  def withActorLevel(actorType: String, level: LogLevel) = {
    withLogLevel(level) && withActor(actorType)
  }

  /**
   * Returns new instance of `LogHistory` which contains only messages of given `callbackName` from given `actorType:
   * String`.
   */
  def withActorCallback(actorType: String, callbackName: ActorCallback) = {
    withActor(actorType) && withActorCallbackName(callbackName)
  }

  /**
   * Returns new instance of `LogHistory` which contains only messages of given `LogLevel` from given `actorType:
   * String`.
   */
  def withActorAddress(actorAddress: Address) = {
    withContextAnnotation(AddressableActor.addressLogAnnotation, isEqual(actorAddress))
  }

  /**
   * Returns new instance of `LogHistory` which contains only messages of given `callbackName`.
   */
  def withActorCallbackName(callbackName: ActorCallback) = {
    withContextAnnotation(AddressableActor.actorCallbackLogAnnotation, isEqual(callbackName))
  }

  /**
   * Computes the intersection of this history and another history.
   */
  def intersect(other: LogHistory) = {
    val myEntriesMap    = entries.toMap
    val otherEntriesMap = other.entries.toMap
    val resultMap = {
      myEntriesMap.keySet.intersect(otherEntriesMap.keySet).map(k => k -> otherEntriesMap(k)).toMap
    }
    new LogHistory(resultMap.view.to(Chunk))
  }

  /**
   * Computes the union of this history and another history.
   */
  def union(other: LogHistory) = {
    val myEntriesMap    = entries.toMap
    val otherEntriesMap = other.entries.toMap
    val resultMap = {
      myEntriesMap.keySet.union(otherEntriesMap.keySet).map(k => k -> otherEntriesMap(k)).toMap
    }
    new LogHistory(resultMap.view.to(Chunk))
  }

  /**
   * Computes the difference of this history and another history.
   */
  def diff(other: LogHistory) = {
    val myEntriesMap    = entries.toMap
    val otherEntriesMap = other.entries.toMap
    val resultMap = {
      myEntriesMap.keySet.diff(otherEntriesMap.keySet).map(k => k -> otherEntriesMap(k)).toMap
    }
    new LogHistory(resultMap.view.to(Chunk))
  }

  /**
   * Alias for `intersect`
   */
  def and(other: LogHistory) = intersect(other)

  /**
   * Alias for `union`
   */
  def or(other: LogHistory) = union(other)

  /**
   * Alias for `intersect`
   */
  def &&(other: LogHistory) = intersect(other)

  /**
   * Alias for `union`
   */
  def ||(other: LogHistory) = union(other)

  /**
   * Returns Chunk of indexed `LogEntry`
   */
  def asIndexedLogEntries: Chunk[(Int, LogEntry)] = entries

  /**
   * Returns Chunk of tuples `(index: Int, message: String)` which contain provided annotation.
   */
  def asIndexedMessages: Chunk[(Int, String)] = entries.map { case (index, entry) =>
    (index, entry.message())
  }

  private def getAnnotation[T](entry: LogEntry, annotation: LogAnnotation[T]): Option[T] = {
    entry.context.getOrDefault(logContext).get(annotation)
  }

  /**
   * Returns Chunk of tuples `(index: Int, message: String, annotation: String)` which contain provided annotation.
   */
  def asIndexedMessageAnnotationTuples[T](annotation: LogAnnotation[T]): Chunk[(Int, String, T)] = {
    entries.collect {
      case (idx, entry) if getAnnotation(entry, annotation).isDefined =>
        (idx, entry.message(), getAnnotation(entry, annotation).get)
    }
  }

  /**
   * Returns Chunk of tuples `(index: Int, message: String, annotations: List[Option[String]])` which contain provided
   * annotations. The annotations are returned in the same order as they listed in annotations argument.
   */
  def asIndexedMessageAnnotationsTuples[T](
    annotations: List[LogAnnotation[T]]
  ): Chunk[(Int, String, List[Option[T]])] = {
    entries.map { case (idx, entry) =>
      (idx, entry.message(), annotations.map(annotation => getAnnotation(entry, annotation)))
    }
  }

  /**
   * Returns Chunk of tuples `(index: Int, message: String)` which contains only messages of given `LogLevel` from given
   * `actorType: String`.
   */
  def asActorLevelIndexedMessages(actorType: String, level: LogLevel) = {
    withActorLevel(actorType, level).asIndexedMessages
  }

  private def isEqual[T](compareTo: T): T => Boolean = value => {
    value == compareTo
  }

  /**
   * This debugging function can be used as
   * {{{
   * for {
   *   output       <- ZTestLogger.logOutput
   *   logHistory = LogHistory(output)
   *   _ <- logHistory.debug(core.AddressableActor.actorTypeLogAnnotation)
   * } yield assert(....)
   * }}}
   */
  def debug[T](annotation: LogAnnotation[T]) = {
    ZIO.succeed {
      asIndexedMessageAnnotationTuples(annotation).foreach { case (idx, message, callback) =>
        println(s"$idx:$callback: \"$message\"")
      }
    }
  }
}

object LogHistory {
  def apply(entries: Chunk[LogEntry]) = new LogHistory(
    entries
      .mapAccum(0) { case (idx: Int, entry: LogEntry) =>
        (idx + 1, (idx, entry))
      }
      ._2
  )
}
