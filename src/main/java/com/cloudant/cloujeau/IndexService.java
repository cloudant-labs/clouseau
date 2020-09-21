package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.*;
import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.util.IOUtils;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau");

    private final String name;

    private final IndexWriter writer;

    private final SearcherManager searcherManager;

    private long updateSeq;

    private long pendingSeq;

    private long purgeSeq;

    private long pendingPurgeSeq;

    private boolean committing = false;

    private boolean forceRefresh = false;

    private boolean idle = false;

    public IndexService(final ServerState state, final String name, final IndexWriter writer)
            throws ReflectiveOperationException, IOException {
        super(state);
        if (state == null) {
            throw new NullPointerException("state cannot be null");
        }
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        if (writer == null) {
            throw new NullPointerException("writer cannot be null");
        }
        this.name = name;
        this.writer = writer;
        this.searcherManager = new SearcherManager(writer, true, null);
        this.updateSeq = getCommittedSeq();
        this.pendingSeq = updateSeq;
        this.purgeSeq = getCommittedPurgeSeq();
        this.pendingPurgeSeq = purgeSeq;
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws IOException {
        if (request instanceof OtpErlangAtom) {
            switch (atomToString(request)) {
            case "get_update_seq":
                return tuple(atom("ok"), fromLong(updateSeq));
            case "get_purge_seq":
                return tuple(atom("ok"), fromLong(purgeSeq));
            }
        } else if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            final OtpErlangObject cmd = tuple.elementAt(0);

            if (cmd instanceof OtpErlangAtom) {
                switch (atomToString(cmd)) {
                case "set_update_seq":
                    pendingSeq = toLong(tuple.elementAt(1));
                    debug("Pending sequence is now " + pendingSeq);
                    return atom("ok");
                case "set_purge_seq":
                    pendingPurgeSeq = toLong(tuple.elementAt(1));
                    debug("purge sequence is now " + pendingPurgeSeq);
                    return atom("ok");
                case "search":
                    return handleSearchCall(from, tuple.elementAt(1));
                }
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
