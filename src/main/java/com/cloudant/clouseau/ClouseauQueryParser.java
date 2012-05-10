// Copyright Cloudant 2012

package com.cloudant.clouseau;

import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;

public final class ClouseauQueryParser extends QueryParser {

    @Override
    protected Query getRangeQuery(final String field, final String lower, final String upper, final boolean inclusive)
            throws ParseException {
        return new TypedField(field).toRangeQuery(lower, upper, inclusive);
    }

    @Override
    protected Query getFieldQuery(final String field, final String queryText, final boolean quoted)
        throws ParseException {
        final TypedField typedField = new TypedField(field);
        if (typedField.getType() == FieldType.STRING) {
            return super.getFieldQuery(field, queryText, quoted);
        }
        return typedField.toTermQuery(queryText);
    }

}