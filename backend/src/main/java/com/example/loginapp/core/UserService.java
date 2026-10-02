package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Users, roles and invitations inside one Organization. */
public final class UserService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Db db;

    public UserService(Db db) {
        this.db = db;
    }

    public List<Map<String, Object>> users(SessionCtx ctx) {
        ctx.require("user.read");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select m.id, m.status, m.joined_at, m.suspended_at, m.ended_at, "
                        + "d.email, d.mfa_enrolled, d.last_login_at, "
                        + "array(select r.code from app.membership_role mr join app.role r on r.id = mr.role_id "
                        + "       where mr.membership_id = m.id order by r.name) as role_codes, "
                        + "array(select r.name from app.membership_role mr join app.role r on r.id = mr.role_id "
                        + "       where mr.membership_id = m.id order by r.name) as role_names "
                        + "from app.membership m join app.member_directory() d on d.membership_id = m.id "
                        + "order by d.email"));
    }

    /** The roles that exist, and for each whether the caller is allowed to grant it. */
    public List<Map<String, Object>> roles(SessionCtx ctx) {
        ctx.require("user.read");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select r.code, r.name, r.assignable, "
                        + "coalesce(app.can_grant(?, r.id), false) as grantable, "
                        + "array(select rp.permission_code || ' (' || rp.scope || ')' from app.role_permission rp "
                        + "       where rp.role_id = r.id order by rp.permission_code) as permissions "
                        + "from app.role r where r.code not like 'support%' order by r.name",
                ctx.membershipId()));
    }

    public List<Map<String, Object>> invitations(SessionCtx ctx) {
        ctx.require("user.read");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select i.id, i.email, i.created_at, i.expires_at, (i.expires_at <= now()) as expired, "
                        + "array(select r.name from app.role r where r.id = any(i.role_ids) order by r.name) as role_names "
                        + "from app.invitation i where i.accepted_at is null and i.revoked_at is null "
                        + "order by i.created_at desc"));
    }

    /** Invites an email with a set of roles. Returns the invitation and the one-time token for its link. */
    public Map<String, Object> invite(SessionCtx ctx, String email, List<String> roleCodes) {
        ctx.require("user.invite");
        requireMember(ctx);
        if (email == null || !EMAIL.matcher(email.trim()).matches()) {
            throw ApiException.badRequest("Enter a valid email address");
        }
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw ApiException.badRequest("Choose at least one role");
        }
        if (roleCodes.contains("payroll_operator") && roleCodes.contains("payroll_approver")) {
            throw new ApiException(409, "role.maker_checker",
                    "One user cannot be both Payroll Operator and Payroll Approver");
        }
        String address = email.trim();
        String token = Secrets.newToken();
        return db.inTenant(ctx.tenantId(), c -> {
            UUID[] roleIds = grantableRoleIds(c, ctx, roleCodes);
            if (Sql.one(c, "select 1 from app.membership m join app.member_directory() d on d.membership_id = m.id "
                    + "where d.email = ?::citext and m.status in ('active','suspended')", address) != null) {
                throw new ApiException(409, "membership.exists", "That email is already a user of this Organization");
            }
            Map<String, Object> row = Sql.one(c,
                    "insert into app.invitation (tenant_id, email, role_ids, token_hash, invited_by, expires_at) "
                            + "values (?, ?::citext, ?, ?, ?, now() + interval '7 days') returning id, email, expires_at",
                    ctx.tenantId(), address, roleIds, Secrets.sha256(token), ctx.membershipId());
            audit(c, ctx, "user.invited", "invitation", AuthService.uuid(row.get("id")),
                    Map.of("email", address, "roles", String.join(", ", roleCodes)));
            row.put("token", token);
            return row;
        });
    }

    /** Replaces the token and resets the expiry on the same invitation. */
    public Map<String, Object> resendInvitation(SessionCtx ctx, UUID invitationId) {
        ctx.require("user.invite");
        String token = Secrets.newToken();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> row = Sql.one(c,
                    "update app.invitation set token_hash = ?, expires_at = now() + interval '7 days' "
                            + "where id = ? and accepted_at is null and revoked_at is null returning id, email, expires_at",
                    Secrets.sha256(token), invitationId);
            if (row == null) {
                throw ApiException.notFound("The pending invitation");
            }
            audit(c, ctx, "invitation.resent", "invitation", invitationId, Map.of("email", row.get("email")));
            row.put("token", token);
            return row;
        });
    }

    public void revokeInvitation(SessionCtx ctx, UUID invitationId) {
        ctx.require("user.invite");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> row = Sql.one(c,
                    "update app.invitation set revoked_at = now() "
                            + "where id = ? and accepted_at is null and revoked_at is null returning email", invitationId);
            if (row == null) {
                throw ApiException.notFound("The pending invitation");
            }
            audit(c, ctx, "invitation.revoked", "invitation", invitationId, Map.of("email", row.get("email")));
            return null;
        });
    }

    /** Sets a user's roles to exactly the given list. Every role added or removed must be one the caller may grant. */
    public void changeRoles(SessionCtx ctx, UUID membershipId, List<String> roleCodes) {
        ctx.require("role.assign");
        requireMember(ctx);
        notSelf(ctx, membershipId, "change your own roles");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> target = lock(c, membershipId);
            if ("ended".equals(target.get("status"))) {
                throw new ApiException(409, "request.refused", "This user has left. Invite them again to give them access");
            }
            Set<String> wanted = new LinkedHashSet<>(roleCodes == null ? List.of() : roleCodes);
            if (wanted.isEmpty()) {
                throw ApiException.badRequest("A user needs at least one role");
            }
            Set<String> current = new LinkedHashSet<>(currentRoleCodes(c, membershipId));
            List<String> added = wanted.stream().filter(r -> !current.contains(r)).toList();
            List<String> removed = current.stream().filter(r -> !wanted.contains(r)).toList();
            List<String> touched = new ArrayList<>(added);
            touched.addAll(removed);
            grantableRoleIds(c, ctx, touched);   // throws when any of them is out of the caller's reach

            for (String code : removed) {
                Sql.update(c, "delete from app.membership_role mr using app.role r "
                        + "where mr.role_id = r.id and mr.membership_id = ? and r.code = ?", membershipId, code);
                audit(c, ctx, "role.removed", "membership", membershipId, Map.of("role", code));
            }
            for (String code : added) {
                Sql.update(c, "insert into app.membership_role (tenant_id, membership_id, role_id, granted_by) "
                                + "select ?, ?, r.id, ? from app.role r where r.code = ? and r.tenant_id is null",
                        ctx.tenantId(), membershipId, ctx.membershipId(), code);
                audit(c, ctx, "role.granted", "membership", membershipId, Map.of("role", code));
            }
            return null;
        });
    }

    public void suspend(SessionCtx ctx, UUID membershipId) {
        setStatus(ctx, membershipId, "active", "suspended", "membership.suspended", "suspend yourself");
        revokeSessions(membershipId);
    }

    public void reactivate(SessionCtx ctx, UUID membershipId) {
        setStatus(ctx, membershipId, "suspended", "active", "membership.reactivated", "reactivate yourself");
    }

    /** Offboarding: all roles are removed, and an employee keeps only the Ex-employee role. */
    public void end(SessionCtx ctx, UUID membershipId) {
        ctx.require("user.manage");
        notSelf(ctx, membershipId, "end your own membership");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> target = lock(c, membershipId);
            if ("ended".equals(target.get("status"))) {
                throw new ApiException(409, "request.refused", "This user has already left");
            }
            requireReach(c, ctx, membershipId);
            Sql.update(c, "delete from app.membership_role where membership_id = ?", membershipId);
            if (target.get("personId") != null) {
                Sql.update(c, "insert into app.membership_role (tenant_id, membership_id, role_id) "
                        + "select ?, ?, r.id from app.role r where r.code = 'ex_employee'", ctx.tenantId(), membershipId);
            }
            Sql.update(c, "update app.membership set status = 'ended', ended_at = now() where id = ?", membershipId);
            audit(c, ctx, "membership.ended", "membership", membershipId, Map.of());
            return null;
        });
        revokeSessions(membershipId);
    }

    public List<Map<String, Object>> auditLog(SessionCtx ctx) {
        if (!ctx.can("audit.read")) {
            throw ApiException.denied("audit.read");
        }
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select id, at, actor_kind, actor_label, action, target_type, target_id, detail "
                        + "from app.audit_event order by at desc, id desc limit 200"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Writes an audit event in the same transaction as the change it records. */
    static void audit(Connection c, SessionCtx ctx, String action, String targetType, UUID targetId,
                      Map<String, ?> detail) throws SQLException {
        Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_membership_id, actor_label, "
                        + "impersonation_grant_id, action, target_type, target_id, detail) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                ctx.tenantId(), ctx.actorKind(), ctx.membershipId(), ctx.email(), ctx.grantId(),
                action, targetType, targetId, Json.object(detail));
    }

    private void setStatus(SessionCtx ctx, UUID membershipId, String from, String to, String action, String selfMessage) {
        ctx.require("user.manage");
        notSelf(ctx, membershipId, selfMessage);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> target = lock(c, membershipId);
            if (!from.equals(target.get("status"))) {
                throw new ApiException(409, "request.refused", "This user is not " + from);
            }
            requireReach(c, ctx, membershipId);
            Sql.update(c, "update app.membership set status = ?, suspended_at = "
                    + (to.equals("suspended") ? "now()" : "null") + " where id = ?", to, membershipId);
            audit(c, ctx, action, "membership", membershipId, Map.of());
            return null;
        });
    }

    private void revokeSessions(UUID membershipId) {
        db.inAuth(c -> Sql.update(c,
                "update auth.session set revoked_at = now() where membership_id = ? and revoked_at is null", membershipId));
    }

    private static void requireMember(SessionCtx ctx) {
        if (ctx.membershipId() == null) {
            throw new ApiException(403, "grant.not_allowed", "Only a user of the Organization can grant roles");
        }
    }

    private static void notSelf(SessionCtx ctx, UUID membershipId, String what) {
        if (membershipId.equals(ctx.membershipId())) {
            throw new ApiException(409, "request.refused", "You cannot " + what);
        }
    }

    private static Map<String, Object> lock(Connection c, UUID membershipId) throws SQLException {
        Map<String, Object> row = Sql.one(c, "select id, status, person_id from app.membership where id = ? for update", membershipId);
        if (row == null) {
            throw ApiException.notFound("The user");
        }
        return row;
    }

    private static List<String> currentRoleCodes(Connection c, UUID membershipId) throws SQLException {
        return Sql.query(c, "select r.code from app.membership_role mr join app.role r on r.id = mr.role_id "
                + "where mr.membership_id = ?", membershipId).stream().map(r -> (String) r.get("code")).toList();
    }

    /** A user can only be suspended, reactivated or ended by someone who could have granted all of their roles. */
    private static void requireReach(Connection c, SessionCtx ctx, UUID membershipId) throws SQLException {
        if (ctx.membershipId() == null) {
            return;   // support sessions are limited by their own permissions and the audit trail
        }
        try {
            grantableRoleIds(c, ctx, currentRoleCodes(c, membershipId).stream()
                    .filter(code -> !code.equals("ex_employee")).toList());
        } catch (ApiException e) {
            throw new ApiException(403, "grant.not_allowed",
                    "You cannot manage this user, because they hold a role you are not allowed to grant");
        }
    }

    /** Resolves role codes to ids, refusing any role the caller may not grant. */
    private static UUID[] grantableRoleIds(Connection c, SessionCtx ctx, List<String> roleCodes) throws SQLException {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return new UUID[0];
        }
        List<UUID> ids = new ArrayList<>();
        for (String code : new LinkedHashSet<>(roleCodes)) {
            Map<String, Object> role = Sql.one(c,
                    "select r.id, r.name, coalesce(app.can_grant(?, r.id), false) as grantable "
                            + "from app.role r where r.code = ? and r.tenant_id is null", ctx.membershipId(), code);
            if (role == null) {
                throw ApiException.badRequest("Unknown role: " + code);
            }
            if (!Boolean.TRUE.equals(role.get("grantable"))) {
                throw new ApiException(403, "grant.not_allowed",
                        "You are not allowed to grant or remove the " + role.get("name") + " role");
            }
            ids.add(AuthService.uuid(role.get("id")));
        }
        return ids.toArray(new UUID[0]);
    }
}
