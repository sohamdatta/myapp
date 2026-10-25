package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.Db;
import com.example.loginapp.core.Migrator;

/**
 * Databases created before Flyway were migrated by a hand-written migrator that recorded each file in
 * public.schema_migration. Flyway must take them over without running anything twice, and refuse one
 * that the old migrator left half done.
 */
class MigratorUpgradeTest {

    private static final String ROLE_PASSWORD = "test-role-password";

    @Test
    void aDatabaseFromTheEarlierMigratorIsBaselinedAndKeepsItsData() throws Exception {
        String url = freshDatabase("hrms_upgrade_test");
        List<String> files = migrationFiles();
        // Build the database as the earlier migrator did: every file, in order, recorded by name.
        try (Connection c = DriverManager.getConnection(url, ownerUser(), ownerPassword()); Statement st = c.createStatement()) {
            st.execute("create table public.schema_migration (version text primary key, applied_at timestamptz not null default now())");
            for (String f : files) {
                st.execute(Files.readString(Path.of("src/main/resources/db/migration", f), StandardCharsets.UTF_8));
                st.execute("insert into public.schema_migration (version) values ('" + f + "')");
            }
            st.execute("create table public.upgrade_marker (note text)");
            st.execute("insert into public.upgrade_marker values ('kept')");
        }

        Db db = db(url);
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);   // a second run changes nothing

        try (Connection c = DriverManager.getConnection(url, ownerUser(), ownerPassword()); Statement st = c.createStatement()) {
            List<String> history = new ArrayList<>();
            try (ResultSet r = st.executeQuery("select version, type from public.flyway_schema_history order by installed_rank")) {
                while (r.next()) {
                    history.add(r.getString(1) + " " + r.getString(2));
                }
            }
            assertEquals(List.of(files.size() + " BASELINE"), history, "baselined at the last file, nothing run again");
            try (ResultSet r = st.executeQuery("select count(*) from public.upgrade_marker where note = 'kept'")) {
                r.next();
                assertEquals(1, r.getInt(1));
            }
        }
    }

    @Test
    void aFreshDatabaseRecordsEveryFileWithItsChecksum() throws Exception {
        String url = freshDatabase("hrms_upgrade_test");
        Migrator.migrate(db(url), ROLE_PASSWORD);
        try (Connection c = DriverManager.getConnection(url, ownerUser(), ownerPassword()); Statement st = c.createStatement();
             ResultSet r = st.executeQuery("select count(*), count(checksum), bool_and(success) from public.flyway_schema_history")) {
            r.next();
            assertEquals(migrationFiles().size(), r.getInt(1));
            assertEquals(migrationFiles().size(), r.getInt(2));
            assertTrue(r.getBoolean(3));
        }
    }

    @Test
    void aGapLeftByTheEarlierMigratorIsRefused() throws Exception {
        String url = freshDatabase("hrms_upgrade_test");
        try (Connection c = DriverManager.getConnection(url, ownerUser(), ownerPassword()); Statement st = c.createStatement()) {
            st.execute("create table public.schema_migration (version text primary key)");
            st.execute("insert into public.schema_migration values ('V1__user_module.sql'), ('V3__attendance.sql')");
        }
        RuntimeException e = assertThrows(RuntimeException.class, () -> Migrator.migrate(db(url), ROLE_PASSWORD));
        assertTrue(String.valueOf(e.getMessage()).contains("every version from 1 to 3")
                || String.valueOf(e.getCause()).contains("every version from 1 to 3"), String.valueOf(e));
    }

    private static List<String> migrationFiles() throws Exception {
        try (Stream<Path> s = Files.list(Path.of("src/main/resources/db/migration"))) {
            return s.map(p -> p.getFileName().toString())
                    .sorted((a, b) -> Integer.compare(version(a), version(b))).toList();
        }
    }

    private static int version(String file) {
        return Integer.parseInt(file.substring(1, file.indexOf("__")));
    }

    private static String freshDatabase(String name) {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser(), ownerPassword());
             Statement st = c.createStatement()) {
            st.execute("drop database if exists " + name + " with (force)");
            st.execute("create database " + name);
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the migration upgrade tests");
        return "jdbc:postgresql://" + host + "/" + name;
    }

    private static Db db(String url) {
        return new Db(source(url, ownerUser(), ownerPassword()), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String ownerUser() {
        return env("APP_DB_OWNER_USER", "postgres");
    }

    private static String ownerPassword() {
        return env("APP_DB_OWNER_PASSWORD", "postgres");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
