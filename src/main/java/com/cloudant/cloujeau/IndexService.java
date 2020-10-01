package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.asAtom;
import static com.cloudant.cloujeau.OtpUtils.asBinary;
import static com.cloudant.cloujeau.OtpUtils.asBoolean;
import static com.cloudant.cloujeau.OtpUtils.asFloat;
import static com.cloudant.cloujeau.OtpUtils.asInt;
import static com.cloudant.cloujeau.OtpUtils.asList;
import static com.cloudant.cloujeau.OtpUtils.asLong;
import static com.cloudant.cloujeau.OtpUtils.asMap;
import static com.cloudant.cloujeau.OtpUtils.asOtp;
import static com.cloudant.cloujeau.OtpUtils.asString;
import static com.cloudant.cloujeau.OtpUtils.emptyList;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
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

public class IndexService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau");

    private final String name;

    private final IndexWriter writer;

    private final SearcherManager searcherManager;

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
        this.searcherManager = new SearcherManager(writer, true, null);
        this.qp = qp;

        searchTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=searches");
        updateTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=updates");
        deleteTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=deletes");
        commitTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexService,name=commits");
        parSearchTimeOutCount = state.metricRegistry
                .counter("com.cloudant.clouseau:type=IndexService,name=partition_search.timeout.count");

        final int commitIntervalSecs = state.config.getInt("clouseau.commit_interval_secs", 30);
        state.executor.scheduleWithFixedDelay(() -> {
            commit();
        }, commitIntervalSecs, commitIntervalSecs, TimeUnit.SECONDS);

        final boolean closeIfIdleEnabled = state.config.getBoolean("clouseau.close_if_idle", false);
        final int idleTimeoutSecs = state.config.getInt("clouseau.idle_check_interval_secs", 300);
        if (closeIfIdleEnabled) {
            state.executor.scheduleWithFixedDelay(() -> {
                closeIfIdle();
            }, idleTimeoutSecs, idleTimeoutSecs, TimeUnit.SECONDS);
        }

        this.updateSeq = getCommittedSeq();
        this.pendingSeq = updateSeq;
        this.purgeSeq = getCommittedPurgeSeq();
        this.pendingPurgeSeq = purgeSeq;
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws IOException {
        idle = false;
        if (request instanceof OtpErlangAtom) {
            switch (asString(request)) {
            case "get_update_seq":
                return tuple(asAtom("ok"), asLong(updateSeq));
            case "get_purge_seq":
                return tuple(asAtom("ok"), asLong(purgeSeq));
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
                    return asAtom("ok");
                }
                case "set_purge_seq": {
                    pendingPurgeSeq = asLong(tuple.elementAt(1));
                    debug("purge sequence is now " + pendingPurgeSeq);
                    return asAtom("ok");
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
                    return asAtom("ok");
                }
                case "update": {
                    final Document doc = ClouseauTypeFactory.newDocument(tuple.elementAt(1), tuple.elementAt(2));
                    debug("Updating " + doc.get("_id"));
                    updateTimer.time(() -> {
                        try {
                            writer.updateDocument(new Term("_id", doc.get("_id")), doc);
                        } catch (final IOException e) {
                            error("I/O exception when updating docs", e);
                            terminate(asBinary(e.getMessage()));
                        }
                    });
                    return asAtom("ok");
                }
                case "search":
                    return handleSearchCall(from, asMap(tuple.elementAt(1)));
                }
            }
        }

        return null;
    }

    @Override
    public void terminate(final OtpErlangObject reason) {
        try {
            searcherManager.close();
        } catch (IOException e) {
            error("Error while closing searcher", e);
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
            final Map<OtpErlangObject, OtpErlangObject> searchRequest) throws IOException {
        final String queryString = asString(searchRequest.getOrDefault(asAtom("query"), asBinary("*:*")));
        final boolean refresh = asBoolean(searchRequest.getOrDefault(asAtom("refresh"), asAtom("true")));
        final int limit = asInt(searchRequest.getOrDefault(asAtom("limit"), asInt(25)));
        final Object partition = searchRequest.get(asAtom("partition"));

        final Query baseQuery;
        try {
            baseQuery = parseQuery(queryString, partition);
        } catch (final ParseException e) {
            return tuple(asAtom("error"), tuple(asAtom("bad_request"), asBinary(e.getMessage())));
        }

        final Query query = baseQuery; // TODO add the other goop.

        if (refresh) {
            try {
                searcherManager.maybeRefreshBlocking();
            } catch (final IOException e) {
                error("I/O exception while refreshing searcher", e);
                IndexService.this.terminate(asBinary(e.getMessage()));
            }
        }

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final Weight weight = searcher.createNormalizedWeight(query);
            final boolean docsScoredInOrder = !weight.scoresDocsOutOfOrder();
            final Collector collector;
            if (limit == 0) {
                collector = new TotalHitCountCollector();
            } else {
                collector = TopScoreDocCollector.create(limit, docsScoredInOrder);
            }
            searcher.search(query, collector);
            return tuple(
                    asAtom("ok"),
                    asList(
                            tuple(asAtom("update_seq"), asOtp(updateSeq)),
                            tuple(asAtom("total_hits"), asOtp(getTotalHits(collector))),
                            tuple(asAtom("hits"), getHits(searcher, collector))));
        } finally {
            searcherManager.release(searcher);
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
        return tuple(asAtom("hit"), order, asOtp(fields));
    }

    private Query parseQuery(final String query, final Object partition) throws ParseException {
        if (asAtom("nil").equals(partition)) {
            return qp.parse(query);
        } else {
            final BooleanQuery result = new BooleanQuery();
            result.add(new TermQuery(new Term("_partition", asString((OtpErlangBinary) partition))), Occur.MUST);
            result.add(qp.parse(query), Occur.MUST);
            return result;
        }
    }

    private OtpErlangObject safeSearch(final Supplier<OtpErlangObject> s) {
        try {
            return s.get();
        } catch (final NumberFormatException e) {
            return tuple(
                    asAtom("error"),
                    tuple(asAtom("bad_request"), asBinary("cannot sort string field as numeric field")));
        } catch (final ClassCastException e) {
            return tuple(asAtom("error"), tuple(asAtom("bad_request"), asBinary(e.getMessage())));
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
                    IndexService.this.terminate(asBinary(e.getMessage()));
                } catch (IOException e) {
                    error("Failed to commit changes", e);
                    IndexService.this.terminate(asBinary(e.getMessage()));
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
            try {
                exit(asBinary("Idle Timeout"));
            } catch (final IOException e) {
                error("I/O exception while closing for idleness", e);
            }
        }
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
