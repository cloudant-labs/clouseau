package com.boundary.logula

import reflect.NameTransformer
import org.slf4j.{LoggerFactory, Logger}

/**
 * Log's companion object.
 */
object Log {
  /**
   * Returns a log for a given class.
   */
  def forClass[A](implicit mf: Manifest[A]) = forName(mf.erasure.getCanonicalName)

  /**
   * Returns a log for a given class.
   */
  def forClass(klass: Class[_]) = forName(klass.getCanonicalName)

  /**
   * Returns a log with the given name.
   */
  def forName(name: String) = new Log(LoggerFactory.getLogger(clean(name)))

  private def clean(s: String) = NameTransformer.decode(if (s.endsWith("$")) {
    s.substring(0, s.length - 1)
  } else {
    s
  }.replaceAll("\\$", "."))

  protected val CallerFQCN = classOf[Log].getCanonicalName
}

/**
 * <b>In general, use the Logging trait rather than using Log directly.</b>
 *
 * A wrapper for org.apache.log4j which allows for a smoother interaction with
 * Scala classes. All messages are treated as format strings:
 * <pre>
 *    log.info("We have this many threads: %d", thread.size)
 * </pre>
 * Each log level has two methods: one for logging regular messages, the other
 * for logging messages with thrown exceptions.
 *
 * The log levels here are those of org.apache.log4j.
 *
 * @author coda
 */
class Log(private val logger: Logger) {

  /**
   * Logs a message with optional parameters at level TRACE.
   */
  def trace(message: String, params: Any*) {
    if (logger.isTraceEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.trace(statement)
    }
  }

  /**
   * Logs a thrown exception and a message with optional parameters at level
   * TRACE.
   */
  def trace(thrown: Throwable, message: String, params: Any*) {
    if (logger.isTraceEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.trace(statement, thrown)
    }
  }

  /**
   * Logs a message with optional parameters at level DEBUG.
   */
  def debug(message: String, params: Any*) {
    if (logger.isDebugEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.debug(statement)
    }
  }

  /**
   * Logs a thrown exception and a message with optional parameters at level
   * DEBUG.
   */
  def debug(thrown: Throwable, message: String, params: Any*) {
    if (logger.isDebugEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.debug(statement, thrown)
    }
  }

  /**
   * Logs a message with optional parameters at level INFO.
   */
  def info(message: String, params: Any*) {
    if (logger.isInfoEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.info(statement)
    }
  }

  /**
   * Logs a thrown exception and a message with optional parameters at level
   * INFO.
   */
  def info(thrown: Throwable, message: String, params: Any*) {
    if (logger.isInfoEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.info(statement, thrown)
    }
  }

  /**
   * Logs a message with optional parameters at level WARN.
   */
  def warn(message: String, params: Any*) {
    if (logger.isWarnEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.warn(statement)
    }
  }

  /**
   * Logs a thrown exception and a message with optional parameters at level
   * WARN.
   */
  def warn(thrown: Throwable, message: String, params: Any*) {
    if (logger.isWarnEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.warn(statement, thrown)
    }
  }

  /**
   * Logs a message with optional parameters at level ERROR.
   */
  def error(message: String, params: Any*) {
    if (logger.isErrorEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.error(statement)
    }
  }

  /**
   * Logs a thrown exception and a message with optional parameters at level
   * ERROR.
   */
  def error(thrown: Throwable, message: String, params: Any*) {
    if (logger.isErrorEnabled) {
      val statement = if (params.isEmpty) message else message.format(params:_*)
      logger.error(statement, thrown)
    }
  }

  def isTraceEnabled = logger.isTraceEnabled

  def isDebugEnabled = logger.isDebugEnabled

  def isInfoEnabled = logger.isInfoEnabled

  def isWarnEnabled = logger.isWarnEnabled

  def isErrorEnabled = logger.isErrorEnabled
}
