package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.*;

import java.io.IOException;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherManager;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexService extends Service {

    private final IndexWriter writer;

    private final SearcherManager searcherManager;

    public IndexService(final ServerState state, final IndexWriter writer)
            throws ReflectiveOperationException, IOException {
        super(state);
        this.writer = writer;
        this.searcherManager = new SearcherManager(writer, true, null);
    }

    @Override
    public OtpErlangObject handleCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangObject request) throws IOException {
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
                return handleSearchCall(conn, from, tuple.elementAt(1));
            }
        }

        return null;
    }

    private OtpErlangObject handleSearchCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangObject searchRequest) {
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

}
