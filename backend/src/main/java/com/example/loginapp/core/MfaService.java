package com.example.loginapp.core;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two-step sign-in with an authenticator app (RFC 6238). Setting up writes a pending secret that becomes the real one only
 * when a code from it is shown right; a code works once (the step it was accepted for is remembered); ten recovery codes
 * cover a lost phone and are stored only as keyed hashes. Wrong codes count toward the same per-email lock as wrong
 * passwords, and a right password alone grants nothing for a login that has this set up.
 */
public final class MfaService {

    public static final String ISSUER = "Nexvance HRMS";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RECOVERY_CODES = 10;
    private static final int CHALLENGE_ATTEMPTS = 5;

    public record Enrolment(String secret, String uri) {}

    public record Status(boolean enrolled, int recoveryCodesLeft) {}

    private enum Result { TOTP, RECOVERY, WRONG }

    private final Db db;
    private final Crypto crypto;
    private final MailOutbox outbox;
    private final RateLimits limits;
    private final Clock clock;

    public MfaService(Db db, Crypto crypto, MailOutbox outbox, RateLimits limits) {
        this(db, crypto, outbox, limits, Clock.systemUTC());
    }

    public MfaService(Db db, Crypto crypto, MailOutbox outbox, RateLimits limits, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.outbox = outbox;
        this.limits = limits;
        this.clock = clock;
    }

    private static String purpose(UUID identityId) {
        return "totp:" + identityId;
    }

    // ── setting up ──────────────────────────────────────────────────────

