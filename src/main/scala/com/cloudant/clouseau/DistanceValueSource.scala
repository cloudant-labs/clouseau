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

package com.cloudant.clouseau

import com.spatial4j.core.context.SpatialContext
import com.spatial4j.core.shape.Point
import org.apache.lucene.queries.function.{ FunctionValues, ValueSource }
import org.apache.lucene.index.{ AtomicReader, AtomicReaderContext }
import org.apache.lucene.search.FieldCache
import org.apache.lucene.util.Bits
import com.spatial4j.core.distance.DistanceCalculator
import java.util.Map

/*
This is lucene spatial's DistanceValueSource but with configurable
x and y field names to better suit our existing API.
 */
case class DistanceValueSource(ctx: SpatialContext,
                               lon: String,
                               lat: String,
                               multiplier: Double,
                               from: Point)
    extends ValueSource {

  def description() = "DistanceValueSource(%s)".format(from)

  def getValues(context: Map[_, _], readerContext: AtomicReaderContext) = {
    val reader: AtomicReader = readerContext.reader

    val ptLon: FieldCache.Doubles = FieldCache.DEFAULT.getDoubles(reader,
      lon, true)
    val ptLat: FieldCache.Doubles = FieldCache.DEFAULT.getDoubles(reader,
      lat, true)
    val validLon: Bits = FieldCache.DEFAULT.getDocsWithField(reader, lon)
    val validLat: Bits = FieldCache.DEFAULT.getDocsWithField(reader, lat)

    new FunctionValues {

      override def floatVal(doc: Int): Float = {
        doubleVal(doc).asInstanceOf[Float]
      }

      override def doubleVal(doc: Int) = {
        if (validLon.get(doc)) {
          assert(validLat.get(doc))
          calculator.distance(from, ptLon.get(doc), ptLat.get(doc)) * multiplier
        } else {
          nullValue
        }
      }

      def toString(doc: Int): String = {
        description + "=" + floatVal(doc)
      }

      private final val from: Point = DistanceValueSource.this.from
      private final val calculator: DistanceCalculator = ctx.getDistCalc
      private final val nullValue = if (ctx.isGeo) 180 * multiplier else
        Double.MaxValue
    }
  }

}
