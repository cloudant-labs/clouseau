package com.cloudant.cloujeau;

import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.Version;

public class LuceneUtils {

    public static Version VERSION = Version.LUCENE_46;

    public static Term doubleToTerm(final String field, final double value) {
        final BytesRef bytesRef = new BytesRef();
        final long asLong = NumericUtils.doubleToSortableLong(value);
        NumericUtils.longToPrefixCoded(asLong, 0, bytesRef);
        return new Term(field, bytesRef);
    }

}