    /** Makes a fresh secret, kept pending until {@link #confirm}. Starting again replaces an earlier pending one. */
    public Enrolment start(SessionCtx ctx) {
        requireOwnLogin(ctx);
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());
            if (Sql.one(c, "select 1 as x from auth.identity where id = ? and mfa_enrolled_at is not null", ctx.identityId()) != null) {
                throw new ApiException(409, "mfa.already_enrolled", "Two-step sign-in is already on. Turn it off first to set it up again");
            }
            try (PreparedStatement ps = c.prepareStatement("update auth.identity set mfa_pending_secret_enc = ? where id = ?")) {
                ps.setBytes(1, crypto.seal(purpose(ctx.identityId()), secret));
                ps.setObject(2, ctx.identityId());
                ps.executeUpdate();
            }
            return null;
        });
        String text = Totp.base32(secret);
        return new Enrolment(text, Totp.otpauthUri(ISSUER, ctx.email(), text));
    }

    /** Turns it on when the code from the app is right. Returns the ten recovery codes, which are shown this once. */
    public List<String> confirm(SessionCtx ctx, String password, String code) {
        requireOwnLogin(ctx);
        String[] failure = new String[1];
        List<String> codes = db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());
            if (AuthService.lockedOut(c, ctx.email())) {
                failure[0] = "locked";
                return null;
            }
            // turning it on is protected like turning it off: a stolen session alone must not be able to lock the owner out
            Map<String, Object> identity = Sql.one(c, "select password_hash from auth.identity where id = ? for update", ctx.identityId());
            if (password == null || !Secrets.verifyPassword(password, (String) identity.get("passwordHash"))) {
                AuthService.recordFailure(c, ctx.email());
                failure[0] = "wrong";
                return null;
            }
            byte[] pending = bytes(c, "select mfa_pending_secret_enc from auth.identity where id = ? and mfa_enrolled_at is null for update", ctx.identityId());
            if (pending == null) {
                throw new ApiException(409, "mfa.not_started", "Start setting up two-step sign-in first");
            }
            byte[] secret = crypto.open(purpose(ctx.identityId()), pending);
            long step = Totp.verify(secret, code, Instant.now(clock), -1);
            if (step < 0) {
                throw new ApiException(400, "auth.invalid_code", "That code is not right. Check the code in the app and try again");
            }
            try (PreparedStatement ps = c.prepareStatement("update auth.identity set mfa_secret_enc = mfa_pending_secret_enc, "
                    + "mfa_pending_secret_enc = null, mfa_enrolled_at = now(), mfa_last_step = ? where id = ?")) {
                ps.setLong(1, step);
                ps.setObject(2, ctx.identityId());
                ps.executeUpdate();
            }
            Sql.update(c, "update auth.session set mfa_at = now() where id = ?", ctx.sessionId());
            EmailChangeService.cancelPending(c, ctx.identityId());
            AuthService.revokeAll(c, ctx.identityId(), ctx.sessionId());   // sessions from before it was on never proved a second factor
            List<String> fresh = newRecoveryCodes(c, ctx.identityId());
            notice(c, ctx.email(), "Two-step sign-in was turned on", "Two-step sign-in was just turned on for " + ctx.email()
                    + ". From now on signing in asks for a code from your authenticator app.\n\n"
                    + "If this was not you, reset your password and tell your administrator.\n");
            return fresh;
        });
        if (failure[0] != null) {
            refuseUnless(failure[0]);
        }
        return codes;
    }

    public Status status(SessionCtx ctx) {
        requireOwnLogin(ctx);
        return db.inAuth(c -> {
            boolean on = Sql.one(c, "select 1 as x from auth.identity where id = ? and mfa_enrolled_at is not null", ctx.identityId()) != null;
            int left = on ? ((Number) Sql.one(c, "select count(*) as n from auth.mfa_recovery_code where identity_id = ? and used_at is null",
                    ctx.identityId()).get("n")).intValue() : 0;
            return new Status(on, left);
        });
    }

    /** Ten new recovery codes replace the old ones; needs a current code. */
    public List<String> regenerate(SessionCtx ctx, String code) {
        requireOwnLogin(ctx);
        Object[] out = new Object[1];
        String outcome = db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());
            if (AuthService.lockedOut(c, ctx.email())) {
                return "locked";
            }
            if (!enrolled(c, ctx.identityId())) {
                return "off";
            }
            if (check(c, ctx.identityId(), code) == Result.WRONG) {
                AuthService.recordFailure(c, ctx.email());
                return "wrong";
            }
            out[0] = newRecoveryCodes(c, ctx.identityId());
            return "ok";
        });
        refuseUnless(outcome);
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) out[0];
        return codes;
    }

    /** Turns it off; needs the password and a current code, and ends every other session. */
    public void disable(SessionCtx ctx, String password, String code) {
        requireOwnLogin(ctx);
        String outcome = db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());
            if (AuthService.lockedOut(c, ctx.email())) {
                return "locked";
            }
            if (!enrolled(c, ctx.identityId())) {
                return "off";
            }
            Map<String, Object> identity = Sql.one(c, "select password_hash from auth.identity where id = ? for update", ctx.identityId());
            boolean passwordOk = password != null && Secrets.verifyPassword(password, (String) identity.get("passwordHash"));
            // the code is only looked at once the password is right: a wrong password must not use up a right code
            boolean codeOk = passwordOk && check(c, ctx.identityId(), code) != Result.WRONG;
            if (!passwordOk || !codeOk) {
                AuthService.recordFailure(c, ctx.email());
                return "wrong";
            }
            Sql.update(c, "update auth.identity set mfa_secret_enc = null, mfa_pending_secret_enc = null, mfa_enrolled_at = null, "
                    + "mfa_last_step = null where id = ?", ctx.identityId());
            Sql.update(c, "update auth.mfa_recovery_code set used_at = coalesce(used_at, now()) where identity_id = ?", ctx.identityId());
            endChallenges(c, ctx.identityId());
            EmailChangeService.cancelPending(c, ctx.identityId());
            AuthService.revokeAll(c, ctx.identityId(), ctx.sessionId());
            AuthService.clearFailures(c, ctx.email());
            notice(c, ctx.email(), "Two-step sign-in was turned off", "Two-step sign-in was just turned off for " + ctx.email()
                    + ", and you were signed out everywhere else.\n\nIf this was not you, reset your password and tell your administrator.\n");
            return "ok";
        });
        refuseUnless(outcome);
    }

    /** Shows a fresh code on this session, so the actions that ask for one in the last {@link StepUp#MINUTES} minutes go ahead. */
    public void stepUp(SessionCtx ctx, String code) {
        requireOwnLogin(ctx);
        String outcome = db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());
            if (AuthService.lockedOut(c, ctx.email())) {
                return "locked";
            }
            if (!enrolled(c, ctx.identityId())) {
                return "off";
            }
            if (check(c, ctx.identityId(), code) == Result.WRONG) {
                AuthService.recordFailure(c, ctx.email());   // guessing here counts like guessing at sign-in
                return "wrong";
            }
            AuthService.clearFailures(c, ctx.email());
            if (Sql.update(c, "update auth.session set step_up_at = now(), mfa_at = coalesce(mfa_at, now()) "
                    + "where id = ? and revoked_at is null", ctx.sessionId()) != 1) {
                throw ApiException.notSignedIn();
            }
            return "ok";
        });
        refuseUnless(outcome);
    }

    // ── signing in ──────────────────────────────────────────────────────

    /** Answers a challenge from {@link AuthService#signIn} with a code (or a recovery code); returns the session token. */
    public String complete(String challenge, String code, String address) {
        if (address != null && !limits.allowed(RateLimits.MFA_FAILURES_PER_ADDRESS, address)) {
            throw new ApiException(429, "request.rate_limited", "Too many attempts. Try again in a few minutes");
        }
        String token = Secrets.newToken();
        String outcome = db.inAuth(c -> {
            byte[] hash = Secrets.sha256(challenge == null ? "" : challenge);
            Map<String, Object> peek = Sql.one(c, "select i.email from auth.mfa_challenge m join auth.identity i on i.id = m.identity_id "
                    + "where m.token_hash = ?", hash);
            if (peek == null) {
                return "gone";
            }
            String email = (String) peek.get("email");
            AuthService.lockEmail(c, email);   // before the challenge's row, as every other sign-in path takes them
            Map<String, Object> row = Sql.one(c, "select m.identity_id, m.attempts, i.status from auth.mfa_challenge m "
                    + "join auth.identity i on i.id = m.identity_id where m.token_hash = ? and m.used_at is null and m.expires_at > now() "
                    + "for update of m", hash);
            if (row == null) {
                return "gone";
            }
            if (AuthService.lockedOut(c, email)) {
                return "locked";
            }
            UUID identityId = AuthService.uuid(row.get("identityId"));
            Result result = "active".equals(row.get("status")) ? check(c, identityId, code) : Result.WRONG;
            if (result == Result.WRONG) {
                AuthService.recordFailure(c, email);
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                Sql.update(c, "update auth.mfa_challenge set attempts = ?, used_at = case when ? >= ? then now() else used_at end where token_hash = ?",
                        attempts, attempts, CHALLENGE_ATTEMPTS, hash);
                return "wrong";
            }
            Sql.update(c, "update auth.mfa_challenge set used_at = now() where token_hash = ?", hash);
            AuthService.clearFailures(c, email);
            Sql.update(c, "update auth.identity set last_login_at = now() where id = ?", identityId);
            AuthService.insertSession(c, token, identityId, "identity", null, null, null, "30 minutes");
            Sql.update(c, "update auth.session set mfa_at = now() where refresh_token_hash = ?", Secrets.sha256(token));
            if (result == Result.RECOVERY) {
                notice(c, email, "A recovery code was used", "A recovery code was just used to sign in to " + email + ". "
                        + "Each recovery code works once. If this was not you, reset your password and tell your administrator.\n");
            }
            return "ok";
        });
        switch (outcome) {
            case "ok" -> { return token; }
            case "locked" -> throw AuthService.tooManyFailures();
            case "gone" -> throw new ApiException(401, "auth.challenge_expired", "That sign-in has expired. Sign in again");
            default -> {
                if (address != null) {
                    limits.hit(RateLimits.MFA_FAILURES_PER_ADDRESS, address);
                }
                throw new ApiException(401, "auth.invalid_code", "That code is not right");
            }
        }
    }

    // ── shared ──────────────────────────────────────────────────────────

    /** Challenges still open when the password or the second step changes are no longer worth anything. */
    static void endChallenges(Connection c, UUID identityId) throws SQLException {
        Sql.update(c, "update auth.mfa_challenge set used_at = now() where identity_id = ? and used_at is null", identityId);
    }

    private static void requireOwnLogin(SessionCtx ctx) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot change two-step sign-in");
        }
    }

    private static void refuseUnless(String outcome) {
        switch (outcome) {
            case "ok" -> { }
            case "locked" -> throw AuthService.tooManyFailures();
            case "off" -> throw new ApiException(409, "mfa.not_enrolled", "Two-step sign-in is not on");
            default -> throw new ApiException(403, "auth.invalid_code", "The password or the code is not right");
        }
    }

    private static boolean enrolled(Connection c, UUID identityId) throws SQLException {
        return Sql.one(c, "select 1 as x from auth.identity where id = ? and mfa_enrolled_at is not null", identityId) != null;
    }

    /** Checks a code against the identity's secret, then its recovery codes. A code that is accepted is used up. */
    private Result check(Connection c, UUID identityId, String submitted) throws SQLException {
        String clean = submitted == null ? "" : submitted.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        byte[] sealed = bytes(c, "select mfa_secret_enc from auth.identity where id = ? and mfa_enrolled_at is not null", identityId);
        if (sealed == null) {
            return Result.WRONG;
        }
        if (clean.matches("\\d{6}")) {
            Number last = (Number) Sql.one(c, "select mfa_last_step as n from auth.identity where id = ?", identityId).get("n");
            long step = Totp.verify(crypto.open(purpose(identityId), sealed), clean, Instant.now(clock), last == null ? -1 : last.longValue());
            if (step >= 0 && Sql.update(c, "update auth.identity set mfa_last_step = ? where id = ? and (mfa_last_step is null or mfa_last_step < ?)",
                    step, identityId, step) == 1) {
                return Result.TOTP;
            }
            return Result.WRONG;
        }
        if (clean.matches("[A-Z2-7]{10}")
                && Sql.update(c, "update auth.mfa_recovery_code set used_at = now() where identity_id = ? and code_hash = ? and used_at is null",
                        identityId, recoveryHash(identityId, clean)) == 1) {
            return Result.RECOVERY;
        }
        return Result.WRONG;
    }

    private byte[] recoveryHash(UUID identityId, String code) {
        return crypto.keyedHash("recovery:" + identityId + ":" + code);
    }

    /** Retires every unused recovery code and makes ten new ones, returned as XXXXX-XXXXX. */
    private List<String> newRecoveryCodes(Connection c, UUID identityId) throws SQLException {
        Sql.update(c, "update auth.mfa_recovery_code set used_at = now() where identity_id = ? and used_at is null", identityId);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] random = new byte[7];
            RANDOM.nextBytes(random);
            String code = Totp.base32(random).substring(0, 10);
            Sql.update(c, "insert into auth.mfa_recovery_code (identity_id, code_hash) values (?, ?)", identityId, recoveryHash(identityId, code));
            shown.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return shown;
    }

    private void notice(Connection c, String email, String subject, String body) throws SQLException {
        outbox.enqueue(c, null, email, "two-step", subject, body.replace("\n", "\n"), "two-step:" + UUID.randomUUID());
    }

    /** Reads one bytea value (Sql never returns them, by design). */
    private static byte[] bytes(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }
}
