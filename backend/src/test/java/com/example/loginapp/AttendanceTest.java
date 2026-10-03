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
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
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
                MONDAY.plusDays(3).toString(), null, null, "A day that has not come"));
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
        assertTrue(csv.contains("emp@" + org.slug + ".test,," + MONDAY + ",present,09:30,18:30,540,0,60,no\n"), csv);
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
