package com.example.loginapp.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.MailSender;

/** Sends what the outbox holds, every few seconds, and tidies old rows once an hour. Does nothing until a mail server is set up. */
public class MailWorker {

    private static final Logger LOG = LoggerFactory.getLogger(MailWorker.class);

    private final MailOutbox outbox;
    private final MailSender sender;

    public MailWorker(MailOutbox outbox, MailSender sender) {
        this.outbox = outbox;
        this.sender = sender;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void deliver() {
        if (sender == null) {
            return;
        }
        try {
            outbox.deliverDue(sender);
        } catch (RuntimeException e) {
            LOG.warn("Sending queued mail failed; will try again", e);
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void tidy() {
        try {
            outbox.purge();
        } catch (RuntimeException e) {
            LOG.warn("Tidying the mail outbox failed", e);
        }
    }
}
