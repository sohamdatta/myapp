package com.nexvance.loginapp.core;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An Organization's own roles, made from the permissions of the catalogue. A custom role belongs to one Organization, has a code of
 * the form {@code custom_<name>}, and may hold only permissions its creator holds, at that scope or wider (the rule that lets
 * someone grant a role). Some permissions stay with the system roles whatever the creator holds: the payroll ones (so the maker-checker
 * rule, kept by role code, cannot be dodged) and the ones that administer users, roles, the Organization and billing.
 */
public final class RoleService {

    public record PermissionGrant(String code, String scope) {}

    public record RoleInput(String name, String description, List<PermissionGrant> permissions) {}

    public record RoleSaved(UUID id, String code) {}

    /** Never in a custom role. */
    static final Set<String> SYSTEM_ONLY = Set.of("payroll.prepare", "payroll.approve", "payroll.release", "compensation.write",
            "statutory.file", "tenant.manage", "billing.manage", "role.assign", "role.manage", "user.manage", "user.invite");

    private static final Set<String> SCOPES = Set.of("self", "team", "assigned", "tenant");

    /** The permissions that never go in a custom role. */
    public static Set<String> systemOnly() {
        return SYSTEM_ONLY;
    }

    private final Db db;

    public RoleService(Db db) {
        this.db = db;
    }

