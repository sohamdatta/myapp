package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.Db;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Sql;

/**
 * The EPF wage ceiling rose from 15,000 to 25,000 on 17 Sep 2026 (V41). This pins what the migration does to the rule
 * rows: the old version is closed, never edited; the new one starts on 1 Sep so that payroll (which reads rules as of the
 * period's last day) and the PF return (as of its first day) agree; it stays a draft; nothing overlaps.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class PfWageCeilingRuleTest {

    private static final String ROLE_PASSWORD = "test-role-password";
    private static boolean reachable;
    private static Db db;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_pfceiling with (force)");
            st.execute("create database hrms_test_pfceiling");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_pfceiling";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
    }

    private static BigDecimal ceilingOn(Connection c, String field, LocalDate day) throws java.sql.SQLException {
        Map<String, Object> r = Sql.one(c, "select (data->>?)::numeric as v from app.payroll_rule where statute = 'pf' and jurisdiction = 'IN' "
                + "and valid_from <= ? and (valid_to is null or valid_to > ?)", field, day, day);
        assertNotNull(r, "a PF rule must cover " + day);
        return (BigDecimal) r.get("v");
    }

    @Test
    void theCeilingIs15000UpToAugustAnd25000FromSeptember() {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        db.inOwner(c -> {
            for (String field : List.of("ceiling", "epsCeiling", "edliCeiling")) {
                assertEquals(0, new BigDecimal("15000").compareTo(ceilingOn(c, field, LocalDate.of(2026, 8, 31))), field + " on 31 Aug");
                assertEquals(0, new BigDecimal("25000").compareTo(ceilingOn(c, field, LocalDate.of(2026, 9, 1))), field + " on 1 Sep");
                assertEquals(0, new BigDecimal("25000").compareTo(ceilingOn(c, field, LocalDate.of(2026, 9, 30))), field + " on 30 Sep");
                assertEquals(0, new BigDecimal("25000").compareTo(ceilingOn(c, field, LocalDate.of(2026, 10, 31))), field + " on 31 Oct");
            }
            return null;
        });
    }

    @Test
    void theOldVersionIsClosedNotEditedAndTheNewOneIsADraftThatKeepsTheOtherFigures() {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        db.inOwner(c -> {
            List<Map<String, Object>> rows = Sql.query(c, "select valid_from, valid_to, status, signed_off_by, data->>'ceiling' as ceiling, "
                    + "data->>'employeeRate' as ee, data->>'ecrSeparator' as sep, data->>'dueDay' as due, data->>'transitionBasis' as basis, "
                    + "data->>'previousCeiling' as previous, data->>'notifiedFrom' as notified from app.payroll_rule "
                    + "where statute = 'pf' and jurisdiction = 'IN' order by valid_from");
            assertEquals(2, rows.size());
            Map<String, Object> old = rows.get(0);
            assertEquals("2025-04-01", String.valueOf(old.get("validFrom")));
            assertEquals("2026-09-01", String.valueOf(old.get("validTo")));
            assertEquals("15000", String.valueOf(old.get("ceiling")), "the old version keeps its figure");
            Map<String, Object> now = rows.get(1);
            assertEquals("2026-09-01", String.valueOf(now.get("validFrom")));
            assertEquals(null, now.get("validTo"));
            assertEquals("draft", now.get("status"), "a consultant has not signed it off");
            assertEquals(null, now.get("signedOffBy"));
            assertEquals("25000", String.valueOf(now.get("ceiling")));
            assertEquals("12", String.valueOf(now.get("ee")), "the rates carry forward");
            assertEquals("#~#", String.valueOf(now.get("sep")), "the return-file settings carry forward");
            assertEquals("15", String.valueOf(now.get("due")));
            assertEquals("whole_month", String.valueOf(now.get("basis")));
            assertEquals("15000", String.valueOf(now.get("previous")));
            assertEquals("2026-09-17", String.valueOf(now.get("notified")), "the real date is kept");
            return null;
        });
    }

    @Test
    void aVersionThatOverlapsIsRefused() {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        assertThrows(RuntimeException.class, () -> db.inOwner(c -> {
            Sql.update(c, "insert into app.payroll_rule (statute, jurisdiction, valid_from, data, source) values "
                    + "('pf', 'IN', '2026-09-17', '{}'::jsonb, 'overlap')");
            return null;
        }));
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
