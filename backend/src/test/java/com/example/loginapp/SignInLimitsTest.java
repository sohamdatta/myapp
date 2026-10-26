package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
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
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UserService;

/**
 * Sign-in limits: an address with no account and one with an account must be refused the same way, at the same
 * point; an invitation link must be no way round the limits; one client address cannot try without end.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class SignInLimitsTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static Db db;
    private static PlatformService platform;
    private static UserService users;
    private static SessionCtx superAdmin;

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_signin_limits with (force)");
            st.execute("create database hrms_test_signin_limits");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the sign-in limit tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_signin_limits";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        platform = new PlatformService(db);
        users = new UserService(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        AuthService plain = new AuthService(db, new RateLimits());
        superAdmin = plain.resolve(plain.enterConsole(plain.resolve(plain.login("root@platform.test", PASSWORD))));
    }

    /** An Organization whose owner has accepted the invitation; returns the owner's email. */
    private static String owner(InvitationService invitations) {
        String slug = "limits-" + SEQ.incrementAndGet();
        String email = "owner@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", email);
        invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return email;
    }

    private static ApiException refusal(org.junit.jupiter.api.function.Executable work) {
        return assertThrows(ApiException.class, work);
    }

    @Test
    void anAccountAndAStrangerAreRefusedTheSameWayAtTheSamePoint() {
        RateLimits limits = new RateLimits();
        AuthService auth = new AuthService(db, limits);
        String real = owner(new InvitationService(db, limits));
        String stranger = "nobody-" + SEQ.incrementAndGet() + "@nowhere.test";
        int after = 5;   // the platform default for auth.lockout.after_failures; asserted below so a changed default is noticed
        for (int i = 0; i < after; i++) {
            assertEquals("auth.invalid_credentials", refusal(() -> auth.login(real, "wrong-password", "10.0.0.1")).code());
            assertEquals("auth.invalid_credentials", refusal(() -> auth.login(stranger, "wrong-password", "10.0.0.2")).code());
        }
        ApiException forReal = refusal(() -> auth.login(real, "wrong-password", "10.0.0.1"));
        ApiException forStranger = refusal(() -> auth.login(stranger, "wrong-password", "10.0.0.2"));
        assertEquals(429, forReal.status());
        assertEquals(forReal.status(), forStranger.status());
        assertEquals(forReal.code(), forStranger.code());
        assertEquals(forReal.getMessage(), forStranger.getMessage());
        // even the right password is refused while locked, for the account; and there is nothing to learn from that
        ApiException right = refusal(() -> auth.login(real, PASSWORD, "10.0.0.1"));
        assertEquals(429, right.status());
        assertFalse(right.getMessage().matches(".*\\d+ minutes.*"), "the message must not say how long: that differs by account");
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        RateLimits limits = new RateLimits();
        AuthService auth = new AuthService(db, limits);
        String real = owner(new InvitationService(db, limits));
        for (int i = 0; i < 3; i++) {
            refusal(() -> auth.login(real, "wrong-password", "10.0.0.3"));
        }
        assertNotNull(auth.login(real, PASSWORD, "10.0.0.3"));
        for (int i = 0; i < 3; i++) {
            refusal(() -> auth.login(real, "wrong-password", "10.0.0.3"));   // would be the 4th-6th, and refused 429, without the clear
        }
        assertEquals("auth.invalid_credentials", refusal(() -> auth.login(real, "wrong-password", "10.0.0.3")).code());
    }

    @Test
    void oneAddressCannotTryEndlesslyAcrossManyEmails() {
        RateLimits limits = new RateLimits();
        AuthService auth = new AuthService(db, limits);
        for (int i = 0; i < 100; i++) {
            assertEquals("auth.invalid_credentials", refusal(() -> auth.login("x" + SEQ.incrementAndGet() + "@nowhere.test", "wrong", "10.9.9.9")).code());
        }
        assertEquals(429, refusal(() -> auth.login("another@nowhere.test", "wrong", "10.9.9.9")).status());
        // a different address is unaffected
        assertEquals("auth.invalid_credentials", refusal(() -> auth.login("another@nowhere.test", "wrong", "10.9.9.8")).code());
    }

    @Test
    void anInvitationLinkIsNoWayRoundTheSignInLimits() {
        RateLimits limits = new RateLimits();
        AuthService auth = new AuthService(db, limits);
        InvitationService invitations = new InvitationService(db, limits);
        String email = owner(invitations);
        // a second Organization invites the same person: they already have an account, so accepting asks for its password
        String slug = "limits-second-" + SEQ.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", email);
        String link = (String) created.get("invitationToken");
        for (int i = 0; i < 5; i++) {
            assertEquals("invitation.email_mismatch", refusal(() -> invitations.accept(link, "wrong-password", null, "10.1.1.1")).code());
        }
        // the account is now locked for sign-in too, and the link refuses in the same words
        assertEquals(429, refusal(() -> auth.login(email, PASSWORD, "10.1.1.2")).status());
        assertEquals(429, refusal(() -> invitations.accept(link, PASSWORD, null, "10.1.1.3")).status());
    }

    @Test
    void invitationLinksCannotBeHammeredFromOneAddress() {
        RateLimits limits = new RateLimits();
        InvitationService invitations = new InvitationService(db, limits);
        for (int i = 0; i < 30; i++) {
            assertEquals(410, refusal(() -> invitations.accept("not-a-token-" + SEQ.incrementAndGet(), PASSWORD, null, "10.2.2.2")).status());
        }
        assertEquals(429, refusal(() -> invitations.accept("not-a-token", PASSWORD, null, "10.2.2.2")).status());
        assertEquals(410, refusal(() -> invitations.accept("not-a-token", PASSWORD, null, "10.2.2.3")).status());
        for (int i = 0; i < 120; i++) {
            assertEquals(410, refusal(() -> { invitations.checkPreviewAllowed("10.3.3.3"); invitations.preview("not-a-token-" + SEQ.incrementAndGet()); }).status());
        }
        assertEquals(429, refusal(() -> invitations.checkPreviewAllowed("10.3.3.3")).status());
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
