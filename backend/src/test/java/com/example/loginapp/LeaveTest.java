package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceService;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AttendanceSettingsService.SettingsInput;
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.LeaveAccrual;
import com.example.loginapp.core.LeaveAdminService;
import com.example.loginapp.core.LeaveAdminService.TypeInput;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Exercises leave against a real PostgreSQL, with a clock the tests control.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class LeaveTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** A Monday far enough ahead that every user has joined before it, and early enough in a year to leave room after it. */
    private static final LocalDate MONDAY = firstMonday();
    private static final LocalDate SUNDAY = MONDAY.plusDays(6);

    private static final MovableClock CLOCK = new MovableClock();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static AttendanceService attendance;
    private static AttendanceSettingsService settings;
    private static LeaveService leave;
    private static LeaveAdminService admin;
    private static SessionCtx superAdmin;

    private static LocalDate firstMonday() {
        LocalDate d = LocalDate.now(IST).plusDays(40).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        // keep the test fortnight inside one calendar year and one month-safe stretch
        if (d.getMonthValue() == 12 && d.getDayOfMonth() > 10) {
            d = LocalDate.of(d.getYear() + 1, 1, 10).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        }
        return d;
    }

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));   // as the application does
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_leave with (force)");
            st.execute("create database hrms_test_leave");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the leave tests");

        String url = "jdbc:postgresql://" + host + "/hrms_test_leave";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);   // a second run must change nothing
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        attendance = new AttendanceService(db, CLOCK);
        settings = new AttendanceSettingsService(db);
        leave = new LeaveService(db, CLOCK);
        admin = new LeaveAdminService(db, CLOCK);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── applying, deciding, cancelling ──────────────────────────────────

    @Test
    void leaveIsAppliedApprovedShownOnTheDayAndCancelled() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        setUp(org, emp);
        UUID casual = type(org, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        now(MONDAY.minusDays(3), "10:00");                               // the Friday before
        admin.adjust(hr, emp.membershipId(), casual, new BigDecimal("5"), null, "Opening balance from the old system");

        Map<String, Object> preview = leave.preview(emp, casual, MONDAY.toString(), MONDAY.plusDays(2).toString(), null);
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) preview.get("days")));
        assertEquals(true, preview.get("covered"));
        UUID id = id(leave.apply(emp, casual, MONDAY.toString(), MONDAY.plusDays(2).toString(), null, "Family function"));
        Map<String, Object> held = leave.balances(emp).get(0);
        assertDays("5", held.get("balance"));
        assertDays("3", held.get("pending"));
        assertDays("2", held.get("available"));

        // the same dates again, and more than is left
        assertError(409, "leave.overlap", () -> leave.apply(emp, casual, MONDAY.plusDays(2).toString(), null, null, "Overlaps the first"));
        assertError(409, "leave.insufficient_balance", () -> leave.apply(emp, casual, MONDAY.plusDays(3).toString(),
                MONDAY.plusDays(5).toString(), null, "Three more days"));
        assertEquals(false, leave.preview(emp, casual, MONDAY.plusDays(3).toString(), MONDAY.plusDays(5).toString(), null).get("covered"));
        assertError(403, "permission.denied", () -> leave.decide(emp, id, true, null));

        Map<String, Object> waiting = leave.requests(hr, null).get(0);
        assertEquals("emp@" + org.slug + ".test", waiting.get("email"));
        assertDays("5", waiting.get("balance"));
        leave.decide(hr, id, true, "Enjoy");
        assertDays("2", leave.balances(emp).get(0).get("balance"));
        assertEquals(List.of("taken", "adjustment"), kinds(emp, casual));

        // not started yet: the user can withdraw it, the days come back and the dates are free again
        leave.cancel(emp, id);
        assertDays("5", leave.balances(emp).get(0).get("balance"));
        assertEquals(List.of("returned", "taken", "adjustment"), kinds(emp, casual));
        UUID again = id(leave.apply(emp, casual, MONDAY.toString(), MONDAY.plusDays(1).toString(), null, "Two days instead"));
        leave.decide(hr, again, true, null);

        now(MONDAY.plusDays(1), "09:00");
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals("on_leave", day.get("status"));
        assertDays("1", day.get("leaveDays"));
        assertEquals(false, day.get("leaveUnpaid"));
        assertTrue(((List<?>) day.get("trail")).contains("Casual leave, approved"));

        // started: only HR can cancel it now
        assertError(409, "leave.started", () -> leave.cancel(emp, again));
        leave.cancelAny(hr, again);
        assertDays("5", leave.balances(emp).get(0).get("balance"));
        assertEquals("absent", day(emp, MONDAY).get("status"));
        assertEquals("cancelled", leave.myRequests(emp).get(0).get("status"));
        assertTrue(actions(org).containsAll(List.of("leave.type_created", "leave.adjusted", "leave.approved", "leave.cancelled")));
    }

    @Test
    void nobodyDecidesOrAdjustsTheirOwnLeave() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        setUp(org, hr);
        UUID casual = type(org, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        now(MONDAY.minusDays(3), "10:00");
        assertError(409, "leave.own_request", () -> admin.adjust(hr, hr.membershipId(), casual, new BigDecimal("5"), null, "For myself"));
        admin.adjust(org.admin, hr.membershipId(), casual, new BigDecimal("5"), null, "Opening balance");
        UUID id = id(leave.apply(hr, casual, MONDAY.toString(), null, null, "A day off"));
        assertEquals(true, leave.requests(hr, "pending").get(0).get("own"));
        assertError(409, "leave.own_request", () -> leave.decide(hr, id, true, null));
        assertError(409, "leave.own_request", () -> admin.applyOnBehalf(hr, hr.membershipId(), casual, MONDAY.plusDays(1).toString(),
                null, null, "Recording my own"));
        // and the database refuses it even if the application did not
        assertError(409, "leave.own_request", () -> db.inTenant(org.id, c -> Sql.update(c,
                "update app.leave_request set status = 'approved', decided_by = ?, decided_at = now() where id = ?", hr.membershipId(), id)));
        leave.decide(org.admin, id, false, "Too busy that week");
        assertEquals("Too busy that week", leave.myRequests(hr).get(0).get("decisionNote"));
        assertDays("5", leave.balances(hr).get(0).get("balance"));
        // ledger entries are add-only for the application's database role
        assertError(403, "permission.denied", () -> db.inTenant(org.id, c -> Sql.update(c, "update app.leave_ledger set days = 99")));
        assertError(403, "permission.denied", () -> db.inTenant(org.id, c -> Sql.update(c, "delete from app.leave_ledger")));
    }

    @Test
    void aShortfallIsRefusedOrBecomesUnpaidAsTheTypeSays() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        setUp(org, emp);
        UUID sick = type(org, "Sick leave", new BigDecimal("0"), "none", null, "unpaid", false);
        UUID unpaid = id(admin.saveType(org.admin, null, new TypeInput("Leave without pay", "regular", false, null, null, null,
                null, null, null, null, null, true)));
        now(MONDAY.minusDays(3), "10:00");
        admin.adjust(org.admin, emp.membershipId(), sick, new BigDecimal("1"), null, "Opening balance");

        Map<String, Object> preview = leave.preview(emp, sick, MONDAY.toString(), MONDAY.plusDays(2).toString(), null);
        assertEquals(true, preview.get("covered"));
        assertDays("2", preview.get("unpaidDays"));
        UUID id = id(leave.apply(emp, sick, MONDAY.toString(), MONDAY.plusDays(2).toString(), null, "Down with a fever"));
        leave.decide(org.admin, id, true, null);
        assertDays("0", leave.balances(emp).stream().filter(b -> b.get("name").equals("Sick leave")).findFirst().orElseThrow().get("balance"));

        UUID lwp = id(leave.apply(emp, unpaid, MONDAY.plusDays(3).toString(), null, null, "Personal work"));
        leave.decide(org.admin, lwp, true, null);
        now(MONDAY.plusDays(5), "09:00");
        // the paid day comes first; the rest of the leave is unpaid
        assertEquals(false, day(emp, MONDAY).get("leaveUnpaid"));
        assertEquals(true, day(emp, MONDAY.plusDays(1)).get("leaveUnpaid"));
        assertEquals(true, day(emp, MONDAY.plusDays(2)).get("leaveUnpaid"));
        Map<String, Object> thursday = day(emp, MONDAY.plusDays(3));
        assertEquals("on_leave", thursday.get("status"));
        assertEquals(true, thursday.get("leaveUnpaid"));
        // unpaid leave has no balance and no ledger entries
        assertTrue(kinds(emp, unpaid).isEmpty());
        assertError(400, "request.invalid", () -> admin.adjust(org.admin, emp.membershipId(), unpaid, BigDecimal.ONE, null, "No balance here"));
    }

    @Test
    void daysOffAreCountedAsTheTypeSaysAndAHalfDayIsHalf() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        setUp(org, emp);
        UUID casual = type(org, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        UUID earned = type(org, "Earned leave", new BigDecimal("0"), "none", null, "refuse", true);
        now(MONDAY.minusDays(3), "10:00");
        admin.adjust(org.admin, emp.membershipId(), casual, new BigDecimal("10"), null, "Opening balance");
        admin.adjust(org.admin, emp.membershipId(), earned, new BigDecimal("10"), null, "Opening balance");
        LocalDate saturday = MONDAY.plusDays(5);
        LocalDate nextMonday = MONDAY.plusDays(7);

        // Saturday to Monday, with the Sunday off in between
        assertDays("2", leave.preview(emp, casual, saturday.toString(), nextMonday.toString(), null).get("days"));
        assertDays("3", leave.preview(emp, earned, saturday.toString(), nextMonday.toString(), null).get("days"));
        assertError(400, "leave.no_days", () -> leave.apply(emp, casual, SUNDAY.toString(), null, null, "A Sunday"));
        assertError(400, "request.invalid", () -> leave.apply(emp, casual, MONDAY.toString(), MONDAY.plusDays(1).toString(), "first",
                "Half of two days"));

        UUID weekend = id(leave.apply(emp, earned, saturday.toString(), nextMonday.toString(), null, "A long weekend"));
        leave.decide(org.admin, weekend, true, null);
        UUID half = id(leave.apply(emp, casual, MONDAY.toString(), null, "first", "Dentist in the morning"));
        assertDays("0.5", leave.myRequests(emp).stream().filter(r -> r.get("id").equals(half.toString())).findFirst().orElseThrow().get("days"));
        leave.decide(org.admin, half, true, null);
        punch(emp, MONDAY, "14:00");
        punch(emp, MONDAY, "18:30");

        now(nextMonday.plusDays(1), "09:00");
        Map<String, Object> monday = day(emp, MONDAY);
        assertEquals("present", monday.get("status"));
        assertDays("0.5", monday.get("leaveDays"));
        assertEquals(0, ((Number) monday.get("lateMinutes")).intValue());
        // the Sunday inside the long weekend is leave, because this type counts days off
        assertEquals("on_leave", day(emp, SUNDAY).get("status"));
        assertDays("7", leave.balances(emp).stream().filter(b -> b.get("name").equals("Earned leave")).findFirst().orElseThrow().get("balance"));
        assertDays("9.5", leave.balances(emp).stream().filter(b -> b.get("name").equals("Casual leave")).findFirst().orElseThrow().get("balance"));
    }

    @Test
    void hrRecordsLeaveOnBehalfOutsideTheUsersOwnWindow() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        setUp(org, emp);
        UUID casual = type(org, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        now(MONDAY, "10:00");
        admin.adjust(hr, emp.membershipId(), casual, new BigDecimal("3"), null, "Opening balance");
        LocalDate longAgo = MONDAY.minusDays(42);   // a Monday six weeks back
        assertError(400, "leave.window", () -> leave.apply(emp, casual, longAgo.toString(), null, null, "Forgot to apply"));
        assertError(400, "leave.window", () -> leave.apply(emp, casual, MONDAY.plusDays(400).toString(), null, null, "Far ahead"));
        Map<String, Object> made = admin.applyOnBehalf(hr, emp.membershipId(), casual, longAgo.toString(), null, null, "Was on leave, told by phone");
        assertEquals("approved", made.get("status"));
        assertDays("2", leave.balances(emp).get(0).get("balance"));
        assertEquals(true, leave.myRequests(emp).get(0).get("appliedByHr"));
        assertError(409, "leave.insufficient_balance", () -> admin.applyOnBehalf(hr, emp.membershipId(), casual,
                MONDAY.toString(), MONDAY.plusDays(4).toString(), null, "More than the balance"));
        assertError(403, "permission.denied", () -> admin.applyOnBehalf(emp, hr.membershipId(), casual, MONDAY.toString(), null, null,
                "Not an administrator"));
        assertTrue(actions(org).contains("leave.applied_on_behalf"));
        // HR Admin alone may see leave but not apply for their own
        assertError(403, "permission.denied", () -> leave.apply(hr, casual, MONDAY.toString(), null, null, "A day off"));
    }

    // ── accrual, comp-off, year end ─────────────────────────────────────

    @Test
    void accrualsAreWrittenOnReadAndOnlyOnce() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID monthly = type(org, "Earned leave", new BigDecimal("18"), "monthly", null, "refuse", false);
        UUID yearly = id(admin.saveType(org.admin, null, new TypeInput("Casual leave", "regular", true, "yearly", new BigDecimal("12"),
                false, null, null, null, null, null, true)));
        now(MONDAY, "10:00");
        LocalDate started = LocalDate.now(IST);                           // the user joined and the types were made today
        List<LeaveAccrual.Entry> expected = LeaveAccrual.due("monthly", new BigDecimal("18"), true, 1, started, MONDAY);
        BigDecimal expectedDays = expected.stream().map(LeaveAccrual.Entry::days).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertFalse(expected.isEmpty());

        List<Map<String, Object>> first = leave.balances(emp);
        List<Map<String, Object>> second = leave.balances(emp);
        assertEquals(first, second);
        Map<String, Object> earned = first.stream().filter(b -> b.get("name").equals("Earned leave")).findFirst().orElseThrow();
        assertEquals(0, expectedDays.compareTo((BigDecimal) earned.get("balance")), earned.toString());
        assertEquals(expected.size(), kinds(emp, monthly).size());
        assertTrue(kinds(emp, monthly).stream().allMatch(k -> k.equals("accrual")));
        // yearly without proration: the whole quota for the year of joining, and again for each leave year begun since
        int years = LeaveAccrual.due("yearly", new BigDecimal("12"), false, 1, started, MONDAY).size();
        assertDays(String.valueOf(12 * years), first.stream().filter(b -> b.get("name").equals("Casual leave")).findFirst().orElseThrow().get("balance"));
        assertEquals(years, kinds(emp, yearly).size());
        // the ledger shows each entry to the user
        assertEquals("accrual", leave.ledger(emp, null, monthly).get(0).get("kind"));
    }

    @Test
    void compOffIsGrantedForAWorkedDayOffUsedAndExpires() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        SessionCtx other = join(org, "other", List.of("employee"));
        setUp(org, emp);
        setUp(org, other);
        UUID comp = id(admin.saveType(org.admin, null, new TypeInput("Comp-off", "comp_off", null, null, null, null, null, null, null,
                null, 30, true)));
        punch(emp, SUNDAY, "10:00");
        punch(emp, SUNDAY, "15:00");                                      // five hours on a weekly off
        punch(other, SUNDAY, "09:30");
        punch(other, SUNDAY, "18:30");                                    // a full day
        now(SUNDAY.plusDays(1), "09:00");

        List<Map<String, Object>> candidates = admin.compOffCandidates(org.admin);
        assertEquals(2, candidates.size());
        Map<String, Object> mine = candidates.stream().filter(c -> c.get("membershipId").equals(emp.membershipId().toString()))
                .findFirst().orElseThrow();
        assertDays("0.5", mine.get("days"));
        admin.grantCompOff(org.admin, emp.membershipId(), null, SUNDAY.toString(), new BigDecimal("0.5"));
        admin.grantCompOff(org.admin, other.membershipId(), comp, SUNDAY.toString(), BigDecimal.ONE);
        assertError(409, "request.refused", () -> admin.grantCompOff(org.admin, emp.membershipId(), null, SUNDAY.toString(), BigDecimal.ONE));
        assertError(400, "request.invalid", () -> admin.grantCompOff(org.admin, emp.membershipId(), null, SUNDAY.toString(), new BigDecimal("2")));
        assertTrue(admin.compOffCandidates(org.admin).isEmpty());
        assertDays("0.5", leave.balances(emp).get(0).get("balance"));

        // one uses it in time
        UUID id = id(leave.apply(emp, comp, SUNDAY.plusDays(2).toString(), null, "second", "Afternoon off in lieu"));
        leave.decide(org.admin, id, true, null);
        // thirty-one days on, the unused day has expired and the used half has not
        now(SUNDAY.plusDays(31), "09:00");
        assertDays("0", leave.balances(emp).get(0).get("balance"));
        assertDays("0", leave.balances(other).get(0).get("balance"));
        assertDays("0", leave.balances(other).get(0).get("balance"));     // read again: nothing is expired twice
        assertEquals(List.of("taken", "comp_off_earned"), kinds(emp, comp));
        assertEquals(List.of("comp_off_expired", "comp_off_earned"), kinds(other, comp));
        assertTrue(actions(org).contains("leave.comp_off_granted"));
    }

    @Test
    void aLeaveYearIsPreviewedThenClosedOnce() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        setUp(org, emp);
        UUID earned = id(admin.saveType(org.admin, null, new TypeInput("Earned leave", "regular", true, "none", null, null,
                new BigDecimal("2"), "encash", null, null, null, true)));
        UUID casual = id(admin.saveType(org.admin, null, new TypeInput("Casual leave", "regular", true, "none", null, null,
                new BigDecimal("0"), "lapse", null, null, null, true)));
        UUID sick = type(org, "Sick leave", new BigDecimal("0"), "none", null, "refuse", false);   // no cap: everything carries
        now(MONDAY, "10:00");
        for (UUID t : List.of(earned, casual, sick)) {
            admin.adjust(org.admin, emp.membershipId(), t, new BigDecimal("5"), null, "Opening balance");
        }
        UUID pending = id(leave.apply(emp, casual, MONDAY.plusDays(1).toString(), null, null, "A day off"));
        int year = MONDAY.getYear();

        assertEquals("This leave year has not ended yet", admin.yearEndPreview(org.admin, year).get("blocked"));
        assertError(409, "request.refused", () -> admin.yearEndRun(org.admin, year));
        now(LocalDate.of(year + 1, 1, 15), "10:00");
        assertError(409, "leave.year_open_requests", () -> admin.yearEndRun(org.admin, year));
        leave.decide(org.admin, pending, false, "Not decided in time");

        long before = entries(org);
        Map<String, Object> preview = admin.yearEndPreview(org.admin, year);
        assertEquals(before, entries(org));                               // a preview writes nothing
        List<?> rows = (List<?>) preview.get("rows");
        assertEquals(2, rows.size());
        assertDays("5", preview.get("lapsed"));
        assertDays("3", preview.get("encashed"));

        Map<String, Object> run = admin.yearEndRun(org.admin, year);
        assertEquals(rows, run.get("rows"));
        assertEquals(before + 2, entries(org));
        Map<String, BigDecimal> balances = new java.util.HashMap<>();
        leave.balances(emp).forEach(b -> balances.put((String) b.get("name"), (BigDecimal) b.get("balance")));
        assertDays("2", balances.get("Earned leave"));
        assertDays("0", balances.get("Casual leave"));
        assertDays("5", balances.get("Sick leave"));
        assertEquals("encash", kinds(emp, earned).get(0));
        assertEquals("lapse", kinds(emp, casual).get(0));

        // closed means closed
        assertError(409, "leave.year_closed", () -> admin.yearEndRun(org.admin, year));
        assertEquals(true, admin.yearEndPreview(org.admin, year).get("closed"));
        assertError(409, "leave.year_closed", () -> admin.adjust(org.admin, emp.membershipId(), sick, BigDecimal.ONE,
                MONDAY.toString(), "A late correction in the closed year"));
        assertError(409, "leave.year_closed", () -> admin.applyOnBehalf(org.admin, emp.membershipId(), sick, MONDAY.toString(), null, null,
                "Leave in the closed year"));
        assertError(409, "leave.year_closed", () -> settings.saveSettings(org.admin, new SettingsInput(4, null, null, null, null)));
        admin.adjust(org.admin, emp.membershipId(), sick, BigDecimal.ONE, null, "The correction, in the open year");
        assertTrue(actions(org).contains("leave.year_closed"));
        assertError(403, "permission.denied", () -> admin.yearEndPreview(emp, year));
    }

    // ── leave and work from home on the same date ───────────────────────

    @Test
    void leaveOnADateWithdrawsWorkFromHomeAndBlocksNewRequests() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        setUp(org, emp);
        UUID casual = type(org, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        now(MONDAY.minusDays(3), "10:00");
        admin.adjust(org.admin, emp.membershipId(), casual, new BigDecimal("5"), null, "Opening balance");
        UUID home = UUID.fromString((String) ((Map<?, ?>) ((List<?>) attendance.createRequests(emp, "work_from_home", MONDAY.toString(),
                null, null, null, null, "A day at home").get("requests")).get(0)).get("id"));
        attendance.decideRequest(org.admin, home, true, null);

        UUID id = id(leave.apply(emp, casual, MONDAY.toString(), MONDAY.plusDays(1).toString(), null, "Plans changed"));
        leave.decide(org.admin, id, true, null);
        assertEquals("cancelled", attendance.myRequests(emp).stream().filter(r -> r.get("id").equals(home.toString()))
                .findFirst().orElseThrow().get("status"));
        assertError(409, "attendance.leave_on_date", () -> attendance.createRequests(emp, "work_from_home",
                MONDAY.plusDays(1).toString(), null, null, null, null, "Home on a leave day"));
        now(MONDAY.plusDays(2), "09:00");
        assertEquals("on_leave", day(emp, MONDAY).get("status"));

        List<Map<String, Object>> calendar = leave.calendar(org.admin, MONDAY.toString(), SUNDAY.toString());
        assertEquals(1, calendar.size());
        assertEquals("Casual leave", calendar.get(0).get("label"));
        assertError(400, "request.invalid", () -> leave.calendar(org.admin, MONDAY.toString(), MONDAY.plusDays(200).toString()));
    }

    // ── who can see and do what ─────────────────────────────────────────

    @Test
    void scopesDecideWhoSeesWhoseLeave() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin"));
        SessionCtx manager = join(org, "manager", List.of("manager", "employee"));
        SessionCtx auditor = join(org, "auditor", List.of("auditor"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        setUp(org, emp);
        UUID casual = type(org, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        now(MONDAY.minusDays(3), "10:00");
        admin.adjust(hr, emp.membershipId(), casual, new BigDecimal("5"), null, "Opening balance");
        UUID id = id(leave.apply(emp, casual, MONDAY.toString(), null, null, "A day off"));

        // an employee sees only their own
        assertError(403, "permission.denied", () -> leave.requests(emp, null));
        assertError(403, "permission.denied", () -> leave.calendar(emp, MONDAY.toString(), SUNDAY.toString()));
        assertError(404, "not_found", () -> leave.userBalances(emp, hr.membershipId()));
        assertError(403, "permission.denied", () -> admin.allBalances(emp));
        assertEquals(1, leave.userBalances(emp, emp.membershipId()).size());
        assertEquals(1, leave.types(emp).size());

        // a manager with nobody reporting to them sees nobody's leave, and others' requests do not exist for them
        assertTrue(leave.requests(manager, null).isEmpty());
        assertTrue(leave.calendar(manager, MONDAY.toString(), SUNDAY.toString()).isEmpty());
        assertError(404, "not_found", () -> leave.decide(manager, id, true, null));
        assertError(404, "not_found", () -> leave.ledger(manager, emp.membershipId(), casual));

        // the auditor reads everything and changes nothing
        assertEquals(5, admin.allBalances(auditor).size());
        assertEquals(1, leave.calendar(auditor, MONDAY.toString(), SUNDAY.toString()).size());
        assertEquals(1, leave.ledger(auditor, emp.membershipId(), casual).size());
        assertError(403, "permission.denied", () -> leave.requests(auditor, null));
        assertError(403, "permission.denied", () -> leave.decide(auditor, id, true, null));
        assertError(403, "permission.denied", () -> admin.saveType(auditor, null, new TypeInput("X", null, null, null, null, null, null,
                null, null, null, null, null)));
        assertEquals("self", auth.me(emp).get("scopes") instanceof Map<?, ?> scopes ? scopes.get("leave.apply") : null);

        // a support session reads; it cannot apply, decide or adjust
        Map<String, Object> grant = platform.requestGrant(superAdmin, org.id, "read_only", "Ticket 91: a leave balance looks wrong");
        SessionCtx support = auth.resolve(platform.startSupportSession(superAdmin, UUID.fromString((String) grant.get("id"))));
        assertEquals(5, admin.allBalances(support).size());
        assertError(403, "permission.denied", () -> leave.balances(support));
        assertError(403, "permission.denied", () -> leave.decide(support, id, true, null));
        assertError(403, "permission.denied", () -> admin.adjust(support, emp.membershipId(), casual, BigDecimal.ONE, null, "From support"));
    }

    @Test
    void organizationsCannotSeeOrUseEachOthersLeave() {
        Org a = newOrg();
        Org b = newOrg();
        SessionCtx emp = join(a, "emp", List.of("employee"));
        setUp(a, emp);
        UUID casual = type(a, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        now(MONDAY.minusDays(3), "10:00");
        admin.adjust(a.admin, emp.membershipId(), casual, new BigDecimal("5"), null, "Opening balance");
        UUID id = id(leave.apply(emp, casual, MONDAY.toString(), null, null, "A day off"));

        assertTrue(leave.types(b.admin).isEmpty());
        assertTrue(leave.requests(b.admin, null).isEmpty());
        assertTrue(leave.calendar(b.admin, MONDAY.toString(), SUNDAY.toString()).isEmpty());
        assertError(404, "not_found", () -> leave.decide(b.admin, id, true, null));
        assertError(404, "not_found", () -> leave.userBalances(b.admin, emp.membershipId()));
        assertError(404, "not_found", () -> admin.adjust(b.admin, emp.membershipId(), casual, BigDecimal.ONE, null, "Reaching across"));
        setUp(b, b.admin);
        assertError(404, "not_found", () -> leave.apply(b.admin, casual, MONDAY.toString(), null, null, "Another Organization's type"));
        assertError(404, "not_found", () -> admin.saveType(b.admin, casual, new TypeInput("Taken over", null, null, "none", null, null, null,
                null, null, null, null, null)));
        assertEquals(0L, db.inTenant(b.id, c -> Sql.one(c, "select count(*) as n from app.leave_ledger")).get("n"));
        // the console and sign-in database roles cannot name the tables at all
        assertError(403, "permission.denied", () -> db.inPlatform(c -> Sql.query(c, "select * from app.leave_ledger")));
        assertError(403, "permission.denied", () -> db.inAuth(c -> Sql.query(c, "select * from app.leave_request")));
        assertError(403, "permission.denied", () -> leave.types(superAdmin));
    }

    @Test
    void leaveTypesAreValidatedAndAnUnusedOneCannotBeAppliedFor() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID casual = type(org, "Casual leave", new BigDecimal("0"), "none", null, "refuse", false);
        assertError(409, "request.refused", () -> type(org, "CASUAL LEAVE", new BigDecimal("0"), "none", null, "refuse", false));
        assertError(400, "request.invalid", () -> type(org, "Bad", new BigDecimal("0"), "monthly", null, "refuse", false));
        assertError(400, "request.invalid", () -> type(org, "Bad", new BigDecimal("12.25"), "yearly", null, "refuse", false));
        assertError(400, "request.invalid", () -> type(org, "Bad", new BigDecimal("12"), "weekly", null, "refuse", false));
        // comp-off is always paid, has no quota, and expires
        UUID comp = id(admin.saveType(org.admin, null, new TypeInput("Comp-off", "comp_off", false, "yearly", new BigDecimal("9"), null,
                null, null, null, null, null, true)));
        Map<String, Object> made = leave.types(emp).stream().filter(t -> t.get("id").equals(comp.toString())).findFirst().orElseThrow();
        assertEquals(List.of(true, "none", 90), List.of(made.get("paid"), made.get("accrual"), ((Number) made.get("expiryDays")).intValue()));

        now(MONDAY.minusDays(3), "10:00");
        // nobody takes leave before HR has assigned a location and shift
        assertError(409, "attendance.not_assigned", () -> leave.apply(emp, casual, MONDAY.toString(), null, null, "A day off"));
        setUp(org, emp);
        admin.saveType(org.admin, casual, new TypeInput("Casual leave", null, null, "none", null, null, null, null, "unpaid", null, null, false));
        assertError(409, "leave.type_inactive", () -> leave.apply(emp, casual, MONDAY.toString(), null, null, "A day off"));
        assertError(400, "request.invalid", () -> leave.apply(emp, casual, MONDAY.toString(), null, null, "Hm"));
        assertError(400, "request.invalid", () -> leave.apply(emp, casual, MONDAY.toString(), null, "middle", "Not a half"));
        assertTrue(actions(org).contains("leave.type_changed"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** A clock the tests set to the moment they want. */
    private static final class MovableClock extends Clock {
        private volatile Instant instant = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MovableClock parent = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return parent.withZone(other);
                }

                @Override
                public Instant instant() {
                    return parent.instant;
                }
            };
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Sets the time to a local time in India on the date. */
    private static void now(LocalDate date, String time) {
        CLOCK.instant = date.atTime(LocalTime.parse(time)).atZone(IST).toInstant();
    }

    private static void punch(SessionCtx ctx, LocalDate date, String time) {
        now(date, time);
        attendance.punch(ctx, null, null, null, "10.0.0.1");
    }

    private static Map<String, Object> day(SessionCtx ctx, LocalDate date) {
        return attendance.myDays(ctx, YearMonth.from(date).toString()).stream()
                .filter(d -> d.get("workDate").equals(date.toString())).findFirst().orElseThrow();
    }

    /** A work location with no punch check and a 09:30 to 18:30 shift, Sundays off, from well before the test week. */
    private static void setUp(Org org, SessionCtx user) {
        List<Map<String, Object>> locations = settings.locations(org.admin);
        UUID location = locations.isEmpty()
                ? id(settings.saveLocation(org.admin, null, new LocationInput("Office", "Asia/Kolkata", "WB", null, null, null, null, "none", true)))
                : UUID.fromString((String) locations.get(0).get("id"));
        List<Map<String, Object>> shifts = settings.shifts(org.admin);
        UUID shift = shifts.isEmpty()
                ? id(settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)))
                : UUID.fromString((String) shifts.get(0).get("id"));
        settings.assign(org.admin, List.of(user.membershipId().toString()), location, shift, List.of(0), MONDAY.minusDays(90).toString());
    }

    private static UUID type(Org org, String name, BigDecimal quota, String accrual, BigDecimal cap, String whenShort, boolean countDaysOff) {
        return id(admin.saveType(org.admin, null, new TypeInput(name, "regular", true, accrual, quota, true, cap, "lapse", whenShort,
                countDaysOff, null, true)));
    }

    private static UUID id(Map<String, Object> row) {
        return UUID.fromString((String) row.get("id"));
    }

    /** The kinds of a user's ledger entries for a type, newest first. */
    private static List<String> kinds(SessionCtx user, UUID leaveTypeId) {
        return db.inTenant(user.tenantId(), c -> Sql.query(c, "select kind from app.leave_ledger where membership_id = ? "
                + "and leave_type_id = ? order by created_at desc, kind desc", user.membershipId(), leaveTypeId))
                .stream().map(r -> (String) r.get("kind")).toList();
    }

    private static long entries(Org org) {
        return (Long) db.inTenant(org.id, c -> Sql.one(c, "select count(*) as n from app.leave_ledger")).get("n");
    }

    private static void assertDays(String expected, Object actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())), "expected " + expected + " but was " + actual);
    }

    /** An Organization with its first Org Admin signed in. */
    private record Org(UUID id, String slug, String adminEmail, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "leave-" + SEQUENCE.incrementAndGet();
        String adminEmail = "admin@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", adminEmail);
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, adminEmail, auth.resolve(token));
    }

    /** Invites name@slug.test with the roles, accepts, and returns the new user's session. */
    private static SessionCtx join(Org org, String name, List<String> roles) {
        String email = name + "@" + org.slug + ".test";
        String invitation = (String) users.invite(org.admin, email, roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static List<String> actions(Org org) {
        return users.auditLog(org.admin).stream().map(e -> (String) e.get("action")).toList();
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(code, e.code(), e.getMessage());
        assertEquals(status, e.status(), e.getMessage());
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
