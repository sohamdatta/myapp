package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Email that has to arrive (a reset link, an undo link) is written to the outbox in the same transaction as the thing that
 * caused it, so nothing is sent for a change that rolled back and nothing is lost if the server stops. A worker sends it,
 * waiting longer after each failure. Delivery is at least once: if the server stops after the mail leaves but before the row
 * is marked, the mail is sent again, with the same Message-ID.
 */
public final class MailOutbox {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String PURPOSE = "mail";
    private static final int CLAIM = 20;
    /** How long a message may stay claimed before another worker may take it: the worker died mid-send. */
    private static final String CLAIM_FOR = "5 minutes";
    /** The wait before attempt 2, 3, ...; after the last attempt the message is failed and waits for a person. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6));
    static final int MAX_ATTEMPTS = BACKOFF.size() + 1;

    /** One message in the outbox as the Email page shows it. Never the body. */
    public record Row(long id, String to, String template, String subject, String status, int attempts, String lastError,
                      String createdAt, String nextAttemptAt) {}

    public record Suppressed(String email, String reason, String at, String by) {}

    public record Counts(long queued, long sending, long sent, long failed, long suppressed) {}

    public record Overview(boolean configured, String server, String from, Counts counts, List<Row> failures,
                           List<Suppressed> suppressions) {}

    private final Db db;
    private final Crypto crypto;

