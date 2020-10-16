package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.asString;
import static com.cloudant.clouseau.OtpUtils.atom;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexCleanupService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau.cleanup");

    private final File rootDir;

    public IndexCleanupService(ServerState state) {
        super(state, "cleanup");
        this.rootDir = new File(state.config.getString("clouseau.dir", "target/indexes"));
    }

    @Override
    public void handleCast(final OtpErlangObject request) throws Exception {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            if (atom("cleanup").equals(tuple.elementAt(0)) && tuple.arity() == 2) {
                cleanupPath(asString(tuple.elementAt(1)));
            }
            if (atom("cleanup").equals(tuple.elementAt(0)) && tuple.arity() == 3) {
                final OtpErlangList list = (OtpErlangList) tuple.elementAt(2);
                final Set<String> activeSigs = new HashSet<String>(list.arity());
                for (int i = 0; i < list.arity(); i++) {
                    activeSigs.add(asString((OtpErlangBinary) list.elementAt(i)));
                }
                cleanupDb(asString(tuple.elementAt(1)), activeSigs);
            }
            if (atom("rename").equals(tuple.elementAt(0))) {
                renamePath(asString(tuple.elementAt(1)));
            }
        }
    }

    private void cleanupPath(String path) {
        final File dir = new File(rootDir, path);
        logger.info("Removing " + path);
        recursivelyDelete(dir);
    }

    private void cleanupDb(String dbName, Set<String> activeSigs) {
        logger.info("Cleaning up " + dbName);
        final Pattern pattern = Pattern.compile("shards/[0-9a-f]+-[0-9a-f]+/" + dbName + "\\.[0-9]+/([0-9a-f]+)$");
        cleanup(rootDir, pattern, activeSigs);
    }

    private void renamePath(String dbName) {
        final File srcDir = new File(rootDir, dbName);
        final DateFormat sdf = new SimpleDateFormat("yyyyMMdd'.'HHmmss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        final String sdfNow = sdf.format(Calendar.getInstance().getTime());
        // move timestamp information in dbName to end of destination path
        // for example, from foo.1234567890 to foo.20170912.092828.deleted.1234567890
        final String destPath = dbName.substring(0, dbName.length() - 10) + sdfNow + ".deleted."
                + dbName.substring(dbName.length() - 10, dbName.length());
        final File destDir = new File(rootDir, destPath);
        logger.info(String.format("Renaming '%s' to '%s'", srcDir.getAbsolutePath(), destDir.getAbsolutePath()));

        if (!srcDir.isDirectory()) {
            return;
        }
        if (!srcDir.renameTo(destDir)) {
            logger.error(
                    String.format(
                            "Failed to rename directory from '%s' to '%s'",
                            srcDir.getAbsolutePath(),
                            destDir.getAbsolutePath()));
        }
    }

    private void cleanup(final File fileOrDir, final Pattern includePattern, final Set<String> activeSigs) {
        if (!fileOrDir.isDirectory()) {
          return;
        }
        for (File file : fileOrDir.listFiles()) {
          cleanup(file, includePattern, activeSigs);
        }
        Matcher m = includePattern.matcher(fileOrDir.getAbsolutePath());
        if (m.find() && !activeSigs.contains(m.group(1))) {
          logger.info("Removing unreachable index " + m.group());
          // final Service main = state.serviceRegistry.lookup("main");
          // main.handleCall(from, request);
//          call('main, ('delete, m.group)) match {
//            case 'ok =>
//              'ok
//            case ('error, 'not_found) =>
//              recursivelyDelete(fileOrDir)
//              fileOrDir.delete
//          }
        }
      }

    private void recursivelyDelete(final File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            for (File file : fileOrDir.listFiles()) {
                recursivelyDelete(file);
            }
            if (fileOrDir.isFile()) {
                fileOrDir.delete();
            }
        }
    }

}
