package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.asArrayOfStrings;
import static com.cloudant.clouseau.OtpUtils.asBinary;
import static com.cloudant.clouseau.OtpUtils.asBoolean;
import static com.cloudant.clouseau.OtpUtils.asBytesRef;
import static com.cloudant.clouseau.OtpUtils.asFloat;
import static com.cloudant.clouseau.OtpUtils.*;
import static com.cloudant.clouseau.OtpUtils.asJava;
import static com.cloudant.clouseau.OtpUtils.asList;
import static com.cloudant.clouseau.OtpUtils.asListOfStrings;
import static com.cloudant.clouseau.OtpUtils.asLong;
import static com.cloudant.clouseau.OtpUtils.asMap;
import static com.cloudant.clouseau.OtpUtils.asOtp;
import static com.cloudant.clouseau.OtpUtils.asSetOfStrings;
import static com.cloudant.clouseau.OtpUtils.asString;
import static com.cloudant.clouseau.OtpUtils.atom;
import static com.cloudant.clouseau.OtpUtils.emptyList;
import static com.cloudant.clouseau.OtpUtils.nilToNull;
import static com.cloudant.clouseau.OtpUtils.props;
import static com.cloudant.clouseau.OtpUtils.tuple;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.params.FacetIndexingParams;
import org.apache.lucene.facet.params.FacetSearchParams;
import org.apache.lucene.facet.range.DoubleRange;
import org.apache.lucene.facet.range.RangeAccumulator;
import org.apache.lucene.facet.range.RangeFacetRequest;
import org.apache.lucene.facet.search.CountFacetRequest;
import org.apache.lucene.facet.search.DrillDownQuery;
import org.apache.lucene.facet.search.FacetRequest;
import org.apache.lucene.facet.search.FacetResult;
import org.apache.lucene.facet.search.FacetResultNode;
import org.apache.lucene.facet.search.FacetsAccumulator;
import org.apache.lucene.facet.search.FacetsCollector;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesAccumulator;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiCollector;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TimeLimitingCollector;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.TotalHitCountCollector;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.grouping.GroupDocs;
import org.apache.lucene.search.grouping.SearchGroup;
import org.apache.lucene.search.grouping.TopGroups;
import org.apache.lucene.search.grouping.term.TermFirstPassGroupingCollector;
import org.apache.lucene.search.grouping.term.TermSecondPassGroupingCollector;
import org.apache.lucene.search.highlight.Formatter;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleFragmenter;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangDouble;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.distance.DistanceUtils;
import com.spatial4j.core.shape.Point;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Timer;

public class IndexService extends Service {

    private static class HighlightParameters {
        private final Highlighter highlighter;
        private final List<String> highlightFields;
        private final int highlightNumber;
        private final List<Analyzer> analyzers;

        public HighlightParameters(Highlighter highlighter, List<String> highlightFields, int highlightNumber,
                List<Analyzer> analyzers) {
            this.highlighter = highlighter;
            this.highlightFields = highlightFields;
            this.highlightNumber = highlightNumber;
            this.analyzers = analyzers;
        }

    }

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
    private final long timeAllowed;
    private final boolean countFieldsEnabled;

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

        searchTimer = Metrics.newTimer(getClass(), "searches");
        updateTimer = Metrics.newTimer(getClass(), "updates");
        deleteTimer = Metrics.newTimer(getClass(), "deletes");
        commitTimer = Metrics.newTimer(getClass(), "commits");
        parSearchTimeOutCount = Metrics.newCounter(getClass(), "partition_search.timeout.count");

        final int commitIntervalSecs = state.config.getInt("clouseau.commit_interval_secs", 30);
        timeAllowed = state.config.getLong("clouseau.search_allowed_timeout_msecs", 5000);
        countFieldsEnabled = state.config.getBoolean("clouseau.count_fields", false);

        commitFuture = state.scheduledExecutor.scheduleWithFixedDelay(() -> {
            commit();
        }, commitIntervalSecs, commitIntervalSecs, TimeUnit.SECONDS);

