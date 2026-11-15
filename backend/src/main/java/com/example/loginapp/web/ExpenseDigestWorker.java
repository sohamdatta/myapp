package com.example.loginapp.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.loginapp.core.ExpenseDigest;

/** Sends the daily digest of expense claims and loan requests that have waited three days: each morning at 09:00 India time. */
public class ExpenseDigestWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseDigestWorker.class);

    private final ExpenseDigest digest;

    public ExpenseDigestWorker(ExpenseDigest digest) {
        this.digest = digest;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    public void morning() {
        try {
            LOG.info("Expense digest: {} people told", digest.run());
        } catch (RuntimeException e) {
            LOG.error("Expense digest failed", e);
        }
    }
}
