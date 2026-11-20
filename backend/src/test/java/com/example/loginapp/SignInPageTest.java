package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SignInPage;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Each Organization's own sign-in address against a real PostgreSQL: which page a host name is, that only members get a
 * session there, that only that Organization can be entered from it, and that the Org Admin alone changes the short name.
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class SignInPageTest {
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final String PUBLIC_URL = "https://hr.example.test";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static SessionCtx superAdmin;
    private static SignInPage pages;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_sign_in_page with (force)");
            st.execute("create database hrms_test_sign_in_page");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the sign-in page tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_sign_in_page";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        pages = new SignInPage(db, PUBLIC_URL);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private record Org(UUID id, String slug, SessionCtx admin, SessionCtx hr) {}

    private static Org organization() {
        String slug = "page-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        String invitation = (String) users.invite(admin, "hr@" + slug + ".test", List.of("hr_admin", "employee")).get("token");
        SessionCtx hr = auth.resolve(invitations.accept(invitation, PASSWORD, null));
        return new Org(UUID.fromString((String) created.get("id")), slug, admin, hr);
    }

    @Test
    void aHostNamesTheSharedPageAnOrganizationsOrOneThatLeadsNowhere() {
        Org org = organization();
        assertTrue(pages.forHost("hr.example.test").shared());
        assertTrue(pages.forHost("HR.example.test:443").shared(), "case and port do not matter");
        assertTrue(pages.forHost("10.0.0.5:8080").shared(), "an address it was not told about is the shared page");
        assertTrue(pages.forHost(null).shared());
        assertTrue(pages.forHost("other.example.com").shared());

        SignInPage.Page page = pages.forHost(org.slug() + ".hr.example.test");
        assertEquals(SignInPage.Kind.ORGANIZATION, page.kind());
        assertEquals(org.id(), page.organization().id());
        assertEquals("Org " + org.slug(), page.organization().name());
        assertEquals(0, page.organization().logoVersion(), "no logo yet: the page shows the product's brand");
        assertEquals(SignInPage.Kind.ORGANIZATION, pages.forHost(org.slug() + ".hr.example.test.").kind(), "a trailing dot is the same host");

        assertEquals(SignInPage.Kind.UNKNOWN, pages.forHost("nobody-here.hr.example.test").kind());
        assertEquals(SignInPage.Kind.UNKNOWN, pages.forHost("a." + org.slug() + ".hr.example.test").kind(), "one label only");

        assertTrue(pages.servesHost("hr.example.test"));
        assertTrue(pages.servesHost(org.slug() + ".hr.example.test"));
        assertFalse(pages.servesHost("nobody-here.hr.example.test"), "no certificate for a name that leads nowhere");
        assertFalse(pages.servesHost("example.com"));

        platform.changeOrganizationStatus(superAdmin, org.id(), "suspend");
        assertEquals(SignInPage.Kind.UNKNOWN, pages.forHost(org.slug() + ".hr.example.test").kind(), "a suspended Organization has no page");
        assertFalse(pages.servesHost(org.slug() + ".hr.example.test"));
    }

    @Test
    void onlyMembersGetASessionOnAnOrganizationsPageAndOnlyItCanBeEntered() {
        Org acme = organization();
        Org other = organization();
        SignInPage.Page page = pages.forHost(acme.slug() + ".hr.example.test");

        // A member signs in and enters their Organization.
        String token = auth.login(acme.hr().email(), PASSWORD);
        pages.requireMember(auth, page, token);
        pages.requireEnterable(page, acme.id());
        assertNotNull(auth.resolve(auth.enterOrganization(auth.resolve(token), acme.id())));

        // Someone from another Organization has the right password but is refused, and the session is ended.
        String outsider = auth.login(other.hr().email(), PASSWORD);
        ApiException refused = assertThrows(ApiException.class, () -> pages.requireMember(auth, page, outsider));
        assertEquals(403, refused.status());
        assertEquals("signin.not_member", refused.code());
        assertThrows(ApiException.class, () -> auth.resolve(outsider), "the session it would have had is ended");

        // From Acme's page, neither another Organization nor the console can be entered.
        assertError(403, "signin.not_member", () -> pages.requireEnterable(page, other.id()));
        assertError(403, "signin.not_member", () -> pages.requireEnterable(page, null));

        // The Super Admin, who belongs to no Organization, is refused there too.
        String root = auth.login("root@platform.test", PASSWORD);
        assertError(403, "signin.not_member", () -> pages.requireMember(auth, page, root));

        // On the shared page anything goes, as before.
        SignInPage.Page shared = pages.forHost("hr.example.test");
        String fine = auth.login(other.hr().email(), PASSWORD);
        pages.requireMember(auth, shared, fine);
        pages.requireEnterable(shared, acme.id());
        pages.requireEnterable(shared, null);
        assertNotNull(auth.resolve(fine));

        // An address that leads nowhere lets nobody in.
        SignInPage.Page nowhere = pages.forHost("nobody-here.hr.example.test");
        assertError(404, "signin.unknown_address", () -> pages.requireEnterable(nowhere, null));
        String member = auth.login(acme.hr().email(), PASSWORD);
        assertError(404, "signin.unknown_address", () -> pages.requireMember(auth, nowhere, member));
    }

    @Test
    void theOrgAdminChangesTheShortNameAndTheOldAddressStopsWorking() {
        Org org = organization();
        Org taken = organization();

        SignInPage.Address address = pages.get(org.admin(), PUBLIC_URL);
        assertEquals(org.slug(), address.slug());
        assertEquals("https://" + org.slug() + ".hr.example.test", address.address());
        assertTrue(address.canChange());
        assertFalse(pages.get(org.hr(), PUBLIC_URL).canChange(), "others see the address but cannot change it");

        assertError(403, "permission.denied", () -> pages.setSlug(org.hr(), "new-name", PUBLIC_URL));
        assertError(403, "permission.denied", () -> pages.get(superAdmin, PUBLIC_URL));
        assertError(400, "request.invalid", () -> pages.setSlug(org.admin(), "-bad", PUBLIC_URL));
        assertError(400, "request.invalid", () -> pages.setSlug(org.admin(), "a.b", PUBLIC_URL));
        assertError(400, "request.invalid", () -> pages.setSlug(org.admin(), "xn--abc", PUBLIC_URL));
        assertError(409, "tenant.slug_reserved", () -> pages.setSlug(org.admin(), "www", PUBLIC_URL));
        assertError(409, "tenant.slug_taken", () -> pages.setSlug(org.admin(), taken.slug(), PUBLIC_URL));

        String fresh = "renamed-" + SEQUENCE.incrementAndGet();
        SignInPage.Address changed = pages.setSlug(org.admin(), "  " + fresh.toUpperCase() + " ", PUBLIC_URL);
        assertEquals(fresh, changed.slug(), "trimmed and lower-cased");
        assertEquals("https://" + fresh + ".hr.example.test", changed.address());
        assertEquals(SignInPage.Kind.UNKNOWN, pages.forHost(org.slug() + ".hr.example.test").kind(), "the old address stops working");
        assertEquals(org.id(), pages.forHost(fresh + ".hr.example.test").organization().id());

        long audited = db.inTenant(org.id(), c -> ((Number) Sql.one(c,
                "select count(*) as n from app.audit_event where action = 'organization.sign_in_address_changed'").get("n")).longValue());
        assertEquals(1, audited);
        pages.setSlug(org.admin(), fresh, PUBLIC_URL);
        assertEquals(1L, (long) db.inTenant(org.id(), c -> ((Number) Sql.one(c,
                "select count(*) as n from app.audit_event where action = 'organization.sign_in_address_changed'").get("n")).longValue()),
                "saving the same name again records nothing");

        // The tenant role can change only its own Organization's slug: the function takes no Organization from the caller.
        assertNull(pages.bySlug(org.slug()));
        assertEquals(taken.id(), pages.bySlug(taken.slug()).id());
    }

    @Test
    void theAddressKeepsTheSchemeAndPortOfTheSharedOne() {
        SignInPage local = new SignInPage(db, "http://localhost:5173");
        assertEquals("http://acme.localhost:5173", local.addressOf("acme", "http://localhost:5173"));
        assertEquals("http://localhost:5173", local.addressOf(null, "http://localhost:5173"));
        SignInPage bare = new SignInPage(db, "hr.example.test");
        assertEquals("hr.example.test", bare.baseHost(), "DOMAIN may be given without a scheme");
        assertEquals("https://acme.hr.example.test", bare.addressOf("acme", "hr.example.test"));
    }

    @Test
    void anOrganizationCannotBeCreatedWithAReservedSlug() {
        assertError(409, "tenant.slug_reserved", () -> platform.createOrganization(superAdmin, "API", "api", "admin@api-org.test"));
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(status, e.status(), e.getMessage());
        assertEquals(code, e.code(), e.getMessage());
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
