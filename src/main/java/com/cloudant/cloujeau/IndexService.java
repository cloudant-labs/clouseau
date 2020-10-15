package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.asArrayOfStrings;
import static com.cloudant.cloujeau.OtpUtils.asBinary;
import static com.cloudant.cloujeau.OtpUtils.asBoolean;
import static com.cloudant.cloujeau.OtpUtils.asFloat;
import static com.cloudant.cloujeau.OtpUtils.asInt;
import static com.cloudant.cloujeau.OtpUtils.asList;
import static com.cloudant.cloujeau.OtpUtils.asListOfStrings;
import static com.cloudant.cloujeau.OtpUtils.asLong;
import static com.cloudant.cloujeau.OtpUtils.asMap;
import static com.cloudant.cloujeau.OtpUtils.asOtp;
import static com.cloudant.cloujeau.OtpUtils.asString;
import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.emptyList;
import static com.cloudant.cloujeau.OtpUtils.nilToNull;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.params.FacetIndexingParams;
import org.apache.lucene.facet.search.DrillDownQuery;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.TotalHitCountCollector;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.distance.DistanceUtils;
import com.spatial4j.core.shape.Point;

public class IndexService extends Service {

    private static final SortField INVERSE_FIELD_SCORE = new SortField(null, SortField.Type.SCORE, true);
    private static final SortField INVERSE_FIELD_DOC = new SortField(null, SortField.Type.DOC, true);
    private static final Pattern SORT_FIELD_RE = Pattern.compile("^([-+])?([\\.\\w]+)(?:<(\\w+)>)?$");
    private static final Pattern FP = Pattern.compile("([-+]?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern DISTANCE_RE = Pattern
            .compile(String.format("^([-+])?<distance,([\\.\\w]+),([\\.\\w]+),%s,%s,(mi|km)>$", FP, FP));

    private static final Logger logger = Logger.getLogger("clouseau");

    private final String name;

    private final IndexWriter writer;

    private DirectoryReader reader;

    private final QueryParser qp;

    private long updateSeq;

    private long pendingSeq;

    private long purgeSeq;

    private long pendingPurgeSeq;

    private boolean forceRefresh = false;

    private boolean idle = true;

    private final Timer searchTimer;
    private final Timer updateTimer;
    private final Timer deleteTimer;
    private final Timer commitTimer;
    private final Counter parSearchTimeOutCount;

    private ScheduledFuture<?> commitFuture;

    private ScheduledFuture<?> closeFuture;

    public IndexService(final ServerState state, final String name, final IndexWriter writer, final QueryParser qp)
            throws ReflectiveOperationException, IOException {
        super(state);
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        if (writer == null) {
            throw new NullPointerException("writer cannot be null");
        }
        if (qp == null) {
            throw new NullPointerException("qp cannot be null");
        }
        this.name = name;
        this.writer = writer;
        this.reader = DirectoryReader.open(writer, true);
        this.qp = qp;

        searchTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=searches");
        updateTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=updates");
        deleteTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=deletes");
        commitTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=commits");
        parSearchTimeOutCount = state.metricRegistry
                .counter("com.cloudant.clouseau:type=IndexService,name=partition_search.timeout.count");

        final int commitIntervalSecs = state.config.getInt("clouseau.commit_interval_secs", 60);
        commitFuture = state.scheduledExecutor.scheduleWithFixedDelay(() -> {
            commit();
        }, commitIntervalSecs, commitIntervalSecs, TimeUnit.SECONDS);

        final boolean closeIfIdleEnabled = state.config.getBoolean("clouseau.close_if_idle", true);
        final int idleTimeoutSecs = state.config.getInt("clouseau.idle_check_interval_secs", 30);
        if (closeIfIdleEnabled) {
            closeFuture = state.scheduledExecutor.scheduleWithFixedDelay(() -> {
                closeIfIdle();
            }, idleTimeoutSecs, idleTimeoutSecs, TimeUnit.SECONDS);
        }

        this.updateSeq = getCommittedSeq();
        this.pendingSeq = updateSeq;
        this.purgeSeq = getCommittedPurgeSeq();
        this.pendingPurgeSeq = purgeSeq;
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws Exception {
        idle = false;
        info("call " + request);
        if (request instanceof OtpErlangAtom) {
            switch (asString(request)) {
            case "get_update_seq":
                return tuple(atom("ok"), asLong(updateSeq));
            case "get_purge_seq":
                return tuple(atom("ok"), asLong(purgeSeq));
            }
        } else if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            final OtpErlangObject cmd = tuple.elementAt(0);

            if (cmd instanceof OtpErlangAtom) {
                switch (asString(cmd)) {
                case "commit": // deprecated
                case "set_update_seq": {
                    pendingSeq = asLong(tuple.elementAt(1));
                    debug("Pending sequence is now " + pendingSeq);
                    return atom("ok");
                }
                case "set_purge_seq": {
                    pendingPurgeSeq = asLong(tuple.elementAt(1));
                    debug("purge sequence is now " + pendingPurgeSeq);
                    return atom("ok");
                }
                case "delete": {
                    final String id = asString(tuple.elementAt(1));
                    debug(String.format("Deleting %s", id));
                    deleteTimer.time(() -> {
                        try {
                            writer.deleteDocuments(new Term("_id", id));
                        } catch (final IOException e) {
                            error("I/O exception when deleting docs", e);
                            terminate(asBinary(e.getMessage()));
                        }
                    });
                    return atom("ok");
                }
                case "update": {
                    return handleUpdateCall(tuple);
                }
                case "search":
                    return handleSearchCall(from, asMap(tuple.elementAt(1)));
                }
            }
        }

        return null;
    }

    private OtpErlangObject handleUpdateCall(final OtpErlangTuple tuple) throws IOException {
        final Document doc = ClouseauTypeFactory.newDocument(tuple.elementAt(1), tuple.elementAt(2));
        if (logger.isDebugEnabled()) {
            debug("Updating " + doc.get("_id"));
        }
        updateTimer.time(() -> {
            try {
                writer.updateDocument(new Term("_id", doc.get("_id")), doc);
            } catch (final IOException e) {
                error("I/O exception when updating docs", e);
                terminate(asBinary(e.getMessage()));
            }
        });
        return atom("ok");
    }

    @Override
    public void handleInfo(final OtpErlangObject request) throws IOException {
        idle = false;
        info("info " + request);
        if (request instanceof OtpErlangAtom) {
            switch (asString(request)) {
            case "delete": {
                final Directory dir = writer.getDirectory();
                writer.close();
                for (String file : dir.listAll()) {
                    dir.deleteFile(file);
                }
                exit(atom("deleted"));
            }
            }
        }
    }

    @Override
    public void terminate(final OtpErlangObject reason) {
        super.terminate(reason);
        commitFuture.cancel(false);
        if (closeFuture != null) {
            closeFuture.cancel(false);
        }
        try {
            reader.close();
        } catch (IOException e) {
            error("Error while closing reader", e);
        }
        try {
            writer.rollback();
        } catch (IOException e1) {
            error("Error while closing writer", e1);
            final Directory dir = writer.getDirectory();
            try {
                if (IndexWriter.isLocked(dir)) {
                    IndexWriter.unlock(dir);
                }
            } catch (IOException e2) {
                error("Error while unlocking dir", e2);
            }
        }
    }

    private OtpErlangObject handleSearchCall(final OtpErlangTuple from,
            final Map<OtpErlangObject, OtpErlangObject> searchRequest) throws Exception {
        final String queryString = asString(searchRequest.getOrDefault(atom("query"), asBinary("*:*")));
        final boolean refresh = asBoolean(searchRequest.getOrDefault(atom("refresh"), atom("true")));
        final int limit = asInt(searchRequest.getOrDefault(atom("limit"), asInt(25)));
        final String partition = asString(searchRequest.get(atom("partition")));

        final List<String> counts = asListOfStrings(nilToNull(searchRequest.get(atom("counts"))));
        final List<String> ranges = asListOfStrings(nilToNull(searchRequest.get(atom("ranges"))));

        final List<String> includeFields = asListOfStrings(nilToNull(searchRequest.get(atom("include_fields"))));
        if (includeFields != null) {
            includeFields.add("_id");
        }

        final Query baseQuery;
        try {
            baseQuery = parseQuery(queryString, partition);
        } catch (final ParseException e) {
            return tuple(atom("error"), tuple(atom("bad_request"), asBinary(e.getMessage())));
        }

        final Query query;
        final OtpErlangList categories = nilToNull(searchRequest.get(atom("drilldown")));
        if (categories == null) {
            query = baseQuery;
        } else {
            final DrillDownQuery drilldownQuery = new DrillDownQuery(FacetIndexingParams.DEFAULT, baseQuery);
            categories.forEach((category) -> {
                final OtpErlangList category1 = (OtpErlangList) category;
                if (category1.arity() < 3) {
                    drilldownQuery.add(new CategoryPath(asArrayOfStrings(category1)));
                } else {
                    final String dim = asString(category1.elementAt(0));
                    final CategoryPath[] categoryPaths = new CategoryPath[category1.arity() - 1];
                    for (int i = 1; i < categoryPaths.length; i++) {
                        categoryPaths[i - 1] = new CategoryPath(dim, asString(category1.elementAt(i)));
                    }
                    drilldownQuery.add(categoryPaths);
                }
            });
            query = drilldownQuery;
        }

        final IndexSearcher searcher = getSearcher(refresh);

        final Weight weight = searcher.createNormalizedWeight(query);
        final boolean docsScoredInOrder = !weight.scoresDocsOutOfOrder();

        final Sort sort = parseSort(searchRequest.getOrDefault(atom("sort"), atom("relevance"))).rewrite(searcher);
        final ScoreDoc after = toScoreDoc(sort, nilToNull(searchRequest.get(atom("after"))));

        final Collector collector;
        if (limit == 0) {
            collector = new TotalHitCountCollector();
        } else {
            collector = TopScoreDocCollector.create(limit, docsScoredInOrder);
        }
        if (logger.isDebugEnabled()) {
            debug("Searching for " + query);
        }
        searchTimer.time(() -> {
            searcher.search(query, collector);
            return null;
        });
        return tuple(
                atom("ok"),
                asList(
                        tuple(atom("update_seq"), asOtp(updateSeq)),
                        tuple(atom("total_hits"), asOtp(getTotalHits(collector))),
                        tuple(atom("hits"), getHits(searcher, collector))));

    }

    private IndexSearcher getSearcher(boolean refresh) throws IOException {
        if (forceRefresh || refresh) {
            reopenIfChanged();
        }
        return new IndexSearcher(reader);
    }

    private void reopenIfChanged() throws IOException {
        final DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
        if (newReader != null) {
            this.reader.close();
            this.reader = newReader;
            this.forceRefresh = false;
        }
    }

    private long getTotalHits(final Collector collector) {
        if (collector instanceof TopDocsCollector) {
            return ((TopDocsCollector<?>) collector).getTotalHits();
        }
        if (collector instanceof TotalHitCountCollector) {
            return ((TotalHitCountCollector) collector).getTotalHits();
        }
        throw new IllegalArgumentException("Can't get total hits for " + collector);
    }

    private OtpErlangList getHits(final IndexSearcher searcher, final Collector collector) throws IOException {
        if (collector instanceof TopDocsCollector) {
            final ScoreDoc[] scoreDocs = ((TopDocsCollector<?>) collector).topDocs().scoreDocs;
            final OtpErlangObject[] objs = new OtpErlangObject[scoreDocs.length];
            for (int i = 0; i < scoreDocs.length; i++) {
                objs[i] = docToHit(searcher, scoreDocs[i]);
            }
            return asList(objs);
        }
        if (collector instanceof TotalHitCountCollector) {
            return emptyList();
        }
        throw new IllegalArgumentException("Can't get hits for " + collector);
    }

    private OtpErlangTuple docToHit(final IndexSearcher searcher, final ScoreDoc scoreDoc) throws IOException {
        final Document doc = searcher.doc(scoreDoc.doc);

        final Map<String, Object> fields = new HashMap<String, Object>();
        doc.getFields().forEach((field) -> {
            final Object value = field.numericValue() == null ? field.stringValue() : field.numericValue();
            final Object current = fields.get(field.name());
            if (current == null) {
                fields.put(field.name(), value);
            } else if (current instanceof List) {
                ((List) current).add(value);
            } else {
                final List<Object> list = new LinkedList<Object>();
                list.add(current);
                list.add(value);
                fields.put(field.name(), list);
            }
        });

        final OtpErlangObject order = asList(asFloat(scoreDoc.score), asInt(scoreDoc.doc));
        return tuple(atom("hit"), order, asOtp(fields));
    }

    private Sort parseSort(final OtpErlangObject obj) throws ParseException {
        if (atom("relevance").equals(obj)) {
            return Sort.RELEVANCE;
        }
        if (obj instanceof OtpErlangBinary) {
            return new Sort(toSortField(asString(obj)));
        }
        if (obj instanceof OtpErlangList) {
            final OtpErlangList list = (OtpErlangList) obj;
            final SortField[] fields = new SortField[list.arity()];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = toSortField(asString(list.elementAt(i)));
            }
            return new Sort(fields);
        }
        throw new ParseException(obj + " is not a valid sort");
    }

