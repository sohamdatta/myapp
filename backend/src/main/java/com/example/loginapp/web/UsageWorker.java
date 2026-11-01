package com.example.loginapp.web;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.loginapp.core.UsageStats;

/**
 * Counts how each Organization used the app: once a night at 01:00 India time, and once shortly after the server starts so a new
 * deployment, or one that was down overnight, catches up without waiting. The counting runs on a thread of its own, because the
 * scheduler has one thread and a first count of 90 days would otherwise hold up the mail worker for as long as it takes.
 * Counting is idempotent and takes a lock, so two servers (or the Super Admin's refresh) at once only wait their turn.
 */
public class UsageWorker {

    private static final Logger LOG = LoggerFactory.getLogger(UsageWorker.class);

    private final UsageStats usage;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "usage-count");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();

    public UsageWorker(UsageStats usage) {
        this.usage = usage;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Kolkata")
    public void nightly() {
        start("nightly");
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = Long.MAX_VALUE)
    public void catchUp() {
        start("catch-up");
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        thread.shutdownNow();
    }

    private void start(String why) {
        if (!busy.compareAndSet(false, true)) {
            return;   // the last count is still going
        }
        thread.submit(() -> {
            try {
                count(why);
            } finally {
                busy.set(false);
            }
        });
    }

    private void count(String why) {
        try {
            UsageStats.RunResult r = usage.run(false);
            if (!r.ran()) {
                LOG.info("Usage counts ({}): another count is running; skipped", why);
                return;
            }
            LOG.info("Usage counts ({}): {} organizations, {} days, {} numbers", why, r.organizations(), r.days(), r.values());
            if (!r.failures().isEmpty()) {
                LOG.warn("Usage counts: {} queries failed, first: {}", r.failures().size(), r.failures().getFirst());
            }
        } catch (RuntimeException e) {
            LOG.warn("Counting usage failed; it will be tried again", e);
        }
    }
}
