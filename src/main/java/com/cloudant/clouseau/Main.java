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

package com.cloudant.clouseau;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import static java.util.concurrent.TimeUnit.*;

import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.log4j.Logger;

import com.ericsson.otp.erlang.ClouseauNode;

public class Main {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private static final int nThreads = Runtime.getRuntime().availableProcessors();
    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);

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

        final int serviceCapacity = config.getInt("clouseau.max_indexes_open", 100);
        final ServiceRegistry serviceRegistry = new ServiceRegistry(serviceCapacity);
        final ClouseauNode node = new ClouseauNode(name, cookie, serviceRegistry);
        final ServerState state = new ServerState(config, node, serviceRegistry, scheduledExecutor);

        serviceRegistry.register(new IndexManagerService(state));
        serviceRegistry.register(new AnalyzerService(state));
        serviceRegistry.register(new IndexCleanupService(state));

        final Thread[] workers = new Thread[nThreads];
        for (int i = 0; i < nThreads; i++) {
            workers[i] = new Thread(new Worker(state), "IdleClouseauWorker-" + i);
            workers[i].start();
        }

        logger.info("Clouseau running as " + name);

        for (int i = 0; i < nThreads; i++) {
            workers[i].join();
        }

        node.close();
    }

    private static class Worker implements Runnable {

        private final ServerState state;

        public Worker(final ServerState state) {
            this.state = state;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    final Service service = state.serviceRegistry.borrowPending();
                    try {
                        final Thread currentThread = Thread.currentThread();
                        final String originalThreadName = currentThread.getName();
                        currentThread.setName(service.toString());
                        service.processMessages();
                        currentThread.setName(originalThreadName);
                    } finally {
                        state.serviceRegistry.returnPending(service);
                    }
                } catch (final InterruptedException e) {
                    logger.fatal("Worker thread was interrupted");
                    System.exit(1);
                }
            }
        }

    }
}
