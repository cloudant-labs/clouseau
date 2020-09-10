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

import static com.cloudant.cloujeau.OtpUtils.existingAtom;
import static com.cloudant.cloujeau.OtpUtils.reply;

import java.io.IOException;
import java.util.Map;
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

import static java.util.Map.entry;

public class Main {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private static final Map<String, Service> services = Map
            .ofEntries(entry("analyzer", new AnalyzerService()), entry("net_kernel", new NetKernelService()));

    public static void main(final String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logger.fatal("Uncaught exception", e);
            System.exit(1);
        });

        final CompositeConfiguration config = new CompositeConfiguration();
        config.addConfiguration(new SystemConfiguration());

        final String fileName = args.length > 0 ? args[0] : "clouseau.ini";
        final HierarchicalINIConfiguration reloadableConfig = new HierarchicalINIConfiguration(fileName);
        reloadableConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        config.addConfiguration(reloadableConfig);

        final String name = config.getString("clouseau.name", "clouseau@127.0.0.1");
        final String cookie = config.getString("clouseau.cookie", "monster");
        final boolean closeIfIdleEnabled = config.getBoolean("clouseau.close_if_idle", false);
        final int idleTimeout = config.getInt("clouseau.idle_check_interval_secs", 300);
        if (closeIfIdleEnabled) {
            logger.info(
                    String.format(
                            "Idle timout is enabled and will check the indexer idle status every %d seconds",
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

    private static void handleMessage(final OtpSelf self, final OtpConnection conn, final OtpMsg msg)
            throws IOException, OtpException {
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
                final OtpErlangTuple tuple = (OtpErlangTuple) obj;
                final OtpErlangAtom atom = (OtpErlangAtom) tuple.elementAt(0);
                if (existingAtom("$gen_call").equals(atom)) {
                    final OtpErlangTuple from = (OtpErlangTuple) tuple.elementAt(1);
                    final OtpErlangObject request = tuple.elementAt(2);
                    final Service service = services.get(msg.getRecipientName());
                    if (service == null) {
                        logger.warn("No registered process called " + msg.getRecipientName());
                        conn.exit(msg.getSenderPid(), existingAtom("noproc"));
                        break;
                    }

                    final OtpErlangObject response = service.handleCall(from, request);
                    if (response != null) {
                        reply(conn, from, response);
                    }
                }
            }
            break;

        default:
            logger.warn("received message of unknown type " + msg.type());
        }

    }

}