    private SortField toSortField(final String field) throws ParseException {
        switch (field) {
        case "<score>":
            return IndexService.INVERSE_FIELD_SCORE;
        case "-<score>":
            return SortField.FIELD_SCORE;
        case "<doc>":
            return SortField.FIELD_DOC;
        case "-<doc>":
            return IndexService.INVERSE_FIELD_DOC;
        default:
            Matcher m = DISTANCE_RE.matcher(field);
            if (m.matches()) {
                final String fieldOrder = m.group(1);
                final String fieldLon = m.group(2);
                final String fieldLat = m.group(3);
                final String lon = m.group(4);
                final String lat = m.group(5);
                final String units = m.group(6);

                final double radius;
                if ("mi".equals(units)) {
                    radius = DistanceUtils.EARTH_EQUATORIAL_RADIUS_MI;
                } else if ("km".equals(units)) {
                    radius = DistanceUtils.EARTH_EQUATORIAL_RADIUS_KM;
                } else if (null == units) {
                    radius = DistanceUtils.EARTH_EQUATORIAL_RADIUS_MI;
                } else {
                    throw new ParseException(units + " is not a recognized unit of measurement");
                }

                final SpatialContext ctx = SpatialContext.GEO;
                final Point point = ctx.makePoint(Double.parseDouble(lon), Double.parseDouble(lat));
                final double degToKm = DistanceUtils.degrees2Dist(1, radius);

                final ValueSource valueSource = new DistanceValueSource(ctx, fieldLon, fieldLat, degToKm, point);
                return valueSource.getSortField(fieldOrder == "-");
            }

            m = SORT_FIELD_RE.matcher(field);
            if (m.matches()) {
                final String fieldOrder = m.group(1);
                final String fieldName = m.group(2);
                final SortField.Type fieldType;
                if ("string".equals(m.group(3))) {
                    fieldType = SortField.Type.STRING;
                } else if ("number".equals(m.group(3))) {
                    fieldType = SortField.Type.DOUBLE;
                } else if (null == m.group(3)) {
                    fieldType = SortField.Type.DOUBLE;
                } else {
                    throw new ParseException("Unrecognized type: " + m.group(3));
                }
                return new SortField(fieldName, fieldType, fieldOrder == "-");
            }
            throw new ParseException("Unrecognized sort parameter: " + field);
        }
    }

