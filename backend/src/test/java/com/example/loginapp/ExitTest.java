package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.ExitService;
import com.example.loginapp.core.ExitService.AcceptInput;
import com.example.loginapp.core.ExitService.DecisionInput;
import com.example.loginapp.core.ExitService.RecommendInput;
import com.example.loginapp.core.ExitService.ResignInput;
import com.example.loginapp.core.ExitService.SettingsInput;
import com.example.loginapp.core.ExitService.StartInput;
import com.example.loginapp.core.ExitService.WithdrawInput;
import com.example.loginapp.core.ExitTasks;
import com.example.loginapp.core.ExitTasks.DoneInput;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Exits against a real PostgreSQL: resignation, the manager's recommendation, acceptance with a
 * notice shortfall, withdrawal, HR-started exits, and what happens once the last working day has
 * passed. Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class ExitTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static Crypto crypto;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static EmployeeService employees;
    private static ExitService exits;
    private static Notifications notifications;
    private static ExitTasks exitTasks;
    private static LeaveService leaves;
    private static SessionCtx superAdmin;
    private static final Map<UUID, String> TOKENS = new HashMap<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_exits with (force)");
            st.execute("create database hrms_test_exits");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the exit tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_exits";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, crypto);
        exits = new ExitService(db, crypto);
        notifications = new Notifications(db);
        exitTasks = new ExitTasks(db);
        leaves = new LeaveService(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── resignation ─────────────────────────────────────────────────────

    @Test
    void aResignationIsRecommendedByTheManagerThenAcceptedByHrWithTheShortfallRecorded() {
        Setup s = setup();
        Map<String, Object> preview = exits.mine(s.worker);
        assertEquals(30, preview.get("noticeDays"));
        assertEquals(TODAY.plusDays(30).toString(), preview.get("proposedLastDay"));

        Map<String, Object> x = exits.resign(s.worker, new ResignInput("career", "A role closer to home", TODAY.plusDays(25).toString()));
        UUID id = UUID.fromString((String) x.get("id"));
        assertEquals("submitted", x.get("status"));
        assertEquals(TODAY.plusDays(30).toString(), x.get("noticeEnd"));
        assertEquals("A role closer to home", x.get("reasonNote"));
        assertError(409, "exit.already_open", () -> exits.resign(s.worker, new ResignInput("pay", null, null)));

        // The manager sees it and is told; a colleague cannot see it; HR waits for the manager.
        assertTrue(events(s.boss).contains("resignation_submitted"));
        assertEquals(1, exits.list(s.boss, "open").size());
        assertError(403, "permission.denied", () -> exits.list(s.colleague, "open"));
        assertError(404, "not_found", () -> exits.get(s.colleague, id));
        assertError(409, "exit.awaiting_manager", () -> exits.accept(s.hr, id, new AcceptInput(null, null, null)));
        assertFalse((Boolean) exits.get(s.hr, id).get("canAccept"));

        exits.recommend(s.boss, id, new RecommendInput(TODAY.plusDays(20).toString(), "Handover in two weeks is enough"));
        assertTrue(events(s.hr).contains("resignation_recommended"));
        Map<String, Object> accepted = exits.accept(s.hr, id, new AcceptInput(null, null, null));
        assertEquals("accepted", accepted.get("status"));
        assertEquals(TODAY.plusDays(20).toString(), accepted.get("lastWorkingDay"));
        assertEquals(10, ((Number) accepted.get("shortfallDays")).intValue());
        assertEquals("leave", accepted.get("shortfallAction"));   // the default: set against earned leave
        assertEquals("notice", accepted.get("stage"));
        assertTrue(events(s.worker).contains("resignation_accepted"));

        // Still working through the notice period, with the day they leave shown on the record.
        Map<String, Object> record = employees.get(s.hr, s.workerRecord);
        assertEquals("active", record.get("status"));
        assertEquals(TODAY.plusDays(20).toString(), record.get("leavingOn"));
        assertTrue(auth.resolve(TOKENS.get(s.worker.membershipId())).can("leave.apply"));
    }

    @Test
    void aResignationIsWithdrawnAtOnceBeforeAcceptanceAndOnlyWithHrsAgreementAfter() {
        Setup s = setup();
        exits.saveSettings(s.hr, settings(false));   // HR accepts without the manager's step
        Map<String, Object> first = exits.resign(s.worker, new ResignInput("pay", null, null));
        Map<String, Object> withdrawn = exits.withdraw(s.worker, new WithdrawInput("Changed my mind"));
        assertEquals("withdrawn", withdrawn.get("status"));
        assertTrue(events(s.boss).contains("resignation_withdrawn"));

        UUID id = UUID.fromString((String) exits.resign(s.worker, new ResignInput("pay", null, null)).get("id"));
        assertFalse(first.get("id").equals(id.toString()));
        exits.accept(s.hr, id, new AcceptInput(TODAY.plusDays(30).toString(), null, null));
        Map<String, Object> asked = exits.withdraw(s.worker, new WithdrawInput("A counter-offer"));
        assertEquals("accepted", asked.get("status"));
        assertEquals("withdrawal_asked", asked.get("stage"));
        assertError(409, "exit.withdrawal_pending", () -> exits.withdraw(s.worker, null));

        exits.decideWithdrawal(s.hr, id, new DecisionInput(false, "The role is already being filled"));
        assertEquals("notice", exits.get(s.hr, id).get("stage"));
        exits.withdraw(s.worker, new WithdrawInput("Please reconsider"));
        Map<String, Object> approved = exits.decideWithdrawal(s.hr, id, new DecisionInput(true, null));
        assertEquals("withdrawn", approved.get("status"));
        assertNull(employees.get(s.hr, s.workerRecord).get("leavingOn"));
        assertTrue(events(s.worker).contains("withdrawal_decided"));
    }

    // ── clearance ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void acceptanceCreatesTheClearanceChecklistWithOwnersDueDatesAndNoDues() {
        Setup s = setup();
        exits.saveSettings(s.hr, new SettingsInput(Map.of("permanent", 30, "probation", 15, "fixed_term", 30, "intern", 0, "consultant", 15),
                false, List.of(), "leave_then_owed", 60, 7, 7, 12, List.of()));   // no leave at all during notice
        UUID id = UUID.fromString((String) exits.resign(s.worker, new ResignInput("pay", null, null)).get("id"));
        LocalDate last = TODAY.plusDays(14);
        Map<String, Object> accepted = exits.accept(s.hr, id, new AcceptInput(last.toString(), "waived", null));
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) accepted.get("tasks");
        assertEquals(9, tasks.size());
        Map<String, Object> handover = task(tasks, "Plan the handover of work");
        assertEquals(s.boss.membershipId().toString(), handover.get("assigneeId"));
        assertEquals(TODAY.plusDays(5).toString(), handover.get("dueDate"));
        // Nobody is IT Admin or Payroll Operator here, so those tasks fall to the HR who accepted.
        assertEquals(s.hr.membershipId().toString(), task(tasks, "Collect the laptop and other equipment").get("assigneeId"));
        assertEquals(last.toString(), task(tasks, "Collect the laptop and other equipment").get("dueDate"));
        assertEquals(s.hr.membershipId().toString(), task(tasks, "Clear open advances, loans and expense claims").get("assigneeId"));
        assertEquals(last.minusDays(2).toString(), task(tasks, "Clear open advances, loans and expense claims").get("dueDate"));
        Map<String, Object> claims = task(tasks, "Submit pending expense claims");
        assertEquals(s.worker.membershipId().toString(), claims.get("assigneeId"));
        assertTrue(events(s.boss).contains("exit_task_assigned"));
        assertEquals(2, exitTasks.mine(s.boss).size());

        // The leaver sees only their own task; their claim is recorded as owed to them.
        Map<String, Object> mine = (Map<String, Object>) exits.mine(s.worker).get("exit");
        assertEquals(1, ((List<?>) mine.get("tasks")).size());
        UUID claimId = UUID.fromString((String) claims.get("id"));
        assertError(403, "permission.denied", () -> exitTasks.skip(s.worker, claimId, "Not needed"));
        Map<String, Object> done = exitTasks.done(s.worker, claimId, new DoneInput(new java.math.BigDecimal("1500.50"), "owed_to_employee",
                "Travel to the client in September"));
        assertEquals("done", done.get("state"));
        assertEquals(0, new java.math.BigDecimal("1500.50").compareTo((java.math.BigDecimal) done.get("amount")));
        assertError(400, "request.invalid", () -> exitTasks.done(s.hr, UUID.fromString((String) handover.get("id")),
                new DoneInput(new java.math.BigDecimal("10"), null, null)));
        exitTasks.skip(s.hr, UUID.fromString((String) handover.get("id")), "Handover done in person");
        assertTrue(((List<String>) exits.get(s.hr, id).get("closeBlockers")).contains("Task: Collect the laptop and other equipment"));

        // No leave during notice, as set; the day before the last day everyone involved is told, once.
        assertError(409, "leave.not_during_notice", () -> leaves.apply(s.worker, UUID.randomUUID(), TODAY.plusDays(2).toString(), null,
                null, "Family function"));
        Notifications eve = new Notifications(db, fixed(last.minusDays(1)));
        assertTrue(names(eve.list(s.hr)).contains("last_day_tomorrow"));
        assertTrue(names(eve.list(s.boss)).contains("last_day_tomorrow"));
        assertEquals(1L, names(eve.list(s.worker)).stream().filter("last_day_tomorrow"::equals).count());
        // An overdue task is chased, at most once a day.
        Notifications after = new Notifications(db, fixed(last.plusDays(1)));
        long overdue = names(after.list(s.hr)).stream().filter("exit_task_overdue"::equals).count();
        assertTrue(overdue > 0);
        assertEquals(overdue, names(after.list(s.hr)).stream().filter("exit_task_overdue"::equals).count());
    }

    // ── HR-started exits and the last working day ───────────────────────

    @Test
    void afterTheLastWorkingDayTheEmploymentEndsAndTheReportsMoveToTheSuccessor() {
        Setup s = setup();
        // The boss is terminated with their last day in a week; their report moves to the colleague.
        Map<String, Object> x = exits.start(s.hr, new StartInput(s.bossRecord.toString(), "termination", "performance",
                "Agreed after the review", TODAY.plusDays(7).toString(), "paid_in_lieu", s.colleagueRecord.toString()));
        assertEquals("accepted", x.get("status"));
        assertEquals("termination", x.get("kind"));
        assertTrue(events(s.boss).isEmpty() || !events(s.boss).contains("resignation_accepted"));

        // Eight days later the app applies it, once, whoever looks first.
        ExitService later = new ExitService(db, crypto, fixed(TODAY.plusDays(8)));
        assertEquals(1, later.list(s.hr, "open").size());
        Map<String, Object> ended = later.get(s.hr, UUID.fromString((String) x.get("id")));
        assertEquals("settling", ended.get("stage"));
        assertEquals(TODAY.plusDays(7).plusMonths(12).toString(), ended.get("formerAccessUntil"));
        later.list(s.hr, "all");
        Map<String, Object> boss = employees.get(s.hr, s.bossRecord);
        assertEquals("exited", boss.get("status"));
        assertEquals(TODAY.plusDays(7).toString(), boss.get("exitDate"));
        assertEquals(1L, count(s.org, "select count(*) from app.audit_event where action = 'exit.ended'"));
        // The worker now reports to the colleague, from the day after the boss's last day.
        Map<String, Object> job = db.inTenant(s.org.id, c -> Sql.one(c, "select manager_id, effective_from, reason from app.job_assignment "
                + "where employment_id = ? order by effective_from desc, created_at desc limit 1", s.workerRecord));
        assertEquals(s.colleagueRecord.toString(), job.get("managerId"));
        assertEquals(TODAY.plusDays(8).toString(), job.get("effectiveFrom"));
    }

    @Test
    void aLastWorkingDayAlreadyPastEndsAccessAtOnceButLeavesTheEmployeesOwnRecords() {
        Setup s = setup();
        employees.exit(s.hr, s.workerRecord, TODAY.minusDays(1).toString(), "Left without notice");
        SessionCtx after = auth.resolve(TOKENS.get(s.worker.membershipId()));
        assertFalse(after.can("leave.apply"));
        assertTrue(after.can("employee.read"));
        assertEquals("exited", employees.get(s.hr, s.workerRecord).get("status"));
        assertError(409, "employee.not_active", () -> employees.exit(s.hr, s.workerRecord, TODAY.toString(), "Again"));
        // The same exit through the new screens: a resignation recorded on paper, waiting for acceptance.
        Map<String, Object> paper = exits.start(s.hr, new StartInput(s.colleagueRecord.toString(), "resignation", "relocation",
                "Letter handed in", null, null, null));
        assertEquals("submitted", paper.get("status"));
        assertEquals(30, ((Number) paper.get("noticeDays")).intValue());
    }

    @Test
    void nobodyDecidesTheirOwnExitAndTheOnlyOrgAdminCannotLeave() {
        Setup s = setup();
        Map<String, Object> mine = exits.resign(s.hr, new ResignInput("personal", null, null));
        UUID id = UUID.fromString((String) mine.get("id"));
        assertError(409, "request.refused", () -> exits.accept(s.hr, id, new AcceptInput(TODAY.plusDays(10).toString(), null, null)));
        assertError(409, "request.refused", () -> exits.start(s.hr, new StartInput(s.hrRecord.toString(), "termination", null, null,
                TODAY.toString(), null, null)));
        assertError(403, "permission.denied", () -> exits.start(s.boss, new StartInput(s.workerRecord.toString(), "termination", null,
                null, TODAY.toString(), null, null)));
        assertError(409, "membership.last_owner", () -> exits.start(s.hr, new StartInput(s.adminRecord.toString(), "termination", null,
                null, TODAY.toString(), null, null)));
        // Settings: notice by type, refused when out of range.
        assertError(400, "request.invalid", () -> exits.saveSettings(s.hr, new SettingsInput(Map.of("permanent", 500), true, null,
                "owed", 60, 7, 7, 12, List.of())));
        Map<String, Object> saved = exits.saveSettings(s.hr, settings(true));
        assertTrue(((String) saved.get("noticeDays")).contains("\"permanent\": 45") || ((String) saved.get("noticeDays")).contains("\"permanent\":45"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static SettingsInput settings(boolean managerStep) {
        return new SettingsInput(Map.of("permanent", 45, "probation", 15, "fixed_term", 30, "intern", 0, "consultant", 15), managerStep,
                null, "leave_then_owed", 60, 7, 7, 12, List.of());
    }

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, SessionCtx hr, SessionCtx boss, SessionCtx worker, SessionCtx colleague, UUID adminRecord, UUID hrRecord,
                         UUID bossRecord, UUID workerRecord, UUID colleagueRecord) {}

    /** An Organization with HR, a manager, the manager's report and a colleague, each with a login and a record. */
    private static Setup setup() {
        String slug = "exit-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        SessionCtx admin = auth.resolve(token);
        TOKENS.put(admin.membershipId(), token);
        Org org = new Org(UUID.fromString((String) created.get("id")), slug, admin);
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx boss = join(org, "boss", List.of("employee", "manager"));
        SessionCtx worker = join(org, "worker", List.of("employee"));
        SessionCtx colleague = join(org, "colleague", List.of("employee"));
        UUID adminRecord = add(admin, "Asha Admin", admin, null, 1001);
        UUID hrRecord = add(admin, "Hari Hr", hr, null, 1002);
        UUID bossRecord = add(admin, "Bina Boss", boss, null, 1003);
        UUID workerRecord = add(admin, "Wasim Worker", worker, bossRecord, 1004);
        UUID colleagueRecord = add(admin, "Chitra Colleague", colleague, null, 1005);
        return new Setup(org, hr, boss, worker, colleague, adminRecord, hrRecord, bossRecord, workerRecord, colleagueRecord);
    }

    private static UUID add(SessionCtx by, String name, SessionCtx login, UUID manager, int code) {
        return UUID.fromString((String) employees.add(by, new NewEmployee(name, null, "1990-01-01", "female", null, "9876543210",
                "permanent", TODAY.minusYears(3).toString(), null, null, "E" + code, "Analyst", null,
                manager == null ? null : manager.toString(), login.membershipId().toString(), null, null, null, null, null, null, null,
                null, null)).get("id"));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        String token = invitations.accept(invitation, PASSWORD, null);
        SessionCtx ctx = auth.resolve(token);
        TOKENS.put(ctx.membershipId(), token);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(SessionCtx ctx) {
        return ((List<Map<String, Object>>) notifications.list(ctx).get("items")).stream().map(n -> (String) n.get("event")).toList();
    }

    private static Map<String, Object> task(List<Map<String, Object>> tasks, String title) {
        return tasks.stream().filter(t -> t.get("title").equals(title)).findFirst().orElseThrow(() -> new AssertionError("no task " + title));
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> list) {
        return ((List<Map<String, Object>>) list.get("items")).stream().map(n -> (String) n.get("event")).toList();
    }

    private static long count(Org org, String sql) {
        return db.inTenant(org.id, c -> (Long) Sql.one(c, sql.replace("select count(*)", "select count(*) as n")).get("n"));
    }

    private static Clock fixed(LocalDate day) {
        return Clock.fixed(day.atTime(12, 0).atZone(IST).toInstant(), ZoneOffset.UTC);
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
