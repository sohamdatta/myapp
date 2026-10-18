package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import com.example.loginapp.core.AttendanceImportService;
import com.example.loginapp.core.AttendanceImportService.Mapping;
import com.example.loginapp.core.AttendanceService;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AttendanceSettingsService.SettingsInput;
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.DeviceService;
import com.example.loginapp.core.DeviceService.DeviceInput;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Exercises the attendance module against a real PostgreSQL, with a clock the
 * tests control, so punches happen at chosen moments.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class AttendanceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** A Monday far enough ahead that every user has joined before it. The days after it are MONDAY.plusDays(n). */
    private static final LocalDate MONDAY = LocalDate.now(IST).plusDays(40).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private static final LocalDate SUNDAY = MONDAY.plusDays(6);

    private static final MovableClock CLOCK = new MovableClock();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static AttendanceService attendance;
    private static AttendanceSettingsService settings;
    private static AttendanceImportService imports;
    private static DeviceService devices;
    private static SessionCtx superAdmin;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));   // as the application does
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_attendance with (force)");
            st.execute("create database hrms_test_attendance");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the attendance tests");

        String url = "jdbc:postgresql://" + host + "/hrms_test_attendance";
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
        imports = new AttendanceImportService(db, CLOCK);
        devices = new DeviceService(db, CLOCK);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── punching ────────────────────────────────────────────────────────

    @Test
    void nobodyPunchesBeforeHrAssignsALocationAndShift() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        now(MONDAY, "09:30");
        assertEquals(false, attendance.today(emp).get("assigned"));
        assertError(409, "attendance.not_assigned", () -> attendance.punch(emp, null, null, null, "10.0.0.1"));
    }

    @Test
    void aDayOfPunchesBecomesAResultWithATrail() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));

        now(MONDAY, "09:35");
        Map<String, Object> today = attendance.today(emp);
        assertEquals(true, today.get("assigned"));
        assertEquals("in", today.get("nextDirection"));
        assertEquals(MONDAY.toString(), today.get("workDate"));
        assertEquals("out", attendance.punch(emp, null, null, null, "10.0.0.1").get("nextDirection"));
        assertError(409, "attendance.too_soon", () -> attendance.punch(emp, null, null, null, "10.0.0.1"));

        now(MONDAY, "18:40");
        Map<String, Object> after = attendance.punch(emp, null, null, null, "10.0.0.1");
        assertEquals("in", after.get("nextDirection"));
        assertEquals(2, ((List<?>) after.get("punches")).size());
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals("present", day.get("status"));
        assertEquals(545, number(day, "workedMinutes"));
        assertEquals(0, number(day, "lateMinutes"));
        assertEquals(65, number(day, "overtimeMinutes"));
        List<?> trail = (List<?>) day.get("trail");
        assertEquals("Result: present", trail.get(trail.size() - 1));
    }

    @Test
    void aLateStartAndShortDayGiveAHalfDay() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        punch(emp, MONDAY, "10:30");
        punch(emp, MONDAY, "15:00");
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals("half_day", day.get("status"));
        assertEquals(50, number(day, "lateMinutes"));
        assertEquals(210, number(day, "earlyMinutes"));
    }

    @Test
    void aNewDayStartsWithAPunchInEvenIfYesterdayWasLeftOpen() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        punch(emp, MONDAY, "09:30");                       // never punched out
        now(MONDAY.plusDays(1), "09:30");
        assertEquals("in", attendance.today(emp).get("nextDirection"));
        attendance.punch(emp, null, null, null, "10.0.0.1");
        assertEquals(true, day(emp, MONDAY).get("incomplete"));
        assertEquals("absent", day(emp, MONDAY).get("status"));
    }

    @Test
    void aNightShiftCountsToTheDayItStarted() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Plant", "none"), shift(org, "Night", "22:00", "06:00"));
        punch(emp, MONDAY, "21:55");
        now(MONDAY.plusDays(1), "06:10");
        Map<String, Object> today = attendance.punch(emp, null, null, null, "10.0.0.1");
        assertEquals(MONDAY.toString(), today.get("workDate"));
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals("present", day.get("status"));
        assertEquals(495, number(day, "workedMinutes"));
    }

    // ── the punch check ─────────────────────────────────────────────────

    @Test
    void theOfficeNetworkCheckRefusesOtherAddresses() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID office = UUID.fromString((String) settings.saveLocation(org.admin, null, new LocationInput(
                "Office", null, "WB", null, null, null, List.of("203.0.113.0/24", "198.51.100.7"), "ip", true)).get("id"));
        assign(org, emp, office, shift(org, "General", "09:30", "18:30"));
        now(MONDAY, "09:30");
        assertError(403, "attendance.outside_network", () -> attendance.punch(emp, null, null, null, "192.0.2.50"));
        assertError(403, "attendance.outside_network", () -> attendance.punch(emp, 22.5726, 88.3639, 10, "192.0.2.50"));
        Map<String, Object> ok = attendance.punch(emp, null, null, null, "203.0.113.9");
        assertEquals("ip_ok", ((Map<?, ?>) ((List<?>) ok.get("punches")).get(0)).get("checkResult"));
        now(MONDAY, "18:30");
        attendance.punch(emp, null, null, null, "198.51.100.7");        // the single address also counts
        // a refused punch is not stored
        assertEquals(2, ((List<?>) attendance.today(emp).get("punches")).size());
        assertEquals(List.of("203.0.113.0/24", "198.51.100.7"), settings.locations(org.admin).get(0).get("allowedIps"));
    }

    @Test
    void theLocationCheckNeedsAPrecisePositionInsideTheRadius() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID office = UUID.fromString((String) settings.saveLocation(org.admin, null, new LocationInput(
                "Office", "Asia/Kolkata", "WB", 22.5726, 88.3639, 200, List.of(), "location", true)).get("id"));
        assign(org, emp, office, shift(org, "General", "09:30", "18:30"));
        now(MONDAY, "09:30");
        assertEquals(true, attendance.today(emp).get("wantsPosition"));
        assertError(400, "attendance.location_required", () -> attendance.punch(emp, null, null, null, "203.0.113.9"));
        assertError(403, "attendance.outside_location", () -> attendance.punch(emp, 22.5851, 88.3468, 10, "203.0.113.9"));
        assertError(403, "attendance.outside_location", () -> attendance.punch(emp, 22.5726, 88.3639, 800, "203.0.113.9"));
        Map<String, Object> ok = attendance.punch(emp, 22.5730, 88.3642, 15, "203.0.113.9");
        assertEquals("location_ok", ((Map<?, ?>) ((List<?>) ok.get("punches")).get(0)).get("checkResult"));
    }

    @Test
    void eitherCheckAcceptsTheNetworkOrThePosition() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID office = UUID.fromString((String) settings.saveLocation(org.admin, null, new LocationInput(
                "Office", null, null, 22.5726, 88.3639, 200, List.of("203.0.113.0/24"), "either", true)).get("id"));
        assign(org, emp, office, shift(org, "General", "09:30", "18:30"));
        now(MONDAY, "09:30");
        assertError(400, "attendance.location_required", () -> attendance.punch(emp, null, null, null, "192.0.2.50"));
        attendance.punch(emp, 22.5726, 88.3639, 20, "192.0.2.50");
        now(MONDAY, "18:30");
        attendance.punch(emp, null, null, null, "203.0.113.200");
        List<?> punches = (List<?>) attendance.today(emp).get("punches");
        assertEquals("location_ok", ((Map<?, ?>) punches.get(0)).get("checkResult"));
        assertEquals("ip_ok", ((Map<?, ?>) punches.get(1)).get("checkResult"));
    }

    // ── holidays, weekly offs, assignments ──────────────────────────────

    @Test
    void holidaysAndWeeklyOffsDecideTheDayAndChangesAreRecalculated() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID office = location(org, "Office", "none");
        UUID branch = location(org, "Branch", "none");
        assign(org, emp, office, shift(org, "General", "09:30", "18:30"));
        now(SUNDAY.plusDays(1), "09:00");
        assertEquals("absent", day(emp, MONDAY).get("status"));
        assertEquals("weekly_off", day(emp, SUNDAY).get("status"));

        // a holiday at another location does not apply
        settings.addHoliday(org.admin, MONDAY.toString(), "Branch day", branch);
        assertEquals("absent", day(emp, MONDAY).get("status"));
        String id = (String) settings.addHoliday(org.admin, MONDAY.toString(), "Founders day", null).get("id");
        assertError(409, "request.refused", () -> settings.addHoliday(org.admin, MONDAY.toString(), "Again", null));
        assertEquals("holiday", day(emp, MONDAY).get("status"));
        assertEquals(2, settings.holidays(emp, MONDAY.getYear()).stream().filter(h -> h.get("onDate").equals(MONDAY.toString())).count());
        settings.removeHoliday(org.admin, UUID.fromString(id));
        assertEquals("absent", day(emp, MONDAY).get("status"));
        assertTrue(actions(org).containsAll(List.of("attendance.holiday_added", "attendance.holiday_removed")));
    }

    @Test
    void anAssignmentAppliesFromItsDateAndACorrectionReplacesIt() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID office = location(org, "Office", "none");
        UUID general = shift(org, "General", "09:30", "18:30");
        UUID late = shift(org, "Late", "12:00", "21:00");
        UUID membership = emp.membershipId();
        settings.assign(org.admin, List.of(membership.toString()), office, general, List.of(0), MONDAY.plusDays(2).toString());

        punch(emp, MONDAY.plusDays(2), "12:05");
        punch(emp, MONDAY.plusDays(2), "21:00");
        now(MONDAY.plusDays(3), "09:00");
        assertEquals("not_assigned", day(emp, MONDAY).get("status"));
        assertEquals(145, number(day(emp, MONDAY.plusDays(2)), "lateMinutes"));

        // the same start date again, with the right shift: the newest row is the one in force
        settings.assign(org.admin, List.of(membership.toString()), office, late, List.of(0, 6), MONDAY.plusDays(2).toString());
        Map<String, Object> day = day(emp, MONDAY.plusDays(2));
        assertEquals(0, number(day, "lateMinutes"));
        assertEquals("present", day.get("status"));
        Map<String, Object> person = settings.people(org.admin).stream()
                .filter(p -> p.get("membershipId").equals(membership.toString())).findFirst().orElseThrow();
        assertNull(person.get("shiftName"));                              // nothing in force on the real today
        assertEquals(MONDAY.plusDays(2).toString(), person.get("nextChange"));
        // both rows are kept
        assertEquals(2L, db.inTenant(org.id, c -> Sql.one(c,
                "select count(*) as n from app.attendance_assignment where membership_id = ?", membership)).get("n"));
    }

    @Test
    void settingsAreValidated() {
        Org org = newOrg();
        assertError(400, "request.invalid", () -> settings.saveLocation(org.admin, null,
                new LocationInput("A", null, null, 22.5, null, null, null, "none", true)));
        assertError(400, "request.invalid", () -> settings.saveLocation(org.admin, null,
                new LocationInput("A", null, null, null, null, null, List.of(), "ip", true)));
        assertError(400, "request.invalid", () -> settings.saveLocation(org.admin, null,
                new LocationInput("A", "Mars/Olympus", null, null, null, null, null, "none", true)));
        assertError(400, "request.invalid", () -> settings.saveLocation(org.admin, null,
                new LocationInput("A", null, null, null, null, null, List.of("not-an-address"), "ip", true)));
        assertError(400, "request.invalid", () -> settings.saveShift(org.admin, null,
                new ShiftInput("S", "09:30", "18:30", 10, 240, 480, true)));
        assertError(400, "request.invalid", () -> settings.saveShift(org.admin, null,
                new ShiftInput("S", "9.30am", "18:30", null, null, null, true)));
        UUID office = location(org, "Office", "none");
        assertError(409, "request.refused", () -> location(org, "OFFICE", "none"));
        settings.saveLocation(org.admin, office, new LocationInput("Head office", null, "WB", null, null, null, null, "none", true));
        assertEquals("Head office", settings.locations(org.admin).get(0).get("name"));
        assertError(404, "not_found", () -> settings.saveLocation(org.admin, UUID.randomUUID(),
                new LocationInput("X", null, null, null, null, null, null, "none", true)));
    }

    // ── requests ────────────────────────────────────────────────────────

    @Test
    void anApprovedCorrectionAddsPunchesAndKeepsTheOriginals() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID office = location(org, "Office", "none");
        UUID general = shift(org, "General", "09:30", "18:30");
        assign(org, emp, office, general);
        assign(org, hr, office, general);
        punch(emp, MONDAY, "11:45");                       // forgot the morning punch and the evening one
        now(MONDAY.plusDays(1), "10:00");
        assertEquals("absent", day(emp, MONDAY).get("status"));

        String id = (String) attendance.createRequest(emp, "regularization", MONDAY.toString(), "09:30", "18:30",
                "Forgot to punch, was in the office").get("id");
        assertError(409, "attendance.request_exists", () -> attendance.createRequest(emp, "regularization",
                MONDAY.toString(), "09:00", "18:00", "Sending it again"));
        assertError(403, "permission.denied", () -> attendance.decideRequest(emp, UUID.fromString(id), true, null));
        Map<String, Object> waiting = attendance.requests(hr, null).get(0);
        assertEquals("absent", waiting.get("dayStatus"));
        assertEquals(false, waiting.get("own"));

        attendance.decideRequest(hr, UUID.fromString(id), true, "Confirmed with the team");
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals("present", day.get("status"));
        assertEquals(540, number(day, "workedMinutes"));
        assertFalse((Boolean) day.get("incomplete"));
        assertTrue(((List<?>) day.get("trail")).contains("Times taken from an approved correction request"));
        // the original punch is still there, next to the two the request added
        assertEquals(List.of("request", "web", "request"), db.inTenant(org.id, c -> Sql.query(c,
                "select source from app.punch where membership_id = ? order by at", emp.membershipId()))
                .stream().map(p -> p.get("source")).toList());
        assertEquals("approved", attendance.myRequests(emp).get(0).get("status"));
        assertError(404, "not_found", () -> attendance.decideRequest(hr, UUID.fromString(id), false, null));
        assertTrue(actions(org).contains("attendance.request_approved"));
    }

    @Test
    void nobodyDecidesTheirOwnRequest() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        assign(org, hr, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        now(MONDAY.plusDays(1), "10:00");
        UUID id = UUID.fromString((String) attendance.createRequest(hr, "work_from_home", MONDAY.toString(), null, null,
                "Plumber visiting at home").get("id"));
        assertEquals(true, attendance.requests(hr, "pending").get(0).get("own"));
        assertError(409, "attendance.own_request", () -> attendance.decideRequest(hr, id, true, null));
        // and the database refuses it even if the application did not
        assertError(409, "attendance.own_request", () -> db.inTenant(org.id, c -> Sql.update(c,
                "update app.attendance_request set status = 'approved', decided_by = ?, decided_at = now() where id = ?",
                hr.membershipId(), id)));
        attendance.decideRequest(org.admin, id, true, null);
        assertEquals("work_from_home", day(hr, MONDAY).get("status"));
    }

    @Test
    void awayRequestsCanBeRejectedOrCancelledAndHaveAWindow() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        now(MONDAY.plusDays(2), "10:00");
        UUID onDuty = UUID.fromString((String) attendance.createRequest(emp, "on_duty", MONDAY.toString(), null, null,
                "Client visit in Howrah").get("id"));
        attendance.decideRequest(org.admin, onDuty, false, "No visit was planned");
        assertEquals("absent", day(emp, MONDAY).get("status"));
        assertEquals("No visit was planned", attendance.myRequests(emp).get(0).get("decisionNote"));

        UUID wfh = UUID.fromString((String) attendance.createRequest(emp, "work_from_home", MONDAY.plusDays(1).toString(),
                null, null, "Working from home today").get("id"));
        attendance.cancelRequest(emp, wfh);
        assertError(404, "not_found", () -> attendance.cancelRequest(emp, wfh));
        assertError(404, "not_found", () -> attendance.decideRequest(org.admin, wfh, true, null));
        assertTrue(attendance.requests(org.admin, "pending").isEmpty());

        assertError(400, "attendance.request_window", () -> attendance.createRequest(emp, "on_duty",
                MONDAY.plusDays(70).toString(), null, null, "Too far ahead to plan"));
        assertError(400, "attendance.request_window", () -> attendance.createRequest(emp, "on_duty",
                MONDAY.minusDays(40).toString(), null, null, "Too long ago to correct"));
        assertError(400, "request.invalid", () -> attendance.createRequest(emp, "on_duty", MONDAY.toString(), null, null, "Hm"));
        assertError(400, "request.invalid", () -> attendance.createRequest(emp, "leave", MONDAY.toString(), null, null, "Not a kind"));
        assertError(400, "request.invalid", () -> attendance.createRequest(emp, "regularization", MONDAY.toString(), "09:30", null,
                "Only one time given"));
    }

    // ── who can see and do what ─────────────────────────────────────────

    @Test
    void scopesDecideWhoSeesWhom() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin"));
        SessionCtx manager = join(org, "manager", List.of("manager", "employee"));
        SessionCtx auditor = join(org, "auditor", List.of("auditor"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        punch(emp, MONDAY, "09:30");
        punch(emp, MONDAY, "18:30");
        now(MONDAY.plusDays(1), "09:00");

        // an employee sees only their own
        assertError(403, "permission.denied", () -> attendance.board(emp, MONDAY.toString()));
        assertError(404, "not_found", () -> attendance.userDays(emp, hr.membershipId(), null));
        assertEquals(MONDAY.toString(), attendance.userDays(emp, emp.membershipId(), month(MONDAY)).stream()
                .filter(d -> d.get("status").equals("present")).findFirst().orElseThrow().get("workDate"));
        assertError(403, "permission.denied", () -> attendance.register(emp, null));
        assertError(403, "permission.denied", () -> settings.locations(emp));

        // a manager's team is empty until employee records carry reporting lines
        assertTrue(attendance.board(manager, MONDAY.toString()).isEmpty());
        assertTrue(attendance.requests(manager, null).isEmpty());
        assertError(404, "not_found", () -> attendance.userDays(manager, emp.membershipId(), null));

        // HR and the auditor see everyone; only HR can change anything
        List<Map<String, Object>> board = attendance.board(hr, MONDAY.toString());
        assertEquals(5, board.size());
        assertEquals("present", board.stream().filter(r -> r.get("membershipId").equals(emp.membershipId().toString()))
                .findFirst().orElseThrow().get("status"));
        assertEquals(5, attendance.board(auditor, MONDAY.toString()).size());
        assertError(403, "permission.denied", () -> attendance.requests(auditor, null));
        assertError(403, "permission.denied", () -> attendance.manualPunch(auditor, emp.membershipId(),
                MONDAY + "T09:00", "in", "Auditors only read"));
        assertError(403, "permission.denied", () -> settings.addHoliday(auditor, MONDAY.toString(), "No", null));
        assertError(403, "permission.denied", () -> attendance.today(hr));       // HR Admin alone has no punch permission
        assertEquals("self", auth.me(emp).get("scopes") instanceof Map<?, ?> scopes ? scopes.get("attendance.read") : null);

        String csv = (String) attendance.register(hr, month(MONDAY)).get("csv");
        assertTrue(csv.startsWith("Email,Employee code,Date,Status"), csv);
        assertTrue(csv.contains("emp@" + org.slug + ".test,," + MONDAY + ",present,09:30,18:30,540,0,60,no,0,no,no\n"), csv);
    }

    @Test
    void hrCanEnterAPunchWithAReasonAndItIsAudited() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        punch(emp, MONDAY, "09:30");
        now(MONDAY.plusDays(1), "09:00");
        assertError(400, "request.invalid", () -> attendance.manualPunch(hr, emp.membershipId(), MONDAY + "T18:30", "out", "ok"));
        assertError(400, "request.invalid", () -> attendance.manualPunch(hr, emp.membershipId(), MONDAY.plusDays(2) + "T18:30",
                "out", "A punch in the future"));
        assertError(403, "permission.denied", () -> attendance.manualPunch(emp, emp.membershipId(), MONDAY + "T18:30", "out",
                "Entering my own punch"));
        attendance.manualPunch(hr, emp.membershipId(), MONDAY + "T18:30", "out", "Turnstile was broken");
        assertEquals("present", day(emp, MONDAY).get("status"));
        assertTrue(actions(org).contains("attendance.manual_punch"));
        // punches are add-only for the application's database role
        assertError(403, "permission.denied", () -> db.inTenant(org.id, c -> Sql.update(c, "update app.punch set at = now()")));
        assertError(403, "permission.denied", () -> db.inTenant(org.id, c -> Sql.update(c, "delete from app.punch")));
    }

    @Test
    void organizationsCannotSeeOrUseEachOthersAttendance() {
        Org a = newOrg();
        Org b = newOrg();
        SessionCtx emp = join(a, "emp", List.of("employee"));
        UUID office = location(a, "Office", "none");
        UUID general = shift(a, "General", "09:30", "18:30");
        assign(a, emp, office, general);
        punch(emp, MONDAY, "09:30");
        now(MONDAY.plusDays(1), "09:00");

        assertTrue(settings.locations(b.admin).isEmpty());
        assertTrue(settings.shifts(b.admin).isEmpty());
        assertError(404, "not_found", () -> attendance.userDays(b.admin, emp.membershipId(), null));
        assertError(409, "attendance.not_assigned", () -> attendance.manualPunch(b.admin, emp.membershipId(),
                MONDAY + "T18:30", "out", "Reaching across"));
        assertError(404, "not_found", () -> settings.assign(b.admin, List.of(b.admin.membershipId().toString()), office, general,
                List.of(0), MONDAY.toString()));
        assertError(404, "not_found", () -> settings.setEmployeeCode(b.admin, emp.membershipId(), "E1"));
        assertEquals(1, attendance.board(b.admin, MONDAY.toString()).size());
        assertEquals(0L, db.inTenant(b.id, c -> Sql.one(c, "select count(*) as n from app.punch")).get("n"));
        // the console and sign-in database roles cannot name the tables at all
        assertError(403, "permission.denied", () -> db.inPlatform(c -> Sql.query(c, "select * from app.punch")));
        assertError(403, "permission.denied", () -> db.inAuth(c -> Sql.query(c, "select * from app.attendance_day")));
        assertError(403, "permission.denied", () -> settings.locations(superAdmin));
    }

    @Test
    void supportSessionsCanReadAttendanceButNotChangeIt() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        now(MONDAY.plusDays(1), "09:00");
        Map<String, Object> grant = platform.requestGrant(superAdmin, org.id, "read_only", "Ticket 77: attendance looks wrong");
        SessionCtx support = auth.resolve(platform.startSupportSession(superAdmin, UUID.fromString((String) grant.get("id"))));
        assertEquals(2, attendance.board(support, MONDAY.toString()).size());
        assertEquals(1, settings.locations(support).size());
        assertError(403, "permission.denied", () -> settings.addHoliday(support, MONDAY.toString(), "No", null));
        assertError(403, "permission.denied", () -> attendance.today(support));
        assertError(403, "permission.denied", () -> attendance.decideRequest(support, UUID.randomUUID(), true, null));
    }

    // ── work from home ──────────────────────────────────────────────────

    @Test
    void workFromHomeCanBePlannedAheadWithinLimitsAndCancelled() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        settings.saveSettings(org.admin, new SettingsInput(null, true, false, 2, 5));
        now(MONDAY.minusDays(3), "10:00");                      // the Friday before

        // a week, Monday to Sunday: five working days are asked for, two are allowed
        assertError(409, "attendance.wfh_limit", () -> attendance.createRequests(emp, "work_from_home",
                MONDAY.toString(), SUNDAY.toString(), null, null, null, "Working from home all week"));
        List<?> made = (List<?>) attendance.createRequests(emp, "work_from_home", MONDAY.toString(),
                MONDAY.plusDays(1).toString(), null, null, null, "Two days at home").get("requests");
        assertEquals(2, made.size());
        assertError(409, "attendance.wfh_limit", () -> attendance.createRequests(emp, "work_from_home",
                MONDAY.plusDays(2).toString(), null, "first", null, null, "A morning at home"));
        Map<String, Object> state = attendance.homeWorking(emp);
        assertEquals(true, state.get("allowed"));
        assertEquals(2, state.get("limitPerWeek"));

        UUID monday = UUID.fromString((String) ((Map<?, ?>) made.get(0)).get("id"));
        UUID tuesday = UUID.fromString((String) ((Map<?, ?>) made.get(1)).get("id"));
        attendance.decideRequest(org.admin, monday, true, null);
        attendance.decideRequest(org.admin, tuesday, true, null);
        now(MONDAY.plusDays(1), "09:00");
        assertEquals("work_from_home", day(emp, MONDAY).get("status"));
        assertEquals(true, day(emp, MONDAY).get("remote"));

        // an approved day that has not passed can be withdrawn, and is then an ordinary day again
        attendance.cancelRequest(emp, tuesday);
        assertError(409, "attendance.cannot_cancel", () -> attendance.cancelRequest(emp, monday));
        now(MONDAY.plusDays(2), "09:00");
        assertEquals("absent", day(emp, MONDAY.plusDays(1)).get("status"));
        assertTrue(actions(org).contains("attendance.request_cancelled"));

        // switched off for the user, then for the Organization
        settings.setWfhAllowed(org.admin, emp.membershipId(), false);
        assertError(403, "attendance.wfh_not_allowed", () -> attendance.createRequests(emp, "work_from_home",
                MONDAY.plusDays(3).toString(), null, null, null, null, "Not allowed any more"));
        assertEquals(false, attendance.homeWorking(emp).get("allowed"));
        // on duty is not work from home and is not limited
        assertEquals(1, ((List<?>) attendance.createRequests(emp, "on_duty", MONDAY.plusDays(3).toString(), null, null, null, null,
                "Client visit in Howrah").get("requests")).size());
        assertError(403, "permission.denied", () -> settings.saveSettings(emp, new SettingsInput(null, true, false, null, null)));
    }

    @Test
    void aRemoteDayAcceptsAPunchFromAnywhereAndOtherDaysDoNot() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID office = UUID.fromString((String) settings.saveLocation(org.admin, null, new LocationInput(
                "Office", null, null, null, null, null, List.of("203.0.113.0/24"), "ip", true)).get("id"));
        UUID general = shift(org, "General", "09:30", "18:30");
        // Wednesday is a regular remote day
        settings.assign(org.admin, List.of(emp.membershipId().toString()), office, general, List.of(0), List.of(3),
                MONDAY.minusDays(60).toString());
        assertError(400, "request.invalid", () -> settings.assign(org.admin, List.of(emp.membershipId().toString()), office, general,
                List.of(0), List.of(0), MONDAY.toString()));
        settings.saveSettings(org.admin, new SettingsInput(null, true, true, null, null));   // remote days need punches

        now(MONDAY, "09:30");
        assertEquals(false, attendance.today(emp).get("remote"));
        assertError(403, "attendance.outside_network", () -> attendance.punch(emp, null, null, null, "192.0.2.50"));

        LocalDate wednesday = MONDAY.plusDays(2);
        now(wednesday, "09:30");
        Map<String, Object> today = attendance.today(emp);
        assertEquals(true, today.get("remote"));
        assertEquals(false, today.get("wantsPosition"));
        Map<String, Object> in = attendance.punch(emp, 12.97, 77.59, 10, "192.0.2.50");
        assertEquals("remote", ((Map<?, ?>) ((List<?>) in.get("punches")).get(0)).get("checkResult"));
        // no position is kept for a remote punch
        assertNull(db.inTenant(org.id, c -> Sql.one(c, "select latitude from app.punch where membership_id = ?", emp.membershipId()))
                .get("latitude"));
        now(wednesday, "18:30");
        attendance.punch(emp, null, null, null, "192.0.2.50");
        Map<String, Object> day = day(emp, wednesday);
        assertEquals("present", day.get("status"));
        assertEquals(true, day.get("remote"));
        assertEquals(540, number(day, "workedMinutes"));
        // a regular remote day needs no request
        assertError(400, "request.invalid", () -> attendance.createRequests(emp, "work_from_home", wednesday.toString(), null, null,
                null, null, "Already a remote day"));

        // with punches not needed, the same day counts in full without them
        settings.saveSettings(org.admin, new SettingsInput(null, true, false, null, null));
        assertEquals("work_from_home", day(emp, wednesday).get("status"));
    }

    @Test
    void aHalfDayAtHomeCountsThatHalf() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        now(MONDAY, "09:00");
        UUID id = UUID.fromString((String) ((Map<?, ?>) ((List<?>) attendance.createRequests(emp, "work_from_home", MONDAY.toString(),
                null, "first", null, null, "Morning at home").get("requests")).get(0)).get("id"));
        assertError(400, "request.invalid", () -> attendance.createRequests(emp, "on_duty", MONDAY.plusDays(1).toString(), null,
                "first", null, null, "Half a day on duty"));
        attendance.decideRequest(org.admin, id, true, null);
        punch(emp, MONDAY, "14:00");
        punch(emp, MONDAY, "18:30");
        now(MONDAY.plusDays(1), "09:00");
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals("present", day.get("status"));
        assertEquals(0, number(day, "lateMinutes"));
        assertEquals(270, number(day, "workedMinutes"));
    }

    // ── device import ───────────────────────────────────────────────────

    @Test
    void aDeviceFileIsImportedOnceHoweverOftenItIsUploaded() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        settings.setEmployeeCode(hr, emp.membershipId(), "E001");
        assertError(409, "attendance.code_taken", () -> settings.setEmployeeCode(hr, hr.membershipId(), "e001"));
        now(MONDAY.plusDays(2), "09:00");
        assertEquals("absent", day(emp, MONDAY).get("status"));

        String file = "Emp Code,Punch Time,Direction\n"
                + "E001," + stamp(MONDAY, "09:28:10") + ",IN\n"
                + "e001," + stamp(MONDAY, "18:31:00") + ",OUT\n"
                + "E001," + stamp(MONDAY, "18:31:00") + ",OUT\n"            // the same row twice
                + "E999," + stamp(MONDAY, "09:00:00") + ",IN\n"             // nobody has this code
                + "E001,yesterday morning,IN\n"
                + "E001," + stamp(MONDAY.plusDays(5), "09:00:00") + ",IN\n" // in the future
                + "E001," + stamp(MONDAY.plusDays(1), "09:30:00") + ",BREAK\n";
        Map<String, Object> preview = imports.preview(hr, "Gate ESSL", file);
        assertEquals(List.of("Emp Code", "Punch Time", "Direction"), preview.get("columns"));
        assertEquals(false, preview.get("mappingSaved"));
        Map<?, ?> guess = (Map<?, ?>) preview.get("mapping");
        assertEquals(List.of(true, 0, 1, 2), List.of(guess.get("hasHeader"), guess.get("codeColumn"),
                guess.get("dateTimeColumn"), guess.get("directionColumn")));

        Mapping mapping = new Mapping(true, 0, 1, null, 2, "dd/MM/yyyy HH:mm:ss", "Asia/Kolkata");
        Map<String, Object> first = imports.importFile(hr, "Gate ESSL", "monday.csv", file, mapping);
        assertEquals(Map.of("rowsTotal", 7, "rowsImported", 2, "rowsDuplicate", 1, "rowsRejected", 4),
                Map.of("rowsTotal", first.get("rowsTotal"), "rowsImported", first.get("rowsImported"),
                        "rowsDuplicate", first.get("rowsDuplicate"), "rowsRejected", first.get("rowsRejected")));
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals("present", day.get("status"));
        assertEquals(542, number(day, "workedMinutes"));

        Map<String, Object> again = imports.importFile(hr, "Gate ESSL", "monday.csv", file, mapping);
        assertEquals(0, again.get("rowsImported"));
        assertEquals(3, again.get("rowsDuplicate"));
        assertEquals(2L, db.inTenant(org.id, c -> Sql.one(c, "select count(*) as n from app.punch where source = 'import'")).get("n"));

        // the mapping is remembered for the device, whatever the case of its name
        Map<String, Object> next = imports.preview(hr, "gate essl", file);
        assertEquals(true, next.get("mappingSaved"));
        assertEquals("dd/MM/yyyy HH:mm:ss", ((Map<?, ?>) next.get("mapping")).get("format"));
        assertEquals(2, ((Number) ((Map<?, ?>) next.get("mapping")).get("directionColumn")).intValue());

        Map<String, Object> health = imports.health(hr);
        Map<?, ?> device = (Map<?, ?>) ((List<?>) health.get("devices")).get(0);
        assertEquals(2L, ((Number) device.get("files")).longValue());
        List<?> batches = (List<?>) health.get("batches");
        List<?> rejected = (List<?>) ((Map<?, ?>) batches.get(batches.size() - 1)).get("rejected");
        assertEquals(4, rejected.size());
        assertTrue(rejected.get(0).toString().startsWith("Row 5: No user has employee code E999"), rejected.toString());
        assertTrue(actions(org).contains("attendance.imported"));
        assertError(403, "permission.denied", () -> imports.importFile(emp, "Gate ESSL", "x.csv", file, mapping));
    }

    @Test
    void aFileWithoutDirectionsAlternatesThroughTheDay() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assign(org, emp, location(org, "Office", "none"), shift(org, "General", "09:30", "18:30"));
        settings.setEmployeeCode(org.admin, emp.membershipId(), "17");
        now(MONDAY.plusDays(2), "09:00");
        // a tab-separated device log with no header: code, date-time, and columns that do not matter
        String morning = "17\t" + iso(MONDAY, "09:30:00") + "\t1\t0\n17\t" + iso(MONDAY, "13:00:00") + "\t1\t0\n";
        Map<String, Object> preview = imports.preview(org.admin, "Door", morning);
        assertEquals(false, ((Map<?, ?>) preview.get("mapping")).get("hasHeader"));
        Mapping mapping = new Mapping(false, 0, 1, null, null, null, null);
        assertEquals(2, imports.importFile(org.admin, "Door", "am.dat", morning, mapping).get("rowsImported"));
        // a later file overlaps the first and carries on the day
        String evening = "17\t" + iso(MONDAY, "13:00:00") + "\t1\t0\n17\t" + iso(MONDAY, "14:00:00") + "\t1\t0\n17\t"
                + iso(MONDAY, "18:30:00") + "\t1\t0\n";
        Map<String, Object> second = imports.importFile(org.admin, "Door", "pm.dat", evening, mapping);
        assertEquals(2, second.get("rowsImported"));
        assertEquals(1, second.get("rowsDuplicate"));
        assertEquals(List.of("in", "out", "in", "out"), db.inTenant(org.id, c -> Sql.query(c,
                "select direction from app.punch where membership_id = ? order by at", emp.membershipId()))
                .stream().map(p -> p.get("direction")).toList());
        Map<String, Object> day = day(emp, MONDAY);
        assertEquals(480, number(day, "workedMinutes"));
        assertEquals("present", day.get("status"));
    }

    // ── present by default ──────────────────────────────────────────────

    /** The first day a new mode may start: today, as the real calendar has it (the test week lies ahead). */
    private static String modeStart() {
        return LocalDate.now(IST).toString();
    }

    @Test
    void presentByDefaultCountsWorkingDaysUntilSomeoneMarksAnException() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID branch = location(org, "Branch", "none");
        assign(org, emp, branch, shift(org, "General", "09:30", "18:30"));
        settings.setMode(org.admin, branch, "present", modeStart());

        now(MONDAY.plusDays(1), "10:00");
        assertEquals("present", attendance.today(emp).get("mode"));
        assertError(409, "attendance.present_by_default", () -> attendance.punch(emp, null, null, null, "10.0.0.1"));
        Map<String, Object> monday = day(emp, MONDAY);
        assertEquals("present", monday.get("status"));
        assertTrue(((List<?>) monday.get("trail")).contains("Present by default; no exception marked"));

        attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "absent", "Did not come in, no call");
        Map<String, Object> absent = day(emp, MONDAY);
        assertEquals("absent", absent.get("status"));
        assertTrue(((List<?>) absent.get("trail")).stream().anyMatch(t -> t.toString().startsWith("Marked absent by ")));
        assertError(409, "attendance.already_marked",
                () -> attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "absent", "Did not come in"));
        attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "half_day", "Came in after lunch");
        assertEquals("half_day", day(emp, MONDAY).get("status"));
        attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "present", "Was at a client all day");
        assertEquals("present", day(emp, MONDAY).get("status"));
        assertError(409, "attendance.nothing_to_undo",
                () -> attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "present", "Already present"));

        // the board says how each person is counted, and what is marked
        attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "absent", "Did not come in after all");
        Map<String, Object> row = attendance.board(org.admin, MONDAY.toString()).stream()
                .filter(r -> r.get("membershipId").equals(emp.membershipId().toString())).findFirst().orElseThrow();
        assertEquals("present", row.get("mode"));
        assertEquals("absent", row.get("mark"));
        assertEquals("absent", row.get("status"));
        assertTrue(actions(org).containsAll(List.of("attendance.mode_changed", "attendance.marked")));
    }

    @Test
    void marksHaveRules() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        SessionCtx other = join(org, "other", List.of("employee"));
        UUID branch = location(org, "Branch", "none");
        UUID shift = shift(org, "General", "09:30", "18:30");
        assign(org, emp, branch, shift);
        UUID office = location(org, "Office", "none");
        assign(org, other, office, shift);
        settings.setMode(org.admin, branch, "present", modeStart());
        now(MONDAY.plusDays(7), "10:00");

        String reason = "Did not come in";
        assertError(400, "request.invalid", () -> attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "late", reason));
        assertError(400, "request.invalid", () -> attendance.mark(org.admin, emp.membershipId(), MONDAY.toString(), "absent", "no"));
        assertError(400, "request.invalid",
                () -> attendance.mark(org.admin, emp.membershipId(), MONDAY.plusDays(8).toString(), "absent", reason));
        assertError(409, "attendance.day_off", () -> attendance.mark(org.admin, emp.membershipId(), SUNDAY.toString(), "absent", reason));
        assertError(409, "attendance.not_present_by_default",
                () -> attendance.mark(org.admin, other.membershipId(), MONDAY.toString(), "absent", reason));
        assertError(403, "permission.denied", () -> attendance.mark(emp, other.membershipId(), MONDAY.toString(), "absent", reason));
        assertError(409, "attendance.own_mark",
                () -> attendance.mark(org.admin, org.admin.membershipId(), MONDAY.toString(), "absent", reason));
        assertError(404, "not_found", () -> attendance.mark(org.admin, UUID.randomUUID(), MONDAY.toString(), "absent", reason));
    }

    @Test
    void aModeChangeHasADateAndCannotReachIntoTheClosedPast() {
        Org org = newOrg();
        UUID branch = location(org, "Branch", "none");
        assertError(400, "request.invalid", () -> settings.setMode(org.admin, branch, "magic", modeStart()));
        assertError(400, "request.invalid", () -> settings.setMode(org.admin, branch, "present", "soon"));
        assertError(409, "attendance.mode_date",
                () -> settings.setMode(org.admin, branch, "present", LocalDate.now(IST).minusMonths(2).toString()));
        assertError(409, "attendance.mode_unchanged", () -> settings.setMode(org.admin, branch, "app", modeStart()));
        settings.setMode(org.admin, branch, "device", LocalDate.now(IST).plusDays(10).toString());
        Map<String, Object> loc = settings.locations(org.admin).stream().filter(l -> l.get("id").equals(branch.toString())).findFirst().orElseThrow();
        assertEquals("app", loc.get("mode"));
        assertEquals("device", loc.get("nextMode"));
        assertEquals(LocalDate.now(IST).plusDays(10).toString(), loc.get("nextModeFrom"));
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assertError(403, "permission.denied", () -> settings.setMode(emp, branch, "present", modeStart()));
    }

    // ── biometric devices ───────────────────────────────────────────────

    @Test
    void aRegisteredDevicePushesPunchesFromTheOfficeNetwork() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        UUID gate = UUID.fromString((String) settings.saveLocation(org.admin, null, new LocationInput("Gate office", "Asia/Kolkata", "KA",
                null, null, null, List.of("203.0.113.0/24"), "ip", true)).get("id"));
        assign(org, emp, gate, shift(org, "General", "09:30", "18:30"));
        settings.setEmployeeCode(org.admin, emp.membershipId(), "E17");
        settings.setMode(org.admin, gate, "device", modeStart());
        String serial = "zk-" + org.slug();
        devices.save(org.admin, null, new DeviceInput("Main gate", serial, gate.toString(), true));
        String sn = serial.toUpperCase();

        // nobody else can claim the same machine
        Org rival = newOrg();
        UUID rivalSite = location(rival, "Site", "none");
        assertError(409, "device.serial_taken",
                () -> devices.save(rival.admin, null, new DeviceInput("Copy", serial, rivalSite.toString(), true)));

        now(MONDAY, "09:40");
        assertError(409, "attendance.device_location", () -> attendance.punch(emp, null, null, null, "203.0.113.9"));

        // calls from outside the office network are turned away, and HR can see why
        assertError(403, "device.outside_network", () -> devices.handshake(sn, "198.51.100.9"));
        Map<String, Object> listed = devices.devices(org.admin).get(0);
        assertTrue(((String) listed.get("lastRefused")).contains("198.51.100.9"));

        String options = devices.handshake(sn, "203.0.113.5");
        assertTrue(options.startsWith("GET OPTION FROM: " + sn));
        assertTrue(options.contains("ATTLOGStamp=None"));
        assertEquals("OK", devices.commands(sn, "203.0.113.5"));

        now(MONDAY, "18:45");
        String body = "E17\t" + iso(MONDAY, "09:31:00") + "\t0\t1\t0\t0\t0\n"
                + "E17\t" + iso(MONDAY, "18:35:00") + "\t0\t1\t0\t0\t0\n"
                + "E99\t" + iso(MONDAY, "10:00:00") + "\t0\t1\n"
                + "rubbish\n"
                + "E17\t" + iso(MONDAY, "23:30:00") + "\t0\t1\n";
        assertEquals("OK: 5", devices.receive(sn, "ATTLOG", "1001", body, "203.0.113.5"));
        Map<String, Object> monday = day(emp, MONDAY);
        assertEquals("present", monday.get("status"));
        assertEquals(544, number(monday, "workedMinutes"));
        assertEquals(List.of("in", "out"), db.inTenant(org.id(), c -> Sql.query(c,
                "select direction from app.punch where membership_id = ? and source = 'device' order by at", emp.membershipId()))
                .stream().map(p -> p.get("direction")).toList());

        // the same lines again add nothing; the device picks up where it left off
        assertEquals("OK: 5", devices.receive(sn, "ATTLOG", "1001", body, "203.0.113.5"));
        assertTrue(devices.handshake(sn, "203.0.113.5").contains("ATTLOGStamp=1001"));
        List<String> outcomes = devices.log(org.admin, UUID.fromString((String) listed.get("id"))).stream()
                .map(l -> (String) l.get("outcome")).toList();
        assertEquals(2, outcomes.stream().filter("imported"::equals).count());
        assertEquals(2, outcomes.stream().filter("duplicate"::equals).count());
        assertEquals(2, outcomes.stream().filter("unknown_code"::equals).count());
        assertEquals(2, outcomes.stream().filter("unreadable"::equals).count());
        assertEquals(2, outcomes.stream().filter("in_the_future"::equals).count());
        assertEquals("E99", ((List<Map<String, Object>>) devices.devices(org.admin).get(0).get("unknownCodes")).get(0).get("code"));

        // other tables are acknowledged and dropped
        assertEquals("OK: 0", devices.receive(sn, "OPERLOG", null, "OPLOG 4\t0\t2026-01-01 10:00:00", "203.0.113.5"));
        assertError(404, "device.unknown", () -> devices.handshake("NOT-A-DEVICE", "203.0.113.5"));
        assertError(400, "device.serial", () -> devices.handshake("x", "203.0.113.5"));
        assertError(400, "device.serial", () -> devices.handshake(null, "203.0.113.5"));

        // switched off, it is a stranger again
        devices.save(org.admin, UUID.fromString((String) listed.get("id")), new DeviceInput("Main gate", null, gate.toString(), false));
        assertError(404, "device.unknown", () -> devices.handshake(sn, "203.0.113.5"));
        assertTrue(actions(org).containsAll(List.of("attendance.device_registered", "attendance.device_changed")));
        assertError(403, "permission.denied", () -> devices.devices(emp));
    }

    @Test
    void aDeviceAtALocationWithNoNetworkSetTakesCallsFromAnywhere() {
        Org org = newOrg();
        UUID site = location(org, "Site", "none");
        String serial = "ESSL" + org.slug().replace("-", "");
        devices.save(org.admin, null, new DeviceInput("Site door", serial, site.toString(), true));
        assertTrue(devices.handshake(serial.toLowerCase(), "198.51.100.20").contains("GET OPTION FROM: " + serial.toUpperCase()));
        assertError(400, "request.invalid", () -> devices.save(org.admin, null, new DeviceInput("Bad", "a b", site.toString(), true)));
        assertError(400, "request.invalid", () -> devices.save(org.admin, null, new DeviceInput("", "GOODSERIAL1", site.toString(), true)));
        assertError(400, "request.invalid", () -> devices.save(org.admin, null, new DeviceInput("No place", "GOODSERIAL2", null, true)));
        assertError(404, "not_found",
                () -> devices.save(org.admin, null, new DeviceInput("Nowhere", "GOODSERIAL3", UUID.randomUUID().toString(), true)));
    }

    @Test
    void deviceLinesAreReadLeniently() {
        List<DeviceService.Line> lines = DeviceService.parse("17\t2026-10-05 09:31:00\t0\t1\r\n\n  18 2026-10-05 09:40:12 1 1\nnot a punch\n");
        assertEquals(3, lines.size());
        assertEquals("17", lines.get(0).code());
        assertEquals("2026-10-05T09:31", lines.get(0).at().toString());
        assertEquals("18", lines.get(1).code());
        assertNull(lines.get(2).at());
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
        return attendance.myDays(ctx, month(date)).stream()
                .filter(d -> d.get("workDate").equals(date.toString())).findFirst().orElseThrow();
    }

    private static String month(LocalDate date) {
        return YearMonth.from(date).toString();
    }

    private static int number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private static String stamp(LocalDate date, String time) {
        return String.format("%02d/%02d/%d %s", date.getDayOfMonth(), date.getMonthValue(), date.getYear(), time);
    }

    private static String iso(LocalDate date, String time) {
        return date + " " + time;
    }

    private static UUID location(Org org, String name, String check) {
        return UUID.fromString((String) settings.saveLocation(org.admin, null,
                new LocationInput(name, "Asia/Kolkata", "WB", null, null, null, null, check, true)).get("id"));
    }

    private static UUID shift(Org org, String name, String start, String end) {
        return UUID.fromString((String) settings.saveShift(org.admin, null,
                new ShiftInput(name, start, end, 10, 480, 240, true)).get("id"));
    }

    /** Assigns from well before the test week, Sundays off. */
    private static void assign(Org org, SessionCtx user, UUID location, UUID shift) {
        settings.assign(org.admin, List.of(user.membershipId().toString()), location, shift, List.of(0),
                MONDAY.minusDays(60).toString());
    }

    /** An Organization with its first Org Admin signed in. */
    private record Org(UUID id, String slug, String adminEmail, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "att-" + SEQUENCE.incrementAndGet();
        String adminEmail = "admin@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", adminEmail);
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        assertNotNull(token);
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
