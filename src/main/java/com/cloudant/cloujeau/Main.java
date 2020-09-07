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

import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;

import org.apache.commons.configuration.*;
import com.ericsson.otp.erlang.*;
import org.apache.log4j.*;

import static com.cloudant.cloujeau.OtpUtils.*;

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
        
        final OtpConnection conn = self.accept();
        while (true) {
            final OtpMsg msg = conn.receiveMsg();
            handleMessage(self, conn, msg);
        }
    }

    private static void handleMessage(OtpSelf self, OtpConnection conn, OtpMsg msg) throws Exception {
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
                    OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
                    OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
                    OtpErlangObject cmd = tuple.elementAt(2);

                    // respond to a "ping".
                    if ("net_kernel".equals(msg.getRecipientName())) {
                        if (cmd instanceof OtpErlangTuple) {
                            if (IS_AUTH.equals(((OtpErlangTuple) cmd).elementAt(0))) {
                                conn.send(fromPid, genCallReply(fromRef, YES));
                            }
                        }
                    } else {
                        conn.send(fromPid, genCallReply(fromRef, self.createPid()));
                    }
                }
            }
            break;

        default:
            logger.warn("received message of unknown type " + msg.type());
        }
    }

}
