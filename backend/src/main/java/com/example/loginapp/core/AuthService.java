package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.loginapp.core.SessionCtx.Perm;

/** Sign-in, sessions and choosing an Organization. */
public final class AuthService {

    private static final int MAX_FAILED_LOGINS = 5;

    private final Db db;

    public AuthService(Db db) {
        this.db = db;
    }

    /** Checks the email and password and returns a token for a session with no Organization chosen yet. */
    public String login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throw new ApiException(400, "request.invalid", "Enter your email and password");
        }
        String token = Secrets.newToken();
        String outcome = db.inAuth(c -> {
            Map<String, Object> identity = Sql.one(c,
                    "select id, password_hash, status, (locked_until is not null and locked_until > now()) as locked "
                            + "from auth.identity where email = ?::citext for update", email.trim());
            if (identity == null) {
                Secrets.verifyPassword(password, "pbkdf2-sha256$210000$AAAAAAAAAAAAAAAAAAAAAA==$AAAA");   // same work as a real check
                return "invalid";
            }
            if (Boolean.TRUE.equals(identity.get("locked"))) {
                return "locked";
            }
            UUID id = UUID.fromString((String) identity.get("id"));
            boolean ok = "active".equals(identity.get("status"))
                    && Secrets.verifyPassword(password, (String) identity.get("passwordHash"));
            if (!ok) {
                Sql.update(c, "update auth.identity set failed_login_count = failed_login_count + 1 where id = ?", id);
                Sql.update(c, "update auth.identity set locked_until = now() + interval '15 minutes', failed_login_count = 0 "
                        + "where id = ? and failed_login_count >= ?", id, MAX_FAILED_LOGINS);
                return "invalid";
            }
            Sql.update(c, "update auth.identity set failed_login_count = 0, locked_until = null, last_login_at = now() where id = ?", id);
            insertSession(c, token, id, "identity", null, null, null, "30 minutes");
            return "ok";
        });
        if (outcome.equals("locked")) {
            throw new ApiException(423, "auth.locked", "Too many failed sign-ins. Try again in 15 minutes");
        }
        if (!outcome.equals("ok")) {
            throw new ApiException(401, "auth.invalid_credentials", "Incorrect email or password");
        }
        return token;
    }

    /** Loads the session for a token, with its current permissions. Throws when it is missing, expired or revoked. */
    public SessionCtx resolve(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.notSignedIn();
        }
        Map<String, Object> s = db.inAuth(c -> {
            Map<String, Object> row = Sql.one(c,
                    "select s.id, s.identity_id, s.kind, s.tenant_id, s.membership_id, s.impersonation_grant_id, i.email, "
                            + "t.name as tenant_name, t.status as tenant_status, "
                            + "g.mode as grant_mode, g.expires_at as grant_expires_at, "
                            + "(g.status = 'approved' and g.revoked_at is null and g.expires_at > now()) as grant_live, "
                            + "p.id as platform_user_id, p.role as platform_role, p.status as platform_status "
                            + "from auth.session s join auth.identity i on i.id = s.identity_id "
                            + "left join platform.tenant t on t.id = s.tenant_id "
                            + "left join platform.impersonation_grant g on g.id = s.impersonation_grant_id "
                            + "left join platform.platform_user p on p.identity_id = s.identity_id "
                            + "where s.refresh_token_hash = ? and s.revoked_at is null and s.expires_at > now() "
                            + "and i.status = 'active' "
                            + "and s.last_seen_at > now() - case s.kind when 'platform' then interval '15 minutes' "
                            + "when 'identity' then interval '30 minutes' else interval '60 minutes' end",
                    Secrets.sha256(token));
            if (row != null) {
                Sql.update(c, "update auth.session set last_seen_at = now() where id = ?::uuid", row.get("id"));
            }
            return row;
        });
        if (s == null) {
            throw ApiException.notSignedIn();
        }
        String kind = (String) s.get("kind");
        UUID tenantId = uuid(s.get("tenantId"));
        UUID membershipId = uuid(s.get("membershipId"));
        List<Perm> permissions = List.of();

        if (kind.equals("platform") && !"active".equals(s.get("platformStatus"))) {
            throw ApiException.notSignedIn();
        }
        if (kind.equals("tenant")) {
            if (!"active".equals(s.get("tenantStatus"))) {
                throw new ApiException(403, "tenant.unavailable", "This Organization is suspended or closed");
            }
            permissions = db.inTenant(tenantId, c -> permissions(c,
                    "select permission_code, scope from app.effective_permissions(?)", membershipId));
            if (permissions.isEmpty()) {
                throw ApiException.notSignedIn();   // suspended, or ended with no roles left
            }
        }
        if (kind.equals("support")) {
            if (!Boolean.TRUE.equals(s.get("grantLive")) || "closed".equals(s.get("tenantStatus"))) {
                throw new ApiException(401, "support.expired", "The support access grant has expired or was revoked");
            }
            String role = "write".equals(s.get("grantMode")) ? "support_write" : "support_read";
            permissions = db.inTenant(tenantId, c -> permissions(c,
                    "select rp.permission_code, rp.scope from app.role r "
                            + "join app.role_permission rp on rp.role_id = r.id where r.code = ? and r.tenant_id is null", role));
        }
        return new SessionCtx(
                uuid(s.get("id")), uuid(s.get("identityId")), (String) s.get("email"), kind,
                tenantId, (String) s.get("tenantName"), membershipId,
                uuid(s.get("impersonationGrantId")), (String) s.get("grantMode"), (String) s.get("grantExpiresAt"),
                kind.equals("platform") ? uuid(s.get("platformUserId")) : null,
                kind.equals("platform") ? (String) s.get("platformRole") : null,
                permissions);
    }

    /** What the signed-in person sees about themselves, and what they can enter. */
    public Map<String, Object> me(SessionCtx ctx) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("email", ctx.email());
        me.put("kind", ctx.kind());
        me.put("organization", ctx.inOrganization() ? Map.of("id", ctx.tenantId().toString(), "name", ctx.tenantName()) : null);
        me.put("membershipId", ctx.membershipId() == null ? null : ctx.membershipId().toString());
        me.put("platformRole", ctx.platformRole());
        me.put("permissions", ctx.permissions().stream().map(Perm::code).distinct().sorted().toList());
        if (ctx.isSupport()) {
            me.put("support", Map.of("mode", ctx.grantMode(), "expiresAt", ctx.grantExpiresAt()));
        }
        me.putAll(destinations(ctx));
        return me;
    }

    /** The Organizations this login can enter, and whether it can open the console. */
    public Map<String, Object> destinations(SessionCtx ctx) {
        return db.inAuth(c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("organizations", Sql.query(c,
                    "select tenant_id, tenant_name, tenant_slug, membership_id, status "
                            + "from auth.memberships_for_identity(?) order by tenant_name", ctx.identityId()));
            Map<String, Object> platform = Sql.one(c,
                    "select role from platform.platform_user where identity_id = ? and status = 'active'", ctx.identityId());
            out.put("console", platform == null ? null : platform.get("role"));
            return out;
        });
    }

    /** Starts a session inside one Organization and ends the current one. Switching always issues a new token. */
    public String enterOrganization(SessionCtx ctx, UUID tenantId) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "End the support session first");
        }
        String token = Secrets.newToken();
        db.inAuth(c -> {
            Map<String, Object> m = Sql.one(c,
                    "select membership_id from auth.memberships_for_identity(?) where tenant_id = ?",
                    ctx.identityId(), tenantId);
            if (m == null) {
                throw new ApiException(403, "tenant.unavailable", "You do not have access to that Organization");
            }
            insertSession(c, token, ctx.identityId(), "tenant", tenantId, uuid(m.get("membershipId")), null, "12 hours");
            revoke(c, ctx.sessionId());
            return null;
        });
        return token;
    }

    /** Starts a console session, which carries no Organization at all. */
    public String enterConsole(SessionCtx ctx) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "End the support session first");
        }
        String token = Secrets.newToken();
        db.inAuth(c -> {
            if (Sql.one(c, "select 1 from platform.platform_user where identity_id = ? and status = 'active'",
                    ctx.identityId()) == null) {
                throw new ApiException(403, "permission.denied", "This login has no platform role");
            }
            insertSession(c, token, ctx.identityId(), "platform", null, null, null, "8 hours");
            revoke(c, ctx.sessionId());
            return null;
        });
        return token;
    }

    public void logout(SessionCtx ctx) {
        db.inAuth(c -> {
            revoke(c, ctx.sessionId());
            return null;
        });
    }

    // ── shared with the other services ──────────────────────────────────

    static void insertSession(Connection c, String token, UUID identityId, String kind, UUID tenantId,
                              UUID membershipId, UUID grantId, String lifetime) throws SQLException {
        Sql.update(c, "insert into auth.session (identity_id, kind, tenant_id, membership_id, impersonation_grant_id, "
                        + "refresh_token_hash, expires_at) values (?, ?, ?, ?, ?, ?, now() + ?::interval)",
                identityId, kind, tenantId, membershipId, grantId, Secrets.sha256(token), lifetime);
    }

    static void revoke(Connection c, UUID sessionId) throws SQLException {
        Sql.update(c, "update auth.session set revoked_at = now() where id = ? and revoked_at is null", sessionId);
    }

    static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static List<Perm> permissions(Connection c, String sql, Object param) throws SQLException {
        List<Perm> out = new ArrayList<>();
        for (Map<String, Object> row : Sql.query(c, sql, param)) {
            out.add(new Perm((String) row.get("permissionCode"), (String) row.get("scope")));
        }
        return out;
    }
}
