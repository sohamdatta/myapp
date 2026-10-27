package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Changing the address a login signs in with. The person gives their password (and a fresh code if two-step is on), a link goes
 * to the new address, and using it moves the login there and ends every session. The old address then gets a link to undo it,
 * good for seven days, which is how a takeover is taken back. Like a reset request, a request for an address that is already
 * someone's answers the same as any other, so it does not tell whether an account exists. While a change can still be undone no
 * other is made and its old address is kept for its owner, and an undo also makes the password unusable and removes any second
 * step set up since, so whoever had the new address keeps nothing.
 */
public final class EmailChangeService {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Db db;
    private final MailOutbox outbox;
    private final RateLimits limits;
    private final String publicUrl;

    public EmailChangeService(Db db, MailOutbox outbox, RateLimits limits, String publicUrl) {
        this.db = db;
        this.outbox = outbox;
        this.limits = limits;
        String withScheme = publicUrl.contains("://") ? publicUrl : "https://" + publicUrl;
        this.publicUrl = withScheme.endsWith("/") ? withScheme.substring(0, withScheme.length() - 1) : withScheme;
    }

    /** Whether an address was left behind by a change that can still be undone: nobody else may take it meanwhile. */
    static boolean reserved(Connection c, String email) throws SQLException {
        return Sql.one(c, "select 1 as x from auth.email_change where old_email = ?::citext and confirmed_at is not null "
                + "and undone_at is null and undo_until > now()", email) != null;
    }

    /** Ends a request not yet confirmed. Called when the password or two-step changes, so a request made with the old ones dies. */
    static void cancelPending(Connection c, UUID identityId) throws SQLException {
        Sql.update(c, "update auth.email_change set cancelled_at = now() where identity_id = ? and confirmed_at is null and cancelled_at is null",
                identityId);
    }

    private static ApiException invalidLink() {
        return new ApiException(400, "email.link_invalid", "This link is not valid any more. Ask for a new one from Security");
    }

    private static void lockBoth(Connection c, String a, String b) throws SQLException {
        String x = a.trim().toLowerCase(java.util.Locale.ROOT);
        String y = b.trim().toLowerCase(java.util.Locale.ROOT);
        AuthService.lockEmail(c, x.compareTo(y) <= 0 ? x : y);   // always the same order, so two changes never wait on each other
        if (!x.equals(y)) {
            AuthService.lockEmail(c, x.compareTo(y) <= 0 ? y : x);
        }
    }

