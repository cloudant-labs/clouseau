// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.GEN_CALL;
import static com.cloudant.cloujeau.OtpUtils.IS_AUTH;
import static com.cloudant.cloujeau.OtpUtils.YES;
import static com.cloudant.cloujeau.OtpUtils.reply;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.log4j.Logger;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpException;
import com.ericsson.otp.erlang.OtpMsg;
import com.ericsson.otp.erlang.OtpSelf;

public class Main {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    public static void main(final String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                logger.fatal("Uncaught exception: " + e.getMessage());
                System.exit(1);
            }
        });

        CompositeConfiguration config = new CompositeConfiguration();
        config.addConfiguration(new SystemConfiguration());

        final String fileName = args.length > 0 ? args[0] : "clouseau.ini";
        HierarchicalINIConfiguration reloadableConfig = new HierarchicalINIConfiguration(fileName);
        reloadableConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        config.addConfiguration(reloadableConfig);

        String name = config.getString("clouseau.name", "clouseau@127.0.0.1");
        String cookie = config.getString("clouseau.cookie", "monster");
        boolean closeIfIdleEnabled = config.getBoolean("clouseau.close_if_idle", false);
        int idleTimeout = config.getInt("clouseau.idle_check_interval_secs", 300);
        if (closeIfIdleEnabled) {
            logger.info(String.format("Idle timout is enabled and will check the indexer idle status every %d seconds",
                    idleTimeout));
        }

        final OtpSelf self = new OtpSelf(name, cookie);
        self.publishPort();

        logger.info("Clouseau running as " + name);

        final ExecutorService executor = Executors.newCachedThreadPool();

        while (true) {
            final OtpConnection conn = self.accept();
            executor.execute(() -> {
                while (conn.isConnected()) {
                    try {
                        final OtpMsg msg = conn.receiveMsg();
                        executor.execute(() -> {
                            try {
                                handleMessage(self, conn, msg);
                            } catch (final OtpException | IOException e) {
                                logger.error("Error when handling message", e);
                                conn.close();
                            }
                        });
                    } catch (final OtpException | IOException e) {
                        logger.error("Error when receiving message", e);
                        conn.close();
                        break;
                    }
                }
            });
        }
    }

    private static void handleMessage(OtpSelf self, OtpConnection conn, OtpMsg msg) throws IOException, OtpException {
        switch (msg.type()) {
        case OtpMsg.linkTag:
            break;

        case OtpMsg.unlinkTag:
            break;

        case OtpMsg.exitTag:
            break;

        case OtpMsg.exit2Tag:
            break;

        case OtpMsg.sendTag:
            break;

        case OtpMsg.regSendTag:
            final OtpErlangObject obj = msg.getMsg();
            if (obj instanceof OtpErlangTuple) {
                OtpErlangTuple tuple = (OtpErlangTuple) obj;
                OtpErlangAtom atom = (OtpErlangAtom) tuple.elementAt(0);
                if (GEN_CALL.equals(atom)) {
                    OtpErlangTuple from = (OtpErlangTuple) tuple.elementAt(1);
                    OtpErlangObject request = tuple.elementAt(2);
                    switch (msg.getRecipientName()) {
                    case "net_kernel":
                        handleNetKernelMsg(conn, from, request);
                        break;
                    case "main":
                        break;
                    case "analyze":
                        handleAnalyzeMsg(conn, from, request);
                        break;
                    case "cleanup":
                        break;
                    default:
                        reply(conn, from, self.createPid());
                        break;
                    }
                }
            }
            break;

        default:
            logger.warn("received message of unknown type " + msg.type());
        }
    }

    private static void handleNetKernelMsg(OtpConnection conn, OtpErlangTuple from, OtpErlangObject request)
            throws IOException {
        if (request instanceof OtpErlangTuple) {
            if (IS_AUTH.equals(((OtpErlangTuple) request).elementAt(0))) {
                // respond to a "ping".
                reply(conn, from, YES);
            }
        }
    }

    private static void handleAnalyzeMsg(OtpConnection conn, OtpErlangTuple from, OtpErlangObject request)
            throws IOException {

    }

}