    public MailOutbox(Db db, Crypto crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    /**
     * Adds a message inside the caller's transaction. An address on the suppression list is recorded as suppressed and never
     * sent. The same dedupe key twice adds nothing (a retried request does not mail twice).
     *
     * @return whether a new row was added
     */
    public boolean enqueue(Connection c, UUID tenantId, String to, String template, String subject, String body, String dedupeKey)
            throws SQLException {
        String address = to == null ? "" : to.trim();
        if (!EMAIL.matcher(address).matches()) {
            throw new IllegalArgumentException("Not an email address: " + to);
        }
        boolean suppressed = Sql.one(c, "select 1 as x from mail.suppression where email = ?::citext", address) != null;
        return Sql.update(c, "insert into mail.outbox (tenant_id, to_email, template, subject, body_enc, status, dedupe_key) "
                        + "values (?, ?::citext, ?, ?, ?, ?, ?) on conflict (dedupe_key) do nothing",
                tenantId, address, template, subject, suppressed ? null : crypto.seal(PURPOSE, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                suppressed ? "suppressed" : "queued", dedupeKey) > 0;
    }

    /** Takes up to a batch of due messages, marks them as being sent, and returns them with their bodies opened. */
    List<MailSender.Mail> claim() {
        return db.inPlatform(c -> {
            // read through JDBC directly: Sql never hands back a bytea, by design
            record Claimed(long id, String to, String subject, byte[] body) {}
            List<Claimed> rows = new ArrayList<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "update mail.outbox set status = 'sending', locked_until = now() + ?::interval, attempts = attempts + 1 "
                            + "where id in (select id from mail.outbox "
                            + "  where next_attempt_at <= now() and (status = 'queued' or (status = 'sending' and locked_until < now())) "
                            + "  order by next_attempt_at limit " + CLAIM + " for update skip locked) "
                            + "returning id, to_email, subject, body_enc")) {
                ps.setString(1, CLAIM_FOR);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Claimed(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBytes(4)));
                    }
                }
            }
            List<MailSender.Mail> out = new ArrayList<>();
            for (Claimed r : rows) {
                boolean listed = Sql.one(c, "select 1 as x from mail.suppression where email = ?::citext", r.to()) != null;
                if (listed) {
                    Sql.update(c, "update mail.outbox set status = 'suppressed', body_enc = null, locked_until = null where id = ?", r.id());
                    continue;
                }
                String body;
                try {
                    body = new String(crypto.open(PURPOSE, r.body()), java.nio.charset.StandardCharsets.UTF_8);
                } catch (RuntimeException e) {
                    // one unreadable message (no body, or sealed under a key we no longer have) must not stop the rest
                    Sql.update(c, "update mail.outbox set status = 'failed', locked_until = null, last_error = ? where id = ?",
                            "The message body could not be read", r.id());
                    continue;
                }
                out.add(new MailSender.Mail(r.id(), r.to(), r.subject(), body));
            }
            return out;
        });
    }

    void markSent(long id) {
        db.inPlatform(c -> Sql.update(c, "update mail.outbox set status = 'sent', sent_at = now(), body_enc = null, "
                + "locked_until = null, last_error = null where id = ?", id));
    }

    /** A failed attempt: waits longer and tries again, or gives up when it was refused for good or the attempts are used up. */
    void markFailed(long id, String error, boolean permanent) {
        String shortError = error == null ? "failed" : error.substring(0, Math.min(error.length(), 500));
        db.inPlatform(c -> {
            int attempts = ((Number) Sql.one(c, "select attempts from mail.outbox where id = ?", id).get("attempts")).intValue();
            if (permanent || attempts >= MAX_ATTEMPTS) {
                return Sql.update(c, "update mail.outbox set status = 'failed', locked_until = null, last_error = ? where id = ?",
                        shortError, id);
            }
            long seconds = BACKOFF.get(attempts - 1).toSeconds();
            return Sql.update(c, "update mail.outbox set status = 'queued', locked_until = null, last_error = ?, "
                    + "next_attempt_at = now() + make_interval(secs => ?) where id = ?", shortError, seconds, id);
        });
    }

    /** Sends what is due, once. Returns how many went. */
    public int deliverDue(MailSender sender) {
        int sent = 0;
        for (MailSender.Mail mail : claim()) {
            try {
                sender.send(mail);
                markSent(mail.id());
                sent++;
            } catch (MailSender.Rejected e) {
                markFailed(mail.id(), e.getMessage(), true);
            } catch (Exception e) {
                markFailed(mail.id(), e.getClass().getSimpleName() + ": " + e.getMessage(), false);
            }
        }
        return sent;
    }

    /** Messages that are done with (sent) and older than a month are removed; failed ones stay until someone looks. */
    public int purge() {
        return db.inPlatform(c -> Sql.update(c, "delete from mail.outbox where status in ('sent', 'suppressed') and created_at < now() - interval '30 days'"));
    }

    // ── The platform Email page ─────────────────────────────────────────

    public Overview overview(SessionCtx ctx, MailSender sender) {
        ctx.requirePlatform("super_admin", "support");
        return db.inPlatform(c -> {
            Map<String, Long> by = new LinkedHashMap<>();
            for (Map<String, Object> r : Sql.query(c, "select status, count(*) as n from mail.outbox group by status")) {
                by.put((String) r.get("status"), ((Number) r.get("n")).longValue());
            }
            Counts counts = new Counts(by.getOrDefault("queued", 0L), by.getOrDefault("sending", 0L), by.getOrDefault("sent", 0L),
                    by.getOrDefault("failed", 0L), by.getOrDefault("suppressed", 0L));
            List<Row> failures = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, "select id, to_email, template, subject, status, attempts, last_error, created_at, "
                    + "next_attempt_at from mail.outbox where status = 'failed' or (status = 'queued' and attempts > 0) "
                    + "order by created_at desc limit 100")) {
                failures.add(new Row(((Number) r.get("id")).longValue(), (String) r.get("toEmail"), (String) r.get("template"),
                        (String) r.get("subject"), (String) r.get("status"), ((Number) r.get("attempts")).intValue(),
                        (String) r.get("lastError"), String.valueOf(r.get("createdAt")), String.valueOf(r.get("nextAttemptAt"))));
            }
            List<Suppressed> suppressions = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, "select email, reason, at, by from mail.suppression order by at desc")) {
                suppressions.add(new Suppressed((String) r.get("email"), (String) r.get("reason"), String.valueOf(r.get("at")), (String) r.get("by")));
            }
            return new Overview(sender != null, sender == null ? null : sender.describe(), sender == null ? null : sender.from(),
                    counts, failures, suppressions);
        });
    }

    /** Puts a failed message back in the queue for another go. */
    public void retry(SessionCtx ctx, long id) {
        ctx.requirePlatform("super_admin");
        int n = db.inPlatform(c -> {
            int changed = Sql.update(c, "update mail.outbox set status = 'queued', attempts = 0, next_attempt_at = now(), locked_until = null "
                    + "where id = ? and status = 'failed' and body_enc is not null", id);
            if (changed > 0) {
                audit(c, ctx, "email.retried", String.valueOf(id));
            }
            return changed;
        });
        if (n == 0) {
            throw new ApiException(409, "email.not_failed", "Only a failed message can be tried again");
        }
    }

    public void suppress(SessionCtx ctx, String email, String reason) {
        ctx.requirePlatform("super_admin");
        String address = email == null ? "" : email.trim();
        if (!EMAIL.matcher(address).matches()) {
            throw ApiException.badRequest("Enter an email address");
        }
        String why = reason == null || reason.isBlank() ? "manual" : reason;
        if (!List.of("bounce", "complaint", "manual").contains(why)) {
            throw ApiException.badRequest("The reason must be bounce, complaint or manual");
        }
        db.inPlatform(c -> {
            Sql.update(c, "insert into mail.suppression (email, reason, by) values (?::citext, ?, ?) "
                    + "on conflict (email) do update set reason = excluded.reason, at = now(), by = excluded.by", address, why, ctx.email());
            audit(c, ctx, "email.suppressed", address);
            return null;
        });
    }

    public void unsuppress(SessionCtx ctx, String email) {
        ctx.requirePlatform("super_admin");
        db.inPlatform(c -> {
            Sql.update(c, "delete from mail.suppression where email = ?::citext", email == null ? "" : email.trim());
            audit(c, ctx, "email.unsuppressed", email == null ? "" : email.trim());
            return null;
        });
    }

    /** Queues a short test message to the address, to see the whole path work. */
    public void sendTest(SessionCtx ctx, String to) {
        ctx.requirePlatform("super_admin");
        db.inPlatform(c -> {
            enqueue(c, null, to, "test", "Test message", "This is a test message from the platform console. If you can read it, mail is working.\n",
                    "test:" + UUID.randomUUID());
            audit(c, ctx, "email.test_sent", to == null ? "" : to.trim());
            return null;
        });
    }

    private static void audit(Connection c, SessionCtx ctx, String action, String target) throws SQLException {
        Sql.update(c, "insert into platform.platform_audit (platform_user_id, action, target_type, detail) values (?, ?, 'email', ?::jsonb)",
                ctx.platformUserId(), action, Json.object(Map.of("target", target)));
    }
}
