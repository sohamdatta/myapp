package com.example.loginapp.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The invitation link: what it offers, and accepting it. */
public final class InvitationService {

    private static final ApiException INVALID = new ApiException(410, "invitation.invalid",
            "This invitation link is not valid. It may have expired, been used or been withdrawn");

    private static ApiException tooMany() {
        return new ApiException(429, "request.rate_limited", "Too many attempts. Try again in a few minutes");
    }

    private final Db db;
    private final RateLimits limits;

    public InvitationService(Db db) {
        this(db, new RateLimits());
    }

    public InvitationService(Db db, RateLimits limits) {
        this.db = db;
        this.limits = limits;
    }

    public Map<String, Object> preview(String token) {
        return lookup(token);
    }

    /** Counts a look at an invitation link against the address asking; refuses when it looks too often (a null address is not limited). */
    public void checkPreviewAllowed(String address) {
        if (address != null && !limits.tryHit(RateLimits.INVITE_PREVIEWS_PER_ADDRESS, address)) {
            throw tooMany();
        }
    }

    /** What the invitation offers. One generic error covers expired, used, revoked and unknown tokens. */
    private Map<String, Object> lookup(String token) {
        if (token == null || token.isBlank()) {
            throw INVALID;
        }
        Map<String, Object> row = db.inAuth(c -> Sql.one(c,
                "select tenant_name, email, role_names, identity_exists, expires_at from auth.invitation_preview(?)",
                Secrets.sha256(token)));
        if (row == null) {
            throw INVALID;
        }
        return row;
    }

    /**
     * Accepts an invitation and returns a session token inside the Organization.
     * A new email sets its password here. An existing login proves itself with
     * its password, or by already being signed in with the invited email.
     */
    public String accept(String token, String password, SessionCtx signedIn) {
        return accept(token, password, signedIn, null);   // no address: callers inside the process, which are not limited by one
    }

    /**
     * As {@link #accept(String, String, SessionCtx)}, limited by the address. Proving an existing login by its password
     * here counts like signing in: wrong passwords add to the same per-email count and lock the account the same way,
     * so an invitation link is no way round the sign-in limits.
     */
    public String accept(String token, String password, SessionCtx signedIn, String address) {
        if (address != null && !limits.tryHit(RateLimits.INVITE_ATTEMPTS_PER_ADDRESS, address)) {
            throw tooMany();
        }
        Map<String, Object> offer = lookup(token);
        String email = (String) offer.get("email");
        boolean exists = Boolean.TRUE.equals(offer.get("identityExists"));
        boolean alreadySignedIn = signedIn != null && signedIn.email().equalsIgnoreCase(email);
        if (!exists) {
            Secrets.checkPasswordPolicy(password);
        }
        String sessionToken = Secrets.newToken();
        Map<String, Object> joined = db.inAuth(c -> {
            UUID identityId;
            if (exists) {
                AuthService.lockEmail(c, email);
                Map<String, Object> identity = Sql.one(c,
                        "select id, password_hash from auth.identity where email = ?::citext and status = 'active' for update", email);
                if (identity == null) {
                    throw INVALID;
                }
                if (!alreadySignedIn) {
                    if (AuthService.lockedOut(c, email)) {
                        return Map.<String, Object>of("refused", "locked");
                    }
                    if (!Secrets.verifyPassword(password, (String) identity.get("passwordHash"))) {
                        // recorded here and refused below: throwing now would roll the failure back
                        AuthService.recordFailure(c, email);
                        return Map.<String, Object>of("refused", "password");
                    }
                    AuthService.clearFailures(c, email);
                }
                identityId = AuthService.uuid(identity.get("id"));
            } else {
                identityId = AuthService.uuid(Sql.one(c,
                        "insert into auth.identity (email, password_hash) values (?::citext, ?) returning id",
                        email, Secrets.hashPassword(password)).get("id"));
            }
            Map<String, Object> result = Sql.one(c,
                    "select tenant_id, membership_id from auth.accept_invitation(?, ?)", Secrets.sha256(token), identityId);
            UUID tenantId = AuthService.uuid(result.get("tenantId"));
            UUID membershipId = AuthService.uuid(result.get("membershipId"));
            Sql.update(c, "update auth.identity set last_login_at = now() where id = ?", identityId);
            AuthService.insertSession(c, sessionToken, identityId, "tenant", tenantId, membershipId, null, "12 hours");
            if (signedIn != null && alreadySignedIn && !signedIn.isSupport()) {
                AuthService.revoke(c, signedIn.sessionId());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenantId", tenantId);
            out.put("membershipId", membershipId);
            return out;
        });
        if ("locked".equals(joined.get("refused"))) {
            throw AuthService.tooManyFailures();
        }
        if ("password".equals(joined.get("refused"))) {
            if (address != null) {
                limits.hit(RateLimits.LOGIN_FAILURES_PER_ADDRESS, address);
            }
            throw new ApiException(403, "invitation.email_mismatch", "Enter the password for " + email + " to accept this invitation");
        }
        UUID tenantId = (UUID) joined.get("tenantId");
        UUID membershipId = (UUID) joined.get("membershipId");
        db.inTenant(tenantId, c -> Sql.update(c,
                "insert into app.audit_event (tenant_id, actor_kind, actor_membership_id, actor_label, action, target_type, target_id) "
                        + "values (?, 'member', ?, ?, 'invitation.accepted', 'membership', ?)",
                tenantId, membershipId, email, membershipId));
        return sessionToken;
    }
}
