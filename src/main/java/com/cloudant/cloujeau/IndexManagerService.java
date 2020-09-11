package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.binaryToString;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.File;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexManagerService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    public IndexManagerService(final ServerState state) {
        super(state);
    }

    @Override
    public OtpErlangObject handleCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangObject request) throws Exception {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            if (atom("open").equals(tuple.elementAt(0))) {
                return handleOpenCall(conn, from, (OtpErlangTuple) request);
            }
        }
        return null;
    }

    private OtpErlangObject handleOpenCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangTuple request) throws Exception {
        final OtpErlangPid peer = (OtpErlangPid) request.elementAt(1);
        final OtpErlangBinary path = (OtpErlangBinary) request.elementAt(2);
        final OtpErlangObject analyzerConfig = request.elementAt(3);

        logger.info(String.format("Opening index at %s", binaryToString(path)));
        final IndexWriter writer = newWriter(path, analyzerConfig);
        final OtpErlangPid pid = state.self.createPid();
        final IndexService index = new IndexService(state, writer);
        state.addService(pid, index);
        conn.link(peer);
        return tuple(atom("ok"), pid);
    }

    private IndexWriter newWriter(final OtpErlangBinary path, final OtpErlangObject analyzerConfig) throws Exception {
        final File rootDir = new File(state.config.getString("clouseau.dir", "target/indexes"));
        final Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
        final Directory dir = newDirectory(new File(rootDir, binaryToString(path)));
        final IndexWriterConfig writerConfig = new IndexWriterConfig(LuceneUtils.VERSION, analyzer);

        return new IndexWriter(dir, writerConfig);
    }

    private Directory newDirectory(final File path) throws ReflectiveOperationException {
        final String lockClassName = state.config
                .getString("clouseau.lock_class", "org.apache.lucene.store.NativeFSLockFactory");
        final Class<?> lockClass = Class.forName(lockClassName);
        final LockFactory lockFactory = (LockFactory) lockClass.getDeclaredConstructor().newInstance();

        final String dirClassName = state.config
                .getString("clouseau.dir_class", "org.apache.lucene.store.NIOFSDirectory");
        final Class<?> dirClass = Class.forName(dirClassName);
        return (Directory) dirClass.getDeclaredConstructor(File.class, LockFactory.class)
                .newInstance(path, lockFactory);
    }

}