    /** Asks for the change. Answers the same whether or not the new address already has a login. */
    public void request(SessionCtx ctx, String newEmail, String password) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot change an address");
        }
        String address = newEmail == null ? "" : newEmail.trim();
        if (address.length() > 254 || !EMAIL.matcher(address).matches()) {
            throw ApiException.badRequest("Enter a valid email address");
        }
        if (address.equalsIgnoreCase(ctx.email())) {
            throw ApiException.badRequest("That is already your address");
        }
        if (password == null || password.isEmpty()) {
            throw ApiException.badRequest("Enter your password");
        }
        StepUp.requireIfEnrolled(db, ctx);
        if (!limits.tryHit(RateLimits.EMAIL_CHANGE_PER_LOGIN, ctx.identityId().toString())) {
            throw new ApiException(429, "request.rate_limited", "Too many requests. Try again later");
        }
        String token = Secrets.newToken();
        String outcome = db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());
            Map<String, Object> identity = Sql.one(c, "select password_hash from auth.identity where id = ? and status = 'active' for update",
                    ctx.identityId());
            if (identity == null) {
                return "invalid";
            }
            if (AuthService.lockedOut(c, ctx.email())) {
                return "locked";
            }
            if (!Secrets.verifyPassword(password, (String) identity.get("passwordHash"))) {
                AuthService.recordFailure(c, ctx.email());
                return "wrong";
            }
            // while a change can still be undone no other one is made: a second change would take the first one's undo with it
            if (Sql.one(c, "select 1 as x from auth.email_change where identity_id = ? and confirmed_at is not null and undone_at is null "
                    + "and undo_until > now()", ctx.identityId()) != null) {
                return "window";
            }
            cancelPending(c, ctx.identityId());
            boolean taken = Sql.one(c, "select 1 as x from auth.identity where email = ?::citext", address) != null || reserved(c, address);
            // the row is made either way and the old address is told either way, so the requester's own inbox does not say whether
            // the new address has a login; only the new address's mail differs, and that goes to the new address
            UUID id = AuthService.uuid(Sql.one(c, "insert into auth.email_change (identity_id, old_email, new_email, confirm_hash, expires_at, cancelled_at) "
                    + "values (?, ?::citext, ?::citext, ?, now() + interval '24 hours', case when ? then now() end) returning id",
                    ctx.identityId(), ctx.email(), address, Secrets.sha256(token), taken).get("id"));
            if (!taken) {
                outbox.enqueue(c, null, address, "email-change-confirm", "Confirm your new sign-in address",
                        "Someone asked to use " + address + " to sign in instead of " + ctx.email() + ".\n\n"
                                + "To confirm, open this link within 24 hours:\n" + publicUrl + "/?confirm-email=" + token + "\n\n"
                                + "If you did not ask for this, ignore this message and nothing changes.\n", "emailchange-confirm:" + id);
            }
            outbox.enqueue(c, null, ctx.email(), "email-change-requested", "A change of your sign-in address was asked for",
                    "A change of the sign-in address for " + ctx.email() + " to " + address + " was asked for. Nothing changes until "
                            + "it is confirmed from the new address.\n\nIf this was not you, change your password now: that cancels the request.\n",
                    "emailchange-requested:" + id);
            return "ok";
        });
        switch (outcome) {
            case "ok" -> { }
            case "locked" -> throw AuthService.tooManyFailures();
            case "window" -> throw new ApiException(409, "email.undo_window",
                    "The address was changed in the last 7 days. Wait until that can no longer be undone, or undo it from the earlier address");
            default -> throw new ApiException(403, "auth.wrong_password", "The password is not correct");
        }
    }

    /** Uses the link sent to the new address: the login moves there and every session ends. */
    public void confirm(String token, String address) {
        refuseIfBusy(address);
        String undo = Secrets.newToken();
        boolean ok = db.inAuth(c -> {
            Map<String, Object> peek = Sql.one(c, "select old_email, new_email from auth.email_change where confirm_hash = ?",
                    Secrets.sha256(token == null ? "" : token));
            if (peek == null) {
                return false;
            }
            lockBoth(c, (String) peek.get("oldEmail"), (String) peek.get("newEmail"));
            Map<String, Object> row = Sql.one(c, "select e.id, e.identity_id, e.old_email, e.new_email from auth.email_change e "
                    + "join auth.identity i on i.id = e.identity_id where e.confirm_hash = ? and e.confirmed_at is null and e.cancelled_at is null "
                    + "and e.expires_at > now() and i.status = 'active' and i.email = e.old_email for update of e", Secrets.sha256(token));
            if (row == null || Sql.one(c, "select 1 as x from auth.identity where email = ?::citext", row.get("newEmail")) != null) {
                return false;
            }
            UUID identityId = AuthService.uuid(row.get("identityId"));
            Sql.update(c, "update auth.identity set email = ?::citext where id = ?", row.get("newEmail"), identityId);
            Sql.update(c, "update auth.email_change set confirmed_at = now(), undo_hash = ?, undo_until = now() + interval '7 days' where id = ?::uuid",
                    Secrets.sha256(undo), row.get("id"));
            AuthService.revokeAll(c, identityId, null);
            MfaService.endChallenges(c, identityId);
            Sql.update(c, "update auth.password_reset set used_at = now() where identity_id = ? and used_at is null", identityId);   // links mailed to the old address
            outbox.enqueue(c, null, (String) row.get("oldEmail"), "email-change-done", "Your sign-in address was changed",
                    "The sign-in address for this login was changed from " + row.get("oldEmail") + " to " + row.get("newEmail") + ", "
                            + "and every session was ended.\n\nIf this was you, nothing more to do. If it was not, undo it within 7 days:\n"
                            + publicUrl + "/?undo-email=" + undo + "\n\nThen change your password.\n", "emailchange-done:" + row.get("id"));
            outbox.enqueue(c, null, (String) row.get("newEmail"), "email-change-welcome", "Your sign-in address is changed",
                    "You can now sign in with " + row.get("newEmail") + ". Every earlier session was ended.\n",
                    "emailchange-welcome:" + row.get("id"));
            return true;
        });
        refuseUnless(ok, address);
    }

    /** Uses the link sent to the old address, within seven days: the login goes back and every session ends. */
    public void undo(String token, String address) {
        refuseIfBusy(address);
        boolean ok = db.inAuth(c -> {
            Map<String, Object> peek = Sql.one(c, "select old_email, new_email from auth.email_change where undo_hash = ?",
                    Secrets.sha256(token == null ? "" : token));
            if (peek == null) {
                return false;
            }
            lockBoth(c, (String) peek.get("oldEmail"), (String) peek.get("newEmail"));
            Map<String, Object> row = Sql.one(c, "select e.id, e.identity_id, e.old_email, e.new_email from auth.email_change e "
                    + "join auth.identity i on i.id = e.identity_id where e.undo_hash = ? and e.undone_at is null and e.undo_until > now() "
                    + "and i.email = e.new_email and i.status = 'active' for update of e", Secrets.sha256(token));
            if (row == null || Sql.one(c, "select 1 as x from auth.identity where email = ?::citext", row.get("oldEmail")) != null) {
                return false;
            }
            UUID identityId = AuthService.uuid(row.get("identityId"));
            // Taking the account back means taking back what the new address could have done meanwhile: the password is made
            // unusable (the owner sets one with a reset link to this address), and a second step set up since is removed
            Sql.update(c, "update auth.identity set email = ?::citext, password_hash = ? where id = ?", row.get("oldEmail"),
                    Secrets.hashPassword(Secrets.newToken()), identityId);
            Sql.update(c, "update auth.password_reset set used_at = now() where identity_id = ? and used_at is null", identityId);
            Sql.update(c, "update auth.mfa_recovery_code set used_at = coalesce(used_at, now()) where identity_id = ? "
                    + "and exists (select 1 from auth.identity i where i.id = ? and i.mfa_enrolled_at >= (select confirmed_at from auth.email_change where id = ?::uuid))",
                    identityId, identityId, row.get("id"));
            Sql.update(c, "update auth.identity set mfa_secret_enc = null, mfa_pending_secret_enc = null, mfa_enrolled_at = null, mfa_last_step = null "
                    + "where id = ? and mfa_enrolled_at >= (select confirmed_at from auth.email_change where id = ?::uuid)", identityId, row.get("id"));
            Sql.update(c, "update auth.email_change set undone_at = now() where id = ?::uuid", row.get("id"));
            Sql.update(c, "update auth.email_change set cancelled_at = now() where identity_id = ? and confirmed_at is null and cancelled_at is null",
                    identityId);
            AuthService.revokeAll(c, identityId, null);
            MfaService.endChallenges(c, identityId);
            outbox.enqueue(c, null, (String) row.get("oldEmail"), "email-change-undone", "Your sign-in address was put back",
                    "The sign-in address for this login is " + row.get("oldEmail") + " again, and every session was ended. The password was "
                            + "made unusable: ask for a reset link on the sign-in page to choose a new one.\n",
                    "emailchange-undone:" + row.get("id"));
            outbox.enqueue(c, null, (String) row.get("newEmail"), "email-change-undone-notice", "A change of sign-in address was undone",
                    "The change to " + row.get("newEmail") + " was undone from the previous address.\n", "emailchange-undone-notice:" + row.get("id"));
            return true;
        });
        refuseUnless(ok, address);
    }

    private void refuseIfBusy(String address) {
        if (address != null && !limits.allowed(RateLimits.EMAIL_LINK_FAILURES_PER_ADDRESS, address)) {
            throw new ApiException(429, "request.rate_limited", "Too many attempts. Try again in a few minutes");
        }
    }

    private void refuseUnless(boolean ok, String address) {
        if (!ok) {
            if (address != null) {
                limits.hit(RateLimits.EMAIL_LINK_FAILURES_PER_ADDRESS, address);
            }
            throw invalidLink();
        }
    }
}