    private ScoreDoc toScoreDoc(final Sort sort, final OtpErlangObject any) {
        if (null == any) {
            return null;
        }
        if (any instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) any;
            if (tuple.arity() != 2) {
                throw new IllegalArgumentException("wrong arity");
            }
            return new ScoreDoc(asInt(tuple.elementAt(0)), asFloat(tuple.elementAt(1)));
        }
        if (any instanceof OtpErlangList) {
            final OtpErlangList list = (OtpErlangList) any;
            final int doc = asInt(list.elementAt(list.arity() - 1));
            // TODO see below
        }
        throw new IllegalArgumentException(any + " cannot be converted to ScoreDoc");
    }

//    private def toScoreDoc(sort: Sort, after: Any): Option[ScoreDoc] = after match {
//    case 'nil =>
//      None
//    case (score: Any, doc: Any) =>
//      Some(new ScoreDoc(ClouseauTypeFactory.toInteger(doc),
//        ClouseauTypeFactory.toFloat(score)))
//    case list: List[Object] =>
//      val doc = list.last
//      sort.getSort match {
//        case Array(SortField.FIELD_SCORE) =>
//          Some(new ScoreDoc(ClouseauTypeFactory.toInteger(doc),
//            ClouseauTypeFactory.toFloat(list.head)))
//        case _ =>
//          val fields = list dropRight 1
//          val sortfields = sort.getSort.toList
//          if (fields.length != sortfields.length) {
//            throw new ParseException("sort order not compatible with given bookmark")
//          }
//          Some(new FieldDoc(ClouseauTypeFactory.toInteger(doc),
//            Float.NaN, (sortfields zip fields) map {
//              case (_, 'null) =>
//                null
//              case (_, str: String) =>
//                Utils.stringToBytesRef(str)
//              case (SortField.FIELD_SCORE, number: java.lang.Double) =>
//                java.lang.Float.valueOf(number.floatValue())
//              case (IndexService.INVERSE_FIELD_SCORE, number: java.lang.Double) =>
//                java.lang.Float.valueOf(number.floatValue())
//              case (SortField.FIELD_DOC, number: java.lang.Double) =>
//                java.lang.Integer.valueOf(number.intValue())
//              case (IndexService.INVERSE_FIELD_DOC, number: java.lang.Double) =>
//                java.lang.Integer.valueOf(number.intValue())
//              case (_, field) =>
//                field
//            } toArray))
//      }
//  }

    private Query parseQuery(final String query, final String partition) throws ParseException {
        if (partition == null) {
            return qp.parse(query);
        } else {
            final BooleanQuery result = new BooleanQuery();
            result.add(new TermQuery(new Term("_partition", partition)), Occur.MUST);
            result.add(qp.parse(query), Occur.MUST);
            return result;
        }
    }

    private OtpErlangObject safeSearch(final Supplier<OtpErlangObject> s) {
        try {
            return s.get();
        } catch (final NumberFormatException e) {
            return tuple(
                    atom("error"),
                    tuple(atom("bad_request"), asBinary("cannot sort string field as numeric field")));
        } catch (final ClassCastException e) {
            return tuple(atom("error"), tuple(atom("bad_request"), asBinary(e.getMessage())));
        }
    }

    private void commit() {
        final long newUpdateSeq = pendingSeq;
        final long newPurgeSeq = pendingPurgeSeq;

        if (newUpdateSeq > updateSeq || newPurgeSeq > purgeSeq) {
            writer.setCommitData(
                    Map.of("update_seq", Long.toString(newUpdateSeq), "purge_seq", Long.toString(newPurgeSeq)));

            commitTimer.time(() -> {
                try {
                    writer.commit();
                } catch (final AlreadyClosedException e) {
                    error("Commit failed to closed writer", e);
                    IndexService.this.exit(asBinary(e.getMessage()));
                } catch (IOException e) {
                    error("Failed to commit changes", e);
                    IndexService.this.exit(asBinary(e.getMessage()));
                }
            });
            updateSeq = newUpdateSeq;
            purgeSeq = newPurgeSeq;
            forceRefresh = true;
            debug(String.format("Committed update sequence %d and purge sequence %d", newUpdateSeq, newPurgeSeq));
        }
    }

    private void closeIfIdle() {
        if (idle) {
            exit(asBinary("Idle Timeout"));
        }
        idle = true;
    }

    private long getCommittedSeq() {
        return getLong("update_seq");
    }

    private long getCommittedPurgeSeq() {
        return getLong("purge_seq");
    }

    private long getLong(final String name) {
        final String val = writer.getCommitData().get(name);
        if (val == null) {
            return 0;
        }
        return Long.parseLong(val);
    }

    private void debug(final String str) {
        logger.debug(prefix_name(str));
    }

    private void info(final String str) {
        logger.info(prefix_name(str));
    }

    private void error(final String str) {
        logger.error(prefix_name(str));
    }

    private void warn(final String str, final Throwable t) {
        logger.warn(prefix_name(str), t);
    }

    private void error(final String str, final Throwable t) {
        logger.error(prefix_name(str), t);
    }

    private void warn(final String str) {
        logger.warn(prefix_name(str));
    }

    private String prefix_name(final String str) {
        return String.format("%s %s", name, str);
    }

    public String toString() {
        return String.format("IndexService(%s)", name);
    }

}
