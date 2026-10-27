package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PasswordService;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;

/**
 * Forgot, reset and change password: the same answer for every address, one use per link, every other session ended, and
 * the limits. Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class PasswordServiceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "another-long-password-1";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final Pattern LINK = Pattern.compile("/\\?reset=([A-Za-z0-9_-]+)");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static Db db;
    private static PlatformService platform;
    private static MailOutbox outbox;
    private static SessionCtx superAdmin;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        ownerUser = env("APP_DB_OWNER_USER", "postgres");
        ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_password with (force)");
            st.execute("create database hrms_test_password");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the password tests");
        url = "jdbc:postgresql://" + host + "/hrms_test_password";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        platform = new PlatformService(db);
        outbox = new MailOutbox(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY));
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        AuthService plain = new AuthService(db, new RateLimits());
        superAdmin = plain.resolve(plain.enterConsole(plain.resolve(plain.login("root@platform.test", PASSWORD))));
    }

    private final RateLimits limits = new RateLimits();
    private final AuthService auth = new AuthService(db, limits);
    private final PasswordService passwords = new PasswordService(db, outbox, limits, "https://hrms.test/");

    /** An Organization owner with a login; returns the email. */
    private String owner() {
        String slug = "pw-" + SEQ.incrementAndGet();
        String email = "owner@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", email);
        new InvitationService(db, limits).accept((String) created.get("invitationToken"), PASSWORD, null);
        return email;
    }


    private String lastLinkTokenSentTo(String email) {
        passwords.sendWaitingLinks();
        MailOutboxTest.Recorder sender = new MailOutboxTest.Recorder();
        outbox.deliverDue(sender);
        String token = null;
        for (var mail : sender.sent) {
            if (mail.to().equalsIgnoreCase(email) && mail.subject().contains("Reset")) {
                Matcher m = LINK.matcher(mail.body());
                assertTrue(m.find(), "the mail carries a link");
                assertTrue(mail.body().contains("https://hrms.test/?reset="), "one slash, from the public address");
                token = m.group(1);
            }
        }
        return token;
    }

    private static int count(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void ownerExec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static ApiException refusal(org.junit.jupiter.api.function.Executable work) {
        return assertThrows(ApiException.class, work);
    }

    @Test
    void everyAddressIsAnsweredTheSameAndOnlyAnAccountGetsALink() throws Exception {
        String real = owner();
        String stranger = "nobody-" + SEQ.incrementAndGet() + "@nowhere.test";
        passwords.requestReset(real, "10.0.1.1");
        passwords.requestReset(stranger, "10.0.1.1");
        passwords.requestReset("not an address", "10.0.1.1");
        passwords.requestReset(null, "10.0.1.1");
        assertNotNull(lastLinkTokenSentTo(real));
        assertEquals(0, count("select count(*) from mail.outbox where to_email = '" + stranger + "'"));
        // the request itself left the same trace for both: one row, handled later
        assertEquals(1, count("select count(*) from auth.reset_request where email = '" + stranger + "'"));
    }

    @Test
    void aLinkWorksOnceAndEndsEverySessionAndTheLock() throws Exception {
        String email = owner();
        String session = auth.login(email, PASSWORD);   // another session, signed in before
        for (int i = 0; i < 5; i++) {
            refusal(() -> auth.login(email, "wrong-password", "10.0.2." + (1 + 0)));
        }
        assertEquals(429, refusal(() -> auth.login(email, PASSWORD)).status());
        passwords.requestReset(email, "10.0.2.9");
        String token = lastLinkTokenSentTo(email);
        passwords.reset(token, NEW_PASSWORD, "10.0.2.9");
        assertEquals(401, refusal(() -> auth.resolve(session)).status(), "the older session is ended");
        assertEquals(401, refusal(() -> auth.login(email, PASSWORD)).status(), "the old password is gone");
        assertNotNull(auth.login(email, NEW_PASSWORD), "the lock ended with the reset, and the new password works");
        ApiException again = refusal(() -> passwords.reset(token, "yet-another-long-pass-2", "10.0.2.9"));
        assertEquals(410, again.status());
        assertEquals("auth.reset_invalid", again.code());
    }

    @Test
    void aNewRequestRetiresTheOlderLinkAndAnExpiredLinkFails() throws Exception {
        String email = owner();
        passwords.requestReset(email, "10.0.3.1");
        String first = lastLinkTokenSentTo(email);
        passwords.requestReset(email, "10.0.3.2");
        String second = lastLinkTokenSentTo(email);
        assertEquals(410, refusal(() -> passwords.reset(first, NEW_PASSWORD, "10.0.3.3")).status());
        ownerExec("update auth.password_reset set expires_at = now() - interval '1 minute' where used_at is null and identity_id = "
                + "(select id from auth.identity where email = '" + email + "')");
        assertEquals(410, refusal(() -> passwords.reset(second, NEW_PASSWORD, "10.0.3.3")).status());
        assertNotNull(auth.login(email, PASSWORD), "nothing changed");
    }

    @Test
    void aWeakPasswordIsRefusedAndTheLinkSurvivesIt() throws Exception {
        String email = owner();
        passwords.requestReset(email, "10.0.4.1");
        String token = lastLinkTokenSentTo(email);
        assertEquals(400, refusal(() -> passwords.reset(token, "short", "10.0.4.2")).status());
        passwords.reset(token, NEW_PASSWORD, "10.0.4.2");
        assertNotNull(auth.login(email, NEW_PASSWORD));
    }

    @Test
    void aSuspendedAccountIsMailedNothingAndAChangeNoticeGoesOut() throws Exception {
        String email = owner();
        ownerExec("update auth.identity set status = 'disabled' where email = '" + email + "'");
        passwords.requestReset(email, "10.0.5.1");
        assertEquals(null, lastLinkTokenSentTo(email));
        ownerExec("update auth.identity set status = 'active' where email = '" + email + "'");
        passwords.requestReset(email, "10.0.5.2");
        String token = lastLinkTokenSentTo(email);
        passwords.reset(token, NEW_PASSWORD, "10.0.5.3");
        MailOutboxTest.Recorder sender = new MailOutboxTest.Recorder();
        outbox.deliverDue(sender);
        assertTrue(sender.sent.stream().anyMatch(m -> m.to().equalsIgnoreCase(email) && m.subject().contains("changed")),
                "the person is told their password changed");
    }

    @Test
    void askingTooOftenIsLimitedPerAddressAndQuietlyIgnoredPerEmail() throws Exception {
        String email = owner();
        for (int i = 0; i < 4; i++) {
            passwords.requestReset(email, "10.0.6." + (i + 1));
        }
        assertEquals(3, count("select count(*) from auth.reset_request where email = '" + email + "'"), "the fourth in the hour is ignored, with no error");
        for (int i = 0; i < 10; i++) {
            passwords.requestReset("x" + SEQ.incrementAndGet() + "@nowhere.test", "10.0.7.7");
        }
        assertEquals(429, refusal(() -> passwords.requestReset("another@nowhere.test", "10.0.7.7")).status());
    }

    @Test
    void guessingTokensFromOneAddressIsStopped() {
        for (int i = 0; i < 20; i++) {
            assertEquals(410, refusal(() -> passwords.reset("not-a-token-" + SEQ.incrementAndGet(), NEW_PASSWORD, "10.0.8.8")).status());
        }
        assertEquals(429, refusal(() -> passwords.reset("not-a-token", NEW_PASSWORD, "10.0.8.8")).status());
        assertEquals(410, refusal(() -> passwords.reset("not-a-token", NEW_PASSWORD, "10.0.8.9")).status());
    }

    @Test
    void changingNeedsTheCurrentPasswordWhichCountsTowardTheLockAndKeepsOnlyThisSession() throws Exception {
        String email = owner();
        String mine = auth.login(email, PASSWORD);
        String other = auth.login(email, PASSWORD);
        SessionCtx ctx = auth.resolve(mine);
        assertEquals(403, refusal(() -> passwords.change(ctx, "wrong-password", NEW_PASSWORD)).status());
        assertEquals(400, refusal(() -> passwords.change(ctx, PASSWORD, "short")).status());
        assertEquals(400, refusal(() -> passwords.change(ctx, PASSWORD, PASSWORD)).status());
        passwords.change(ctx, PASSWORD, NEW_PASSWORD);
        assertNotNull(auth.resolve(mine), "this session stays");
        assertEquals(401, refusal(() -> auth.resolve(other)).status(), "every other one ends");
        assertNotNull(auth.login(email, NEW_PASSWORD));
        // wrong current passwords add to the sign-in lock
        SessionCtx again = auth.resolve(mine);
        for (int i = 0; i < 4; i++) {
            assertEquals(403, refusal(() -> passwords.change(again, "wrong-password", "third-long-password-3")).status());
        }
        assertEquals(403, refusal(() -> passwords.change(again, "wrong-password", "third-long-password-3")).status());
        assertEquals(429, refusal(() -> passwords.change(again, NEW_PASSWORD, "third-long-password-3")).status());
        assertEquals(429, refusal(() -> auth.login(email, NEW_PASSWORD)).status());
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
