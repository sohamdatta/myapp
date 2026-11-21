package com.nexvance.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.nexvance.loginapp.core.ApiException;
import com.nexvance.loginapp.core.AuthService;
import com.nexvance.loginapp.core.Db;
import com.nexvance.loginapp.core.RoleService;
import com.nexvance.loginapp.core.RoleService.RoleInput;
import com.nexvance.loginapp.core.InvitationService;
import com.nexvance.loginapp.core.Migrator;
import com.nexvance.loginapp.core.PlatformService;
import com.nexvance.loginapp.core.SessionCtx;
import com.nexvance.loginapp.core.Sql;
import com.nexvance.loginapp.core.UserService;

/**
 * Organization-defined roles against a real PostgreSQL: who may make them, what they may hold, and what they refresh.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class RolesTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static RoleService roles;
    private static SessionCtx superAdmin;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_roles with (force)");
            st.execute("create database hrms_test_roles");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the role tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_roles";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        roles = new RoleService(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    @Test
    void anOwnerMakesEditsAndDeletesARoleAndHoldersAreRefreshed() {
        SessionCtx admin = org();
        List<RoleService.PermissionGrant> held = heldByAdmin(admin, 2);
        RoleService.RoleSaved saved = roles.create(admin, new RoleInput("Team Lead!", "Leads a team", held));
        assertEquals("custom_team_lead", saved.code());
        assertError(409, "role.exists", () -> roles.create(admin, new RoleInput("team lead!", null, held)));

        // it shows in the list, marked as the Organization's own, and can be given to someone
        Map<String, Object> listed = users.roles(admin).stream().filter(r -> saved.code().equals(r.get("code"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, listed.get("isSystem"));
        SessionCtx lead = join(admin, "lead", List.of("employee"));
        users.changeRoles(admin, lead.membershipId(), List.of("employee", saved.code()));

        // editing the role refreshes the people holding it
        long before = scopeVersion(admin, lead.membershipId());
        roles.update(admin, saved.id(), new RoleInput("Team Lead", null, held.subList(0, 1)));
        assertTrue(scopeVersion(admin, lead.membershipId()) > before);

        // a role somebody holds cannot be deleted; once released it can
        assertError(409, "role.in_use", () -> roles.delete(admin, saved.id()));
        users.changeRoles(admin, lead.membershipId(), List.of("employee"));
        roles.delete(admin, saved.id());
        assertTrue(users.roles(admin).stream().noneMatch(r -> saved.code().equals(r.get("code"))));
    }

    @Test
    void sensitivePermissionsAndPermissionsTheCreatorLacksAreRefused() {
        SessionCtx admin = org();
        for (String code : List.of("payroll.approve", "payroll.prepare", "role.manage", "user.manage", "tenant.manage")) {
            assertError(409, "role.system_only", () -> roles.create(admin, new RoleInput("Bad " + code, null,
                    List.of(new RoleService.PermissionGrant(code, "tenant")))));
        }
        assertError(400, "request.invalid", () -> roles.create(admin, new RoleInput("Empty", null, List.of())));
        assertError(400, "request.invalid", () -> roles.create(admin, new RoleInput("X", null, heldByAdmin(admin, 1))));
        assertError(400, "request.invalid", () -> roles.create(admin, new RoleInput("Nonsense", null,
                List.of(new RoleService.PermissionGrant("no.such.permission", "tenant")))));
        // someone who may manage roles but holds one permission cannot hand out another
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/hrms_test_roles", env("APP_DB_OWNER_USER", "postgres"),
                env("APP_DB_OWNER_PASSWORD", "postgres")); Statement st = c.createStatement()) {
            st.execute("insert into app.role (code, name, is_system) values ('test_rolemaker', 'Role maker', true) on conflict do nothing");
            st.execute("insert into app.role_permission (role_id, permission_code, scope) select id, p, 'tenant' from app.role, "
                    + "unnest(array['role.manage', 'expense.approve']) p where code = 'test_rolemaker' on conflict do nothing");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        SessionCtx maker = join(admin, "maker", List.of("test_rolemaker"));
        assertError(403, "role.not_held", () -> roles.create(maker, new RoleInput("Reach", null,
                List.of(new RoleService.PermissionGrant("leave.approve", "tenant")))));
        assertEquals("custom_narrow", roles.create(maker, new RoleInput("Narrow", null,
                List.of(new RoleService.PermissionGrant("expense.approve", "tenant")))).code());
    }

    @Test
    void anHrAdminCannotManageRolesAndOrganizationsDoNotSeeEachOthersRoles() {
        SessionCtx admin = org();
        SessionCtx hr = join(admin, "hr", List.of("hr_admin"));
        assertError(403, "permission.denied", () -> roles.create(hr, new RoleInput("Mine", null, heldByAdmin(admin, 1))));
        assertError(403, "permission.denied", () -> roles.catalogue(hr));

        RoleService.RoleSaved saved = roles.create(admin, new RoleInput("Private", null, heldByAdmin(admin, 1)));
        SessionCtx other = org();
        assertTrue(users.roles(other).stream().noneMatch(r -> saved.code().equals(r.get("code"))));
        assertError(404, "not_found", () -> roles.update(other, saved.id(), new RoleInput("Stolen", null, heldByAdmin(other, 1))));
        assertError(404, "not_found", () -> roles.delete(other, saved.id()));
        // system roles are not editable even by an owner
        UUID system = db.inTenant(admin.tenantId(), c -> UUID.fromString(String.valueOf(Sql.one(c, "select id from app.role where code = 'hr_admin'").get("id"))));
        assertError(404, "not_found", () -> roles.delete(admin, system));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static List<RoleService.PermissionGrant> heldByAdmin(SessionCtx admin, int n) {
        List<RoleService.PermissionGrant> out = new java.util.ArrayList<>();
        for (Map<String, Object> p : roles.catalogue(admin)) {
            if (p.get("held") != null && out.size() < n) {
                out.add(new RoleService.PermissionGrant((String) p.get("code"), "tenant"));
            }
        }
        if (out.size() < n) {
            throw new IllegalStateException("the owner holds too few permissions for this test");
        }
        return out;
    }

    private static long scopeVersion(SessionCtx admin, UUID membershipId) {
        return db.inTenant(admin.tenantId(), c -> ((Number) Sql.one(c, "select scope_version from app.membership where id = ?", membershipId)
                .get("scopeVersion")).longValue());
    }

    private static SessionCtx org() {
        String slug = "rol-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "admin@" + slug + ".test");
        return auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
    }

    private static SessionCtx join(SessionCtx admin, String name, List<String> roleCodes) {
        String invitation = (String) users.invite(admin, name + "@" + SEQUENCE.incrementAndGet() + ".test", roleCodes).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(code, e.code(), e.getMessage());
        assertEquals(status, e.status(), e.getMessage());
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
