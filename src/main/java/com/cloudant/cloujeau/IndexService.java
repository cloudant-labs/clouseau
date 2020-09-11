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

        return null;
    }

    private long getCommittedSeq() {
        final String updateSeq = writer.getCommitData().get("update_seq");
        if (updateSeq == null) {
            return 0;
        }
        return Long.parseLong(updateSeq);
    }

}
