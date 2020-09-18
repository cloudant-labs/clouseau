package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils._long;
import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.util.IOUtils;

import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau");

    private final String name;

    private final IndexWriter writer;

    private final SearcherManager searcherManager;

    public IndexService(final ServerState state, final String name, final IndexWriter writer)
            throws ReflectiveOperationException, IOException {
        super(state);
        this.name = name;
        this.writer = writer;
        this.searcherManager = new SearcherManager(writer, true, null);
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws IOException {
        if (atom("get_update_seq").equals(request)) {
            return tuple(atom("ok"), _long(getCommittedSeq()));
        }
        if (atom("get_purge_seq").equals(request)) {
            return tuple(atom("ok"), _long(getCommittedPurgeSeq()));
        }
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            final OtpErlangObject cmd = tuple.elementAt(0);

            if (atom("set_update_seq").equals(cmd)) {
                return tuple(atom("ok"));
            }

            if (atom("set_purge_seq").equals(cmd)) {
                return tuple(atom("ok"));
            }

            if (atom("search").equals(cmd)) {
                return handleSearchCall(from, tuple.elementAt(1));
            }
        }

        return null;
    }

    @Override
    public void terminate(final OtpErlangObject reason) {
        IOUtils.closeWhileHandlingException(searcherManager, writer);
    }

    private OtpErlangObject handleSearchCall(final OtpErlangTuple from, final OtpErlangObject searchRequest) {
        return null;
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
