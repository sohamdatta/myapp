package com.example.loginapp.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.loginapp.core.BankHashes;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;

/**
 * Gives a fingerprint to every bank account that does not have one: the accounts that existed before the column did, shortly after
 * the server starts, and (as a safety net) any written another way, once an hour. A pass over nothing to do is one cheap query.
 */
public class BankHashWorker {

    private static final Logger LOG = LoggerFactory.getLogger(BankHashWorker.class);

    private final Db db;
    private final Crypto crypto;

    public BankHashWorker(Db db, Crypto crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 3_600_000)
    public void fill() {
        try {
            int done = BankHashes.fill(db, crypto);
            if (done > 0) {
                LOG.info("Bank account fingerprints filled in: {}", done);
            }
        } catch (RuntimeException e) {
            LOG.warn("Filling in bank account fingerprints failed; it will be tried again: {}", e.getClass().getSimpleName());
        }
    }
}
