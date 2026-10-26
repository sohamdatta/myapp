package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.Sql;

/**
 * A session's last-seen time is what the idle limit is measured from. It is written at most once a
 * minute, not on every request. Needs PostgreSQL as described in {@link UserModuleTest}.
 */
class SessionTouchTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";

    private static Db db;
    private static AuthService auth;
    private static boolean reachable;

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = System.getenv("APP_TEST_DB_HOST") == null ? "localhost:5432" : System.getenv("APP_TEST_DB_HOST");
        String ownerUser = System.getenv("APP_DB_OWNER_USER") == null ? "postgres" : System.getenv("APP_DB_OWNER_USER");
        String ownerPassword = System.getenv("APP_DB_OWNER_PASSWORD") == null ? "postgres" : System.getenv("APP_DB_OWNER_PASSWORD");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_session_touch with (force)");
            st.execute("create database hrms_test_session_touch");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        if (!reachable) {
            return;
        }
        String url = "jdbc:postgresql://" + host + "/hrms_test_session_touch";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        new PlatformService(db).ensureFirstSuperAdmin("root@touch.test", PASSWORD);
    }

    @Test
    void lastSeenIsWrittenAtMostOncePerMinute() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        String token = auth.login("root@touch.test", PASSWORD);

        auth.resolve(token);
        Instant first = lastSeen();

        // Seen a moment ago: another request leaves the stored time alone.
        auth.resolve(token);
        auth.resolve(token);
        assertEquals(first, lastSeen(), "a request within the minute must not rewrite last_seen_at");

        // Last seen five minutes ago: the next request brings it up to date, and the session still works.
        db.inOwner(c -> Sql.update(c, "update auth.session set last_seen_at = now() - interval '5 minutes'"));
        Instant stale = lastSeen();
        auth.resolve(token);
        Instant refreshed = lastSeen();
        assertTrue(refreshed.isAfter(stale.plusSeconds(240)), "a request after five idle minutes refreshes last_seen_at");
    }

    @Test
    void aSessionIdleBeyondItsLimitIsStillRefused() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        String token = auth.login("root@touch.test", PASSWORD);
        // A plain sign-in is an 'identity' session, idle limit 30 minutes.
        db.inOwner(c -> Sql.update(c, "update auth.session set last_seen_at = now() - interval '31 minutes' where revoked_at is null"));

        org.junit.jupiter.api.Assertions.assertThrows(com.example.loginapp.core.ApiException.class, () -> auth.resolve(token));
    }

    private static Instant lastSeen() throws Exception {
        return Instant.parse((String) db.inOwner(c -> Sql.one(c,
                "select last_seen_at from auth.session where revoked_at is null order by issued_at desc limit 1")).get("lastSeenAt"));
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }
}
