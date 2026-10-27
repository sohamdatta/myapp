package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Forgotten, reset and changed passwords. Asking for a reset link says the same thing and does the same work whether or not
 * the address has an account; a worker ({@link #sendWaitingLinks}) later decides and mails. A link works once, for 30 minutes.
 * A new password ends every other session. A reset never signs anyone in.
 */
public final class PasswordService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int BATCH = 20;

    private final Db db;
    private final MailOutbox outbox;
    private final RateLimits limits;
    private final String publicUrl;

    /** @param production true when the server is set up for production; links to localhost are then a mistake */
    public PasswordService(Db db, MailOutbox outbox, RateLimits limits, String publicUrl, boolean production) {
        if (production && publicUrl.matches("(?i)^https?://(localhost|127\\.0\\.0\\.1)([:/].*)?$")) {
            throw new IllegalStateException("APP_PUBLIC_URL is not set: reset links in email would point at " + publicUrl);
        }
        this.db = db;
        this.outbox = outbox;
        this.limits = limits;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    private static ApiException invalidLink() {
        return new ApiException(410, "auth.reset_invalid", "This reset link has expired or was already used. Ask for a new one");
    }

    // ── forgot ──────────────────────────────────────────────────────────

    /**
     * Records the request, whoever is asking, and returns nothing: the caller answers the same way every time. Too many from
     * one address is refused (that says nothing about any account); too many for one email is accepted and ignored.
     */
    public void requestReset(String email, String address) {
        if (address != null && !limits.tryHit(RateLimits.FORGOT_PER_ADDRESS, address)) {
            throw new ApiException(429, "request.rate_limited", "Too many requests. Try again in a few minutes");
        }
        String clean = email == null ? "" : email.trim();
        if (!EMAIL.matcher(clean).matches() || clean.length() > 254) {
            return;   // answered the same as any other; nothing to look up
        }
        if (!limits.tryHit(RateLimits.FORGOT_PER_EMAIL, clean)) {
            return;
        }
        db.inAuth(c -> Sql.update(c, "insert into auth.reset_request (email) values (?::citext)", clean));
    }

    /** Called every few seconds: turns waiting requests into links in the outbox, for accounts that exist and are active. */
    public int sendWaitingLinks() {
        return db.inAuth(c -> {
            List<Map<String, Object>> waiting = Sql.query(c, "select id, email from auth.reset_request where handled_at is null "
                    + "order by id limit " + BATCH + " for update skip locked");
            for (Map<String, Object> r : waiting) {
                long id = ((Number) r.get("id")).longValue();
                String email = (String) r.get("email");
                Map<String, Object> identity = Sql.one(c, "select id from auth.identity where email = ?::citext and status = 'active'", email);
                if (identity != null) {
                    AuthService.lockEmail(c, email);   // one reset change at a time per address, as sign-in and change-password do
                    UUID identityId = AuthService.uuid(identity.get("id"));
                    if (Sql.one(c, "select 1 as x from auth.password_reset where identity_id = ? and used_at is null "
                            + "and expires_at > now() + interval '25 minutes'", identityId) != null) {
                        // a link went out in the last five minutes and is still good: the mail is on its way, so another
                        // request neither mails again nor kills it (otherwise requests could keep a person's link dying)
                        Sql.update(c, "update auth.reset_request set handled_at = now() where id = ?", id);
                        continue;
                    }
                    String token = Secrets.newToken();
                    Sql.update(c, "update auth.password_reset set used_at = now() where identity_id = ? and used_at is null", identityId);
                    Sql.update(c, "insert into auth.password_reset (identity_id, token_hash, expires_at) "
                            + "values (?, ?, now() + interval '30 minutes')", identityId, Secrets.sha256(token));
                    outbox.enqueue(c, null, email, "password-reset", "Reset your password",
                            "Someone asked to reset the password for " + email + ".\n\n"
                                    + "To choose a new password, open this link within 30 minutes:\n" + publicUrl + "/?reset=" + token + "\n\n"
                                    + "If it was not you, ignore this message; your password has not changed.\n",
                            "reset:" + id);
                }
                Sql.update(c, "update auth.reset_request set handled_at = now() where id = ?", id);
            }
            Sql.update(c, "delete from auth.reset_request where handled_at < now() - interval '1 day'");
            return waiting.size();
        });
    }

    // ── reset ───────────────────────────────────────────────────────────

    /** Sets a new password with a link's token. Ends all of the person's sessions; they sign in afresh. */
    public void reset(String token, String newPassword, String address) {
        if (address != null && !limits.allowed(RateLimits.RESET_FAILURES_PER_ADDRESS, address)) {
            throw new ApiException(429, "request.rate_limited", "Too many attempts. Try again in a few minutes");
        }
        Secrets.checkPasswordPolicy(newPassword);
        String hash = Secrets.hashPassword(newPassword);
        String outcome = db.inAuth(c -> {
            byte[] hashed = Secrets.sha256(token == null ? "" : token);
            // the address first (no lock), then its lock, then the link's row: the order sign-in and change-password use
            Map<String, Object> peek = Sql.one(c, "select i.email from auth.password_reset r join auth.identity i on i.id = r.identity_id "
                    + "where r.token_hash = ?", hashed);
            if (peek == null) {
                return "invalid";
            }
            AuthService.lockEmail(c, (String) peek.get("email"));
            Map<String, Object> link = Sql.one(c, "select r.id, r.identity_id, i.email from auth.password_reset r "
                    + "join auth.identity i on i.id = r.identity_id where r.token_hash = ? and r.used_at is null and r.expires_at > now() "
                    + "and i.status = 'active' for update of r", hashed);
            if (link == null) {
                return "invalid";
            }
            UUID identityId = AuthService.uuid(link.get("identityId"));
            String email = (String) link.get("email");
            Sql.update(c, "update auth.password_reset set used_at = now() where identity_id = ? and used_at is null", identityId);
            Sql.update(c, "update auth.identity set password_hash = ? where id = ?", hash, identityId);
            AuthService.revokeAll(c, identityId, null);
            AuthService.clearFailures(c, email);
            notifyChanged(c, email, "reset:" + link.get("id"));
            return "ok";
        });
        if (!outcome.equals("ok")) {
            if (address != null) {
                limits.hit(RateLimits.RESET_FAILURES_PER_ADDRESS, address);
            }
            throw invalidLink();
        }
    }

    // ── change (signed in) ──────────────────────────────────────────────

    /**
     * Changes the password of the person signed in, who must give the current one. A wrong current password counts toward the
     * same per-email lock as a wrong sign-in. Every other session ends; this one stays.
     */
    public void change(SessionCtx ctx, String current, String next) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot change a password");
        }
        if (current == null || current.isEmpty()) {
            throw ApiException.badRequest("Enter your current password");
        }
        Secrets.checkPasswordPolicy(next);
        if (next.equals(current)) {
            throw ApiException.badRequest("Choose a password different from the current one");
        }
        String hash = Secrets.hashPassword(next);
        String outcome = db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());   // first, as sign-in does, so the two never wait on each other's row
            Map<String, Object> identity = Sql.one(c, "select id, password_hash from auth.identity where id = ? for update", ctx.identityId());
            if (identity == null) {
                return "invalid";
            }
            if (AuthService.lockedOut(c, ctx.email())) {
                return "locked";
            }
            if (!Secrets.verifyPassword(current, (String) identity.get("passwordHash"))) {
                AuthService.recordFailure(c, ctx.email());   // recorded here, refused below: throwing now would roll it back
                return "wrong";
            }
            Sql.update(c, "update auth.identity set password_hash = ? where id = ?", hash, ctx.identityId());
            AuthService.revokeAll(c, ctx.identityId(), ctx.sessionId());
            Sql.update(c, "update auth.password_reset set used_at = now() where identity_id = ? and used_at is null", ctx.identityId());
            AuthService.clearFailures(c, ctx.email());
            notifyChanged(c, ctx.email(), "changed:" + UUID.randomUUID());
            return "ok";
        });
        switch (outcome) {
            case "ok" -> { }
            case "locked" -> throw AuthService.tooManyFailures();
            default -> throw new ApiException(403, "auth.wrong_password", "The current password is not correct");
        }
    }

    private void notifyChanged(Connection c, String email, String dedupe) throws SQLException {
        outbox.enqueue(c, null, email, "password-changed", "Your password was changed",
                "The password for " + email + " was just changed, and you were signed out everywhere else.\n\n"
                        + "If this was you, nothing more to do. If it was not, ask for a reset link on the sign-in page right away "
                        + "and tell your administrator.\n", dedupe);
    }
}
