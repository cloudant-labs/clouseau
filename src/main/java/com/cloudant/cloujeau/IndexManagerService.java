package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.*;
import static com.cloudant.cloujeau.OtpUtils.asBinary;
import static com.cloudant.cloujeau.OtpUtils.asString;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexManagerService extends Service {

    private class LRU {

        private int initialCapacity = 100;
        private float loadFactor = 0.75f;

        private class InnerLRU extends LinkedHashMap<String, OtpErlangPid> {
            private InnerLRU(final int initialCapacity, final float loadFactor) {
                super(initialCapacity, loadFactor, true);
            }

            @Override
            protected boolean removeEldestEntry(final Entry<String, OtpErlangPid> eldest) {
                final boolean result = size() > state.config.getInt("clouseau.max_indexes_open", 100);
                if (result) {
                    send(eldest.getValue(), tuple(atom("close"), atom("lru")));
                }
                return result;
            }
        }

        private Counter lruMisses = state.metricRegistry
                .counter("com.cloudant.clouseau:type=IndexManagerService,name=lru.misses");
        private Counter lruEvictions = state.metricRegistry
                .counter("com.cloudant.clouseau:type=IndexManagerService,name=lru.evictions");

        private Map<String, OtpErlangPid> pathToPid = new InnerLRU(initialCapacity, loadFactor);
        private Map<OtpErlangPid, String> pidToPath = new HashMap<OtpErlangPid, String>(initialCapacity, loadFactor);

        private OtpErlangPid get(final String path) {
            final OtpErlangPid pid = pathToPid.get(path);
            if (pid != null) {
                lruMisses.inc();
            }
            return pid;
        }

        private void put(final String path, final OtpErlangPid pid) {
            final OtpErlangPid prev = pathToPid.put(path, pid);
            pidToPath.remove(prev);
            pidToPath.put(pid, path);
        }

        private void remove(final OtpErlangPid pid) {
            final String path = pidToPath.remove(pid);
            pathToPid.remove(path);
            if (path != null) {
                lruEvictions.inc();
            }
        }

        private boolean isEmpty() {
            return pidToPath.isEmpty();
        }

        private void close() {
            pidToPath.forEach((pid, path) -> {
                send(pid, tuple(atom("close"), atom("closing")));
            });
        }

        private void closeByPath(final String pathPrefix) {
            pidToPath.forEach((pid, path) -> {
                if (path.startsWith(pathPrefix)) {
                    logger.info("closing lru for " + path);
                    send(pid, tuple(atom("close"), atom("closing")));
                }
            });
        }

    }

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private final Timer openTimer;
    private final LRU lru;

    public IndexManagerService(final ServerState state) {
        super(state, "main");
        openTimer = state.metricRegistry.timer("com.cloudant.clouseau:type=IndexManagerService,name=opens");
        lru = new LRU();
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws Exception {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            switch (asString(tuple.elementAt(0))) {
            case "open":
                return open(from, tuple);
            case "disk_size":
                return getDiskSize(asString(tuple.elementAt(1)));
            }
        }
        if (request instanceof OtpErlangAtom) {
            switch (asString(request)) {
            case "get_root_dir":
                return tuple(atom("ok"), asBinary(rootDir().getAbsolutePath()));

            case "version":
                return tuple(atom("ok"), asBinary(getClass().getPackage().getImplementationVersion()));
            }
        }
        return null;
    }

    @Override
    public void handleInfo(final OtpErlangObject request) throws Exception {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            switch (asString(tuple.elementAt(0))) {
            case "touch_lru":
                lru.get(asString(tuple.elementAt(1)));
                break;
            }
        }
    }

    private OtpErlangObject open(final OtpErlangTuple from, final OtpErlangTuple request) throws Exception {
        final OtpErlangPid peer = (OtpErlangPid) request.elementAt(1);
        final OtpErlangBinary path = (OtpErlangBinary) request.elementAt(2);
        final OtpErlangObject analyzerConfig = request.elementAt(3);
        final String strPath = asString(path);

        final OtpErlangPid pid = lru.get(strPath);
        if (pid != null) {
            return tuple(atom("ok"), pid);
        }

        logger.info(String.format("Opening index at %s", strPath));
        try {
            return openTimer.time(() -> {
                final Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
                final IndexWriter writer = newWriter(path, analyzer);
                final QueryParser qp = new ClouseauQueryParser(LuceneUtils.VERSION, "default", analyzer);
                final IndexService index = new IndexService(state, strPath, writer, qp);
                state.serviceRegistry.register(index);
                index.link(peer);
                lru.put(strPath, index.self());
                return tuple(atom("ok"), index.self());
            });
        } catch (final IOException e) {
            return tuple(atom("error"), asBinary(e.getMessage()));
        }
    }

    private OtpErlangObject getDiskSize(final String path) {
        final File indexDir = new File(rootDir(), path);
        final String[] files = indexDir.list();
        long diskSize = 0;
        if (files != null) {
            for (final String file : files) {
                diskSize += new File(indexDir, file).length();
            }
        }
        return tuple(atom("ok"), asList(tuple(atom("disk_size"), asOtp(diskSize))));
    }

    private File rootDir() {
        return new File(state.config.getString("clouseau.dir", "target/indexes"));
    }

    private IndexWriter newWriter(final OtpErlangBinary path, final Analyzer analyzer) throws Exception {
        final Directory dir = newDirectory(new File(rootDir(), asString(path)));
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
