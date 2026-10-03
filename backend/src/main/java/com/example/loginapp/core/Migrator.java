package com.example.loginapp.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Applies the SQL files in db/migration, in order, once each. Runs as the
 * database owner at startup, then sets the passwords of the three application roles.
 */
public final class Migrator {

    private static final List<String> FILES = List.of(
            "V1__user_module.sql",
            "V2__seed_roles_and_permissions.sql",
            "V3__attendance.sql");

    private Migrator() {}

    public static void migrate(Db db, String rolePassword) {
        db.inOwner(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("create table if not exists public.schema_migration ("
                        + "version text primary key, applied_at timestamptz not null default now())");
            }
            return null;
        });
        for (String file : FILES) {
            db.inOwner(c -> {
                if (Sql.one(c, "select 1 from public.schema_migration where version = ?", file) != null) {
                    return null;
                }
                try (Statement st = c.createStatement()) {
                    st.execute(read(file));
                }
                Sql.update(c, "insert into public.schema_migration (version) values (?)", file);
                return null;
            });
        }
        db.inOwner(c -> {
            String literal = "'" + rolePassword.replace("'", "''") + "'";
            try (Statement st = c.createStatement()) {
                for (String role : List.of("app_platform", "app_auth", "app_tenant")) {
                    st.execute("alter role " + role + " with login password " + literal);
                }
            }
            return null;
        });
    }

    private static String read(String file) throws SQLException {
        try (InputStream in = Migrator.class.getResourceAsStream("/db/migration/" + file)) {
            if (in == null) {
                throw new SQLException("Migration file is missing: " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Cannot read migration file " + file, e);
        }
    }
}
