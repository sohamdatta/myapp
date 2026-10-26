package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ClientErrors;
import com.example.loginapp.core.ClientErrors.Report;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Sql;

/**
 * What the browser reports when a page crashes: kept short and free of personal data, limited per address,
 * and cleared after 90 days.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class ClientErrorsTest {

    private static final String ROLE_PASSWORD = "test-role-password";

    private static Db db;
    private static final AtomicLong NOW = new AtomicLong(Instant.parse("2026-11-02T10:00:00Z").toEpochMilli());
    private static final Clock CLOCK = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(NOW.get());
        }
    };

    private ClientErrors errors;

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_client_errors with (force)");
            st.execute("create database hrms_test_client_errors");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the client error tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_client_errors";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
    }

    @BeforeEach
    void fresh() {
        db.inOwner(c -> Sql.update(c, "delete from auth.client_error"));
        errors = new ClientErrors(db, CLOCK);   // its own limits for each test
    }

    private static List<Map<String, Object>> rows() {
        return db.inOwner(c -> Sql.query(c, "select page, region, message, build from auth.client_error order by id"));
    }

    @Test
    void aReportIsKeptWithoutAnythingPersonal() {
        assertTrue(errors.record("10.0.0.1", new Report("people", "page",
                "Cannot read 'name' of undefined for asha.rao@globex.test, member 7e1c4b0e-3f0a-4c52-9f7d-0a6c1d7b2e11, "
                        + "PAN ABCDE1234F, account 123456789012, token abcdefghijklmnopqrstuvwxyz0123456789ABCDEF", "1.4.2")));
        Map<String, Object> row = rows().get(0);
        assertEquals("people", row.get("page"));
        assertEquals("page", row.get("region"));
        assertEquals("1.4.2", row.get("build"));
        String message = (String) row.get("message");
        assertFalse(message.contains("asha.rao"), message);
        assertFalse(message.contains("7e1c4b0e"), message);
        assertFalse(message.contains("123456789012") || message.contains("ABCDE1234F"), message);
        assertFalse(message.contains("abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"), message);
        assertTrue(message.contains("[email]") && message.contains("[id]") && message.contains("[number]") && message.contains("[pan]") && message.contains("[token]"), message);
    }

    @Test
    void whatDoesNotLookRightIsReplacedOrDropped() {
        // a page that is not an id, a region with a slash, a build with spaces and a message of a megabyte
        assertTrue(errors.record("10.0.0.1", new Report("https://x.test/a?token=1", "../etc", "boom\nsecond line\t", "build 1 <script>")));
        assertTrue(errors.record("10.0.0.1", new Report("leave", "nav", "word ".repeat(200_000), null)));
        List<Map<String, Object>> rows = rows();
        assertEquals("unknown", rows.get(0).get("page"));
        assertEquals("unknown", rows.get(0).get("region"));
        assertEquals("boom second line", rows.get(0).get("message"));
        assertNull(rows.get(0).get("build"));
        assertEquals(300, ((String) rows.get(1).get("message")).length());
        // nothing to say, nothing kept
        assertFalse(errors.record("10.0.0.1", new Report("leave", "nav", "  \n ", null)));
        assertFalse(errors.record("10.0.0.1", null));
        assertEquals(2, rows().size());
    }

    @Test
    void anAddressMayReportAFewTimesAMinute() {
        for (int i = 0; i < ClientErrors.PER_ADDRESS_PER_MINUTE; i++) {
            assertTrue(errors.record("10.0.0.2", new Report("people", "page", "error " + i, null)), "report " + i);
        }
        assertFalse(errors.record("10.0.0.2", new Report("people", "page", "one too many", null)));
        assertTrue(errors.record("10.0.0.3", new Report("people", "page", "another address", null)));
        assertEquals(ClientErrors.PER_ADDRESS_PER_MINUTE + 1, rows().size());
        NOW.addAndGet(61_000);
        assertTrue(errors.record("10.0.0.2", new Report("people", "page", "a minute later", null)));
    }

    @Test
    void everyoneTogetherHasACeiling() {
        int kept = 0;
        for (int i = 0; i < ClientErrors.EVERYONE_PER_MINUTE + 50; i++) {
            if (errors.record("10.1." + (i / 250) + "." + (i % 250), new Report("people", "page", "error " + i, null))) {
                kept++;
            }
        }
        assertEquals(ClientErrors.EVERYONE_PER_MINUTE, kept);
    }

    @Test
    void rowsOlderThanNinetyDaysAreClearedByTheNextReport() {
        db.inOwner(c -> Sql.update(c, "insert into auth.client_error (occurred_at, page, region, message) "
                + "values (now() - interval '91 days', 'people', 'page', 'old'), (now() - interval '10 days', 'people', 'page', 'recent')"));
        assertTrue(errors.record("10.0.0.4", new Report("people", "page", "new", null)));
        assertEquals(java.util.Set.of("recent", "new"), rows().stream().map(r -> r.get("message")).collect(java.util.stream.Collectors.toSet()));
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
