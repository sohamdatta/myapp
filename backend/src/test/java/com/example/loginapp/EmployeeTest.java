package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.JobChange;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.IdChecks;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.LeaveAdminService;
import com.example.loginapp.core.LeaveAdminService.TypeInput;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * The employee record against a real PostgreSQL: encryption at rest, revealing,
 * reporting lines and the manager's team, departments, exit, and isolation
 * between Organizations.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class EmployeeTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PAN = "ABCPE1234F";
    private static final String ACCOUNT = "123456789012";

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static EmployeeService employees;
    private static AttendanceService attendance;
    private static AttendanceSettingsService settings;
    private static LeaveService leave;
    private static LeaveAdminService leaveAdmin;
    private static SessionCtx superAdmin;
    private static String ownerUrl;
    private static String ownerUser;
    private static String ownerPassword;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        ownerUser = env("APP_DB_OWNER_USER", "postgres");
        ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_employee with (force)");
            st.execute("create database hrms_test_employee");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the employee tests");

        ownerUrl = "jdbc:postgresql://" + host + "/hrms_test_employee";
        db = new Db(source(ownerUrl, ownerUser, ownerPassword), source(ownerUrl, "app_platform", ROLE_PASSWORD),
                source(ownerUrl, "app_auth", ROLE_PASSWORD), source(ownerUrl, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY));
        attendance = new AttendanceService(db);
        settings = new AttendanceSettingsService(db);
        leave = new LeaveService(db);
        leaveAdmin = new LeaveAdminService(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── the record and its identifiers ──────────────────────────────────

    @Test
    void anEmployeeIsAddedWithIdentifiersThatAreStoredEncryptedAndShownAsLastFour() throws Exception {
        Org org = newOrg();
        String aadhaar = "23456789012" + IdChecks.verhoeffDigit("23456789012");
        UUID id = add(org.admin, person("Asha Roy", PAN, aadhaar, "100200300400", ACCOUNT, "SBIN0001234", null, null));

        Map<String, Object> e = employees.get(org.admin, id);
        assertEquals("Asha Roy", e.get("legalName"));
        assertEquals("Software engineer", e.get("designation"));
        assertEquals("active", e.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ids = (List<Map<String, Object>>) e.get("identifiers");
        assertEquals(Map.of("aadhaar", aadhaar.substring(8), "pan", "234F", "uan", "0400"),
                Map.of(ids.get(0).get("kind"), ids.get(0).get("last4"), ids.get(1).get("kind"), ids.get(1).get("last4"),
                        ids.get(2).get("kind"), ids.get(2).get("last4")));
        @SuppressWarnings("unchecked")
        Map<String, Object> bank = (Map<String, Object>) e.get("bankAccount");
        assertEquals("9012", bank.get("accountLast4"));
        assertEquals(true, e.get("canReveal"));
        assertFalse(e.toString().contains(PAN) || e.toString().contains(ACCOUNT) || e.toString().contains(aadhaar));

        // Nothing in the database holds the clear values, and Aadhaar is not kept beyond its last four digits.
        String dump = dump(org.id);
        assertFalse(dump.contains(PAN), "PAN in clear");
        assertFalse(dump.contains(ACCOUNT), "account number in clear");
        assertFalse(dump.contains(aadhaar), "Aadhaar in clear");
        assertFalse(dump.contains("100200300400"), "UAN in clear");
        assertTrue(dump.contains("234F"));
        try (Connection c = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword);
             ResultSet rs = c.createStatement().executeQuery(
                     "select value_enc is null and value_hash is null from app.statutory_id where kind = 'aadhaar' and tenant_id = '" + org.id + "'")) {
            assertTrue(rs.next() && rs.getBoolean(1));
        }
        assertTrue(actions(org).contains("employee.added"));
    }

    @Test
    void revealingAFullValueNeedsSensitiveAccessAndAReasonAndIsAudited() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        SessionCtx approver = join(org, "approver", List.of("payroll_approver"));
        UUID own = add(org.admin, linked(person("Ravi Das", PAN, null, null, ACCOUNT, "HDFC0ABC123", null, null), emp));
        UUID other = add(org.admin, person("Mina Sen", "BCDPF2345G", null, null, null, null, null, null));

        assertEquals(PAN, employees.reveal(org.admin, own, "pan", "Checking the TDS return").get("value"));
        assertEquals(ACCOUNT, employees.reveal(org.admin, own, "bank_account", "Bank file rejected").get("value"));
        assertError(400, "request.invalid", () -> employees.reveal(org.admin, own, "pan", "x"));
        assertError(400, "request.invalid", () -> employees.reveal(org.admin, own, "aadhaar", "Never in full"));

        // An employee sees their own full PAN, and nobody else's record at all.
        assertEquals(PAN, employees.reveal(emp, own, "pan", "Filling a loan form").get("value"));
        assertError(404, "not_found", () -> employees.get(emp, other));
        assertError(404, "not_found", () -> employees.reveal(emp, other, "pan", "Curious about it"));

        // Reading the record does not give the full value: a Payroll Approver sees last four only.
        assertEquals("234F", ((Map<?, ?>) ((List<?>) employees.get(approver, own).get("identifiers")).get(0)).get("last4"));
        assertEquals(false, employees.get(approver, own).get("canReveal"));
        assertError(403, "permission.denied", () -> employees.reveal(approver, own, "pan", "Checking something"));

        // Support, even with a write grant, never sees full values.
        Map<String, Object> grant = platform.requestGrant(superAdmin, org.id, "write", "Ticket 7: employee record looks wrong");
        SessionCtx support = auth.resolve(platform.startSupportSession(superAdmin, UUID.fromString((String) grant.get("id"))));
        assertEquals("Ravi Das", employees.get(support, own).get("legalName"));
        assertError(403, "permission.denied", () -> employees.reveal(support, own, "pan", "Ticket 7 needs it"));

        List<Map<String, Object>> reveals = users.auditLog(org.admin).stream()
                .filter(a -> a.get("action").equals("employee.revealed")).toList();
        assertEquals(3, reveals.size());
        assertTrue(((String) reveals.get(reveals.size() - 1).get("detail")).contains("Checking the TDS return"));
    }

    @Test
    void aValueCopiedIntoAnotherOrganizationCannotBeRead() throws Exception {
        Org a = newOrg();
        Org b = newOrg();
        UUID ea = add(a.admin, person("Asha Roy", PAN, null, null, null, null, null, null));
        UUID eb = add(b.admin, person("Ravi Das", "BCDPF2345G", null, null, null, null, null, null));
        try (Connection c = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword)) {
            c.createStatement().executeUpdate("update app.statutory_id s set value_enc = (select value_enc from app.statutory_id "
                    + "where tenant_id = '" + a.id + "' and kind = 'pan') where s.tenant_id = '" + b.id + "' and s.kind = 'pan'");
        }
        assertThrows(IllegalStateException.class, () -> employees.reveal(b.admin, eb, "pan", "After the copy"));
        assertEquals(PAN, employees.reveal(a.admin, ea, "pan", "Still readable here").get("value"));
    }

    @Test
    void aPanIsUniqueWithinAnOrganizationOnly() {
        Org a = newOrg();
        Org b = newOrg();
        add(a.admin, person("Asha Roy", PAN, null, null, null, null, null, null));
        assertError(409, "employee.duplicate_identifier", () -> add(a.admin, person("Asha R", PAN, null, null, null, null, null, null)));
        add(b.admin, person("Asha Roy", PAN, null, null, null, null, null, null));   // another employer may hold the same PAN
    }

    @Test
    void badIdentifiersAreRefusedWithAReason() {
        Org org = newOrg();
        assertError(400, "employee.invalid_pan", () -> add(org.admin, person("A", "ABCCE1234F", null, null, null, null, null, null)));
        assertError(400, "employee.invalid_aadhaar", () -> add(org.admin, person("A", null, "234567890123", null, null, null, null, null)));
        assertError(400, "employee.invalid_ifsc", () -> add(org.admin, person("A", null, null, null, ACCOUNT, "SBIN1001234", null, null)));
        assertError(400, "request.invalid", () -> add(org.admin, new NewEmployee("A", null, null, null, null, null, "permanent",
                TODAY.toString(), null, null, null, " ", null, null, null, null, null, null, null, null, null, null, null, null)));
        assertTrue(employees.list(org.admin, null, "all", null).isEmpty());   // nothing half-written
    }

    @Test
    void aPendingPanAndCorrectionsKeepHistory() {
        Org org = newOrg();
        UUID id = add(org.admin, person("Asha Roy", null, null, null, null, null, null, "Applied for PAN on 2 Oct"));
        Map<?, ?> pending = (Map<?, ?>) ((List<?>) employees.get(org.admin, id).get("identifiers")).get(0);
        assertEquals("Applied for PAN on 2 Oct", pending.get("pendingReason"));
        assertError(404, "not_found", () -> employees.reveal(org.admin, id, "pan", "Nothing to show yet"));

        employees.setIdentifier(org.admin, id, "pan", PAN, null);
        employees.setBank(org.admin, id, null, ACCOUNT, "SBIN0001234", "State Bank", "Park Street", null);
        employees.setBank(org.admin, id, null, "987654321098", "HDFC0ABC123", null, null, null);
        assertEquals(PAN, employees.reveal(org.admin, id, "pan", "After the correction").get("value"));
        assertEquals("987654321098", employees.reveal(org.admin, id, "bank_account", "Latest account").get("value"));
        assertEquals(2L, count(org.id, "select count(*) from app.statutory_id where kind = 'pan'"));
        assertEquals(2L, count(org.id, "select count(*) from app.bank_account"));
    }

    // ── jobs, reporting lines and the manager's team ────────────────────

    @Test
    void jobChangesAreDatedAndReportingLoopsAreRefused() {
        Org org = newOrg();
        UUID a = add(org.admin, person("Ananya Head", null, null, null, null, null, null, null));
        UUID b = add(org.admin, reportingTo(person("Bikram Lead", null, null, null, null, null, null, null), a));
        UUID c = add(org.admin, reportingTo(person("Chitra Dev", null, null, null, null, null, null, null), b));

        assertError(409, "employee.manager_loop", () -> employees.changeJob(org.admin, a,
                new JobChange(TODAY.toString(), "Director", null, c.toString(), "Reorganisation")));
        assertError(409, "employee.manager_loop", () -> employees.changeJob(org.admin, a,
                new JobChange(TODAY.toString(), "Director", null, a.toString(), null)));

        UUID dept = UUID.fromString((String) employees.saveDepartment(org.admin, null, "Engineering", null, null).get("id"));
        employees.changeJob(org.admin, c, new JobChange(TODAY.plusDays(10).toString(), "Senior engineer", dept.toString(), a.toString(), "Promotion"));
        Map<String, Object> now = employees.get(org.admin, c);
        assertEquals("Software engineer", now.get("designation"));   // the change is dated in the future
        assertEquals("Bikram Lead", now.get("managerName"));
        assertEquals(2, ((List<?>) now.get("jobHistory")).size());
        assertError(400, "request.invalid", () -> employees.changeJob(org.admin, c,
                new JobChange("2000-01-01", "Intern", null, null, null)));   // before joining
    }

    @Test
    void aManagerSeesAndDecidesTheirTeamOnly() {
        Org org = newOrg();
        SessionCtx boss = join(org, "boss", List.of("manager", "employee"));
        SessionCtx report = join(org, "report", List.of("employee"));
        SessionCtx stranger = join(org, "stranger", List.of("employee"));
        UUID bossId = add(org.admin, linked(person("Bina Boss", null, null, null, null, null, null, null), boss));
        add(org.admin, linked(reportingTo(person("Raju Report", null, null, null, null, null, null, null), bossId), report));
        add(org.admin, linked(person("Sonal Stranger", null, null, null, null, null, null, null), stranger));
        for (SessionCtx s : List.of(boss, report, stranger)) {
            assign(org, s);
        }
        UUID unpaid = UUID.fromString((String) leaveAdmin.saveType(org.admin, null,
                new TypeInput("Leave without pay", "regular", false, "none", BigDecimal.ZERO, true, null, "lapse", "unpaid", false, null, true)).get("id"));
        LocalDate tuesday = TODAY.plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
        UUID mine = UUID.fromString((String) leave.apply(report, unpaid, tuesday.toString(), tuesday.toString(), null, "Visiting family").get("id"));
        UUID theirs = UUID.fromString((String) leave.apply(stranger, unpaid, tuesday.toString(), tuesday.toString(), null, "Visiting family").get("id"));

        List<Map<String, Object>> queue = leave.requests(boss, null);
        assertEquals(List.of(mine.toString()), queue.stream().map(r -> r.get("id")).toList());
        assertError(404, "not_found", () -> leave.decide(boss, theirs, true, null));
        leave.decide(boss, mine, true, "Enjoy");

        List<Object> board = attendance.board(boss, TODAY.toString()).stream().map(r -> r.get("membershipId")).toList();
        assertEquals(List.of(report.membershipId().toString()), board);
        assertFalse(attendance.userDays(boss, report.membershipId(), null).isEmpty());
        assertError(404, "not_found", () -> attendance.userDays(boss, stranger.membershipId(), null));
        assertEquals(1, leave.calendar(boss, tuesday.toString(), tuesday.toString()).size());
        assertError(404, "not_found", () -> leave.userBalances(boss, stranger.membershipId()));

        // The employee list follows the same line: the manager sees their report and themselves.
        List<Object> names = employees.list(boss, null, null, null).stream().map(r -> r.get("legalName")).toList();
        assertEquals(List.of("Bina Boss", "Raju Report"), names);
    }

    // ── departments, exit, isolation ────────────────────────────────────

    @Test
    void departmentsFormATreeWithoutLoops() {
        Org org = newOrg();
        UUID eng = UUID.fromString((String) employees.saveDepartment(org.admin, null, "Engineering", null, null).get("id"));
        UUID web = UUID.fromString((String) employees.saveDepartment(org.admin, null, "Web", eng.toString(), null).get("id"));
        assertError(409, "department.loop", () -> employees.saveDepartment(org.admin, eng, "Engineering", web.toString(), null));
        assertError(409, "request.refused", () -> employees.saveDepartment(org.admin, null, "web", eng.toString(), null));
        employees.saveDepartment(org.admin, null, "Web", null, null);   // the same name at the top level is a different department
        assertEquals(3, employees.departments(org.admin).size());
    }

    @Test
    void anExitEndsAccessAfterTheLastWorkingDayNotBefore() {
        Org org = newOrg();
        SessionCtx leaving = join(org, "leaving", List.of("employee"));
        SessionCtx later = join(org, "later", List.of("employee"));
        UUID gone = add(org.admin, linked(person("Leela Gone", null, null, null, null, null, null, null), leaving));
        UUID stays = add(org.admin, linked(person("Lata Later", null, null, null, null, null, null, null), later));

        employees.exit(org.admin, stays, TODAY.toString(), "Resigned");           // last day is today: still working
        assertTrue(auth.resolve(token(later)).can("leave.apply"));
        assertEquals("exited", employees.get(org.admin, stays).get("status"));

        employees.exit(org.admin, gone, TODAY.minusDays(1).toString(), "Resigned");   // last day was yesterday
        SessionCtx after = auth.resolve(token(leaving));
        assertFalse(after.can("leave.apply"));
        assertTrue(after.can("employee.read"));                                    // Ex-employee: own records only
        assertEquals("Leela Gone", employees.get(after, gone).get("legalName"));
        assertError(409, "employee.not_active", () -> employees.exit(org.admin, gone, TODAY.toString(), "Again"));
        // Nobody records their own exit, and the only Org Admin cannot be exited.
        SessionCtx hr = join(org, "hr", List.of("hr_admin"));
        UUID adminRecord = add(org.admin, linked(person("Only Admin", null, null, null, null, null, null, null), org.admin));
        assertError(409, "request.refused", () -> employees.exit(org.admin, adminRecord, TODAY.toString(), "Leaving"));
        assertError(409, "membership.last_owner", () -> employees.exit(hr, adminRecord, TODAY.toString(), "Leaving"));
    }

    @Test
    void organizationsSeeNothingOfEachOther() {
        Org a = newOrg();
        Org b = newOrg();
        UUID ea = add(a.admin, person("Asha Roy", PAN, null, null, ACCOUNT, "SBIN0001234", null, null));
        UUID dept = UUID.fromString((String) employees.saveDepartment(a.admin, null, "Engineering", null, null).get("id"));
        assertTrue(employees.list(b.admin, null, "all", null).isEmpty());
        assertTrue(employees.departments(b.admin).isEmpty());
        assertError(404, "not_found", () -> employees.get(b.admin, ea));
        assertError(404, "not_found", () -> employees.reveal(b.admin, ea, "pan", "Not my employee"));
        assertError(404, "not_found", () -> employees.changeJob(b.admin, ea, new JobChange(TODAY.toString(), "X", null, null, null)));
        assertError(404, "not_found", () -> add(b.admin, new NewEmployee("X", null, null, null, null, null, "permanent",
                TODAY.toString(), null, null, null, "Engineer", dept.toString(), null, null, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void linkingAUserCarriesTheEmployeeCodeToAttendance() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        assertEquals(List.of("emp@" + org.slug + ".test"),
                employees.unlinkedUsers(org.admin).stream().map(r -> r.get("email")).filter(e -> e.toString().startsWith("emp")).toList());
        NewEmployee p = person("Esha Mitra", null, null, null, null, null, null, null);
        UUID id = add(org.admin, new NewEmployee(p.legalName(), null, null, null, null, null, "permanent", TODAY.minusYears(1).toString(),
                null, null, "E042", "Analyst", null, null, null, null, null, null, null, null, null, null, null, null));
        employees.link(org.admin, id, emp.membershipId());
        assertEquals(1L, count(org.id, "select count(*) from app.membership where employee_code = 'E042' and person_id is not null"));
        assertError(409, "employee.user_linked", () -> employees.link(org.admin, id, emp.membershipId()));
        assertError(403, "permission.denied", () -> employees.add(emp, p));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, String adminEmail, SessionCtx admin) {}

    private static final Map<UUID, String> TOKENS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Org newOrg() {
        String slug = "people-" + SEQUENCE.incrementAndGet();
        String adminEmail = "admin@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", adminEmail);
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, adminEmail, auth.resolve(token));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String email = name + "@" + org.slug + ".test";
        String invitation = (String) users.invite(org.admin, email, roles).get("token");
        String token = invitations.accept(invitation, PASSWORD, null);
        SessionCtx ctx = auth.resolve(token);
        TOKENS.put(ctx.membershipId(), token);
        return ctx;
    }

    private static String token(SessionCtx ctx) {
        return TOKENS.get(ctx.membershipId());
    }

    private static NewEmployee person(String name, String pan, String aadhaar, String uan, String account, String ifsc,
                                      String managerId, String panPending) {
        return new NewEmployee(name, null, "1995-04-12", "female", null, "9876543210", "permanent",
                TODAY.minusYears(1).toString(), null, null, null, "Software engineer", null, managerId, null,
                pan, panPending, aadhaar, uan, account == null ? null : name, account, ifsc, null, null);
    }

    private static NewEmployee reportingTo(NewEmployee p, UUID manager) {
        return new NewEmployee(p.legalName(), p.preferredName(), p.dateOfBirth(), p.gender(), p.personalEmail(), p.mobile(),
                p.employmentType(), p.joiningDate(), p.probationEnd(), p.contractEnd(), p.employeeCode(), p.designation(),
                p.departmentId(), manager.toString(), p.membershipId(), p.pan(), p.panPendingReason(), p.aadhaar(), p.uan(),
                p.bankHolder(), p.bankAccount(), p.ifsc(), p.bankName(), p.branch());
    }

    private static NewEmployee linked(NewEmployee p, SessionCtx user) {
        return new NewEmployee(p.legalName(), p.preferredName(), p.dateOfBirth(), p.gender(), p.personalEmail(), p.mobile(),
                p.employmentType(), p.joiningDate(), p.probationEnd(), p.contractEnd(), p.employeeCode(), p.designation(),
                p.departmentId(), p.managerId(), user.membershipId().toString(), p.pan(), p.panPendingReason(), p.aadhaar(), p.uan(),
                p.bankHolder(), p.bankAccount(), p.ifsc(), p.bankName(), p.branch());
    }

    private static UUID add(SessionCtx ctx, NewEmployee p) {
        return UUID.fromString((String) employees.add(ctx, p).get("id"));
    }

    private static void assign(Org org, SessionCtx user) {
        List<Map<String, Object>> locations = settings.locations(org.admin);
        UUID location = locations.isEmpty()
                ? UUID.fromString((String) settings.saveLocation(org.admin, null,
                        new LocationInput("Office", "Asia/Kolkata", "WB", null, null, null, null, "none", true)).get("id"))
                : UUID.fromString((String) locations.get(0).get("id"));
        List<Map<String, Object>> shifts = settings.shifts(org.admin);
        UUID shift = shifts.isEmpty()
                ? UUID.fromString((String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id"))
                : UUID.fromString((String) shifts.get(0).get("id"));
        settings.assign(org.admin, List.of(user.membershipId().toString()), location, shift, List.of(0), TODAY.minusDays(90).toString());
    }

    private static List<String> actions(Org org) {
        return users.auditLog(org.admin).stream().map(r -> (String) r.get("action")).toList();
    }

    /** Every column of every employee table for one Organization, as text, the way a database dump would show it. */
    private static String dump(UUID tenantId) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection c = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword)) {
            for (String table : List.of("person", "employment", "job_assignment", "statutory_id", "bank_account", "audit_event")) {
                try (ResultSet rs = c.createStatement().executeQuery("select * from app." + table + " where tenant_id = '" + tenantId + "'")) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        for (int i = 1; i <= n; i++) {
                            byte[] raw = rs.getBytes(i);
                            if (raw != null) {
                                out.append(new String(raw, StandardCharsets.ISO_8859_1)).append('|');
                                out.append(rs.getString(i)).append('|');
                            }
                        }
                    }
                }
            }
        }
        return out.toString();
    }

    private static long count(UUID tenantId, String sql) {
        return db.inTenant(tenantId, c -> (Long) Sql.one(c, sql.replace("select count(*)", "select count(*) as n")).get("n"));
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
