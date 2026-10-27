package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.MailSender;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.SessionCtx;

/**
 * The email outbox: written with the change that caused it, encrypted until sent, retried with growing waits, never sent
 * twice by two workers at once, and unreadable from an Organization's connection.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class MailOutboxTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static Db db;
    private static MailOutbox outbox;
    private static SessionCtx superAdmin;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    /** Records what it is asked to send, and fails on demand. */
    public static final class Recorder implements MailSender {
        final List<Mail> sent = new ArrayList<>();
        final Set<Long> seen = ConcurrentHashMap.newKeySet();
        final AtomicInteger doubles = new AtomicInteger();
        volatile Exception failWith;

        @Override
        public synchronized void send(Mail mail) throws Exception {
            if (failWith != null) {
                throw failWith;
            }
            if (!seen.add(mail.id())) {
                doubles.incrementAndGet();
            }
            sent.add(mail);
        }

        @Override
        public String describe() {
            return "recorder";
        }

        @Override
        public String from() {
            return "noreply@test";
        }
    }

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        ownerUser = env("APP_DB_OWNER_USER", "postgres");
        ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_mail with (force)");
            st.execute("create database hrms_test_mail");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the mail outbox tests");
        url = "jdbc:postgresql://" + host + "/hrms_test_mail";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        outbox = new MailOutbox(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY));
        new PlatformService(db).ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        AuthService auth = new AuthService(db, new RateLimits());
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private static String address() {
        return "person" + SEQ.incrementAndGet() + "@mail.test";
    }

    private static long enqueue(String to, String body, String dedupe) {
        db.inAuth(c -> {
            outbox.enqueue(c, null, to, "test", "Subject", body, dedupe);
            return null;
        });
        return ownerLong("select id from mail.outbox where to_email = '" + to + "' order by id desc limit 1");
    }

    private static long ownerLong(String sql) {
        return db.inOwner(c -> ((Number) Sql.one(c, sql).get(sql.contains(" as ") ? "n" : "id")).longValue());
    }

    private static String ownerText(String sql, String column) {
        return db.inOwner(c -> {
            var row = Sql.one(c, sql);
            return row == null ? null : (String) row.get(column);
        });
    }

    private static void ownerExec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Moves a queued row's next attempt to now, as if its wait had passed. */
    private static void waitPassed(long id) throws SQLException {
        ownerExec("update mail.outbox set next_attempt_at = now() where id = " + id);
    }

    @Test
    void aMessageIsSentOnceAndItsBodyIsGoneAfterwardsAndEncryptedBefore() throws Exception {
        Recorder sender = new Recorder();
        String to = address();
        long id = enqueue(to, "Click https://x.test/reset?t=secret-token", null);
        // at rest the link is not readable
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select body_enc from mail.outbox where id = " + id)) {
            rs.next();
            assertFalse(new String(rs.getBytes(1), java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret-token"));
        }
        outbox.deliverDue(sender);
        assertEquals(1, sender.sent.stream().filter(m -> m.to().equals(to)).count());
        assertTrue(sender.sent.stream().filter(m -> m.to().equals(to)).findFirst().orElseThrow().body().contains("secret-token"));
        assertEquals("sent", ownerText("select status from mail.outbox where id = " + id, "status"));
        assertEquals(0L, ownerLong("select count(*) as n from mail.outbox where id = " + id + " and body_enc is not null"));
        outbox.deliverDue(sender);
        assertEquals(1, sender.sent.stream().filter(m -> m.to().equals(to)).count(), "a sent message is not sent again");
    }

    @Test
    void theSameDedupeKeyAddsNothingTwice() {
        String to = address();
        String key = "dedupe-" + SEQ.incrementAndGet();
        enqueue(to, "first", key);
        db.inAuth(c -> {
            assertFalse(outbox.enqueue(c, null, to, "test", "Subject", "second", key));
            return null;
        });
        assertEquals(1L, ownerLong("select count(*) as n from mail.outbox where to_email = '" + to + "'"));
    }

    @Test
    void aSuppressedAddressIsNeverMailed() throws Exception {
        Recorder sender = new Recorder();
        String to = address();
        outbox.suppress(superAdmin, to, "bounce");
        long id = enqueue(to, "body", null);
        assertEquals("suppressed", ownerText("select status from mail.outbox where id = " + id, "status"));
        outbox.deliverDue(sender);
        assertTrue(sender.sent.stream().noneMatch(m -> m.to().equals(to)));
        // suppressed after queuing: caught when claimed
        String later = address();
        long id2 = enqueue(later, "body", null);
        outbox.suppress(superAdmin, later, "manual");
        outbox.deliverDue(sender);
        assertTrue(sender.sent.stream().noneMatch(m -> m.to().equals(later)));
        assertEquals("suppressed", ownerText("select status from mail.outbox where id = " + id2, "status"));
        // lifted, new mail goes
        outbox.unsuppress(superAdmin, later);
        enqueue(later, "again", null);
        outbox.deliverDue(sender);
        assertEquals(1, sender.sent.stream().filter(m -> m.to().equals(later)).count());
    }

    @Test
    void aFailureWaitsLongerEachTimeAndGivesUpAfterTheLastAttempt() throws Exception {
        Recorder sender = new Recorder();
        sender.failWith = new java.io.IOException("connection refused");
        String to = address();
        long id = enqueue(to, "body", null);
        long[] waitsSeconds = {60, 300, 1800, 7200, 21600};
        for (int attempt = 1; attempt <= 5; attempt++) {
            outbox.deliverDue(sender);
            assertEquals("queued", ownerText("select status from mail.outbox where id = " + id, "status"));
            long wait = ownerLong("select extract(epoch from next_attempt_at - now())::bigint as n from mail.outbox where id = " + id);
            assertTrue(Math.abs(wait - waitsSeconds[attempt - 1]) <= 5, "attempt " + attempt + " waits about " + waitsSeconds[attempt - 1] + "s, was " + wait);
            outbox.deliverDue(sender);   // not due yet: nothing happens
            assertEquals(attempt, ownerLong("select attempts as n from mail.outbox where id = " + id));
            waitPassed(id);
        }
        outbox.deliverDue(sender);   // the sixth
        assertEquals("failed", ownerText("select status from mail.outbox where id = " + id, "status"));
        assertTrue(ownerText("select last_error from mail.outbox where id = " + id, "lastError").contains("connection refused"));
        // a person puts it back and it goes
        sender.failWith = null;
        outbox.retry(superAdmin, id);
        outbox.deliverDue(sender);
        assertEquals("sent", ownerText("select status from mail.outbox where id = " + id, "status"));
    }

    @Test
    void aRefusalForGoodFailsAtOnce() throws Exception {
        Recorder sender = new Recorder();
        sender.failWith = new MailSender.Rejected("550 no such user");
        long id = enqueue(address(), "body", null);
        outbox.deliverDue(sender);
        assertEquals("failed", ownerText("select status from mail.outbox where id = " + id, "status"));
        assertEquals(1L, ownerLong("select attempts as n from mail.outbox where id = " + id));
    }

    @Test
    void aLateResultFromAWorkerThatLostItsClaimIsDropped() throws Exception {
        String to = address();
        long id = enqueue(to, "body", null);
        var slow = outbox.deliverDue(new MailSender() {
            @Override
            public void send(Mail mail) throws Exception {
                // while this worker is "sending", its claim runs out and another worker takes and finishes the message
                ownerExec("update mail.outbox set locked_until = now() - interval '1 minute' where id = " + id);
                Recorder other = new Recorder();
                outbox.deliverDue(other);
                assertEquals(1, other.sent.stream().filter(m -> m.to().equals(to)).count());
                throw new java.io.IOException("timed out after the other worker sent it");
            }

            @Override
            public String describe() {
                return "slow";
            }

            @Override
            public String from() {
                return "noreply@test";
            }
        });
        assertEquals(0, slow);
        assertEquals("sent", ownerText("select status from mail.outbox where id = " + id, "status"), "the late failure must not undo it");
    }

    @Test
    void aMessageLeftSendingByADeadWorkerIsPickedUpAgain() throws Exception {
        Recorder sender = new Recorder();
        String to = address();
        long id = enqueue(to, "body", null);
        ownerExec("update mail.outbox set status = 'sending', attempts = 1, locked_until = now() - interval '1 minute' where id = " + id);
        outbox.deliverDue(sender);
        assertEquals(1, sender.sent.stream().filter(m -> m.to().equals(to)).count());
        // still locked: left alone
        String other = address();
        long id2 = enqueue(other, "body", null);
        ownerExec("update mail.outbox set status = 'sending', attempts = 1, locked_until = now() + interval '4 minutes' where id = " + id2);
        outbox.deliverDue(sender);
        assertTrue(sender.sent.stream().noneMatch(m -> m.to().equals(other)));
    }

    @Test
    void twoWorkersAtOnceNeverSendTheSameMessageTwice() throws Exception {
        Recorder sender = new Recorder();
        long before = ownerLong("select coalesce(max(id), 0) as n from mail.outbox");
        for (int i = 0; i < 60; i++) {
            enqueue(address(), "body", null);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            work.add(pool.submit(() -> {
                go.await();
                for (int round = 0; round < 6; round++) {
                    outbox.deliverDue(sender);
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : work) {
            f.get();
        }
        pool.shutdown();
        assertEquals(0, sender.doubles.get());
        assertEquals(0L, ownerLong("select count(*) as n from mail.outbox where id > " + before + " and status in ('queued', 'sending')"));
        assertEquals(60L, ownerLong("select count(*) as n from mail.outbox where id > " + before + " and status = 'sent'"));
    }

    @Test
    void anOrganizationConnectionCanAddMailButNeverReadIt() throws Exception {
        for (String role : List.of("app_tenant", "app_auth")) {
            try (Connection c = DriverManager.getConnection(url, role, ROLE_PASSWORD); Statement st = c.createStatement()) {
                for (String sql : List.of("select * from mail.outbox", "select dedupe_key from mail.outbox", "select * from mail.suppression",
                        "insert into mail.outbox (to_email, template, subject) values ('x@mail.test', 't', 's')",
                        "insert into mail.suppression (email, reason) values ('x@mail.test', 'manual')")) {
                    SQLException denied = assertThrows(SQLException.class, () -> st.execute(sql), role + ": " + sql);
                    assertEquals("42501", denied.getSQLState());
                }
            }
        }
        // the function is the way in, and it refuses mail claiming another Organization
        try (Connection c = DriverManager.getConnection(url, "app_tenant", ROLE_PASSWORD); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("select set_config('app.tenant_id', '00000000-0000-0000-0000-000000000001', true)");
            SQLException other = assertThrows(SQLException.class, () -> st.execute(
                    "select mail.enqueue('00000000-0000-0000-0000-000000000002', 'x@mail.test', 't', 's', 'b'::bytea, null)"));
            assertEquals("42501", other.getSQLState());
        }
    }

    @Test
    void onlyAPlatformSuperAdminChangesMailAndOthersAreRefused() {
        SessionCtx notPlatform = new SessionCtx(null, null, "a@b.test", "identity", null, null, null, null, null, null, null, null,
                List.of(), Set.of());
        assertEquals(403, assertThrows(ApiException.class, () -> outbox.overview(notPlatform, null)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> outbox.suppress(notPlatform, "x@mail.test", "manual")).status());
        assertEquals(403, assertThrows(ApiException.class, () -> outbox.sendTest(notPlatform, "x@mail.test")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> outbox.suppress(superAdmin, "not-an-address", "manual")).status());
        assertEquals(409, assertThrows(ApiException.class, () -> outbox.retry(superAdmin, ownerLong("select id from mail.outbox order by id limit 1"))).status());
        assertNull(null);
    }

    @Test
    void theOverviewCountsMessagesAndListsFailuresWithoutBodies() throws Exception {
        Recorder sender = new Recorder();
        sender.failWith = new MailSender.Rejected("550 nope");
        String to = address();
        enqueue(to, "private body", null);
        outbox.deliverDue(sender);
        MailOutbox.Overview overview = outbox.overview(superAdmin, sender);
        assertTrue(overview.configured());
        assertEquals("recorder", overview.server());
        assertTrue(overview.counts().failed() >= 1);
        assertTrue(overview.failures().stream().anyMatch(r -> r.to().equalsIgnoreCase(to) && r.lastError().contains("550")));
        assertFalse(overview.toString().contains("private body"));
        assertFalse(outbox.overview(superAdmin, null).configured());
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
