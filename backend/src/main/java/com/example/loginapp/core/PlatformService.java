package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The platform console: Organizations, platform users and support access.
 * Runs on the platform connection, which cannot read Organization tables.
 */
public final class PlatformService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Db db;

    public PlatformService(Db db) {
        this.db = db;
    }

    // ── Organizations ───────────────────────────────────────────────────

    public List<Map<String, Object>> organizations(SessionCtx ctx) {
        ctx.requirePlatform("super_admin", "support", "billing");
        return db.inPlatform(c -> Sql.query(c,
                "select t.id, t.name, t.slug, t.status, t.plan_code, t.created_at, "
                        + "s.active_members, s.pending_invitations, s.owners "
                        + "from platform.tenant t cross join lateral platform.tenant_stats(t.id) s "
                        + "order by t.created_at desc"));
    }

    /** Creates an Organization and invites its first Org Admin. Returns the Organization and the invitation token. */
    public Map<String, Object> createOrganization(SessionCtx ctx, String name, String slug, String planCode, String adminEmail) {
        ctx.requirePlatform("super_admin");
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("Enter the Organization's name");
        }
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw ApiException.badRequest("The slug must be 3 to 40 lowercase letters, digits or hyphens");
        }
        requireEmail(adminEmail);
        String token = Secrets.newToken();
        return db.inPlatform(c -> {
            Map<String, Object> tenant = Sql.one(c,
                    "insert into platform.tenant (name, slug, plan_code, created_by) values (?, ?, ?, ?) "
                            + "returning id, name, slug, status, plan_code, created_at",
                    name.trim(), slug, planCode == null || planCode.isBlank() ? "essential" : planCode.trim(),
                    ctx.platformUserId());
            UUID tenantId = AuthService.uuid(tenant.get("id"));
            Sql.query(c, "select platform.invite_first_owner(?, ?::citext, ?)", tenantId, adminEmail.trim(), Secrets.sha256(token));
            audit(c, ctx, "tenant.created", "tenant", tenantId, Map.of("name", name.trim(), "slug", slug));
            audit(c, ctx, "tenant.first_owner_invited", "tenant", tenantId, Map.of("email", adminEmail.trim()));
            tenant.put("invitationEmail", adminEmail.trim());
            tenant.put("invitationToken", token);
            return tenant;
        });
    }

    /** Sends a fresh first invitation. Refused by the database once the Organization has any member. */
    public Map<String, Object> inviteFirstAdmin(SessionCtx ctx, UUID tenantId, String adminEmail) {
        ctx.requirePlatform("super_admin");
        requireEmail(adminEmail);
        String token = Secrets.newToken();
        return db.inPlatform(c -> {
            Sql.query(c, "select platform.invite_first_owner(?, ?::citext, ?)", tenantId, adminEmail.trim(), Secrets.sha256(token));
            audit(c, ctx, "tenant.first_owner_invited", "tenant", tenantId, Map.of("email", adminEmail.trim()));
            return Map.of("invitationEmail", adminEmail.trim(), "invitationToken", token);
        });
    }

    /** suspend, reactivate or close. Suspending and closing end every session in the Organization. */
    public void changeOrganizationStatus(SessionCtx ctx, UUID tenantId, String action) {
        ctx.requirePlatform("super_admin");
        String sql = switch (action) {
            case "suspend" -> "update platform.tenant set status = 'suspended', suspended_at = now() where id = ? and status = 'active'";
            case "reactivate" -> "update platform.tenant set status = 'active', suspended_at = null where id = ? and status = 'suspended'";
            case "close" -> "update platform.tenant set status = 'closed', closed_at = now() where id = ? and status <> 'closed'";
            default -> throw ApiException.badRequest("Unknown action: " + action);
        };
        db.inPlatform(c -> {
            if (Sql.update(c, sql, tenantId) == 0) {
                throw new ApiException(409, "request.refused", "The Organization is not in a state that allows this");
            }
            audit(c, ctx, "tenant." + switch (action) {
                case "suspend" -> "suspended";
                case "reactivate" -> "reactivated";
                default -> "closed";
            }, "tenant", tenantId, Map.of());
            return null;
        });
        if (!action.equals("reactivate")) {
            db.inAuth(c -> Sql.update(c, "update auth.session set revoked_at = now() "
                    + "where tenant_id = ? and kind = 'tenant' and revoked_at is null", tenantId));
        }
    }

    // ── Platform users ──────────────────────────────────────────────────

    public List<Map<String, Object>> platformUsers(SessionCtx ctx) {
        ctx.requirePlatform("super_admin");
        return db.inPlatform(c -> Sql.query(c,
                "select p.id, i.email, p.role, p.status, p.created_at "
                        + "from platform.platform_user p join auth.identity i on i.id = p.identity_id order by i.email"));
    }

    /** Adds a platform user. A new email gets the given password; an existing login keeps its own. */
    public void addPlatformUser(SessionCtx ctx, String email, String role, String password) {
        ctx.requirePlatform("super_admin");
        requireEmail(email);
        if (role == null || !List.of("super_admin", "support", "billing").contains(role)) {
            throw ApiException.badRequest("The role must be super_admin, support or billing");
        }
        UUID identityId = ensureIdentity(email.trim(), password);
        db.inPlatform(c -> {
            if (Sql.one(c, "select 1 from platform.platform_user where identity_id = ?", identityId) != null) {
                throw new ApiException(409, "request.refused", "That email is already a platform user");
            }
            Map<String, Object> row = Sql.one(c,
                    "insert into platform.platform_user (identity_id, role, created_by) values (?, ?, ?) returning id",
                    identityId, role, ctx.platformUserId());
            audit(c, ctx, "platform_user.added", "platform_user", AuthService.uuid(row.get("id")),
                    Map.of("email", email.trim(), "role", role));
            return null;
        });
    }

    public void setPlatformUserStatus(SessionCtx ctx, UUID platformUserId, boolean active) {
        ctx.requirePlatform("super_admin");
        if (!active && platformUserId.equals(ctx.platformUserId())) {
            throw new ApiException(409, "request.refused", "You cannot suspend yourself");
        }
        db.inPlatform(c -> {
            if (Sql.update(c, "update platform.platform_user set status = ? where id = ?",
                    active ? "active" : "suspended", platformUserId) == 0) {
                throw ApiException.notFound("The platform user");
            }
            if (Sql.one(c, "select 1 from platform.platform_user where role = 'super_admin' and status = 'active'") == null) {
                throw new ApiException(409, "request.refused", "The platform must keep at least one active Super Admin");
            }
            audit(c, ctx, active ? "platform_user.reactivated" : "platform_user.suspended", "platform_user", platformUserId, Map.of());
            return null;
        });
    }

    // ── Support access ──────────────────────────────────────────────────

    public List<Map<String, Object>> grants(SessionCtx ctx) {
        ctx.requirePlatform("super_admin", "support");
        return db.inPlatform(c -> Sql.query(c,
                "select g.id, g.tenant_id, t.name as tenant_name, g.mode, g.status, g.reason, g.auto_approved, "
                        + "g.requested_at, g.granted_at, g.expires_at, g.revoked_at, "
                        + "(g.status = 'approved' and g.revoked_at is null and g.expires_at > now()) as live, "
                        + "(g.platform_user_id = ?) as mine, ri.email as requested_by, ai.email as approved_by "
                        + "from platform.impersonation_grant g join platform.tenant t on t.id = g.tenant_id "
                        + "join platform.platform_user rp on rp.id = g.platform_user_id "
                        + "join auth.identity ri on ri.id = rp.identity_id "
                        + "left join platform.platform_user ap on ap.id = g.approved_by "
                        + "left join auth.identity ai on ai.id = ap.identity_id "
                        + "order by g.requested_at desc limit 100", ctx.platformUserId()));
    }

    /**
     * Requests access to one Organization. A second Super Admin must approve it;
     * when no other active Super Admin exists it is approved automatically and flagged.
     */
    public Map<String, Object> requestGrant(SessionCtx ctx, UUID tenantId, String mode, String reason) {
        ctx.requirePlatform("super_admin", "support");
        if (reason == null || reason.trim().length() < 10) {
            throw ApiException.badRequest("Give a reason of at least 10 characters");
        }
        String grantMode = "write".equals(mode) ? "write" : "read_only";
        return db.inPlatform(c -> {
            if (Sql.one(c, "select 1 from platform.tenant where id = ? and status <> 'closed'", tenantId) == null) {
                throw ApiException.notFound("The Organization");
            }
            boolean otherApprover = Sql.one(c, "select 1 from platform.platform_user "
                    + "where role = 'super_admin' and status = 'active' and id <> ?", ctx.platformUserId()) != null;
            boolean auto = !otherApprover && "super_admin".equals(ctx.platformRole());
            Map<String, Object> grant = auto
                    ? Sql.one(c, "insert into platform.impersonation_grant (platform_user_id, tenant_id, reason, mode, status, "
                            + "approved_by, auto_approved, granted_at, expires_at) "
                            + "values (?, ?, ?, ?, 'approved', ?, true, now(), now() + interval '60 minutes') returning id, status",
                    ctx.platformUserId(), tenantId, reason.trim(), grantMode, ctx.platformUserId())
                    : Sql.one(c, "insert into platform.impersonation_grant (platform_user_id, tenant_id, reason, mode) "
                            + "values (?, ?, ?, ?) returning id, status",
                    ctx.platformUserId(), tenantId, reason.trim(), grantMode);
            UUID grantId = AuthService.uuid(grant.get("id"));
            audit(c, ctx, "grant.requested", "grant", grantId, Map.of("mode", grantMode, "reason", reason.trim()));
            if (auto) {
                audit(c, ctx, "grant.auto_approved", "grant", grantId, Map.of());
            }
            return grant;
        });
    }

    /** approve, reject or revoke. The requester cannot approve their own request. */
    public void decideGrant(SessionCtx ctx, UUID grantId, String action) {
        ctx.requirePlatform("super_admin");
        String sql = switch (action) {
            case "approve" -> "update platform.impersonation_grant set status = 'approved', approved_by = ?, "
                    + "granted_at = now(), expires_at = now() + interval '60 minutes' "
                    + "where id = ? and status = 'requested' and platform_user_id <> ?";
            case "reject" -> "update platform.impersonation_grant set status = 'rejected', approved_by = ? "
                    + "where id = ? and status = 'requested' and platform_user_id <> ?";
            case "revoke" -> "update platform.impersonation_grant set status = 'revoked', revoked_at = now() "
                    + "where (?::uuid is not null) and id = ? and status = 'approved' and (?::uuid is not null)";
            default -> throw ApiException.badRequest("Unknown action: " + action);
        };
        db.inPlatform(c -> {
            if (Sql.update(c, sql, ctx.platformUserId(), grantId, ctx.platformUserId()) == 0) {
                throw new ApiException(409, "request.refused", action.equals("revoke")
                        ? "Only an approved grant can be revoked"
                        : "Only a pending request made by someone else can be decided");
            }
            audit(c, ctx, "grant." + switch (action) {
                case "approve" -> "approved";
                case "reject" -> "rejected";
                default -> "revoked";
            }, "grant", grantId, Map.of());
            return null;
        });
    }

    /**
     * Starts a support session under an approved grant and returns its token.
     * The session is an ordinary Organization-scoped one, and its start is
     * written to that Organization's own audit log.
     */
    public String startSupportSession(SessionCtx ctx, UUID grantId) {
        ctx.requirePlatform("super_admin", "support");
        Map<String, Object> grant = db.inPlatform(c -> {
            Map<String, Object> row = Sql.one(c,
                    "select g.id, g.tenant_id, g.mode, g.reason, g.expires_at from platform.impersonation_grant g "
                            + "join platform.tenant t on t.id = g.tenant_id "
                            + "where g.id = ? and g.platform_user_id = ? and g.status = 'approved' "
                            + "and g.revoked_at is null and g.expires_at > now() and t.status <> 'closed'",
                    grantId, ctx.platformUserId());
            if (row == null) {
                throw new ApiException(409, "support.expired", "This grant is not yours, or is no longer active");
            }
            audit(c, ctx, "support.session_started", "grant", grantId, Map.of());
            return row;
        });
        UUID tenantId = AuthService.uuid(grant.get("tenantId"));
        String token = Secrets.newToken();
        db.inAuth(c -> Sql.update(c,
                "insert into auth.session (identity_id, kind, tenant_id, impersonation_grant_id, refresh_token_hash, expires_at) "
                        + "values (?, 'support', ?, ?, ?, ?::timestamptz)",
                ctx.identityId(), tenantId, grantId, Secrets.sha256(token), grant.get("expiresAt")));
        db.inTenant(tenantId, c -> Sql.update(c,
                "insert into app.audit_event (tenant_id, actor_kind, actor_label, impersonation_grant_id, action, target_type, detail) "
                        + "values (?, 'support', ?, ?, 'support.session_started', 'support', ?::jsonb)",
                tenantId, ctx.email(), grantId, Json.object(Map.of(
                        "mode", grant.get("mode"), "reason", grant.get("reason"), "expiresAt", grant.get("expiresAt")))));
        return token;
    }

    // ── startup ─────────────────────────────────────────────────────────

    /** Creates the first Super Admin when the platform has none. Does nothing otherwise. */
    public void ensureFirstSuperAdmin(String email, String password) {
        boolean exists = db.inPlatform(c -> Sql.one(c, "select 1 from platform.platform_user") != null);
        if (exists || email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        UUID identityId = ensureIdentity(email.trim(), password);
        db.inPlatform(c -> Sql.update(c,
                "insert into platform.platform_user (identity_id, role) values (?, 'super_admin')", identityId));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private UUID ensureIdentity(String email, String password) {
        return db.inAuth(c -> {
            Map<String, Object> existing = Sql.one(c, "select id from auth.identity where email = ?::citext", email);
            if (existing != null) {
                return AuthService.uuid(existing.get("id"));
            }
            Secrets.checkPasswordPolicy(password);
            return AuthService.uuid(Sql.one(c,
                    "insert into auth.identity (email, password_hash) values (?::citext, ?) returning id",
                    email, Secrets.hashPassword(password)).get("id"));
        });
    }

    private static void requireEmail(String email) {
        if (email == null || !EMAIL.matcher(email.trim()).matches()) {
            throw ApiException.badRequest("Enter a valid email address");
        }
    }

    private static void audit(Connection c, SessionCtx ctx, String action, String targetType, UUID targetId,
                              Map<String, ?> detail) throws SQLException {
        Sql.update(c, "insert into platform.platform_audit (platform_user_id, action, target_type, target_id, detail) "
                + "values (?, ?, ?, ?, ?::jsonb)", ctx.platformUserId(), action, targetType, targetId, Json.object(detail));
    }
}
