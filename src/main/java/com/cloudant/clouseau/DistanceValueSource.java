package com.cloudant.clouseau;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.FieldCache;
import org.apache.lucene.util.Bits;

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.distance.DistanceCalculator;
import com.spatial4j.core.shape.Point;

/**
 * This is lucene spatial's DistanceValueSource but with configurable x and y
 * field names to better suit our existing API.
 **/
class DistanceValueSource extends ValueSource {

    private final SpatialContext ctx;
    private final String lon;
    private final String lat;
    private final Double multiplier;
    private final Point from;

    DistanceValueSource(final SpatialContext ctx, final String lon, final String lat, final Double multiplier,
            final Point from) {
        this.ctx = ctx;
        this.lon = lon;
        this.lat = lat;
        this.multiplier = multiplier;
        this.from = from;
    }

    @Override
    public FunctionValues getValues(Map context, AtomicReaderContext readerContext) throws IOException {
        AtomicReader reader = readerContext.reader();

        final FieldCache.Doubles ptLon = FieldCache.DEFAULT.getDoubles(reader, lon, true);
        final FieldCache.Doubles ptLat = FieldCache.DEFAULT.getDoubles(reader, lat, true);
        final Bits validLon = FieldCache.DEFAULT.getDocsWithField(reader, lon);
        final Bits validLat = FieldCache.DEFAULT.getDocsWithField(reader, lat);

        return new FunctionValues() {

            private final Point from = DistanceValueSource.this.from;
            private final DistanceCalculator calculator = ctx.getDistCalc();
            private final double nullValue = (ctx.isGeo() ? 180 * multiplier : Double.MAX_VALUE);

            @Override
            public float floatVal(int doc) {
                return (float) doubleVal(doc);
            }

            @Override
            public double doubleVal(int doc) {
                // make sure it has minX and area
                if (validLon.get(doc)) {
                    assert validLat.get(doc);
                    return calculator.distance(from, ptLon.get(doc), ptLat.get(doc)) * multiplier;
                }
                return nullValue;
            }

            @Override
            public String toString(int doc) {
                return description() + "=" + floatVal(doc);
            }
        };
    }

    @Override
    public String description() {
        return String.format("DistanceValueSource(%s)", from);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        DistanceValueSource that = (DistanceValueSource) o;

        if (!from.equals(that.from))
            return false;
        if (!ctx.equals(that.ctx))
            return false;
        if (multiplier != that.multiplier)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        return from.hashCode();
    }

}