        final boolean closeIfIdleEnabled = state.config.getBoolean("clouseau.close_if_idle", true);
        final int idleTimeoutSecs = state.config.getInt("clouseau.idle_check_interval_secs", 300);
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
        if (request instanceof OtpErlangAtom) {
            switch (asString(request)) {
            case "get_update_seq":
                return tuple(atom("ok"), asLong(updateSeq));
            case "get_purge_seq":
                return tuple(atom("ok"), asLong(purgeSeq));
            case "info":
                return tuple(atom("ok"), getInfo());
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
                        return null;
                    });
                    return atom("ok");
                }
                case "update": {
                    return handleUpdateCall(tuple);
                }
                case "search": {
                    return handleSearchCall(asMap(tuple.elementAt(1)));
                }
                case "group1": {
                    return handleGroup1Call(tuple);
                }
                case "group2": {
                    return handleGroup2Call(asMap(tuple.elementAt(1)));
                }
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
        try {
            updateTimer.time(() -> {
                writer.updateDocument(new Term("_id", doc.get("_id")), doc);
                return null;
            });
        } catch (final Exception e) {
            error("exception when updating docs", e);
            terminate(asBinary(e.getMessage()));
        }
        return atom("ok");
    }

    @Override
    public void handleInfo(final OtpErlangObject request) throws IOException {
        idle = false;
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
            case "count_fields":
                countFields();
            }
        }
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            final OtpErlangObject cmd = tuple.elementAt(0);
            switch (asString(cmd)) {
            case "close":
                exit(tuple.elementAt(1));
            }
        }
    }

    @Override
    public void terminate(final OtpErlangObject reason) {
        final String strReason = reason instanceof OtpErlangBinary ? asString(reason) : reason.toString();
        info("Terminating for reason " + strReason);
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

    private OtpErlangObject handleSearchCall(final Map<OtpErlangObject, OtpErlangObject> searchRequest)
            throws IOException {
        final String queryString = asString(searchRequest.getOrDefault(atom("query"), asBinary("*:*")));
        final boolean refresh = asBoolean(searchRequest.getOrDefault(atom("refresh"), atom("true")));
        final int limit = asInt(searchRequest.getOrDefault(atom("limit"), asInt(25)));
        final String partition = asString(searchRequest.get(atom("partition")));

        final List<String> counts = asListOfStrings(nilToNull(searchRequest.get(atom("counts"))));
        final OtpErlangObject ranges = nilToNull(searchRequest.get(atom("ranges")));

        final Set<String> includeFields = asSetOfStrings(nilToNull(searchRequest.get(atom("include_fields"))));
        if (includeFields != null) {
            includeFields.add("_id");
        }

        final Query baseQuery = parseQuery(queryString, partition);

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

        return safeSearch(() -> {
            final Sort sort = parseSort(searchRequest.getOrDefault(atom("sort"), atom("relevance"))).rewrite(searcher);
            final ScoreDoc after = toScoreDoc(sort, nilToNull(searchRequest.get(atom("after"))));

            final Collector hitsCollector;
            if (limit == 0) {
                hitsCollector = new TotalHitCountCollector();
            } else if (after == null && Sort.RELEVANCE.equals(sort)) {
                hitsCollector = TopScoreDocCollector.create(limit, docsScoredInOrder);
            } else if (after != null && Sort.RELEVANCE.equals(sort)) {
                hitsCollector = TopScoreDocCollector.create(limit, after, docsScoredInOrder);
            } else if (after == null && sort != null) {
                hitsCollector = TopFieldCollector.create(sort, limit, true, false, false, docsScoredInOrder);
            } else if (after instanceof FieldDoc && sort != null) {
                hitsCollector = TopFieldCollector
                        .create(sort, limit, (FieldDoc) after, true, false, false, docsScoredInOrder);
            } else {
                throw new IllegalArgumentException();
            }

            final Collector countsCollector = createCountsCollector(counts);
            final Collector rangesCollector = createRangesCollector(ranges);

            final Collector collector = MultiCollector.wrap(hitsCollector, countsCollector, rangesCollector);

            if (logger.isDebugEnabled()) {
                debug("Searching for " + query);
            }
            searchTimer.time(() -> {
                if (partition == null) {
                    searcher.search(query, collector);
                } else {
                    try {
                        searcher.search(
                                query,
                                new TimeLimitingCollector(collector, TimeLimitingCollector.getGlobalCounter(),
                                        timeAllowed));
                    } catch (final TimeLimitingCollector.TimeExceededException e) {
                        parSearchTimeOutCount.inc();
                        throw new OtpReplyException("Query exceeded allowed time: " + timeAllowed + "ms.", e);
                    }
                }
                return null;
            });

            final HighlightParameters HPs = getHighlightParameters(searchRequest, query);

            final List<OtpErlangObject> elems = new ArrayList<OtpErlangObject>(5);
            elems.add(tuple(atom("update_seq"), asOtp(updateSeq)));
            elems.add(tuple(atom("total_hits"), asOtp(getTotalHits(hitsCollector))));
            elems.add(tuple(atom("hits"), getHits(hitsCollector, searcher, includeFields, HPs)));
            final OtpErlangObject countsOtp = convertFacets((FacetsCollector) countsCollector);
            if (countsOtp != null) {
                elems.add(tuple(atom("counts"), countsOtp));
            }
            final OtpErlangObject rangesOtp = convertFacets((FacetsCollector) rangesCollector);
            if (rangesOtp != null) {
                elems.add(tuple(atom("ranges"), rangesOtp));
            }

            return tuple(atom("ok"), asList(elems));
        });
    }

    private Collector createCountsCollector(final List<String> counts) throws IOException, ParseException {
        if (counts == null) {
            return null;
        }
        final SortedSetDocValuesReaderState state;
        try {
            state = new SortedSetDocValuesReaderState(reader);
        } catch (final IllegalArgumentException e) {
            if (e.getMessage().contains("was not indexed with SortedSetDocValues")) {
                return null;
            }
            throw e;
        }

        final List<FacetRequest> countFacetRequests = new ArrayList<FacetRequest>(counts.size());
        for (int i = 0; i < counts.size(); i++) {
            countFacetRequests.add(new CountFacetRequest(new CategoryPath(counts.get(i)), Integer.MAX_VALUE));
        }
        final FacetSearchParams facetSearchParams = new FacetSearchParams(countFacetRequests);
        final FacetsAccumulator acc;
        try {
            acc = new SortedSetDocValuesAccumulator(state, facetSearchParams);
        } catch (final IllegalArgumentException e) {
            throw new ParseException(e.getMessage());
        }

        return FacetsCollector.create(acc);
    }

    private Collector createRangesCollector(final OtpErlangObject ranges) throws ParseException {
        if (ranges == null) {
            return null;
        }
        final OtpErlangList rangeList = props(ranges);
        if (rangeList != null) {
            final List<FacetRequest> rangeFacetRequests = new ArrayList<FacetRequest>(rangeList.arity());
            for (int i = 0; i < rangeList.arity(); i++) {
                final OtpErlangObject item = rangeList.elementAt(i);
                if (item instanceof OtpErlangTuple && ((OtpErlangTuple) item).arity() == 2) {
                    final String name = asString(((OtpErlangTuple) item).elementAt(0));
                    final OtpErlangList list = props(((OtpErlangTuple) item).elementAt(1));
                    final List<DoubleRange> ranges1 = new ArrayList<DoubleRange>(list.arity());
                    for (final OtpErlangObject row : list) {
                        final String label = asString(((OtpErlangTuple) row).elementAt(0));
                        final String rangeQuery = asString(((OtpErlangTuple) row).elementAt(1));
                        final Query q = qp.parse(rangeQuery);
                        if (q instanceof NumericRangeQuery) {
                            final NumericRangeQuery<?> nq = (NumericRangeQuery<?>) q;
                            ranges1.add(
                                    new DoubleRange(label, nq.getMin().doubleValue(), nq.includesMin(),
                                            nq.getMax().doubleValue(), nq.includesMax()));
                        } else {
                            throw new ParseException(rangeQuery + " was not a well-formed range specification");
                        }
                    }
                    rangeFacetRequests.add(new RangeFacetRequest<DoubleRange>(name, ranges1));
                } else {
                    throw new ParseException("invalid ranges query");
                }
            }

            final FacetsAccumulator acc = new RangeAccumulator(rangeFacetRequests);
            return FacetsCollector.create(acc);
        }
        throw new ParseException(ranges + " is not a valid ranges query");
    }

    private OtpErlangObject convertFacets(final FacetsCollector c) throws IOException {
        if (c == null) {
            return null;
        }
        OtpErlangObject[] elems = new OtpErlangObject[c.getFacetResults().size()];
        for (int i = 0; i < elems.length; i++) {
            elems[i] = convertFacet(c.getFacetResults().get(i));
        }
        return new OtpErlangList(elems);
    }

    private OtpErlangObject convertFacet(final FacetResult facet) {
        return convertFacetNode(facet.getFacetResultNode());
    }

    private OtpErlangObject convertFacetNode(final FacetResultNode node) {
        OtpErlangObject[] elems = new OtpErlangObject[node.label.components.length];
        for (int i = 0; i < elems.length; i++) {
            elems[i] = asBinary(node.label.components[i]);
        }
        final OtpErlangObject label = new OtpErlangList(elems);

        elems = new OtpErlangObject[node.subResults.size()];
        for (int i = 0; i < elems.length; i++) {
            elems[i] = convertFacetNode(node.subResults.get(i));
        }
        final OtpErlangList children = new OtpErlangList(elems);

        return new OtpErlangTuple(new OtpErlangObject[] { label, new OtpErlangDouble(node.value), children });
    }

    private OtpErlangObject handleGroup1Call(final OtpErlangTuple tuple) throws IOException, ParseException {
        final String queryString = asString(tuple.elementAt(1));
        final String field = asString(tuple.elementAt(2));
        final boolean refresh = asBoolean(tuple.elementAt(3));
        final OtpErlangObject groupSort = tuple.elementAt(4);
        final int groupOffset = asInt(tuple.elementAt(5));
        final int groupLimit = asInt(tuple.elementAt(6));

        return safeSearch(() -> {
            final Query query = parseQuery(queryString, null);
            final IndexSearcher searcher = getSearcher(refresh);
            final String fieldName = validateGroupField(field);
            final TermFirstPassGroupingCollector collector = new TermFirstPassGroupingCollector(fieldName,
                    parseSort(groupSort).rewrite(searcher), groupLimit);
            return searchTimer.time(() -> {
                searcher.search(query, collector);
                final Collection<SearchGroup<BytesRef>> topGroups = collector.getTopGroups(groupOffset, true);
                if (topGroups == null) {
                    return tuple(atom("ok"), emptyList());
                }
                final OtpErlangObject[] elems = new OtpErlangObject[topGroups.size()];
                final Iterator<SearchGroup<BytesRef>> it = topGroups.iterator();
                for (int i = 0; i < elems.length; i++) {
                    final SearchGroup<BytesRef> g = it.next();
                    elems[i] = tuple(asOtp(g.groupValue), asList(convertOrder(g.sortValues)));
                }
                return tuple(atom("ok"), asList(elems));
            });
        });
    }

    private OtpErlangObject handleGroup2Call(final Map<OtpErlangObject, OtpErlangObject> options) throws IOException {
        final String queryString = asString(options.getOrDefault(atom("query"), asBinary("*:*")));
        final String field = asString(options.get(atom("field")));
        final boolean refresh = asBoolean(options.getOrDefault(atom("refresh"), atom("true")));
        final OtpErlangList groups = (OtpErlangList) options.get(atom("groups"));
        final OtpErlangObject groupSort = options.get(atom("group_sort"));
        final OtpErlangObject docSort = options.get(atom("sort"));
        final int docLimit = asInt(options.getOrDefault(atom("limit"), asInt(25)));
        final Set<String> includeFields = asSetOfStrings(nilToNull(options.get(atom("include_fields"))));
        if (includeFields != null) {
            includeFields.add("_id");
        }

        final Query query = parseQuery(queryString, null);
        final IndexSearcher searcher = getSearcher(refresh);
        final Collection<SearchGroup<BytesRef>> groups1 = new ArrayList<SearchGroup<BytesRef>>(groups.arity());
        for (int i = 0; i < groups.arity(); i++) {
            groups1.add(makeSearchGroup(groups.elementAt(i)));
        }
        return safeSearch(() -> {
            final String fieldName = validateGroupField(field);
            final TermSecondPassGroupingCollector collector = new TermSecondPassGroupingCollector(fieldName, groups1,
                    parseSort(groupSort).rewrite(searcher), parseSort(docSort).rewrite(searcher), docLimit, true, false,
                    true);
            searchTimer.time(() -> {
                searcher.search(query, collector);
                return null;
            });
            final TopGroups<BytesRef> topGroups = collector.getTopGroups(0);
            if (topGroups == null) {
                return tuple(atom("ok"), asInt(0), asInt(0), emptyList());
            }
            final HighlightParameters HPs = getHighlightParameters(options, query);
            final OtpErlangObject[] elems = new OtpErlangObject[topGroups.groups.length];
            for (int i = 0; i < elems.length; i++) {
                final GroupDocs<BytesRef> g = topGroups.groups[i];
                final OtpErlangObject[] docs = new OtpErlangObject[g.scoreDocs.length];
                for (int j = 0; j < docs.length; j++) {
                    docs[j] = docToHit(searcher, g.scoreDocs[j], includeFields, HPs);
                }
                elems[i] = tuple(asOtp(g.groupValue), asOtp(g.totalHits), new OtpErlangList(docs));
            }
            return tuple(
                    atom("ok"),
                    asInt(topGroups.totalHitCount),
                    asInt(topGroups.totalGroupedHitCount),
                    new OtpErlangList(elems));
        });
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

    private OtpErlangList getHits(final Collector collector, final IndexSearcher searcher,
            final Set<String> includeFields, final HighlightParameters HPs)
            throws IOException, InvalidTokenOffsetsException {
        if (collector instanceof TopDocsCollector) {
            final ScoreDoc[] scoreDocs = ((TopDocsCollector<?>) collector).topDocs().scoreDocs;
            final OtpErlangObject[] objs = new OtpErlangObject[scoreDocs.length];
            for (int i = 0; i < scoreDocs.length; i++) {
                objs[i] = docToHit(searcher, scoreDocs[i], includeFields, HPs);
            }
            return asList(objs);
        }
        if (collector instanceof TotalHitCountCollector) {
            return emptyList();
        }
        throw new IllegalArgumentException("Can't get hits for " + collector);
    }

    private OtpErlangTuple docToHit(final IndexSearcher searcher, final ScoreDoc scoreDoc,
            final Set<String> includeFields, final HighlightParameters HPs)
            throws IOException, InvalidTokenOffsetsException {
        final Document doc;
        if (includeFields == null) {
            doc = searcher.doc(scoreDoc.doc);
        } else {
            doc = searcher.doc(scoreDoc.doc, includeFields);
        }

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
        final List<OtpErlangObject> order;
        if (scoreDoc instanceof FieldDoc) {
            order = convertOrder(((FieldDoc) scoreDoc).fields);
            order.add(asOtp(scoreDoc.doc));
        } else {
            order = new ArrayList<OtpErlangObject>(2);
            order.add(asFloat(scoreDoc.score));
            order.add(asInt(scoreDoc.doc));
        }
        if (HPs != null) {
            final OtpErlangObject[] elems = new OtpErlangObject[HPs.highlightFields.size()];
            for (int i = 0; i < HPs.highlightFields.size(); i++) {
                final String field = HPs.highlightFields.get(i);
                final Analyzer analyzer = HPs.analyzers.get(i);
                final String[] frags = HPs.highlighter
                        .getBestFragments(analyzer, field, doc.get(field), HPs.highlightNumber);
                elems[i] = tuple(asBinary(field), asOtp(frags));
            }
            fields.put("_highlights", new OtpErlangList(elems));
        }

        return tuple(atom("hit"), asOtp(order), asOtp(fields));
    }

    private List<OtpErlangObject> convertOrder(final Object... fields) {
        final List<OtpErlangObject> result = new ArrayList<OtpErlangObject>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null) {
                result.add(atom("null"));
            } else {
                result.add(asOtp(fields[i]));
            }
        }
        return result;
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

    private ScoreDoc toScoreDoc(final Sort sort, final OtpErlangObject after) throws ParseException {
        if (null == after) {
            return null;
        }
        if (after instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) after;
            if (tuple.arity() != 2) {
                throw new IllegalArgumentException("wrong arity");
            }
            return new ScoreDoc(asInt(tuple.elementAt(1)), asFloat(tuple.elementAt(0)));
        }
        if (after instanceof OtpErlangList) {
            final OtpErlangList list = (OtpErlangList) after;
            final int doc = asInt(list.elementAt(list.arity() - 1));
            final SortField[] sortFields = sort.getSort();
            if (sortFields.length == 1 && SortField.FIELD_SCORE.equals(sortFields[0])) {
                return new ScoreDoc(doc, asFloat(list.elementAt(0)));
            }
            if (list.arity() - 1 != sortFields.length) {
                throw new ParseException("sort order not compatible with given bookmark");
            }
            final Object[] fields = new Object[sortFields.length];
            for (int i = 0; i < fields.length; i++) {
                if (atom("null").equals(list.elementAt(i))) {
                    fields[i] = null;
                } else if (list.elementAt(i) instanceof OtpErlangBinary) {
                    fields[i] = new BytesRef(asString(list.elementAt(i)));
                } else if (SortField.FIELD_SCORE.equals(sortFields[i])) {
                    fields[i] = asFloat(list.elementAt(i));
                } else if (INVERSE_FIELD_SCORE.equals(sortFields[i])) {
                    fields[i] = asFloat(list.elementAt(i));
                } else if (SortField.FIELD_DOC.equals(sortFields[i])) {
                    fields[i] = asInt(list.elementAt(i));
                } else if (INVERSE_FIELD_DOC.equals(sortFields[i])) {
                    fields[i] = asInt(list.elementAt(i));
                } else {
                    fields[i] = asJava(list.elementAt(i));
                }
            }
            return new FieldDoc(doc, Float.NaN, fields);
        }
        throw new IllegalArgumentException(after + " cannot be converted to ScoreDoc");
    }

    private SearchGroup<BytesRef> makeSearchGroup(final OtpErlangObject any) {
        final OtpErlangTuple tuple = (OtpErlangTuple) any;
        final SearchGroup<BytesRef> result = new SearchGroup<BytesRef>();
        if (tuple.elementAt(0) instanceof OtpErlangBinary) {
            result.groupValue = asBytesRef(tuple.elementAt(0));
        }
        final OtpErlangList order = (OtpErlangList) tuple.elementAt(1);
        result.sortValues = new Object[order.arity()];
        for (int i = 0; i < order.arity(); i++) {
            result.sortValues[i] = asJava(order.elementAt(i));
        }
        return result;
    }

    private Query parseQuery(final String query, final String partition) {
        return safeSearch(() -> {
            if (partition == null) {
                return qp.parse(query);
            } else {
                final BooleanQuery result = new BooleanQuery();
                result.add(new TermQuery(new Term("_partition", partition)), Occur.MUST);
                result.add(qp.parse(query), Occur.MUST);
                return result;
            }
        });
    }

    private <T> T safeSearch(final SafeSearch<T> s) throws OtpReplyException {
        try {
            return s.search();
        } catch (final NumberFormatException e) {
            throw new OtpReplyException("bad_request", "cannot sort string field as numeric field", e);
        } catch (final ClassCastException e) {
            throw new OtpReplyException("bad_request", "Malformed query syntax", e);
        } catch (final ParseException e) {
            throw new OtpReplyException("bad_request", e.getMessage(), e);
        } catch (final Exception e) {
            final String err = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            error("Error during safeSearch", e);
            throw new OtpReplyException(err, e);
        }
    }

    private HighlightParameters getHighlightParameters(final Map<OtpErlangObject, OtpErlangObject> options,
            final Query query) throws ParseException {
        final OtpErlangObject highlightFields = nilToNull(options.get(atom("highlight_fields")));
        if (highlightFields == null) {
            return null;
        }

        if (highlightFields instanceof OtpErlangList) {
            final List<String> highlightFieldsList = asListOfStrings((OtpErlangList) highlightFields);
            final String preTag = asString(options.getOrDefault(atom("highlight_pre_tag"), asBinary("<em>")));
            final String postTag = asString(options.getOrDefault(atom("highlight_post_tag"), asBinary("</em>")));
            final int highlightNumber = asInt(options.getOrDefault(atom("highlight_number"), asInt(1)));
            final int highlightSize = asInt(options.getOrDefault(atom("highlight_size"), asInt(0)));
            final Formatter htmlFormatter = new SimpleHTMLFormatter(preTag, postTag);
            final Highlighter highlighter = new Highlighter(htmlFormatter, new QueryScorer(query));
            if (highlightSize > 0) {
                highlighter.setTextFragmenter(new SimpleFragmenter(highlightSize));
            }
            final List<Analyzer> analyzers = new ArrayList<Analyzer>();
            for (int i = 0; i < highlightFieldsList.size(); i++) {
                final String field = highlightFieldsList.get(i);
                if (qp.getAnalyzer() instanceof PerFieldAnalyzer) {
                    analyzers.add(((PerFieldAnalyzer) qp.getAnalyzer()).getWrappedAnalyzer(field));
                } else {
                    analyzers.add(qp.getAnalyzer());
                }
            }
            return new HighlightParameters(highlighter, highlightFieldsList, highlightNumber, analyzers);
        }

        throw new ParseException(highlightFields + " is not a valid highlight_fields query");
    }

    private String validateGroupField(final String field) throws ParseException {
        final Matcher m = SORT_FIELD_RE.matcher(field);
        if (m.find()) {
            final String fieldName = m.group(2);
            final String fieldType = m.group(3);
            if (fieldType == null || "string".equals(fieldType)) {
                return fieldName;
            }
            throw new ParseException("Group by number not supported. Group by string terms only.");
        }
        throw new ParseException("Unrecognized group_field parameter: " + field);
    }

    private void commit() {
        final long newUpdateSeq = pendingSeq;
        final long newPurgeSeq = pendingPurgeSeq;

        if (newUpdateSeq > updateSeq || newPurgeSeq > purgeSeq) {
            writer.setCommitData(
                    JDKUtils.mapOf("update_seq", Long.toString(newUpdateSeq), "purge_seq", Long.toString(newPurgeSeq)));

            try {
                commitTimer.time(() -> {
                    writer.commit();
                    return null;
                });
            } catch (final AlreadyClosedException e) {
                error("Commit failed to closed writer", e);
                IndexService.this.exit(asBinary(e.getMessage()));
            } catch (Exception e) {
                error("Failed to commit changes", e);
                IndexService.this.exit(asBinary(e.getMessage()));
            }
            updateSeq = newUpdateSeq;
            purgeSeq = newPurgeSeq;
            forceRefresh = true;
            info(String.format("Committed update sequence %d and purge sequence %d", newUpdateSeq, newPurgeSeq));
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

    private void countFields() {
        if (countFieldsEnabled) {
            final int warningThreshold = state.config.getInt("clouseau.field_count_warn_threshold", 5000);
            final Set<String> fields = new HashSet<String>();
            final Iterator<AtomicReaderContext> leaves = reader.leaves().iterator();
            while (leaves.hasNext() && fields.size() <= warningThreshold) {
                final Iterator<FieldInfo> fieldInfoIter = leaves.next().reader().getFieldInfos().iterator();
                while (fieldInfoIter.hasNext() && fields.size() <= warningThreshold) {
                    fields.add(fieldInfoIter.next().name);
                }
            }
            if (fields.size() > warningThreshold) {
                warn(
                        String.format(
                                "Index has more than %d fields, too many fields will lead to heap exhaustion",
                                warningThreshold));
            }
        }
    }

    private OtpErlangObject getInfo() throws IOException {
        reopenIfChanged();
        return asList(
                tuple(atom("disk_size"), getDiskSize()),
                tuple(atom("doc_count"), asInt(reader.numDocs())),
                tuple(atom("doc_del_count"), asInt(reader.numDeletedDocs())),
                tuple(atom("pending_seq"), asLong(pendingSeq)),
                tuple(atom("committed_seq"), asLong(getCommittedSeq())),
                tuple(atom("purge_seq"), asLong(purgeSeq)));
    }

    private OtpErlangLong getDiskSize() throws IOException {
        long sum = 0;
        for (String name : reader.directory().listAll()) {
            sum += reader.directory().fileLength(name);
        }
        return asLong(sum);
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
