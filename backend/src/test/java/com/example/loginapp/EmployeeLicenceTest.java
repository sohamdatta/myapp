package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
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
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;

/**
 * Employee licences against a real PostgreSQL: an Organization cannot have more employees joining or
 * working than it bought, the platform console can change the number (not below what is in use), and an
 * Organization with no number has no limit. Needs PostgreSQL as described in {@link UserModuleTest};
 * skipped when it cannot be reached.
 */
class EmployeeLicenceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static EmployeeService employees;
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
            st.execute("drop database if exists hrms_test_licences with (force)");
            st.execute("create database hrms_test_licences");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the employee licence tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_licences";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, crypto);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private record Org(UUID id, SessionCtx admin) {}

    private static Org organization(Integer licences) {
        String slug = "lic-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "admin@" + slug + ".test", null,
                licences);
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        return new Org(UUID.fromString((String) created.get("id")), admin);
    }

    private static void add(SessionCtx by, String name, int code) {
        employees.add(by, new NewEmployee(name, null, "1990-01-01", "female", null, "9876543210", "permanent",
                TODAY.minusYears(2).toString(), null, null, "E" + code, "Analyst", null, null, null, null, null, null, null, null, null, null,
                null, null));
    }

    private static String refusal(Runnable action) {
        return assertThrows(ApiException.class, action::run).code();
    }

    @Test
    void anOrganizationCannotAddPastItsLicences() {
        Org org = organization(2);
        add(org.admin, "Asha Admin", 1);
        add(org.admin, "Hari Hr", 2);
        assertEquals("licence.limit", refusal(() -> add(org.admin, "Bina Boss", 3)));
        EmployeeService.Licence licence = employees.licence(org.admin);
        assertEquals(2, licence.licensed());
        assertEquals(2, licence.used());
    }

    @Test
    void theConsoleCanRaiseTheLimitButNotBelowWhatIsInUse() {
        Org org = organization(2);
        add(org.admin, "Asha Admin", 1);
        add(org.admin, "Hari Hr", 2);
        assertEquals("licence.below_use", refusal(() -> platform.setLicences(superAdmin, org.id, 1)));
        platform.setLicences(superAdmin, org.id, 3);
        add(org.admin, "Bina Boss", 3);
        assertEquals("licence.limit", refusal(() -> add(org.admin, "Chitra Colleague", 4)));
    }

    @Test
    void noNumberMeansNoLimit() {
        Org org = organization(null);
        for (int i = 1; i <= 5; i++) {
            add(org.admin, "Person " + i, i);
        }
        EmployeeService.Licence licence = employees.licence(org.admin);
        assertNull(licence.licensed());
        assertEquals(5, licence.used());
        platform.setLicences(superAdmin, org.id, 5);
        assertEquals("licence.limit", refusal(() -> add(org.admin, "Person 6", 6)));
        platform.setLicences(superAdmin, org.id, null);
        add(org.admin, "Person 6", 6);
    }

    @Test
    void theConsoleListShowsLicencesAndUse() {
        Org org = organization(10);
        add(org.admin, "Asha Admin", 1);
        Map<String, Object> row = platform.organizations(superAdmin).stream()
                .filter(o -> org.id.toString().equals(String.valueOf(o.get("id")))).findFirst().orElseThrow();
        assertEquals(10, ((Number) row.get("licensedEmployees")).intValue());
        assertEquals(1, ((Number) row.get("employeesUsed")).intValue());
    }

    @Test
    void nonsenseNumbersAreRefused() {
        assertEquals("request.invalid", refusal(() -> organization(0)));
        Org org = organization(null);
        assertEquals("request.invalid", refusal(() -> platform.setLicences(superAdmin, org.id, -3)));
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
