package com.nexvance.loginapp;

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

import org.springframework.mock.web.MockHttpServletRequest;

import com.nexvance.loginapp.core.ApiException;
import com.nexvance.loginapp.core.AuthService;
import com.nexvance.loginapp.core.Crypto;
import com.nexvance.loginapp.core.Db;
import com.nexvance.loginapp.core.EmailChangeService;
import com.nexvance.loginapp.core.InvitationService;
import com.nexvance.loginapp.core.MailOutbox;
import com.nexvance.loginapp.core.MfaService;
import com.nexvance.loginapp.core.Migrator;
import com.nexvance.loginapp.core.PasswordService;
import com.nexvance.loginapp.core.PlatformService;
import com.nexvance.loginapp.core.RateLimits;
import com.nexvance.loginapp.core.SessionCtx;
import com.nexvance.loginapp.core.SignInPage;
import com.nexvance.loginapp.core.Sql;
import com.nexvance.loginapp.core.Totp;
import com.nexvance.loginapp.core.UserService;
import com.nexvance.loginapp.web.AuthController;
import com.nexvance.loginapp.web.OidcController;
import com.nexvance.loginapp.web.SignInPageController;

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
    private static RateLimits limits;
    private static Crypto crypto;
    private static MfaService mfa;
    private static AuthController controller;
    private static SignInPageController pageController;

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
        pages = new SignInPage(db, PUBLIC_URL, "*.hr.example.test");
        limits = new RateLimits();
        crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        MailOutbox outbox = new MailOutbox(db, crypto);
        mfa = new MfaService(db, crypto, outbox, limits);
        controller = new AuthController(auth, invitations, new PasswordService(db, outbox, limits, PUBLIC_URL, false), mfa,
                new EmailChangeService(db, outbox, limits, PUBLIC_URL), pages, false);
        pageController = new SignInPageController(pages, PUBLIC_URL);
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
        assertTrue(local.enabled(), "on localhost the addresses work without ORG_ADDRESSES");
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

    // ── turned on only where the server serves the addresses ─────────────

    @Test
    void withoutOrgAddressesEveryAddressIsTheSharedPageAndNoneIsOffered() {
        Org org = organization();
        SignInPage off = new SignInPage(db, PUBLIC_URL, "");
        assertFalse(off.enabled());
        assertTrue(off.forHost(org.slug() + ".hr.example.test").shared(), "no branding and no members-only rule when off");
        assertFalse(off.servesHost(org.slug() + ".hr.example.test"), "and no certificate");
        assertTrue(off.servesHost("hr.example.test"));
        assertFalse(off.get(org.admin(), PUBLIC_URL).enabled(), "settings do not offer an address that would not open");
        assertTrue(pages.get(org.admin(), PUBLIC_URL).enabled());

        assertTrue(new SignInPage(db, PUBLIC_URL, "https://*.HR.example.test").enabled(), "a scheme and case do not matter");
        IllegalStateException wrong = assertThrows(IllegalStateException.class, () -> new SignInPage(db, PUBLIC_URL, "*.other.example"));
        assertTrue(wrong.getMessage().contains("ORG_ADDRESSES=*.hr.example.test"), wrong.getMessage());
        assertThrows(IllegalStateException.class, () -> new SignInPage(db, "https://hrms.example.test", "*.hr.example.test"),
                "APP_PUBLIC_URL on another host than DOMAIN would leave every address without a certificate");
    }

    // ── a short name given up is held ─────────────────────────────────────

    @Test
    void aShortNameGivenUpIsHeldFromOthersButCanBeTakenBack() {
        Org acme = organization();
        Org other = organization();
        String old = acme.slug();
        String fresh = "moved-" + SEQUENCE.incrementAndGet();
        pages.setSlug(acme.admin(), fresh, PUBLIC_URL);

        assertError(409, "tenant.slug_held", () -> pages.setSlug(other.admin(), old, PUBLIC_URL));
        assertError(409, "tenant.slug_held", () -> platform.createOrganization(superAdmin, "Squatter", old, "x@squat.test"));
        assertEquals(SignInPage.Kind.UNKNOWN, pages.forHost(old + ".hr.example.test").kind(), "the old address leads nowhere meanwhile");

        // Acme takes it back; its newer name is now held for it instead.
        assertEquals(old, pages.setSlug(acme.admin(), old, PUBLIC_URL).slug());
        assertError(409, "tenant.slug_held", () -> pages.setSlug(other.admin(), fresh, PUBLIC_URL));

        // Still held a day before the 90 are up, free a day after.
        db.inOwner(c -> Sql.update(c, "update platform.retired_slug set retired_at = now() - interval '89 days' where slug = ?", fresh));
        assertError(409, "tenant.slug_held", () -> pages.setSlug(other.admin(), fresh, PUBLIC_URL));
        db.inOwner(c -> Sql.update(c, "update platform.retired_slug set retired_at = now() - interval '91 days' where slug = ?", fresh));
        assertEquals(fresh, pages.setSlug(other.admin(), fresh, PUBLIC_URL).slug());
    }

    @Test
    void theSuperAdminFollowsTheSameRulesForANewOrganization() {
        assertError(400, "request.invalid", () -> platform.createOrganization(superAdmin, "Punycode", "xn--abc", "a@puny.test"));
        assertError(400, "request.invalid", () -> platform.createOrganization(superAdmin, "Double", "two--dashes", "a@two.test"));
    }

    @Test
    void aSuspendedMemberIsToldSoNotThatTheyDoNotBelong() {
        Org org = organization();
        users.suspend(org.admin(), org.hr().membershipId());
        SignInPage.Page page = pages.forHost(org.slug() + ".hr.example.test");
        String token = auth.login(org.hr().email(), PASSWORD);
        assertError(403, "signin.suspended", () -> pages.requireMember(auth, page, token));
        assertThrows(ApiException.class, () -> auth.resolve(token), "no session is left behind");
    }

    // ── the same rules, through the endpoints with a real Host header ─────

    @Test
    void aGoogleSignInIsHeldToTheOrganizationsPageToo() {
        Org acme = organization();
        Org other = organization();
        String acmeHost = acme.slug() + ".hr.example.test";
        OidcController google = new OidcController(null, null, null, auth, pages, false, PUBLIC_URL);

        // A login of another Organization is refused on Acme's address and its session ends; a member's goes through.
        String outsider = auth.login(other.hr().email(), PASSWORD);
        assertEquals("signin.not_member", assertThrows(ApiException.class,
                () -> google.signedIn(new AuthService.SignIn(outsider, false), request(acmeHost, true))).code());
        assertThrows(ApiException.class, () -> auth.resolve(outsider), "the refused session is ended");
        String member = auth.login(acme.hr().email(), PASSWORD);
        assertNotNull(((java.util.Map<?, ?>) google.signedIn(new AuthService.SignIn(member, false), request(acmeHost, true)).getBody()).get("token"));

        // On the shared address anyone signs in, and a login that still needs its code is held to the page when the code is given.
        String anyone = auth.login(other.hr().email(), PASSWORD);
        assertNotNull(((java.util.Map<?, ?>) google.signedIn(new AuthService.SignIn(anyone, false), request("hr.example.test", true)).getBody()).get("token"));
        assertEquals(200, google.signedIn(new AuthService.SignIn("challenge", true), request(acmeHost, true)).getStatusCode().value());
    }

    @Test
    void aMadeUpHostOnlyPicksWhichPagesRulesApply() {
        Org acme = organization();
        Org other = organization();
        String acmeHost = acme.slug() + ".hr.example.test";

        // Sent straight to the server, with no proxy replacing the header: a browser can name any host it likes.
        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.setRemoteAddr("10.0.0.9");
        spoofed.addHeader("Host", acmeHost);
        spoofed.addHeader("X-Forwarded-Host", "hr.example.test");
        // It gets the shared page's rules: what the shared address gives anyone with the right password, nothing more.
        assertNotNull(controller.login(new AuthController.LoginRequest(other.hr().email(), PASSWORD), spoofed).get("token"));
        String identity = (String) controller.login(new AuthController.LoginRequest(other.hr().email(), PASSWORD), spoofed).get("token");
        // ... and entering Acme itself still needs membership of it, whatever host is named.
        assertThrows(ApiException.class, () -> controller.enter("Bearer " + identity, new AuthController.EnterRequest(acme.id().toString(), null), spoofed));
        // Naming Acme's host does not let an outsider in either.
        assertEquals("signin.not_member", assertThrows(ApiException.class,
                () -> controller.login(new AuthController.LoginRequest(other.hr().email(), PASSWORD), request(acmeHost, true))).code());
    }

    private static MockHttpServletRequest request(String host, boolean forwarded) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("10.0.0.1");
        if (forwarded) {
            http.addHeader("Host", "app:8080");   // as behind Caddy, which names the browser's host in X-Forwarded-Host
            http.addHeader("X-Forwarded-Host", host);
        } else {
            http.addHeader("Host", host);
        }
        return http;
    }

    @Test
    void theEndpointsApplyThePageTheRequestWasMadeAt() {
        Org acme = organization();
        Org other = organization();
        String acmeHost = acme.slug() + ".hr.example.test";

        // What the page shows, by Host and by X-Forwarded-Host.
        assertEquals(acme.id(), pageController.page(request(acmeHost, true)).getBody().organization().id());
        assertEquals(acme.id(), pageController.page(request(acmeHost + ":443", false)).getBody().organization().id());
        assertNull(pageController.page(request("hr.example.test", true)).getBody().organization());
        assertTrue(pageController.page(request("ghost.hr.example.test", true)).getBody().unknown());
        assertEquals(200, pageController.tlsCheck(acmeHost).getStatusCode().value());
        assertEquals(404, pageController.tlsCheck("ghost.hr.example.test").getStatusCode().value());

        // Sign-in: a member gets in; someone else is refused with the right password; on an unknown address nobody is.
        var in = controller.login(new AuthController.LoginRequest(acme.hr().email(), PASSWORD), request(acmeHost, true));
        assertNotNull(in.get("token"));
        ApiException refused = assertThrows(ApiException.class,
                () -> controller.login(new AuthController.LoginRequest(other.hr().email(), PASSWORD), request(acmeHost, true)));
        assertEquals("signin.not_member", refused.code());
        assertEquals("auth.invalid_credentials", assertThrows(ApiException.class,
                () -> controller.login(new AuthController.LoginRequest(other.hr().email(), "wrong-password-1"), request(acmeHost, true))).code(),
                "a wrong password gets the usual answer, so the page tells a guesser nothing");
        assertEquals("signin.unknown_address", assertThrows(ApiException.class,
                () -> controller.login(new AuthController.LoginRequest(acme.hr().email(), PASSWORD), request("ghost.hr.example.test", true))).code());

        // Entering: only Acme from Acme's page, never another Organization or the console.
        String identity = (String) controller.login(new AuthController.LoginRequest(acme.hr().email(), PASSWORD), request(acmeHost, true)).get("token");
        assertEquals("signin.not_member", assertThrows(ApiException.class, () -> controller.enter("Bearer " + identity,
                new AuthController.EnterRequest(other.id().toString(), null), request(acmeHost, true))).code());
        assertEquals("signin.not_member", assertThrows(ApiException.class, () -> controller.enter("Bearer " + identity,
                new AuthController.EnterRequest(null, true), request(acmeHost, true))).code());
        assertNotNull(controller.enter("Bearer " + identity, new AuthController.EnterRequest(acme.id().toString(), null),
                request(acmeHost, true)).get("token"));

        // On the shared address an outsider of Acme signs in as before.
        assertNotNull(controller.login(new AuthController.LoginRequest(other.hr().email(), PASSWORD), request("hr.example.test", true)).get("token"));
    }

    @Test
    void theTwoStepCodeIsHeldToThePageToo() {
        Org acme = organization();
        Org other = organization();
        SessionCtx ctx = auth.resolve(auth.login(other.hr().email(), PASSWORD));
        MfaService.Enrolment e = mfa.start(ctx);
        List<String> recovery = mfa.confirm(ctx, PASSWORD,
                Totp.code(Totp.fromBase32(e.secret()), Totp.stepAt(java.time.Instant.now())));
        String acmeHost = acme.slug() + ".hr.example.test";

        var challenged = controller.login(new AuthController.LoginRequest(other.hr().email(), PASSWORD), request(acmeHost, true));
        assertEquals(true, challenged.get("mfaRequired"));
        ApiException refused = assertThrows(ApiException.class, () -> controller.mfa(
                new AuthController.MfaRequest((String) challenged.get("challenge"), recovery.get(0)), request(acmeHost, true)));
        assertEquals("signin.not_member", refused.code(), "a right code does not open another Organization's page either");
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
