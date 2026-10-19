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
            "V3__attendance.sql",
            "V4__leave_and_wfh.sql",
            "V5__employee_record.sql",
            "V6__onboarding.sql",
            "V7__joiner_flow.sql",
            "V8__documents_and_checklists.sql",
            "V9__letters_policies_codes_messages.sql",
            "V10__employee_import.sql",
            "V11__employee_exit.sql",
            "V12__org_chart.sql",
            "V13__payroll.sql", "V14__suspended_access.sql", "V15__attendance_modes.sql",
            "V16__performance_probation.sql", "V17__performance_goals.sql", "V18__performance_reviews.sql",
            "V19__performance_calibration.sql", "V20__performance_plans.sql");

    /**
     * Lets app_definer see every row of every table under row-level security, for databases where it
     * could not be created with BYPASSRLS (see V1). Run after each migration, so a table added later
     * is covered the moment it exists; a table that already has the policy is skipped.
     */
    static final String DEFINER_POLICIES = """
            do $$
            declare t record;
            begin
              for t in select n.nspname, c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                        where c.relrowsecurity and n.nspname in ('app', 'auth', 'platform')
                          and not exists (select 1 from pg_policy p where p.polrelid = c.oid and p.polname = 'definer_access')
              loop
                execute format('create policy definer_access on %I.%I to app_definer using (true) with check (true)',
                               t.nspname, t.relname);
              end loop;
            end $$""";

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
                    st.execute(DEFINER_POLICIES);
                }
                Sql.update(c, "insert into public.schema_migration (version) values (?)", file);
                return null;
            });
        }
        db.inOwner(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(DEFINER_POLICIES);   // databases migrated before the policies existed
            }
            return null;
        });
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
