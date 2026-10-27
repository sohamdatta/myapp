package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PayrollConfig;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PayrollRuns;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.UserService;
import com.example.loginapp.core.SessionCtx;

/**
 * Size check: an Organization of 2,000 employees, the payroll month calculated, and the main lists read.
 * The budgets come from the roadmap (Q1): a payroll calculation for 2,000 employees under 60 seconds, and a
 * list read under 300 ms at the 95th percentile.
 *
 * <p>Does nothing unless {@code SCALE_TEST=true}, because seeding takes minutes; the "Scale check" workflow
 * (workflow_dispatch) runs it. {@code SCALE_EMPLOYEES} changes the size (default 2000). The result is printed as
 * lines starting {@code SCALE} and kept in the report. Over a budget the test fails unless
 * {@code SCALE_WARN_ONLY=true}, which is how the workflow runs it until the first real figures are known.
 *
 * <p>The database {@code hrms_test_scale} is left behind with hr@scale.test (password {@code correct-horse-battery}), which
 * the k6 script in perf/k6 signs in as.
 *
 * <p>Employees are added without sign-in accounts (as after a CSV import, before anyone is invited), so no
 * password is hashed 2,000 times; the figures cover payroll and the lists, not sign-in.
 */
class PayrollScaleTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long CALCULATE_BUDGET_MS = 60_000;
    private static final long READ_P95_BUDGET_MS = 300;

    @Test
    void twoThousandEmployeesCalculateInAMinuteAndListQuickly() throws Exception {
        assumeTrue("true".equals(System.getenv("SCALE_TEST")), "set SCALE_TEST=true to run the size check");
        int size = Integer.parseInt(env("SCALE_EMPLOYEES", "2000"));
        boolean warnOnly = "true".equals(System.getenv("SCALE_WARN_ONLY"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_scale with (force)");
            st.execute("create database hrms_test_scale");
        }
        String url = "jdbc:postgresql://" + host + "/hrms_test_scale";
        Db db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        AuthService auth = new AuthService(db);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        EmployeeService employees = new EmployeeService(db, crypto);
        PayrollConfig config = new PayrollConfig(db);
        PayrollPay pay = new PayrollPay(db);
        PayrollRuns runs = new PayrollRuns(db, crypto);
        PlatformService platform = new PlatformService(db);
        platform.ensureFirstSuperAdmin("root@scale.test", PASSWORD);
        SessionCtx superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@scale.test", PASSWORD))));
        Map<String, Object> created = platform.createOrganization(superAdmin, "Scale Org", "scale", "growth", "admin@scale.test");
        SessionCtx admin = auth.resolve(new InvitationService(db).accept((String) created.get("invitationToken"), PASSWORD, null));

        // The person k6 signs in as (hr@scale.test): the database is left in place for the read load.
        InvitationService invitations = new InvitationService(db);
        String invitation = (String) new UserService(db).invite(admin, "hr@scale.test", List.of("hr_admin", "payroll_operator", "employee")).get("token");
        invitations.accept(invitation, PASSWORD, null);

        long t0 = System.nanoTime();
        LocalDate today = LocalDate.now(IST);
        String staff = (String) ((Map<?, ?>) ((List<?>) config.setup(admin).get("structures")).get(0)).get("id");
        LocalDate start = today.withDayOfMonth(1);
        for (int i = 1; i <= size; i++) {
            UUID id = UUID.fromString((String) employees.add(admin, new NewEmployee("Person " + i, null, "1990-01-15", i % 2 == 0 ? "female" : "male",
                    null, "9" + String.format("%09d", i), "permanent", today.minusYears(1).toString(), null, null, null,
                    i % 7 == 0 ? "Manager" : "Analyst", null, null, null, null, null, null, null, null, null, null, null, null)).get("id"));
            long ctc = 360_000 + (i % 50) * 24_000L;
            pay.revise(admin, id, new PayrollPay.ReviseInput(start.toString(), staff, BigDecimal.valueOf(ctc), "Seed"));
        }
        long seedMs = (System.nanoTime() - t0) / 1_000_000;

        // reads: the People list, with and without a search; 40 rounds each, p95
        long[] list = new long[40];
        long[] search = new long[40];
        for (int i = 0; i < list.length; i++) {
            long s = System.nanoTime();
            List<Map<String, Object>> all = employees.list(admin, null, null, null);
            list[i] = (System.nanoTime() - s) / 1_000_000;
            // The list is cut at 5,000 rows (EmployeeService.list), so all 2,000 come back.
            assertEquals(Math.min(size, 5000), all.size());
            s = System.nanoTime();
            employees.list(admin, "Person 17", null, null);
            search[i] = (System.nanoTime() - s) / 1_000_000;
        }

        // the run
        LocalDate period = start.plusMonths(1);
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(admin).get("groups")).get(0)).get("id");
        UUID run = UUID.fromString((String) runs.open(admin, UUID.fromString(group), period.toString()).get("id"));
        runs.freeze(admin, run);
        long c0 = System.nanoTime();
        Map<String, Object> totals = runs.calculate(admin, run);
        long calcMs = (System.nanoTime() - c0) / 1_000_000;
        assertEquals(size, ((Number) totals.get("people")).intValue(), "everyone is in the run");

        long listP95 = p95(list);
        long searchP95 = p95(search);
        System.out.printf("SCALE employees=%d seed_ms=%d calculate_ms=%d (budget %d) people_list_p95_ms=%d people_search_p95_ms=%d (budget %d)%n",
                size, seedMs, calcMs, CALCULATE_BUDGET_MS, listP95, searchP95, READ_P95_BUDGET_MS);
        if (!warnOnly) {
            assertTrue(calcMs < CALCULATE_BUDGET_MS, "payroll calculation took " + calcMs + " ms for " + size + " employees");
            assertTrue(listP95 < READ_P95_BUDGET_MS, "People list p95 " + listP95 + " ms");
            assertTrue(searchP95 < READ_P95_BUDGET_MS, "People search p95 " + searchP95 + " ms");
        }
    }

    private static long p95(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.95) - 1];
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
