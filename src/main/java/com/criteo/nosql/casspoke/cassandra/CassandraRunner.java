package com.criteo.nosql.casspoke.cassandra;

import com.criteo.nosql.casspoke.cassandra.metrics.CassandraMetrics;
import com.criteo.nosql.casspoke.cassandra.metrics.CassandraMetricsFactory;
import com.criteo.nosql.casspoke.config.Config;
import com.criteo.nosql.casspoke.discovery.IDiscovery;
import com.criteo.nosql.casspoke.discovery.Service;
import com.datastax.driver.core.AuthProvider;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.PlainTextAuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;

public class CassandraRunner implements AutoCloseable, Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CassandraRunner.class);

    private final Config cfg;
    private final IDiscovery discovery;
    private final long measurementPeriodInMs;
    private final long refreshDiscoveryPeriodInMs;

    private final CassandraMetricsFactory cassandraMetricsFactory;

    protected Map<Service, Set<InetSocketAddress>> services;
    protected final Map<Service, Optional<CassandraMonitor>> monitors;
    protected final Map<Service, CassandraMetrics> metrics;

    public CassandraRunner(Config cfg, IDiscovery discovery) {
        this.cfg = cfg;

        this.discovery = discovery;
        this.measurementPeriodInMs = Long.parseLong(cfg.getApp().getOrDefault("measurementPeriodInSec", "30")) * 1000L;
        this.refreshDiscoveryPeriodInMs = Long.parseLong(cfg.getApp().getOrDefault("refreshDiscoveryPeriodInSec", "300")) * 1000L;

        this.cassandraMetricsFactory = CassandraMetricsFactory.getInstance(cfg);

        this.services = Collections.emptyMap();
        this.monitors = new HashMap<>();
        this.metrics = new HashMap<>();
    }

    /**
     * Run monitors and discovery periodically.
     * It is an infinite loop. We can stop by interrupting its Thread
     */
    @Override
    public void run() {

        final List<EVENT> evts = Arrays.asList(EVENT.UPDATE_TOPOLOGY, EVENT.POKE);

        try {
            for (; ; ) {
                final long start = System.currentTimeMillis();
                final EVENT evt = evts.get(0);
                dispatch_events(evt);
                final long stop = System.currentTimeMillis();
                logger.info("{} took {} ms", evt, stop - start);

                rescheduleEvent(evt, start, stop);
                Collections.sort(evts, Comparator.comparingLong(event -> event.nexTick));

                final long sleep_duration = evts.get(0).nexTick - System.currentTimeMillis() - 1;
                if (sleep_duration > 0) {
                    Thread.sleep(sleep_duration);
                    logger.info("WAIT took {} ms", sleep_duration);
                }
            }
        } catch (InterruptedException e) {
            logger.error("The run was interrupted");
            Thread.currentThread().interrupt();
        }
    }

    private void rescheduleEvent(EVENT lastEvt, long start, long stop) {
        final long duration = stop - start;
        if (duration >= measurementPeriodInMs) {
            logger.warn("Operation took longer than 1 tick, please increase tick rate if you see this message too often");
        }

        switch (lastEvt) {
            case UPDATE_TOPOLOGY:
                lastEvt.nexTick = start + refreshDiscoveryPeriodInMs;
                break;

            case POKE:
                lastEvt.nexTick = start + measurementPeriodInMs;
                break;
        }
    }

    private void dispatch_events(EVENT evt) {
        switch (evt) {
            case UPDATE_TOPOLOGY:
                updateTopology();
                break;

            case POKE:
                poke();
                break;
        }
    }

    public void updateTopology() {
        final Map<Service, Set<InetSocketAddress>> new_services = discovery.getServicesNodes();

        // Discovery down?
        if (new_services.isEmpty()) {
            logger.warn("Discovery sent back no service to monitor. Is it down? Check your configuration.");
            return;
        }

        // Dispose old monitors
        services.forEach((service, addresses) -> {
            if (!Objects.equals(addresses, new_services.get(service))) {
                logger.info("{} has changed, its monitor will be disposed.", service);
                monitors.remove(service)
                        .ifPresent(mon -> mon.close());
                metrics.remove(service)
                        .close();
            }
        });

        // Create new ones
        final int timeoutInMs = Integer.parseInt(cfg.getApp().getOrDefault("timeoutInSec", "60")) * 1000;

        Optional<AuthProvider> authProvider = Optional.empty();
        if (cfg.getApp().containsKey("username") && cfg.getApp().containsKey("password")) {
            authProvider = Optional.of(new PlainTextAuthProvider(cfg.getApp().get("username"), cfg.getApp().get("password")));
        }
        final Optional<AuthProvider> finalAuthProvider = authProvider;

        new_services.forEach((service, new_addresses) -> {
            if (!Objects.equals(services.get(service), new_addresses)) {
                logger.info("A new Monitor for {} will be created.", service);
                CassandraMetrics cassMetrics = cassandraMetricsFactory.createMetrics(service);
                monitors.put(service, CassandraMonitor.fromNodes(service, new_addresses, timeoutInMs, (Host host) -> cassMetrics.clear(), finalAuthProvider));
                metrics.put(service, cassMetrics);
            }
        });

        services = new_services;
    }

    public void poke() {
        monitors.forEach((service, monitor) -> {
            final CassandraMetrics m = metrics.get(service);
            m.updateGetLatency(monitor.map(CassandraMonitor::collectGetLatencies).orElse(Collections.emptyMap()));
            m.updateSetLatency(monitor.map(CassandraMonitor::collectSetLatencies).orElse(Collections.emptyMap()));
            m.updateAvailability(monitor.map(CassandraMonitor::collectAvailability).orElse(Collections.emptyMap()));
        });
    }

    @Override
    public void close() {
        monitors.values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(mon -> mon.close());
        metrics.values()
                .forEach(metric -> metric.close());
    }

    private enum EVENT {
        UPDATE_TOPOLOGY(System.currentTimeMillis()),
        POKE(System.currentTimeMillis());

        public long nexTick;

        EVENT(long nexTick) {
            this.nexTick = nexTick;
        }
    }
}
