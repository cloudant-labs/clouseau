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

object conversions {
  /**
   * Scala 2.9.1 doesn't have extractors on PartialFunction. These were introduced in
   *
   * [scala/scala#7111](https://github.com/scala/scala/pull/7111) and released in 2.13.0
   *
   * See also [SIP-38](https://docs.scala-lang.org/sips/converters-among-optional-functions-partialfunctions-and-extractor-objects.html)
   *
   * with extractors you can do something like
   *
   * ```scala
   * val asString: PartialFunction[Any, String] =
   *   { case string: String => string }
   *
   * def test(value: Any): Option[String] = {
   *   value match {
   *     case asString(s) => Some(s)
   *     case _ => None
   *   }
   * }
   * ```
   *
   * This implicit conversion adds a middle ground solution. It can be used as follows:
   *
   * ```scala
   * import conversions._ /// Here we bring implicit convertors in scope
   *
   * val asString: PartialFunction[Any, String] =
   *   { case string: String => string }
   *
   * def test(value: Any): Option[String] = {
   *   val asStringExtractor = asString.Extractor /// This is the extra line we need to add
   *   value match {
   *     case asStringExtractor(s) => Some(s)     /// Use Extractor to get the matching content
   *     case _ => None
   *   }
   * }
   * ```
   *
   * @param pf
   */
  class ExtendedPF[A, B](val pf: PartialFunction[A, B]) {
    object Extractor {
      def unapply(a: A): Option[B] = pf.lift(a)
    }
  }
  implicit def extendPartialFunction[A, B](pf: PartialFunction[A, B]): ExtendedPF[A, B] = new ExtendedPF(pf)
}