    /** The permissions that can go in a role, with the scopes the caller may give each. */
    public List<Map<String, Object>> catalogue(SessionCtx ctx) {
        ctx.require("role.manage");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select p.code, p.description, (select min(case g.scope when 'tenant' then 3 when 'assigned' then 2 when 'team' then 2 else 1 end) "
                        + "from app.effective_permissions(?) g where g.permission_code = p.code) as held "
                        + "from app.permission p where p.code not in (select unnest(?::text[])) and p.code not like 'support%' order by p.code",
                ctx.membershipId(), SYSTEM_ONLY.toArray(String[]::new)));
    }

    public RoleSaved create(SessionCtx ctx, RoleInput in) {
        ctx.require("role.manage");
        String name = cleanName(in);
        return db.inTenant(ctx.tenantId(), c -> {
            requireNameFree(c, name, null);
            List<PermissionGrant> grants = checked(c, ctx, in.permissions());
            String code = freeCode(c, name);
            Map<String, Object> role = Sql.one(c, "insert into app.role (tenant_id, code, name, is_system, assignable, description) "
                    + "values (?, ?, ?, false, true, ?) returning id", ctx.tenantId(), code, name, blank(in.description()));
            UUID id = AuthService.uuid(role.get("id"));
            insertGrants(c, id, grants);
            UserService.audit(c, ctx, "role.created", "role", id, Map.of("name", name, "permissions", grants.size()));
            return new RoleSaved(id, code);
        });
    }

    /** Renames a role and replaces its permissions. Everyone holding it gets the change on their next request. */
    public RoleSaved update(SessionCtx ctx, UUID id, RoleInput in) {
        ctx.require("role.manage");
        String name = cleanName(in);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> role = own(c, id);
            requireNameFree(c, name, id);
            List<PermissionGrant> grants = checked(c, ctx, in.permissions());
            Sql.update(c, "update app.role set name = ?, description = ? where id = ?", name, blank(in.description()), id);
            Sql.update(c, "delete from app.role_permission where role_id = ?", id);
            insertGrants(c, id, grants);
            UserService.audit(c, ctx, "role.changed", "role", id, Map.of("name", name, "permissions", grants.size()));
            return new RoleSaved(id, (String) role.get("code"));
        });
    }

    /** Deletes a role nobody holds and nothing refers to. */
    public void delete(SessionCtx ctx, UUID id) {
        ctx.require("role.manage");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> role = own(c, id);
            if (Sql.exists(c, "select 1 from app.membership_role where role_id = ?", id)
                    || Sql.exists(c, "select 1 from app.invitation where ? = any(role_ids) and accepted_at is null and revoked_at is null", id)) {
                throw new ApiException(409, "role.in_use", "People still hold this role. Change their roles first");
            }
            try {
                Sql.update(c, "delete from app.role_permission where role_id = ?", id);
                Sql.update(c, "delete from app.role where id = ?", id);
            } catch (SQLException e) {
                if ("23503".equals(e.getSQLState())) {
                    throw new ApiException(409, "role.in_use", "A checklist or onboarding step still uses this role. Change it there first");
                }
                throw e;
            }
            UserService.audit(c, ctx, "role.deleted", "role", id, Map.of("name", role.get("name")));
            return null;
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> own(java.sql.Connection c, UUID id) throws SQLException {
        Map<String, Object> role = Sql.one(c, "select id, code, name from app.role where id = ? and not is_system and tenant_id = app.current_tenant()", id);
        if (role == null) {
            throw ApiException.notFound("The role");
        }
        return role;
    }

    private static String cleanName(RoleInput in) {
        String name = in == null || in.name() == null ? "" : in.name().trim();
        if (name.length() < 2 || name.length() > 60) {
            throw ApiException.badRequest("Give the role a name of 2 to 60 characters");
        }
        return name;
    }

    private static void requireNameFree(java.sql.Connection c, String name, UUID except) throws SQLException {
        if (Sql.exists(c, "select 1 from app.role where lower(name) = lower(?) and (?::uuid is null or id <> ?::uuid)", name, except, except)) {
            throw new ApiException(409, "role.exists", "A role called " + name + " already exists");
        }
    }

    /** Valid, allowed and held by the caller at that scope or wider. */
    private static List<PermissionGrant> checked(java.sql.Connection c, SessionCtx ctx, List<PermissionGrant> given) throws SQLException {
        if (ctx.membershipId() == null) {
            throw ApiException.denied("role.manage");
        }
        if (given == null || given.isEmpty()) {
            throw ApiException.badRequest("Choose at least one permission");
        }
        List<PermissionGrant> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PermissionGrant g : given) {
            if (g == null || g.code() == null || g.scope() == null || !SCOPES.contains(g.scope())) {
                throw ApiException.badRequest("Each permission needs a code and a scope: self, team, assigned or tenant");
            }
            if (!seen.add(g.code())) {
                throw ApiException.badRequest("Permission " + g.code() + " is listed twice");
            }
            if (SYSTEM_ONLY.contains(g.code()) || g.code().startsWith("support")) {
                throw new ApiException(409, "role.system_only", g.code() + " stays with the system roles and cannot go in a role of your own");
            }
            if (!Sql.exists(c, "select 1 from app.permission where code = ?", g.code())) {
                throw ApiException.badRequest("Unknown permission: " + g.code());
            }
            if (!Sql.exists(c, "select 1 from app.effective_permissions(?) p where p.permission_code = ? and (p.scope = 'tenant' or p.scope = ?)",
                    ctx.membershipId(), g.code(), g.scope())) {
                throw new ApiException(403, "role.not_held", "You do not hold " + g.code() + " at that scope, so you cannot give it");
            }
            out.add(new PermissionGrant(g.code(), g.scope()));
        }
        return out;
    }

    private static void insertGrants(java.sql.Connection c, UUID roleId, List<PermissionGrant> grants) throws SQLException {
        for (PermissionGrant g : grants) {
            Sql.update(c, "insert into app.role_permission (role_id, permission_code, scope) values (?, ?, ?)", roleId, g.code(), g.scope());
        }
    }

    /** custom_ followed by the name in lower case letters, digits and underscores, made unique for the Organization. */
    private static String freeCode(java.sql.Connection c, String name) throws SQLException {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "role";
        }
        String base = "custom_" + (slug.length() > 30 ? slug.substring(0, 30) : slug);
        String code = base;
        for (int n = 2; Sql.exists(c, "select 1 from app.role where code = ?", code); n++) {
            code = base + "_" + n;
        }
        return code;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
