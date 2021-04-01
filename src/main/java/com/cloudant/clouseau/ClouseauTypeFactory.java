package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.asBinary;
import static com.cloudant.clouseau.OtpUtils.asMap;
import static com.cloudant.clouseau.OtpUtils.*;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.Field.TermVector;
import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.index.FacetFields;
import org.apache.lucene.facet.params.FacetIndexingParams;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetFields;
import org.apache.lucene.facet.taxonomy.CategoryPath;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangDouble;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangRangeException;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class ClouseauTypeFactory {

    private static final Logger logger = Logger.getLogger("clouseau.tf");

    public static Document newDocument(final OtpErlangObject id, final OtpErlangObject fields) throws IOException {
        final Document result = new Document();
        result.add(new StringField("_id", asString(id), Store.YES));

        for (final OtpErlangObject field : (OtpErlangList) fields) {
            addFields(result, (OtpErlangTuple) field);
        }
        return result;
    }

    private static void addFields(final Document doc, final OtpErlangTuple field) throws IOException {
        final String name = asString(field.elementAt(0));
        final OtpErlangObject value = field.elementAt(1);
        final Map<OtpErlangObject, OtpErlangObject> options = asMap(field.elementAt(2));

        if (value instanceof OtpErlangBinary) {
            final String strValue = asString(value);
            final Field f = constructField(name, strValue, toStore(options), toIndex(options), toTermVector(options));
            if (f != null) {
                final Float boost = toFloat(options.get(asBinary("boost")));
                if (boost != null) {
                    f.setBoost(boost);
                }
                doc.add(f);
                if (isFacet(options) && !strValue.isEmpty()) {
                    final FacetIndexingParams fp = FacetIndexingParams.DEFAULT;
                    final char delim = fp.getFacetDelimChar();
                    if (name.indexOf(delim) == -1 && strValue.indexOf(delim) == -1) {
                        final FacetFields facets = new SortedSetDocValuesFacetFields(fp);
                        facets.addFields(doc, Collections.singletonList(new CategoryPath(name, strValue)));
                    }
                }
            }
        } else if (value instanceof OtpErlangAtom) {
            final Field f = constructField(
                    name,
                    asString(value),
                    toStore(options),
                    Index.NOT_ANALYZED,
                    toTermVector(options));
            if (f != null) {
                doc.add(f);
            }
        } else {
            final Double doubleValue = toDouble(value);
            if (doubleValue != null) {
                doc.add(new DoubleField(name, doubleValue, toStore(options)));
                if (isFacet(options)) {
                    doc.add(new DoubleDocValuesField(name, doubleValue));
                }
            } else {
                logger.warn("Unrecognized value: " + value);
            }
        }
    }

    private static Field constructField(String name, String value, Store store, Index index, TermVector tv) {
        try {
            return new Field(name, value, store, index, tv);
        } catch (IllegalArgumentException e) {
            logger.error(String.format("Failed to construct field '%s' with reason '%s'", name, e.getMessage()));
            return null;
        } catch (NullPointerException e) {
            logger.error(String.format("Failed to construct field '%s' with reason '%s'", name, e.getMessage()));
            return null;
        }
    }

    private static Store toStore(Map<OtpErlangObject, OtpErlangObject> options) {
        OtpErlangObject store = options.get(asBinary("store"));
        if (store == null) {
            return Store.NO;
        }

        final String str = asString(store);

        if ("true".equals(str)) {
            return Store.YES;
        }
        if ("false".equals(str)) {
            return Store.NO;
        }

        try {
            return Store.valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Store.NO;
        }
    }

    private static Index toIndex(Map<OtpErlangObject, OtpErlangObject> options) {
        OtpErlangObject index = options.get(asBinary("index"));
        if (index == null) {
            return Index.ANALYZED;
        }

        String str = asString(index);

        if ("true".equals(str)) {
            return Index.ANALYZED;
        }
        if ("false".equals(str)) {
            return Index.NO;
        }

        try {
            return Index.valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Index.ANALYZED;
        }
    }

    private static TermVector toTermVector(Map<OtpErlangObject, OtpErlangObject> options) {
        OtpErlangObject termvector = options.get(asBinary("termvector"));
        if (termvector == null) {
            return TermVector.NO;
        }
        return TermVector.valueOf(asString(termvector).toUpperCase());
    }

    private static boolean isFacet(Map<OtpErlangObject, OtpErlangObject> options) {
        OtpErlangObject facet = options.get(asBinary("facet"));
        if (facet != null) {
            String str = asString(facet);
            switch (str) {
            case "true":
                return true;
            case "false":
                return false;
            }
        }

        return false;
    }

    private static Float toFloat(OtpErlangObject obj) {
        try {
            if (obj instanceof OtpErlangLong) {
                return (float) ((OtpErlangLong) obj).intValue();
            }
            if (obj instanceof OtpErlangDouble) {
                return (float) ((OtpErlangDouble) obj).floatValue();
            }
        } catch (OtpErlangRangeException e) {
            throw new IllegalArgumentException("out of range", e);
        }
        return null;
    }

    private static Double toDouble(OtpErlangObject obj) {
        if (obj instanceof OtpErlangLong) {
            return (double) ((OtpErlangLong) obj).longValue();
        }
        if (obj instanceof OtpErlangDouble) {
            return ((OtpErlangDouble) obj).doubleValue();
        }
        return null;
    }

}
