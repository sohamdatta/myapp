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
import com.example.loginapp.core.EmailChangeService;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.SessionCtx;

/**
 * Forgot, reset and change password: the same answer for every address, one use per link, every other session ended, and
 * the limits. Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class EmailChangeTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final java.util.List<com.example.loginapp.core.MailSender.Mail> MAIL = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            st.execute("drop database if exists hrms_test_email with (force)");
            st.execute("create database hrms_test_email");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the email change tests");
        url = "jdbc:postgresql://" + host + "/hrms_test_email";
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
    private final EmailChangeService changes = new EmailChangeService(db, outbox, limits, "https://hrms.test/");

    private String owner() {
        String slug = "em-" + SEQ.incrementAndGet();
        String email = "owner@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", email);
        new InvitationService(db, limits).accept((String) created.get("invitationToken"), PASSWORD, null);
        return email;
    }

    private static String fresh() {
        return "new" + SEQ.incrementAndGet() + "@elsewhere.test";
    }

    /** The token in the last mail to this address whose body holds the given link prefix, or null. */
    private static String linkTo(String email, String prefix) {
        MailOutboxTest.Recorder sender = new MailOutboxTest.Recorder();
        outbox.deliverDue(sender);
        MAIL.addAll(sender.sent);
        String token = null;
        for (var mail : MAIL) {
            if (mail.to().equalsIgnoreCase(email)) {
                Matcher m = Pattern.compile(Pattern.quote(prefix) + "([A-Za-z0-9_-]+)").matcher(mail.body());
                if (m.find()) {
                    token = m.group(1);
                }
            }
        }
        return token;
    }

    private static boolean mailedTo(String email, String subjectPart) {
        linkTo(email, "zzz");
        return MAIL.stream().anyMatch(m -> m.to().equalsIgnoreCase(email) && m.subject().contains(subjectPart));
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
    void theNewAddressConfirmsAndEverySessionEnds() throws Exception {
        String email = owner();
        String target = fresh();
        String session = auth.login(email, PASSWORD);
        SessionCtx ctx = auth.resolve(session);
        changes.request(ctx, target, PASSWORD);
        assertTrue(mailedTo(email, "change of your sign-in address"), "the old address hears of the request");
        String link = linkTo(target, "https://hrms.test/?confirm-email=");
        assertNotNull(link);
        assertEquals("Incorrect email or password", refusal(() -> auth.login(target, PASSWORD)).getMessage(), "nothing has changed yet");
        changes.confirm(link, "10.9.0.1");
        assertEquals(401, refusal(() -> auth.resolve(session)).status(), "every session ended");
        assertEquals(401, refusal(() -> auth.login(email, PASSWORD)).status(), "the old address no longer signs in");
        assertNotNull(auth.login(target, PASSWORD));
        assertEquals(400, refusal(() -> changes.confirm(link, "10.9.0.1")).status(), "a link is used once");
        assertNotNull(linkTo(email, "https://hrms.test/?undo-email="), "the old address gets the way back");
    }

    @Test
    void undoPutsTheAddressBackWithinSevenDaysOnly() throws Exception {
        String email = owner();
        String target = fresh();
        changes.request(auth.resolve(auth.login(email, PASSWORD)), target, PASSWORD);
        changes.confirm(linkTo(target, "https://hrms.test/?confirm-email="), "10.9.1.1");
        String undo = linkTo(email, "https://hrms.test/?undo-email=");
        String signedIn = auth.login(target, PASSWORD);
        changes.undo(undo, "10.9.1.1");
        assertEquals(401, refusal(() -> auth.resolve(signedIn)).status(), "sessions end again");
        assertNotNull(auth.login(email, PASSWORD));
        assertEquals(401, refusal(() -> auth.login(target, PASSWORD)).status());
        assertEquals(400, refusal(() -> changes.undo(undo, "10.9.1.1")).status(), "once");
        assertTrue(mailedTo(email, "put back"));

        // a second change, undone too late
        String again = fresh();
        changes.request(auth.resolve(auth.login(email, PASSWORD)), again, PASSWORD);
        changes.confirm(linkTo(again, "https://hrms.test/?confirm-email="), "10.9.1.2");
        String late = linkTo(email, "https://hrms.test/?undo-email=");
        ownerExec("update auth.email_change set undo_until = now() - interval '1 minute' where undo_hash is not null and undone_at is null");
        assertEquals(400, refusal(() -> changes.undo(late, "10.9.1.2")).status());
        assertNotNull(auth.login(again, PASSWORD), "it stays changed");
    }

    @Test
    void anAddressThatHasALoginIsAnsweredTheSameAndGetsNothing() throws Exception {
        String email = owner();
        String taken = owner();
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        changes.request(ctx, taken, PASSWORD);   // no error, so it does not tell
        assertEquals(0, linkCount(taken));
        assertEquals(0, rows("select count(*) from auth.email_change e join auth.identity i on i.id = e.identity_id where i.email = '" + email + "'"));
    }

    private static int linkCount(String email) {
        return linkTo(email, "https://hrms.test/?confirm-email=") == null ? 0 : 1;
    }

    private static int rows(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void theRequestNeedsThePasswordAndCountsWrongOnes() {
        String email = owner();
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        assertEquals(403, refusal(() -> changes.request(ctx, fresh(), "wrong-password-here")).status());
        assertEquals(400, refusal(() -> changes.request(ctx, "not-an-address", PASSWORD)).status());
        assertEquals(400, refusal(() -> changes.request(ctx, email.toUpperCase(), PASSWORD)).status(), "the same address");
        for (int i = 0; i < 5; i++) {
            refusal(() -> changes.request(ctx, fresh(), "wrong-password-here"));
        }
        assertEquals(429, refusal(() -> changes.request(ctx, fresh(), PASSWORD)).status(), "too many wrong passwords lock the login");
    }

    @Test
    void aNewRequestCancelsTheEarlierOneAndAnOldLinkExpires() throws Exception {
        String email = owner();
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        String first = fresh();
        String second = fresh();
        changes.request(ctx, first, PASSWORD);
        String firstLink = linkTo(first, "https://hrms.test/?confirm-email=");
        changes.request(ctx, second, PASSWORD);
        assertEquals(400, refusal(() -> changes.confirm(firstLink, "10.9.3.1")).status(), "replaced");
        String secondLink = linkTo(second, "https://hrms.test/?confirm-email=");
        ownerExec("update auth.email_change set expires_at = now() - interval '1 minute' where confirmed_at is null");
        assertEquals(400, refusal(() -> changes.confirm(secondLink, "10.9.3.2")).status(), "24 hours");
        assertNotNull(auth.login(email, PASSWORD), "nothing moved");
    }

    @Test
    void wrongLinksAreLimitedPerAddress() {
        for (int i = 0; i < 20; i++) {
            int n = i;
            assertEquals(400, refusal(() -> changes.confirm("not-a-link-" + n, "10.9.4.4")).status());
        }
        assertEquals(429, refusal(() -> changes.confirm("not-a-link", "10.9.4.4")).status());
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
