package com.example.loginapp.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.MailSender;
import com.example.loginapp.core.PasswordService;

/** Handles waiting password-reset requests and sends what the outbox holds, every few seconds, and tidies old rows once an hour. Does nothing until a mail server is set up. */
public class MailWorker {

    private static final Logger LOG = LoggerFactory.getLogger(MailWorker.class);

    private final MailOutbox outbox;
    private final PasswordService passwords;
    private final MailSender sender;

    public MailWorker(MailOutbox outbox, PasswordService passwords, MailSender sender) {
        this.outbox = outbox;
        this.passwords = passwords;
        this.sender = sender;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void deliver() {
        try {
            passwords.sendWaitingLinks();   // turns "forgot password" requests into mail, even before a server is set up
        } catch (RuntimeException e) {
            LOG.warn("Handling password reset requests failed; will try again", e);
        }
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
