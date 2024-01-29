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

/**
 * mvn test -Dtest="com.cloudant.clouseau.UtilsSpec"
 */

package com.cloudant.clouseau

import org.specs2.mutable.SpecificationWithJUnit
import scala.Some
import Utils.ensureElementsType
import conversions._
import scala.collection.mutable

class UtilsSpec extends SpecificationWithJUnit {
  "an ensureElementsType" should {
    val asListOfStrings: PartialFunction[Any, List[String]] = ensureElementsType(
      { case string: String => string },
      { case (container, element) => throw new Exception(container.toString + " contains non-string element " + element.toString) }
    )
    val extractor = asListOfStrings.Extractor

    "throw exception when encounter non matching case" in {
      val input = List("1", "2", 3, "4")
      input must beLike { case extractor(input) => ko } must throwA[Exception].like {
        case e => e.getMessage must contain("List(1, 2, 3, 4) contains non-string element 3")
      }
    }

    "skip processing of elements after first element of wrong type" in {
      val input = List("1", "2", 3, "4")
      val recorder: mutable.ListBuffer[Any] = mutable.ListBuffer.empty
      val condition: PartialFunction[Any, List[String]] = ensureElementsType(
        {
          case string: String => {
            recorder += string
            string
          }
        },
        {
          case (_container, element) => {
            recorder += element
            throw new MatchError(element)
          }
        }
      )
      val extractor = condition.Extractor
      input must beLike { case extractor(input) => ok } must throwA[MatchError]
      recorder must beEqualTo(mutable.ListBuffer("1", "2", 3))
    }

    "usable when orElse doesn't throw exception" in {
      /*
        This is not supported use case for the function.
        Because there is no way to distinguish the success and failure.

        This test is written only to verify that orElse function works correctly.
       */
      val condition: PartialFunction[Any, List[String]] = ensureElementsType(
        {
          case string: String => { string }
        },
        {
          case (container, element) => { List(element.toString) }
        }
      )
      val extractor = condition.Extractor

      List("1", "2", "3", "4") must beLike { case extractor(input) => input must beEqualTo(List("1", "2", "3", "4")) }
      List("1", "2", 3, "4") must beLike { case extractor(input) => input must beEqualTo(List("3")) }
      List("1") must beLike { case extractor(input) => input must beEqualTo(List("1")) }
      List(1) must beLike { case extractor(input) => input must beEqualTo(List("1")) }
    }
  }
}
