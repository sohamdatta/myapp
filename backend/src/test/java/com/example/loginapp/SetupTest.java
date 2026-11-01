package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SetupService;
import com.example.loginapp.core.SetupService.Applied;
import com.example.loginapp.core.SetupService.Choice;
import com.example.loginapp.core.SetupService.LeaveChoice;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.StarterSetups;
import com.example.loginapp.core.UserService;

/**
 * Starting an Organization from a starting setup, against a real PostgreSQL: it fills only empty
 * sections, in one transaction, for the Org Admin alone; leave follows the Leave module; holidays
 * are the next twelve months in India time; and what payroll still needs is counted from the data.
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class SetupTest {
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Clock OCT_28_2026 = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static AttendanceSettingsService attendance;
    private static SetupService setup;
    private static SessionCtx superAdmin;

    private record Org(UUID id, SessionCtx admin) {}

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_setup with (force)");
            st.execute("create database hrms_test_setup");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the setup tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_setup";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        attendance = new AttendanceSettingsService(db);
        setup = new SetupService(db, OCT_28_2026);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── filling an empty Organization ───────────────────────────────────

    @Test
    void aFactorySetupFillsAnEmptyOrganizationOnceAndIsAudited() {
        Org org = newOrg(null);
        SetupService.Status before = setup.status(org.admin());
        assertTrue(before.open().calendar() && before.open().leave());
        assertEquals(0, before.facts().workLocations() + before.facts().shifts() + before.facts().leaveTypes());
        assertEquals(List.of("office", "factory", "retail"), before.starters().stream().map(StarterSetups.Starter::id).toList());
        assertTrue(before.states().contains(new SetupService.State("TN", "Tamil Nadu")), "the states to choose from come from the same list that checks them");

        Applied applied = setup.apply(org.admin(), new Choice("factory", "  Coimbatore unit 1 ", "tn", null));
        assertEquals(new Applied(true, true, 1, 3, 3, 3), applied);

        assertEquals(List.of("Coimbatore unit 1|TN|none"), rows(org, "select name || '|' || state_code || '|' || punch_check as v from app.work_location"));
        assertEquals(List.of("Early", "General", "Late"), rows(org, "select name as v from app.shift order by name"));
        assertEquals(List.of("Casual leave|8.0|0.0|lapse", "Earned leave|15.0|30.0|lapse", "Sick leave|7.0|0.0|lapse"),
                rows(org, "select name || '|' || yearly_quota || '|' || carry_forward_cap || '|' || excess_at_year_end as v from app.leave_type order by name"));
        // From 28 October 2026 the next twelve months hold three fixed national holidays, each for every location.
        assertEquals(List.of("2027-01-26|Republic Day", "2027-08-15|Independence Day", "2027-10-02|Gandhi Jayanti"),
                rows(org, "select on_date || '|' || name as v from app.holiday where work_location_id is null order by on_date"));
        assertEquals(1, rows(org, "select action as v from app.audit_event where action = 'setup.starter_applied'").size());
        assertEquals(3, rows(org, "select action as v from app.audit_event where action = 'attendance.shift_created'").size());

        SetupService.Status after = setup.status(org.admin());
        assertFalse(after.open().calendar() || after.open().leave());
        assertEquals(1, after.facts().workLocations());
        assertEquals(3, after.facts().leaveTypes());
        assertError(409, "setup.already_started", () -> setup.apply(org.admin(), new Choice("office", "Head office", "KA", null)));
        assertEquals(3, rows(org, "select name as v from app.shift").size(), "a second try changed nothing");
    }

    @Test
    void leaveChangesAreLaidOverTheStartingSetup() {
        Org org = newOrg(null);
        setup.apply(org.admin(), new Choice("office", "Head office", "MH", List.of(
                new LeaveChoice("casual", new BigDecimal("10.5"), "UP_TO_15"),
                new LeaveChoice("earned", null, "UP_TO_30_PAY_REST"))));
        assertEquals(List.of("Casual leave|10.5|15.0|lapse", "Earned leave|15.0|30.0|encash", "Sick leave|6.0|0.0|lapse"),
                rows(org, "select name || '|' || yearly_quota || '|' || carry_forward_cap || '|' || excess_at_year_end as v from app.leave_type order by name"));
    }

    @Test
    void onlyEmptySectionsAreFilled() {
        // Leave is off: the work calendar is still filled, because payroll takes the state from it.
        Org noLeave = newOrg(List.of("attendance", "payroll"));
        assertFalse(setup.status(noLeave.admin()).open().leave());
        assertEquals(new Applied(true, false, 1, 2, 3, 0), setup.apply(noLeave.admin(), new Choice("retail", "Main shop", "GA", null)));
        assertEquals(0, rows(noLeave, "select name as v from app.leave_type").size());

        // A location made by hand first: only leave is filled, and nothing of the calendar is touched.
        Org handMade = newOrg(null);
        attendance.saveLocation(handMade.admin(), null, new LocationInput("Plant", null, "KA", null, null, null, List.of(), "none", true));
        assertEquals(new Applied(false, true, 0, 0, 0, 3), setup.apply(handMade.admin(), new Choice("factory", null, null, null)),
                "with the calendar already started, no location is needed");
        assertEquals(List.of("Plant"), rows(handMade, "select name as v from app.work_location"));
        assertEquals(0, rows(handMade, "select name as v from app.shift").size());

        // With the calendar and leave both started, there is nothing to fill.
        assertError(409, "setup.already_started", () -> setup.apply(handMade.admin(), new Choice("factory", "Again", "KA", null)));
    }

    @Test
    void fixedHolidaysAreTheNextTwelveMonths() {
        StarterSetups.FixedHoliday republicDay = StarterSetups.NATIONAL_HOLIDAYS.getFirst();
        assertEquals(List.of(LocalDate.of(2027, 1, 26)), StarterSetups.holidayDates(republicDay, LocalDate.of(2026, 12, 30)),
                "near the year end, the coming January and not the one just gone");
        assertEquals(List.of(LocalDate.of(2026, 1, 26)), StarterSetups.holidayDates(republicDay, LocalDate.of(2026, 1, 26)), "today counts");
        assertEquals(List.of(LocalDate.of(2027, 1, 26)), StarterSetups.holidayDates(republicDay, LocalDate.of(2026, 1, 27)));
        // 1 January 2027 and a 26 January a year ahead: only one date falls inside the twelve months.
        assertEquals(1, StarterSetups.holidayDates(republicDay, LocalDate.of(2027, 1, 1)).size());
    }

    @Test
    void holidaysFollowTheIndianDayNotUtc() {
        // 18:30 UTC on 26 January 2026 is already 27 January in India, so that year's Republic Day has gone.
        SetupService lateInTheEvening = new SetupService(db, Clock.fixed(Instant.parse("2026-01-26T18:30:00Z"), ZoneOffset.UTC));
        Org org = newOrg(null);
        assertEquals(3, lateInTheEvening.apply(org.admin(), new Choice("office", "Office", "DL", null)).holidays());
        assertEquals(List.of("2026-08-15|2026-10-02|2027-01-26"),
                rows(org, "select string_agg(on_date::text, '|' order by on_date) as v from app.holiday"));
    }

    @Test
    void twoPeopleApplyingAtOnceFillItOnce() throws Exception {
        Org org = newOrg(null);
        int tries = 4;
        ExecutorService pool = Executors.newFixedThreadPool(tries);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < tries; i++) {
            String starter = i % 2 == 0 ? "factory" : "office";
            Callable<Integer> attempt = () -> {
                go.await();
                try {
                    setup.apply(org.admin(), new Choice(starter, "Plant " + starter, "KA", null));
                    return 200;
                } catch (ApiException e) {
                    return e.status();
                }
            };
            results.add(pool.submit(attempt));
        }
        go.countDown();
        int ok = 0;
        int refused = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) {
                ok++;
            } else {
                refused++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "exactly one of the simultaneous requests fills the Organization");
        assertEquals(tries - 1, refused);
        assertEquals(1, rows(org, "select name as v from app.work_location").size());
    }

    // ── who may, and what is refused ────────────────────────────────────

    @Test
    void onlyTheOrgAdminMaySeeOrApply() {
        Org org = newOrg(null);
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx employee = join(org, "emp", List.of("employee"));
        for (SessionCtx who : List.of(hr, employee)) {
            assertError(403, "permission.denied", () -> setup.status(who));
            assertError(403, "permission.denied", () -> setup.apply(who, new Choice("office", "Office", "KA", null)));
        }
        assertEquals(0, rows(org, "select name as v from app.work_location").size());
        assertEquals("tenant.required", assertThrows(ApiException.class, () -> setup.status(superAdmin)).code(),
                "the platform console has no Organization to set up");
    }

    @Test
    void badChoicesAreRefusedWithWhatToDo() {
        Org org = newOrg(null);
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), null));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("mine", "Office", "KA", null)));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", " ", "KA", null)));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "x".repeat(81), "KA", null)));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", "ZZ", null)));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", null, null)));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", null, "KA", null)));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", "KA",
                List.of(new LeaveChoice("bereavement", BigDecimal.TEN, null)))));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", "KA",
                List.of(new LeaveChoice("casual", BigDecimal.ONE, null), new LeaveChoice("casual", BigDecimal.TWO, null)))));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", "KA",
                List.of(new LeaveChoice("casual", new BigDecimal("0.3"), null)))));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", "KA",
                List.of(new LeaveChoice("casual", BigDecimal.ZERO, null)))));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", "KA",
                List.of(new LeaveChoice("casual", new BigDecimal("61"), null)))));
        assertError(400, "request.invalid", () -> setup.apply(org.admin(), new Choice("office", "Office", "KA",
                List.of(new LeaveChoice("casual", BigDecimal.TEN, "FOREVER")))));
        assertEquals(0, rows(org, "select name as v from app.work_location").size(), "a refused choice leaves nothing behind");
        assertEquals(0, rows(org, "select name as v from app.leave_type").size());
    }

    @Test
    void oneOrganizationNeverSeesOrChangesAnother() {
        Org a = newOrg(null);
        Org b = newOrg(null);
        setup.apply(a.admin(), new Choice("factory", "Plant A", "TN", null));
        assertEquals(0, setup.status(b.admin()).facts().workLocations());
        assertTrue(setup.status(b.admin()).open().calendar());
        assertEquals(0, rows(b, "select name as v from app.shift").size());
        assertEquals(0, rows(b, "select action as v from app.audit_event where action like 'setup.%'").size());
    }

    // ── what payroll still needs ────────────────────────────────────────

    @Test
    void readinessNamesStatesWithoutProfessionalTaxRulesAndDraftRules() {
        Org org = newOrg(null);
        SetupService.Readiness empty = setup.status(org.admin()).readiness();
        assertTrue(empty.applies());
        assertEquals(0, empty.noBank() + empty.noPan() + empty.withoutLocation());
        assertTrue(empty.statesWithoutRules().isEmpty());
        assertTrue(empty.draftRules(), "every rule is still awaiting the consultant's sign-off");

        setup.apply(org.admin(), new Choice("office", "Chennai office", "TN", null));
        assertEquals(List.of("TN"), setup.status(org.admin()).readiness().statesWithoutRules());

        Org covered = newOrg(null);
        setup.apply(covered.admin(), new Choice("office", "Pune office", "MH", null));
        assertTrue(setup.status(covered.admin()).readiness().statesWithoutRules().isEmpty(), "Maharashtra has a professional tax rule");

        Org noPayroll = newOrg(List.of("attendance", "leave"));
        assertFalse(setup.status(noPayroll.admin()).readiness().applies());
    }

    @Test
    void locationsWithoutAStateAreCounted() {
        Org org = newOrg(null);
        attendance.saveLocation(org.admin(), null, new LocationInput("Depot", null, null, null, null, null, List.of(), "none", true));
        assertEquals(1, setup.status(org.admin()).readiness().locationsWithoutState());
    }

    @Test
    void aNewOrgAdminIsToldSetupIsOpenUntilTheOrganizationHasALocation() {
        Org org = newOrg(null);
        assertEquals(true, auth.me(org.admin()).get("setupOpen"));
        setup.apply(org.admin(), new Choice("office", "Office", "KA", null));
        assertEquals(false, auth.me(org.admin()).get("setupOpen"));
        SessionCtx employee = join(org, "emp", List.of("employee"));
        assertEquals(false, auth.me(employee).get("setupOpen"), "only the Org Admin is sent to Getting started");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Org newOrg(List<String> modules) {
        String slug = "setup-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test", modules);
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        return new Org(UUID.fromString((String) created.get("id")), admin);
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String token = (String) users.invite(org.admin(), name + "@" + org.id() + ".test", roles).get("token");
        return auth.resolve(invitations.accept(token, PASSWORD, null));
    }

    /** One text value per row of the query, read inside the Organization. */
    private static List<String> rows(Org org, String sql) {
        return db.inTenant(org.id(), c -> Sql.query(c, sql).stream().map(r -> String.valueOf(r.get("v"))).toList());
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(status, e.status(), e.getMessage());
        assertEquals(code, e.code(), e.getMessage());
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
