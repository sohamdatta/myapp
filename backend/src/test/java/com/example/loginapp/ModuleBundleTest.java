package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;

/**
 * Attendance with Leave as one choice in the console, against a real PostgreSQL: choosing or switching
 * it applies to both modules, and each module still works alone. Needs PostgreSQL as described in {@link UserModuleTest};
 * skipped when it cannot be reached.
 */
class ModuleBundleTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static InvitationService invitations;
    private static PlatformService platform;
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
            st.execute("drop database if exists hrms_test_bundle with (force)");
            st.execute("create database hrms_test_bundle");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the module bundle tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_bundle";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private static Map<String, Boolean> active(UUID org) {
        Map<String, Object> row = platform.organizations(superAdmin).stream()
                .filter(o -> org.toString().equals(String.valueOf(o.get("id")))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modules = (List<Map<String, Object>>) row.get("modules");
        Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> m : modules) {
            out.put((String) m.get("module"), Boolean.TRUE.equals(m.get("active")));
        }
        return out;
    }

    private static UUID create(List<String> modules) {
        String slug = "bun-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "admin@" + slug + ".test", modules);
        return UUID.fromString((String) created.get("id"));
    }

    @Test
    void choosingAttendanceWithLeaveCreatesBoth() {
        Map<String, Boolean> on = active(create(List.of("attendance_leave")));
        assertEquals(Map.of("attendance", true, "leave", true, "payroll", false), on);
    }

    @Test
    void switchingTheChoiceAppliesToBothAndEachStillWorksAlone() {
        UUID org = create(List.of("payroll"));
        assertEquals(Map.of("attendance", false, "leave", false, "payroll", true), active(org));
        platform.setModule(superAdmin, org, "attendance_leave", true, null);
        assertEquals(Map.of("attendance", true, "leave", true, "payroll", true), active(org));
        platform.setModule(superAdmin, org, "attendance_leave", false, null);
        assertEquals(Map.of("attendance", false, "leave", false, "payroll", true), active(org));
        platform.setModule(superAdmin, org, "leave", true, null);
        assertEquals(Map.of("attendance", false, "leave", true, "payroll", true), active(org));
    }

    @Test
    void anUnknownChoiceIsRefused() {
        UUID org = create(null);
        assertEquals("request.invalid", assertThrows(ApiException.class, () -> platform.setModule(superAdmin, org, "chess", true, null)).code());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource s = new PGSimpleDataSource();
        s.setUrl(url);
        s.setUser(user);
        s.setPassword(password);
        return s;
    }
}
