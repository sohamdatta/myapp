package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.Json;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PayrollConfig;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PayrollRuns;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UsageStats;
import com.example.loginapp.core.UserService;

/**
 * Usage statistics: the nightly counts for each Organization, kept apart, only numbers, backfilled and idempotent, and read by the
 * platform team as a grid, a trend and a cross-Organization view, while the console's database role still reads no Organization's tables.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class UsageStatsTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static EmployeeService employees;
    private static AttendanceSettingsService settings;
    private static PayrollConfig config;
    private static PayrollPay pay;
    private static PayrollRuns runs;
    private static UsageStats usage;
    private static SessionCtx superAdmin;
    private static boolean reachable;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_usage with (force)");
            st.execute("create database hrms_test_usage");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_usage";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        employees = new EmployeeService(db, crypto);
        settings = new AttendanceSettingsService(db);
        config = new PayrollConfig(db);
        pay = new PayrollPay(db);
        runs = new PayrollRuns(db, crypto);
        usage = new UsageStats(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private record Case(Org org, SessionCtx operator, SessionCtx asha, SessionCtx ravi, UUID ashaEmployment) {}

    /** An Organization with two paid people and a PAN-less third, and the TDS statement's deductor filled in. */
    private Case organization() throws Exception {
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx a = join(org, "asha", List.of("employee"));
        SessionCtx r = join(org, "ravi", List.of("employee"));
        SessionCtx n = join(org, "nopan", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Bengaluru office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(a.membershipId().toString(), r.membershipId().toString(), n.membershipId().toString()),
                UUID.fromString(loc), UUID.fromString(shift), List.of(0), TODAY.minusDays(120).toString());
        UUID asha = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", "100200300400", "111111111111", a)).get("id"));
        UUID ravi = UUID.fromString((String) employees.add(org.admin, person("Ravi Kumar", "ABCPK1234F", "100200300401", "222222222222", r)).get("id"));
        UUID nopan = UUID.fromString((String) employees.add(org.admin, person("Nita Das", null, "100200300402", "333333333333", n)).get("id"));
        Map<String, Object> setup = config.setup(operator);
        for (String[] reg : List.of(new String[] {"pf", null, "KABLR0012345000"}, new String[] {"esi", null, "53000123450001001"},
                new String[] {"pt", "KA", "PTKA-123456"}, new String[] {"lwf", "KA", "LWF-KA-998"})) {
            config.addRegistration(operator, new PayrollConfig.RegistrationInput(reg[0], reg[1], reg[2]));
        }
        db.inOwner(c -> Sql.update(c, "update app.payroll_settings set entity_name = 'Acme Pvt Ltd', pan = 'AAACA1234A', tan = 'BLRA12345B', "
                + "address = '1 MG Road, Bengaluru' where tenant_id = ?", org.id));
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");
        String from = TODAY.withDayOfMonth(1).toString();
        for (UUID e : List.of(asha, ravi, nopan)) {
            pay.revise(org.admin, e, new PayrollPay.ReviseInput(from, staff, new BigDecimal("36000000"), "Joining salary"));
        }
        return new Case(org, operator, a, r, asha);
    }

    /** Calculates and approves a month; with {@code finish} it is also paid and released, which the next month's run waits for. */
    private UUID approve(Case k, LocalDate period, boolean finish) throws Exception {
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(k.operator).get("groups")).get(0)).get("id");
        UUID run = UUID.fromString((String) runs.open(k.operator, UUID.fromString(group), period.toString()).get("id"));
        runs.freeze(k.operator, run);
        runs.calculate(k.operator, run);
        for (Object o : (List<?>) runs.get(k.operator, run).get("exceptions")) {
            Map<?, ?> x = (Map<?, ?>) o;
            if ("blocking".equals(x.get("level")) && Boolean.TRUE.equals(x.get("acceptable"))) {
                runs.accept(k.org.admin, run, UUID.fromString((String) x.get("id")), "Reviewed for the test");
            }
        }
        runs.approve(k.org.admin, run);
        if (finish) {
            runs.markPaid(k.org.admin, run, new PayrollRuns.PaidInput(null, "UTR000123"));
            Object state = db.inOwner(c -> Sql.one(c, "select state from app.payroll_run where id = ?", run).get("state"));
            if ("paid".equals(state)) {
                runs.release(k.org.admin, run);
            }
        }
        return run;
    }

    private static final LocalDate MONTH = TODAY.withDayOfMonth(1);

    /** The counted value for an Organization, day and metric, or null when none was stored. */
    private static BigDecimal stored(UUID tenant, LocalDate day, String metric) {
        Map<String, Object> r = db.inOwner(c -> Sql.one(c, "select value from platform.usage_daily where tenant_id = ? and day = ? and metric = ?", tenant, day, metric));
        return r == null ? null : new BigDecimal(String.valueOf(r.get("value")));
    }

    private static void module(UUID tenant, String module, boolean on) {
        platform.setModule(superAdmin, tenant, module, on, null);
    }

    @Test
    void theRunCountsEachOrganizationApartAndStoresOnlyNumbers() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        Case other = organization();
        approve(k, MONTH, false);   // approved today in the first Organization only
        // One Organization has existed for 100 days (the backfill is 90); the other was created just now.
        db.inOwner(c -> Sql.update(c, "update platform.tenant set created_at = now() - interval '100 days' where id = ?", k.org.id));

        UsageStats.RunResult first = usage.run(true);
        assertTrue(first.ran());
        assertEquals(List.of(), first.failures(), "every query runs: " + first.failures());
        assertTrue(first.organizations() >= 2);

        // This Organization's own counts: three payslips in one approved run, its people, and the sign-ins that created them.
        assertEquals(0, BigDecimal.ONE.compareTo(stored(k.org.id, TODAY, "payroll_runs_approved")));
        assertEquals(0, new BigDecimal("3").compareTo(stored(k.org.id, TODAY, "payroll_employees_paid")));
        assertTrue(stored(k.org.id, TODAY, "payroll_hours_to_approval").signum() >= 0, "a median is stored when something was approved");
        assertTrue(stored(k.org.id, TODAY, "members").compareTo(new BigDecimal("5")) >= 0);
        assertTrue(stored(k.org.id, TODAY, "signins_web").signum() > 0, "the people who joined signed in today");
        assertTrue(stored(k.org.id, TODAY, "members_active_7d").signum() > 0);

        // The other Organization did none of that, and nothing leaked across: its count is zero, not this one's.
        assertEquals(0, BigDecimal.ZERO.compareTo(stored(other.org.id, TODAY, "payroll_runs_approved")));
        assertEquals(0, BigDecimal.ZERO.compareTo(stored(other.org.id, TODAY, "payroll_employees_paid")));
        assertEquals(null, stored(other.org.id, TODAY, "payroll_hours_to_approval"), "a median of nothing is left out, not stored as 0");

        // The first run worked out the last 90 days for each Organization, and the table can hold nothing but numbers.
        assertEquals(90L, ((Number) db.inOwner(c -> Sql.one(c, "select count(distinct day) as n from platform.usage_daily where tenant_id = ?", k.org.id).get("n"))).longValue());
        assertEquals(1L, ((Number) db.inOwner(c -> Sql.one(c, "select count(distinct day) as n from platform.usage_daily where tenant_id = ?", other.org.id).get("n"))).longValue(),
                "an Organization is counted from the day it was created, not from 90 days of zeros before it existed");
        assertEquals(0, db.inOwner(c -> Sql.query(c, "select 1 from platform.usage_daily where tenant_id = ? and day < ?", other.org.id, TODAY)).size());
        List<String> columns = db.inOwner(c -> Sql.query(c, "select column_name || ':' || data_type as x from information_schema.columns "
                + "where table_schema = 'platform' and table_name = 'usage_daily' order by ordinal_position")).stream().map(m -> String.valueOf(m.get("x"))).toList();
        assertEquals(List.of("tenant_id:uuid", "day:date", "metric:text", "value:numeric", "computed_at:timestamp with time zone"), columns,
                "no column that could hold a name, an amount or any content");
        assertEquals(0L, ((Number) db.inOwner(c -> Sql.one(c, "select count(*) as n from platform.usage_daily where value < 0").get("n"))).longValue());

        // Running again changes nothing but the time it was worked out.
        BigDecimal before = stored(k.org.id, TODAY, "payroll_employees_paid");
        long rows = ((Number) db.inOwner(c -> Sql.one(c, "select count(*) as n from platform.usage_daily where tenant_id = ?", k.org.id).get("n"))).longValue();
        UsageStats.RunResult second = usage.run(true);
        assertEquals(List.of(), second.failures());
        assertEquals(0, before.compareTo(stored(k.org.id, TODAY, "payroll_employees_paid")));
        assertEquals(rows, ((Number) db.inOwner(c -> Sql.one(c, "select count(*) as n from platform.usage_daily where tenant_id = ?", k.org.id).get("n"))).longValue());
        assertTrue(second.days() <= second.organizations() * 8, "later runs fill what is missing and redo the last week, no more: " + second.days());
    }

    @Test
    void theGridSaysActiveQuietOrBoughtButNotUsedAndTheViewsAddUp() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        Case other = organization();
        approve(k, MONTH, false);
        module(other.org.id, "leave", false);
        usage.run(true);

        UsageStats.Grid grid = usage.grid(superAdmin);
        UsageStats.OrganizationRow row = grid.organizations().stream().filter(o -> o.id().equals(k.org.id.toString())).findFirst().orElseThrow();
        UsageStats.OrganizationRow quiet = grid.organizations().stream().filter(o -> o.id().equals(other.org.id.toString())).findFirst().orElseThrow();
        java.util.function.Function<UsageStats.OrganizationRow, Map<String, String>> states = o ->
                o.cells().stream().collect(java.util.stream.Collectors.toMap(UsageStats.Cell::module, UsageStats.Cell::state));
        assertEquals("active", states.apply(row).get("payroll"), "a run was approved today");
        assertEquals("active", states.apply(row).get("core"), "people signed in today");
        assertEquals("bought_not_used", states.apply(row).get("attendance"), "on, and not one punch in 30 days");
        assertEquals("bought_not_used", states.apply(row).get("leave"));
        assertEquals("not_bought", states.apply(quiet).get("leave"), "switched off for this Organization");
        assertEquals("bought_not_used", states.apply(quiet).get("payroll"));
        assertEquals(grid.latestDay(), TODAY.toString());

        // Twelve weeks for one Organization: the week it was approved in shows it, and the earlier weeks have nothing to say about it.
        UsageStats.Trend trend = usage.trend(superAdmin, k.org.id);
        assertEquals(12, trend.weekStarts().size());
        UsageStats.Series approved = trend.series().stream().filter(s -> s.code().equals("payroll_runs_approved")).findFirst().orElseThrow();
        assertEquals(12, approved.weeks().size());
        assertEquals(0, BigDecimal.ONE.compareTo(approved.weeks().getLast()), "this week");
        assertEquals(null, approved.weeks().getFirst(), "twelve weeks ago the Organization did not exist: nothing, not a zero");
        assertEquals(404, assertThrows(ApiException.class, () -> usage.trend(superAdmin, UUID.randomUUID())).status());

        // One metric across Organizations: each Organization is a row, and the total is the sum of its weeks.
        UsageStats.MetricView view = usage.metric(superAdmin, "payroll_employees_paid");
        UsageStats.OrganizationSeries mine = view.organizations().stream().filter(o -> o.id().equals(k.org.id.toString())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3").compareTo(mine.total()));
        assertEquals(404, assertThrows(ApiException.class, () -> usage.metric(superAdmin, "no_such_metric")).status());
        assertTrue(usage.metrics(superAdmin).stream().anyMatch(m -> m.code().equals("members") && m.kind().equals("level") && !m.isUse()));
    }


    @Test
    void lateRecordsAreCorrectedAndOverlappingRunsTakeTurns() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        db.inOwner(c -> Sql.update(c, "update platform.tenant set created_at = now() - interval '20 days' where id = ?", k.org.id));
        usage.run(false);
        LocalDate yesterday = TODAY.minusDays(1);
        LocalDate lastWeek = TODAY.minusDays(5);
        db.inOwner(c -> Sql.update(c, "update platform.usage_daily set value = 999 where tenant_id = ? and metric = 'members' and day in (?, ?)", k.org.id, yesterday, lastWeek));
        // A number that was wrong (counted before the day was over, or before a late record arrived) is put right by the next run.
        usage.run(false);
        assertTrue(stored(k.org.id, yesterday, "members").compareTo(new BigDecimal("999")) < 0, "yesterday is counted again");
        assertTrue(stored(k.org.id, lastWeek, "members").compareTo(new BigDecimal("999")) < 0, "so are the last seven days");

        // Two runs at once: one counts, the other sees it is taken and stops, and neither fails.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<UsageStats.RunResult> a = pool.submit(() -> usage.run(true));
            java.util.concurrent.Future<UsageStats.RunResult> b = pool.submit(() -> usage.run(true));
            UsageStats.RunResult ra = a.get();
            UsageStats.RunResult rb = b.get();
            assertTrue(ra.ran() || rb.ran(), "at least one counted");
            assertTrue(ra.failures().isEmpty() && rb.failures().isEmpty());
        } finally {
            pool.shutdownNow();
        }

        // While another count holds the lock, a refresh by hand is told so rather than waiting or clashing.
        db.inOwner(c -> {
            Sql.one(c, "select pg_advisory_xact_lock(hashtext('usage_stats'))");
            ApiException busy = assertThrows(ApiException.class, () -> usage.refresh(superAdmin));
            assertEquals(409, busy.status());
            assertEquals("usage.running", busy.code());
            assertFalse(usage.run(false).ran());
            return null;
        });
        assertTrue(usage.refresh(superAdmin).ran(), "and it works again once the lock is free");
    }

    @Test
    void aClosedOrganizationIsNotCountedAndTheBillingRoleMayLookButNotCount() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case closing = organization();
        Case staying = organization();
        platform.changeOrganizationStatus(superAdmin, closing.org.id, "close");
        usage.run(true);
        assertEquals(null, stored(closing.org.id, TODAY, "members"), "closed: nothing counted");
        assertTrue(stored(staying.org.id, TODAY, "members") != null);
        assertTrue(usage.grid(superAdmin).organizations().stream().noneMatch(o -> o.id().equals(closing.org.id.toString())));

        String email = "billing" + SEQUENCE.incrementAndGet() + "@platform.test";
        platform.addPlatformUser(superAdmin, email, "billing", PASSWORD);
        SessionCtx billing = auth.resolve(auth.enterConsole(auth.resolve(auth.login(email, PASSWORD))));
        assertTrue(usage.grid(billing).organizations().size() >= 1);
        assertTrue(usage.metric(billing, "members") != null);
        assertEquals(403, assertThrows(ApiException.class, () -> usage.refresh(billing)).status());
    }

    @Test
    void aWeekIsWorkedOutTheWayEachMetricsKindSays() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        usage.run(true);
        LocalDate monday = TODAY.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        // Known numbers on three complete days of last week: a count adds up, a level is the last day's, a median averages the days that have one.
        db.inOwner(c -> {
            Sql.update(c, "delete from platform.usage_daily where tenant_id = ? and day >= ? and metric in ('signins_web', 'members', 'payroll_hours_to_approval')", k.org.id, monday.minusWeeks(1));
            for (int i = 0; i < 3; i++) {
                LocalDate day = monday.minusWeeks(1).plusDays(i);
                Sql.update(c, "insert into platform.usage_daily (tenant_id, day, metric, value) values (?, ?, 'signins_web', 2), (?, ?, 'members', ?), (?, ?, 'payroll_hours_to_approval', ?) "
                        + "on conflict do nothing", k.org.id, day, k.org.id, day, 5 + i, k.org.id, day, 10 + 2 * i);
            }
            return null;
        });
        UsageStats.Trend trend = usage.trend(superAdmin, k.org.id);
        int last = trend.weekStarts().size() - 2;   // the week before this one
        assertEquals(monday.minusWeeks(1).toString(), trend.weekStarts().get(last));
        java.util.function.Function<String, BigDecimal> week = code -> trend.series().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow().weeks().get(last);
        assertEquals(0, new BigDecimal("6").compareTo(week.apply("signins_web")), "a count adds up the days: 2 + 2 + 2");
        assertEquals(0, new BigDecimal("7").compareTo(week.apply("members")), "a level is the last day's: 5, 6, 7");
        assertEquals(0, new BigDecimal("12").compareTo(week.apply("payroll_hours_to_approval")), "a median is the average of the days that have one: 10, 12, 14");
    }

    @Test
    void everyViewExportsAsCsvThatSpreadsheetsWillNotRunAsAFormula() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Map<String, Object> created = platform.createOrganization(superAdmin, "=HYPERLINK(\"http://x.test\",\"a, b\")", "usage-csv-" + SEQUENCE.incrementAndGet(), "growth", "admin@usage-csv.test");
        UUID tenant = UUID.fromString((String) created.get("id"));
        usage.run(true);
        String grid = usage.gridCsv(superAdmin);
        assertTrue(grid.startsWith("Organization,Status,Members,Core HR state,"), grid.lines().findFirst().orElse(""));
        assertTrue(grid.contains("\"'=HYPERLINK(\"\"http://x.test\"\",\"\"a, b\"\")\""), "a leading = is defused and the quotes and comma are escaped: " + grid);
        String trend = usage.trendCsv(superAdmin, tenant);
        assertTrue(trend.startsWith("Metric,Area,How a week is worked out,"), trend);
        String metric = usage.metricCsv(superAdmin, "signins_web");
        assertTrue(metric.startsWith("Organization,") && metric.contains(",Total"), metric);
        assertEquals(404, assertThrows(ApiException.class, () -> usage.metricCsv(superAdmin, "nope")).status());
    }

    @Test
    void onlyThePlatformTeamSeesItAndOnlyASuperAdminRefreshesIt() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        String email = "support" + SEQUENCE.incrementAndGet() + "@platform.test";
        platform.addPlatformUser(superAdmin, email, "support", PASSWORD);
        SessionCtx support = auth.resolve(auth.enterConsole(auth.resolve(auth.login(email, PASSWORD))));
        assertTrue(usage.grid(support).organizations().size() >= 1, "support may look");
        assertEquals(403, assertThrows(ApiException.class, () -> usage.refresh(support)).status(), "but not run it");
        // An Organization's own people, even its Org Admin, are not on the platform.
        assertEquals(403, assertThrows(ApiException.class, () -> usage.grid(k.org.admin)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> usage.trend(k.org.admin, k.org.id)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> usage.metric(k.operator, "members")).status());
        assertEquals(403, assertThrows(ApiException.class, () -> usage.gridCsv(k.asha)).status());
        assertTrue(usage.refresh(superAdmin).failures().isEmpty());
    }

    @Test
    void rowsOlderThanTwoYearsAreDroppedAndTheConsoleStillReadsNoOrganizationData() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        usage.run(false);
        db.inOwner(c -> Sql.update(c, "insert into platform.usage_daily (tenant_id, day, metric, value) values (?, ?, 'members', 4)", k.org.id, TODAY.minusYears(2).minusDays(3)));
        db.inOwner(c -> Sql.update(c, "insert into platform.usage_daily (tenant_id, day, metric, value) values (?, ?, 'members', 4)", k.org.id, TODAY.minusYears(2).plusDays(3)));
        usage.run(false);
        assertEquals(null, stored(k.org.id, TODAY.minusYears(2).minusDays(3), "members"), "past two years: dropped");
        assertTrue(stored(k.org.id, TODAY.minusYears(2).plusDays(3), "members") != null, "inside two years: kept");

        // Counting needs no access for the console: it holds numbers, and cannot read any table an Organization keeps.
        for (String table : List.of("punch", "leave_request", "onboarding", "payroll_run", "payslip", "tax_declaration", "exit_case", "membership", "audit_event")) {
            Boolean any = db.inOwner(c -> (Boolean) Sql.one(c, "select has_table_privilege('app_platform', 'app." + table + "', 'select') as p").get("p"));
            assertEquals(false, any, "app_platform must not read app." + table);
        }
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.query(c, "select count(*) from app.punch")));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.query(c, "select * from app.leave_request")));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.query(c, "select * from auth.session")));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "wage-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static NewEmployee person(String name, String pan, String uan, String account, SessionCtx user) {
        return new NewEmployee(name, null, "1995-04-12", "female", null, "9876543210", "permanent",
                TODAY.minusYears(1).toString(), null, null, null, "Analyst", null, null, user.membershipId().toString(),
                pan, null, null, uan, name, account, "HDFC0001234", null, null);
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
