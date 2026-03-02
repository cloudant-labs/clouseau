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

import org.locationtech.spatial4j.context.SpatialContext
import org.locationtech.spatial4j.shape.Point
import org.apache.lucene.search.DoubleValuesSource
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.search.DoubleValues
import org.apache.lucene.index.DocValues
import org.apache.lucene.search.IndexSearcher

/*
This is lucene spatial's DistanceValueSource but with configurable
x and y field names to better suit our existing API.
 */
case class DistanceValueSource(ctx: SpatialContext,
                               lon: String,
                               lat: String,
                               multiplier: Double,
                               from: Point)
    extends DoubleValuesSource {

  val nullValue = 180 * multiplier

  def description() = "DistanceValueSource(%s)".format(from)

  override def toString(): String = {
        description
  }

  override def getValues(readerContext: LeafReaderContext, scores: DoubleValues):DoubleValues = {
    val reader = readerContext.reader()
    val ptX = DocValues.getNumeric(reader, lon)
    val ptY = DocValues.getNumeric(reader, lat)

    DoubleValues.withDefault(
      new DoubleValues() {
        val from = DistanceValueSource.this.from
        val calculator = ctx.getDistCalc
        override def doubleValue() = {
          val x = java.lang.Double.longBitsToDouble(ptX.longValue())
          val y = java.lang.Double.longBitsToDouble(ptY.longValue())
          calculator.distance(from, x, y) * multiplier
        }
        override def advanceExact(doc: Int) = {
          ptX.advanceExact(doc) && ptY.advanceExact(doc)
        }
      },
      nullValue
    )
  }

  override def needsScores() = false

  override def isCacheable(ctx: LeafReaderContext) = {
    DocValues.isCacheable(ctx, lon, lat)
  }

  override def rewrite(searcher: IndexSearcher) = {
    this
  }

}
