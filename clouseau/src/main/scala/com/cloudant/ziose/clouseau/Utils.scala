// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.ziose.clouseau

import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.NumericUtils
import zio.{&, LogLevel, ZIO}
import zio._
import com.cloudant.ziose.core.EngineWorker
import com.cloudant.ziose.core.Node
import com.cloudant.ziose.core.ActorFactory
import com.cloudant.ziose.otp
import com.cloudant.ziose.otp.OTPNodeConfig
import com.cloudant.ziose.core.Engine

object Utils {

  def doubleToTerm(field: String, value: Double): Term = {
    val bytesRef = new BytesRef
    val asLong = NumericUtils.doubleToSortableLong(value)
    NumericUtils.longToPrefixCoded(asLong, 0, bytesRef)
    new Term(field, bytesRef)
  }

  implicit def stringToBytesRef(string: String): BytesRef = {
    new BytesRef(string)
  }

  /**
   * The function `ensureElementsType` maps over elements of a list and applies a partial function `pf`
   * to each element until it encounters an element for which the `pf` is not defined. At that point,
   * it stops iteration and calls the `orElse` partial function passing the original `list`
   * and the current element.
   *
   * This function is intended to be used to refine a generic list to a list containing specific type of elements.
   * For example, you can convert a `List[Any]` to `List[Inner]`, where `Inner` is a generic parameter
   * of the function.
   *
   *  @param pf - `PartialFunction[Any, Inner]` - Partial function to apply to each element of the given list
   *  @param orElse - `PartialFunction[List[Any], Any), Inner]` - Partial function to apply to the first element for which `pf` is not defined.
   *
   * Here's an example usage:
   *
   * ```scala
   * val asListOfStrings: PartialFunction[Any, List[String]] = ensureElementsType(
   *   { case string: String => string },
   *   { case (container, element) => throw new ParseException(container.toString + " contains non-string element " + element.toString) }
   * )
   *
   * val asListOfStringsExtractor = asListofStrings.Extractor
   *
   * value match {
   *  case Some(asDrilldownExtractor(value)) =>
   *    Some(value)
   *  case None =>
   *    None
   * }
   * ```
   * In this example, the `asListOfStrings` partial function will convert any list to a `List[String]`,
   * and throw an exception if it encounters a non-string element.
   */

  def ensureElementsType[Inner](pf: PartialFunction[Any, Inner], orElse: PartialFunction[(List[Any], Any), List[Inner]]): PartialFunction[Any, List[Inner]] = {
    case list: List[_] => {
      val extractor = pf.lift
      val result: Either[Any, List[Inner]] = list.foldLeft(Right(List.empty[Inner]): Either[Any, List[Inner]]) {
        case (Right(acc), value) => extractor(value) match {
          case Some(v) => Right(acc :+ v)
          case None => Left(value)
        }
        case (err @ Left(_), _) => err
      }

      result match {
        case Right(list: List[_]) => list.asInstanceOf[List[Inner]]
        case Left(err) => orElse.apply((list.asInstanceOf[List[Any]], err))
      }
    }
  }

  def clouseauNode: ZIO[EngineWorker & Node & ActorFactory & OTPNodeConfig, Throwable, ClouseauNode] = for {
    runtime <- ZIO.runtime[EngineWorker & Node & ActorFactory]
    worker  <- ZIO.service[EngineWorker]
    metricsRegistry = ClouseauMetrics.makeRegistry
    node <- ZIO.succeed(new ClouseauNode()(runtime, worker, metricsRegistry, LogLevel.Debug))
  } yield node

  def mkConfig(config: ClouseauConfiguration): ZIO[OTPNodeConfig, Throwable, Configuration] = for {
    nodeCfg <- ZIO.service[OTPNodeConfig]
  } yield Configuration(config, nodeCfg, CapacityConfiguration())

  def defaultConfig: ZIO[OTPNodeConfig, Throwable, Configuration] = mkConfig(ClouseauConfiguration())

  def testEnvironment(engineId: Engine.EngineId, workerId: Engine.WorkerId, nodeName: String = "test") =
    otp.Utils.testEnvironment(engineId, workerId, nodeName)

  val logger = Runtime.removeDefaultLoggers
}
