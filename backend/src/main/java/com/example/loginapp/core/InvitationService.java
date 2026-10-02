package com.example.loginapp.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The invitation link: what it offers, and accepting it. */
public final class InvitationService {

    private static final ApiException INVALID = new ApiException(410, "invitation.invalid",
            "This invitation link is not valid. It may have expired, been used or been withdrawn");

    private final Db db;

    public InvitationService(Db db) {
        this.db = db;
    }

    /** What the invitation offers. One generic error covers expired, used, revoked and unknown tokens. */
    public Map<String, Object> preview(String token) {
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
        Map<String, Object> offer = preview(token);
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
                Map<String, Object> identity = Sql.one(c,
                        "select id, password_hash from auth.identity where email = ?::citext and status = 'active'", email);
                if (identity == null) {
                    throw INVALID;
                }
                if (!alreadySignedIn && !Secrets.verifyPassword(password, (String) identity.get("passwordHash"))) {
                    throw new ApiException(403, "invitation.email_mismatch",
                            "Enter the password for " + email + " to accept this invitation");
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
        UUID tenantId = (UUID) joined.get("tenantId");
        UUID membershipId = (UUID) joined.get("membershipId");
        db.inTenant(tenantId, c -> Sql.update(c,
                "insert into app.audit_event (tenant_id, actor_kind, actor_membership_id, actor_label, action, target_type, target_id) "
                        + "values (?, 'member', ?, ?, 'invitation.accepted', 'membership', ?)",
                tenantId, membershipId, email, membershipId));
        return sessionToken;
    }
}
