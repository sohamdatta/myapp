package com.example.loginapp.core;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Applies the SQL files in db/migration with Flyway, once each and in version order, as the database
 * owner at startup, then sets the passwords of the three application roles.
 *
 * <p>Flyway keeps each applied file's checksum in {@code public.flyway_schema_history} and refuses to
 * start when an applied file has been edited or one is missing. Databases set up by the earlier
 * hand-written migrator, which recorded file names in {@code public.schema_migration}, are baselined
 * at the highest version that table lists, so nothing runs twice.
 */
public final class Migrator {

    static final String LOCATION = "classpath:db/migration";
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");

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
        Integer baseline = db.inOwner(Migrator::earlierMigratorVersion);
        FluentConfiguration config = Flyway.configure()
                .dataSource(db.ownerDataSource())
                .locations(LOCATION)
                .schemas("public")
                .createSchemas(false)
                .placeholderReplacement(false)   // the SQL is run as written; templates use {{...}}
                .validateMigrationNaming(true)
                .callbacks(new DefinerPolicies());
        if (baseline != null) {
            config.baselineOnMigrate(true)
                    .baselineVersion(String.valueOf(baseline))
                    .baselineDescription("Applied by the earlier migrator (public.schema_migration)");
        }
        config.load().migrate();

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

    /**
     * The highest version the earlier migrator applied, when this database was set up by it and Flyway
     * has not run yet; otherwise null. Refuses a database where that migrator left a gap.
     */
    static Integer earlierMigratorVersion(java.sql.Connection c) throws SQLException {
        boolean flyway = Sql.one(c, "select 1 from pg_class where oid = to_regclass('public.flyway_schema_history')") != null;
        boolean earlier = Sql.one(c, "select 1 from pg_class where oid = to_regclass('public.schema_migration')") != null;
        if (flyway || !earlier) {
            return null;
        }
        List<Integer> versions = new ArrayList<>();
        for (var row : Sql.query(c, "select version from public.schema_migration")) {
            Matcher m = VERSION.matcher(String.valueOf(row.get("version")));
            if (!m.matches()) {
                throw new SQLException("Unexpected entry in public.schema_migration: " + row.get("version"));
            }
            versions.add(Integer.parseInt(m.group(1)));
        }
        if (versions.isEmpty()) {
            return null;
        }
        int highest = versions.stream().mapToInt(Integer::intValue).max().getAsInt();
        if (versions.size() != highest || versions.stream().distinct().count() != highest) {
            throw new SQLException("public.schema_migration does not list every version from 1 to " + highest
                    + "; fix it by hand before upgrading");
        }
        return highest;
    }

    /** Runs DEFINER_POLICIES after each migration, in the same transaction, as the earlier migrator did. */
    private static final class DefinerPolicies implements Callback {
        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.AFTER_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            try (Statement st = context.getConnection().createStatement()) {
                st.execute(DEFINER_POLICIES);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not add the definer policies", e);
            }
        }

        @Override
        public String getCallbackName() {
            return "definer-policies";
        }
    }
}
