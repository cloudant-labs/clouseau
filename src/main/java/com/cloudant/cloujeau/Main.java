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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.log4j.Logger;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jmx.JmxReporter;
import com.ericsson.otp.erlang.OtpNode;

public class Main {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private static final MetricRegistry METRIC_REGISTRY = new MetricRegistry();
    private static final JmxReporter JMX_REPORTER = JmxReporter.forRegistry(METRIC_REGISTRY).build();

    private static final Thread SHUTDOWN_HOOK = new Thread() {
        public void run() {
            JMX_REPORTER.stop();
        }
    };

    static {
        JMX_REPORTER.start();
        Runtime.getRuntime().addShutdownHook(SHUTDOWN_HOOK);
    }

    private static final ScheduledExecutorService executor = Executors
            .newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    public static void main(final String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logger.fatal("Uncaught exception", e);
            System.exit(1);
        });

        final CompositeConfiguration config = new CompositeConfiguration();
        config.addConfiguration(new SystemConfiguration());

        final String fileName = args.length > 0 ? args[0] : "clouseau.ini";
        final FileConfiguration reloadableConfig = new HierarchicalINIConfiguration(fileName);
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

        final OtpNode node = new OtpNode(name, cookie);

        final ServerState state = new ServerState(config, executor, node, METRIC_REGISTRY);

        final ExecutorService executor = Executors.newCachedThreadPool();
        executor.execute(new IndexManagerService(state));
        executor.execute(new AnalyzerService(state));

        logger.info("Clouseau running as " + name);

        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

}
