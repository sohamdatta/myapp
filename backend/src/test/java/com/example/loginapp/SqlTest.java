package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.example.loginapp.core.Sql;

/**
 * The JDBC helpers every query goes through, on a scratch table: rows as camel-cased maps, one row for {@code one},
 * counting and existence done by the database, and streaming a large result without holding it. Needs PostgreSQL as
 * described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class SqlTest {

    private static Connection c;

    @BeforeAll
    static void open() throws Exception {
        String host = System.getenv().getOrDefault("APP_TEST_DB_HOST", "localhost:5432");
        String user = System.getenv().getOrDefault("APP_DB_OWNER_USER", "postgres");
        String password = System.getenv().getOrDefault("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection admin = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", user, password);
             Statement st = admin.createStatement()) {
            st.execute("drop database if exists hrms_test_sql with (force)");
            st.execute("create database hrms_test_sql");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the SQL helper tests");
        c = DriverManager.getConnection("jdbc:postgresql://" + host + "/hrms_test_sql", user, password);
        c.setAutoCommit(false);   // streaming with a fetch size needs an open transaction, as Db runs them
        try (Statement st = c.createStatement()) {
            st.execute("create table item (id int primary key, full_name text, created_at timestamptz default now())");
            st.execute("insert into item (id, full_name) select n, 'Item ' || n from generate_series(1, 2500) n");
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (c != null) {
            c.close();
        }
    }

    @Test
    void rowsComeBackAsCamelCasedMapsInOrder() throws Exception {
        List<Map<String, Object>> rows = Sql.query(c, "select id, full_name from item where id <= ? order by id", 3);
        assertEquals(3, rows.size());
        assertEquals(List.of("id", "fullName"), List.copyOf(rows.get(0).keySet()));
        assertEquals("Item 2", rows.get(1).get("fullName"));
    }

    @Test
    void oneGivesTheFirstRowOrNull() throws Exception {
        assertEquals(1, Sql.one(c, "select id from item order by id").get("id"));
        assertNull(Sql.one(c, "select id from item where id < 0"));
    }

    @Test
    void oneAsksTheDatabaseForOneRowOnly() throws Exception {
        // A connection that records what is asked of each statement: one() must ask for a single row (setMaxRows(1)).
        List<Integer> maxRows = new ArrayList<>();
        Connection spy = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            Object result = method.invoke(c, args);
            if (method.getName().equals("prepareStatement")) {
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class}, (p, m, a) -> {
                    if (m.getName().equals("setMaxRows")) {
                        maxRows.add((Integer) a[0]);
                    }
                    try {
                        return m.invoke(result, a);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
            }
            return result;
        });
        assertEquals(1, Sql.one(spy, "select id from item order by id").get("id"));
        assertEquals(List.of(1), maxRows, "one() asked for one row");
        maxRows.clear();
        assertEquals(2500, Sql.query(spy, "select id from item").size());
        assertEquals(List.of(), maxRows, "query() sets no limit");
    }

    @Test
    void countAndExistsAreAnsweredByTheDatabase() throws Exception {
        assertEquals(2500, Sql.count(c, "select 1 from item"));
        assertEquals(10, Sql.count(c, "select 1 from item where id <= ?", 10));
        assertEquals(0, Sql.count(c, "select 1 from item where id < 0"));
        assertTrue(Sql.exists(c, "select 1 from item where id = ?", 7));
        assertFalse(Sql.exists(c, "select 1 from item where id = ?", -1));
    }

    @Test
    void eachStreamsEveryRowInOrderWithoutBuildingAList() throws Exception {
        List<Integer> seen = new ArrayList<>();
        Sql.each(c, "select id, full_name from item order by id", 100, row -> {
            assertEquals("Item " + row.get("id"), row.get("fullName"));
            seen.add((Integer) row.get("id"));
        });
        assertEquals(2500, seen.size());
        assertEquals(1, seen.get(0));
        assertEquals(2500, seen.get(2499));
    }

    @Test
    void timestampsAreReadAsIsoStrings() throws Exception {
        Object created = Sql.one(c, "select created_at from item where id = 1").get("createdAt");
        assertTrue(created instanceof String s && s.endsWith("Z"), "was " + created);
    }
}
