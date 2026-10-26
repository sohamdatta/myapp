package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.loginapp.core.Sql;

/**
 * Sql.batch writes many rows in one go. Uses a temporary table on a plain connection, so it needs
 * PostgreSQL (see {@link UserModuleTest}) but no migrations; skipped when the database is not reachable
 * unless APP_TEST_DB_REQUIRED is set.
 */
class SqlBatchTest {

    private Connection c;

    @BeforeEach
    void connect() throws Exception {
        String host = System.getenv("APP_TEST_DB_HOST") == null ? "localhost:5432" : System.getenv("APP_TEST_DB_HOST");
        String user = System.getenv("APP_DB_OWNER_USER") == null ? "postgres" : System.getenv("APP_DB_OWNER_USER");
        String password = System.getenv("APP_DB_OWNER_PASSWORD") == null ? "postgres" : System.getenv("APP_DB_OWNER_PASSWORD");
        try {
            c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", user, password);
        } catch (SQLException e) {
            if ("true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
                throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set", e);
            }
            assumeTrue(false, "PostgreSQL is not reachable");
        }
        try (Statement st = c.createStatement()) {
            st.execute("create temp table batch_probe (id uuid primary key, name text not null, amount numeric(12,2), "
                    + "meta jsonb, tags text[])");
        }
    }

    @AfterEach
    void close() throws Exception {
        if (c != null) {
            c.close();
        }
    }

    @Test
    void writesEveryRowWithTheSameBindingsAsUpdate() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        UUID[] ids = new UUID[250];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
            rows.add(new Object[] {ids[i], "row " + i, new BigDecimal(i).movePointLeft(2), "{\"n\": " + i + "}",
                    i % 2 == 0 ? new String[] {"even", "x"} : null});
        }

        int written = Sql.batch(c, "insert into batch_probe (id, name, amount, meta, tags) values (?, ?, ?, ?::jsonb, ?)", rows);

        assertEquals(250, written);
        assertEquals(250, ((Number) Sql.one(c, "select count(*) as n from batch_probe").get("n")).intValue());
        Map<String, Object> third = Sql.one(c, "select name, amount, meta, tags from batch_probe where id = ?", ids[3]);
        assertEquals("row 3", third.get("name"));
        assertEquals(0, new BigDecimal("0.03").compareTo((BigDecimal) third.get("amount")));
        assertEquals("{\"n\": 3}", third.get("meta"));
        assertEquals(null, third.get("tags"));
        assertEquals(List.of("even", "x"), Sql.one(c, "select tags from batch_probe where id = ?", ids[4]).get("tags"));
    }

    @Test
    void anEmptyBatchWritesNothingAndSendsNothing() throws Exception {
        assertEquals(0, Sql.batch(c, "insert into batch_probe (id, name) values (?, ?)", List.of()));
        assertEquals(0, ((Number) Sql.one(c, "select count(*) as n from batch_probe").get("n")).intValue());
    }

    @Test
    void aRowThatBreaksARuleFailsTheBatchAndAnEnclosingTransactionCanRollItBack() throws Exception {
        c.setAutoCommit(false);
        UUID same = UUID.randomUUID();
        List<Object[]> rows = List.of(new Object[] {same, "first"}, new Object[] {UUID.randomUUID(), "second"}, new Object[] {same, "duplicate"});

        assertThrows(SQLException.class, () -> Sql.batch(c, "insert into batch_probe (id, name) values (?, ?)", rows));
        c.rollback();

        assertEquals(0, ((Number) Sql.one(c, "select count(*) as n from batch_probe").get("n")).intValue());
    }
}
