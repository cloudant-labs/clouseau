package com.cloudant.ziose.test.helpers

import zio.test.TestAspect

object Aspects {
  /*
   * needsTest is a TestAspect to be used to annotate the tests that yet need to be written.
   * See the example of its use bellow.
   *
   * ```scala
   * import com.cloudant.ziose.test.helpers.Aspects._
   *
   * suite("My test suite description") {
   *   test("My test description") {
   *     ???
   *   } @@ needsTest
   * }
   * ```
   */
  val needsTest = TestAspect.tag("not implemented") @@ TestAspect.ignore
}
