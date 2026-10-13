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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.ExitInterview;
import com.example.loginapp.core.ExitLetters;
import com.example.loginapp.core.ExitService;
import com.example.loginapp.core.LetterService;
import com.example.loginapp.core.ExitService.AcceptInput;
import com.example.loginapp.core.ExitService.DecisionInput;
import com.example.loginapp.core.ExitService.RecommendInput;
import com.example.loginapp.core.ExitService.ResignInput;
import com.example.loginapp.core.ExitService.SettingsInput;
import com.example.loginapp.core.ExitService.StartInput;
import com.example.loginapp.core.ExitService.WithdrawInput;
import com.example.loginapp.core.ExitSettlement;
import com.example.loginapp.core.ExitSettlement.LineInput;
import com.example.loginapp.core.ExitSettlement.PaidInput;
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
    private static ExitSettlement settlements;
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
        settlements = new ExitSettlement(db, crypto);
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

    // ── the full and final settlement ───────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void theSettlementIsWorkedOutApprovedByASecondPersonAndPaidLineByLine() {
        Setup s = setup();
        exits.saveSettings(s.hr, settings(false));
        // Five years and seven months of service: gratuity counts six years.
        db.inTenant(s.org.id, c -> Sql.update(c, "update app.employment set joining_date = ? where id = ?",
                TODAY.minusYears(5).minusMonths(7), s.workerRecord));
        UUID id = UUID.fromString((String) exits.resign(s.worker, new ResignInput("pay", null, null)).get("id"));
        assertError(409, "exit.not_accepted", () -> settlements.prepare(s.hr, id));
        LocalDate last = TODAY.plusDays(20);
        Map<String, Object> accepted = exits.accept(s.hr, id, new AcceptInput(last.toString(), "owed", null));
        int shortfall = ((Number) accepted.get("shortfallDays")).intValue();
        assertEquals(25, shortfall);
        assertEquals("none", ((Map<String, Object>) accepted.get("settlement")).get("state"));

        assertError(403, "permission.denied", () -> settlements.prepare(s.worker, id));
        Map<String, Object> draft = settlements.prepare(s.hr, id);
        assertEquals("draft", draft.get("state"));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) draft.get("lines");
        Map<String, Object> salary = line(lines, "salary");
        LocalDate from = last.withDayOfMonth(1);
        assertEquals(0, java.math.BigDecimal.valueOf(last.getDayOfMonth()).compareTo((java.math.BigDecimal) salary.get("quantity")));
        assertTrue(LocalDate.parse((String) salary.get("dueDate")).isAfter(last));
        assertTrue(LocalDate.parse((String) salary.get("dueDate")).isBefore(last.plusDays(5)));
        assertEquals(0, java.math.BigDecimal.valueOf(shortfall).compareTo((java.math.BigDecimal) line(lines, "notice_recovery").get("quantity")));
        assertEquals("demand", line(lines, "notice_recovery").get("direction"));
        Map<String, Object> gratuity = line(lines, "gratuity");
        assertEquals(0, java.math.BigDecimal.valueOf(6).compareTo((java.math.BigDecimal) gratuity.get("quantity")));
        assertEquals(last.plusDays(30).toString(), gratuity.get("dueDate"));
        assertTrue((Boolean) ((Map<String, Object>) draft.get("gratuity")).get("eligible"));
        assertFalse(from.isAfter(last));
        // Preparing again keeps what is there and adds nothing twice.
        assertEquals(lines.size(), ((List<?>) settlements.prepare(s.hr, id).get("lines")).size());

        // The employee sees nothing of a draft; the preparer cannot approve; every amount must be given.
        assertTrue(((List<?>) settlements.get(s.worker, id).get("lines")).isEmpty());
        assertError(409, "settlement.same_person", () -> settlements.approve(s.hr, id));
        assertError(409, "settlement.amount_missing", () -> settlements.approve(s.org.admin, id));
        assertError(409, "settlement.line_exists", () -> settlements.addLine(s.hr, id, new LineInput("salary", "Salary again", "earning",
                null, null, java.math.BigDecimal.ONE, null, null)));
        settlements.addLine(s.hr, id, new LineInput("bonus", "Statutory bonus", "earning", null, null, new java.math.BigDecimal("8000"),
                last.plusDays(2).toString(), null));
        Map<String, Object> current = settlements.get(s.hr, id);
        for (Map<String, Object> l : (List<Map<String, Object>>) current.get("lines")) {
            java.math.BigDecimal amount = switch ((String) l.get("code")) {
                case "salary" -> new java.math.BigDecimal("50000");
                case "gratuity" -> new java.math.BigDecimal("120000");
                case "tax" -> new java.math.BigDecimal("5000");
                case "notice_recovery" -> new java.math.BigDecimal("30000");
                default -> (java.math.BigDecimal) l.get("amount");
            };
            settlements.changeLine(s.hr, id, UUID.fromString((String) l.get("id")), new LineInput(null, null, null,
                    (java.math.BigDecimal) l.get("quantity"), (String) l.get("unit"), amount, (String) l.get("dueDate"), (String) l.get("note")));
        }
        Map<String, Object> approved = settlements.approve(s.org.admin, id);
        assertEquals("approved", approved.get("state"));
        assertEquals(0, new java.math.BigDecimal("173000").compareTo((java.math.BigDecimal) approved.get("net")));
        assertEquals(0, new java.math.BigDecimal("30000").compareTo((java.math.BigDecimal) approved.get("demands")));
        assertNotNull(approved.get("statementDocumentId"));
        assertTrue(events(s.worker).contains("exit_letter_issued"));
        Map<String, Object> seen = settlements.get(s.worker, id);
        assertFalse(((List<?>) seen.get("lines")).isEmpty());
        assertFalse((Boolean) seen.get("canPay"));
        UUID salaryId = UUID.fromString((String) salary.get("id"));
        assertError(409, "settlement.locked", () -> settlements.changeLine(s.hr, id, salaryId, new LineInput(null, null, null, null, null,
                java.math.BigDecimal.TEN, null, null)));

        // Paid line by line, with the bank reference; a paid line is final; demands do not hold it up.
        assertError(400, "request.invalid", () -> settlements.paid(s.hr, id, salaryId, new PaidInput(TODAY.plusDays(1).toString(), "UTR1")));
        assertError(400, "request.invalid", () -> settlements.paid(s.hr, id, salaryId, new PaidInput(null, " ")));
        settlements.paid(s.hr, id, salaryId, new PaidInput(null, "UTR000123"));
        assertError(409, "settlement.paid", () -> settlements.paid(s.hr, id, salaryId, new PaidInput(null, "UTR000124")));
        assertThrows(RuntimeException.class, () -> db.inTenant(s.org.id, c -> Sql.update(c,
                "update app.exit_settlement_line set amount = 1 where id = ?", salaryId)));
        Map<String, Object> paid = null;
        for (Map<String, Object> l : (List<Map<String, Object>>) settlements.get(s.hr, id).get("lines")) {
            if (l.get("paidOn") == null && !"demand".equals(l.get("direction"))) {
                paid = settlements.paid(s.hr, id, UUID.fromString((String) l.get("id")), new PaidInput(TODAY.toString(), "REF-" + l.get("code")));
            }
        }
        assertEquals("paid", paid.get("state"));
        assertEquals(1L, count(s.org, "select count(*) from app.issued_letter where kind = 'settlement'"));
    }

    // ── letters and the interview ───────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void lettersWaitForTheLastDayAndClearanceAndTheInterviewIsReadOnlyByHr() {
        Setup s = setup();
        exits.saveSettings(s.hr, settings(false));
        UUID id = UUID.fromString((String) exits.resign(s.worker, new ResignInput("career", null, null)).get("id"));
        LocalDate last = TODAY.plusDays(10);
        Map<String, Object> accepted = exits.accept(s.hr, id, new AcceptInput(last.toString(), "waived", null));
        // The acceptance letter goes out on acceptance.
        List<Map<String, Object>> issued = (List<Map<String, Object>>) accepted.get("letters");
        assertEquals(List.of("exit_acceptance"), issued.stream().map(l -> l.get("kind")).toList());
        assertTrue(events(s.worker).contains("exit_letter_issued"));
        List<Map<String, Object>> templates = (List<Map<String, Object>>) accepted.get("letterTemplates");
        assertFalse(templates.stream().anyMatch(t -> "termination".equals(t.get("kind"))));   // not for a resignation
        UUID relieving = templateId(templates, "relieving");
        UUID experience = templateId(templates, "experience");
        assertTrue(((String) templates.stream().filter(t -> "relieving".equals(t.get("kind"))).findFirst().get().get("waitingFor"))
                .startsWith("Waits for the last working day"));
        ExitLetters letters = new ExitLetters(db, crypto);
        assertError(409, "exit.letter_not_ready", () -> letters.issue(s.hr, id, relieving));
        assertTrue(((List<String>) exits.get(s.hr, id).get("closeBlockers")).contains("the relieving letter is not issued"));

        // On the last day the experience letter can go; the relieving letter waits for clearance.
        ExitLetters onTheDay = new ExitLetters(db, crypto, fixed(last));
        assertError(409, "exit.letter_not_ready", () -> onTheDay.issue(s.hr, id, relieving));
        onTheDay.issue(s.hr, id, experience);
        String text = (String) onTheDay.preview(s.hr, id, experience).get("text");
        assertTrue(text.contains("Wasim Worker") && text.contains(ExitValuesDate.of(last)), text);
        db.inTenant(s.org.id, c -> Sql.update(c, "update app.exit_task set done_at = now(), done_by = ? where exit_case_id = ? and done_at is null",
                s.hr.membershipId(), id));
        onTheDay.issue(s.hr, id, relieving);
        assertFalse(((List<String>) exits.get(s.hr, id).get("closeBlockers")).contains("the relieving letter is not issued"));
        assertEquals(3, ((List<?>) ((Map<String, Object>) exits.mine(s.worker).get("exit")).get("letters")).size());
        assertError(403, "permission.denied", () -> letters.issue(s.worker, id, relieving));

        // Exit letters take the exit's placeholders; joining letters do not, and never list exit letters.
        LetterService letterService = new LetterService(db, crypto, new DocumentService(db, crypto));
        letterService.saveTemplate(s.org.admin, null, new LetterService.TemplateInput("relieving", "Short relieving letter",
                "{{person.legal_name}} left on {{exit.last_working_day}}.", "accept", null, true));
        assertError(400, "template.unknown_placeholder", () -> letterService.saveTemplate(s.org.admin, null,
                new LetterService.TemplateInput("other", "Odd", "Left on {{exit.last_working_day}}.", "none", null, true)));

        // The interview: sent by HR, answered by the employee, read only by HR.
        assertTrue((Boolean) exits.get(s.hr, id).get("canSendInterview"));
        ExitInterview interview = new ExitInterview(db, crypto);
        interview.send(s.hr, id);
        assertError(409, "interview.exists", () -> interview.send(s.hr, id));
        assertTrue(events(s.worker).contains("interview_sent"));
        Map<String, Object> mine = (Map<String, Object>) ((Map<String, Object>) exits.mine(s.worker).get("exit")).get("interview");
        assertEquals("sent", mine.get("status"));
        assertEquals(8, ((List<?>) mine.get("questions")).size());
        assertError(400, "request.invalid", () -> interview.answer(s.worker, new ExitInterview.AnswersInput(Map.of("manager", 9))));
        assertError(400, "request.invalid", () -> interview.answer(s.worker, new ExitInterview.AnswersInput(Map.of("reason", "Boredom"))));
        interview.answer(s.worker, new ExitInterview.AnswersInput(Map.of("reason", "Career growth", "manager", 4,
                "change", "More time for learning")));
        assertError(409, "interview.done", () -> interview.decline(s.worker));
        assertEquals(Map.of("status", "submitted"), exits.get(s.boss, id).get("interview"));
        assertError(403, "permission.denied", () -> interview.get(s.boss, id));
        Map<String, Object> read = interview.get(s.hr, id);
        assertEquals("More time for learning", ((Map<String, Object>) read.get("answers")).get("change"));
        assertEquals(4, ((Number) ((Map<String, Object>) read.get("answers")).get("manager")).intValue());
        assertEquals(0L, count(s.org, "select count(*) from app.exit_interview where position(convert_to('learning', 'UTF8') in answers_enc) > 0"));
        Map<String, Object> noted = interview.notes(s.hr, id, new ExitInterview.NotesInput("Spoke for twenty minutes; wants to return later."));
        assertEquals("submitted", noted.get("status"));
        assertTrue(((String) noted.get("notes")).startsWith("Spoke"));

        // New questions are a new version; the interview already sent keeps its own.
        exits.saveSettings(s.hr, new SettingsInput(Map.of("permanent", 45, "probation", 15, "fixed_term", 30, "intern", 0, "consultant", 15),
                false, null, "leave_then_owed", 60, 7, 7, 12, List.of(), List.of(Map.of("type", "rating", "text", "How was it?"))));
        assertEquals(2, ((Number) exits.getSettings(s.hr).get("interviewVersion")).intValue());
        assertEquals(8, ((List<?>) interview.get(s.hr, id).get("questions")).size());
        assertError(400, "request.invalid", () -> exits.saveSettings(s.hr, new SettingsInput(Map.of("permanent", 45, "probation", 15,
                "fixed_term", 30, "intern", 0, "consultant", 15), false, null, "leave_then_owed", 60, 7, 7, 12, List.of(),
                List.of(Map.of("type", "choice", "text", "Pick", "choices", List.of("Only one"))))));
    }

    private static UUID templateId(List<Map<String, Object>> templates, String kind) {
        return UUID.fromString((String) templates.stream().filter(t -> kind.equals(t.get("kind"))).findFirst()
                .orElseThrow(() -> new AssertionError("no template " + kind)).get("id"));
    }

    /** Dates as letters write them. */
    private static final class ExitValuesDate {
        static String of(LocalDate d) {
            return java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH).format(d);
        }
    }

    // ── other kinds of exit ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void contractEndsAndRetirementsAreProposedToHrAndConfirmedOrCancelled() {
        Setup s = setup();
        LocalDate contractEnd = TODAY.plusDays(20);
        db.inTenant(s.org.id, c -> {
            Sql.update(c, "update app.employment set employment_type = 'fixed_term', contract_end = ? where id = ?", contractEnd, s.workerRecord);
            Sql.update(c, "update app.person set date_of_birth = ? where id = (select person_id from app.employment where id = ?)",
                    TODAY.minusYears(60).plusDays(30), s.colleagueRecord);
            return null;
        });
        List<Map<String, Object>> open = exits.list(s.hr, "open");
        Map<String, Object> contract = open.stream().filter(x -> "end_of_contract".equals(x.get("kind"))).findFirst().orElseThrow();
        assertEquals("proposed", contract.get("status"));
        assertEquals("to_confirm", contract.get("stage"));
        assertEquals(contractEnd.toString(), contract.get("requestedLastDay"));
        Map<String, Object> retirement = open.stream().filter(x -> "retirement".equals(x.get("kind"))).findFirst().orElseThrow();
        LocalDate retireOn = TODAY.minusYears(60).plusDays(30).plusYears(60).with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        assertEquals(retireOn.toString(), retirement.get("requestedLastDay"));
        assertTrue(events(s.hr).contains("exit_proposed"));
        assertEquals(2, open.size());
        exits.list(s.hr, "open");   // looking again proposes nothing twice
        assertEquals(2, exits.list(s.hr, "open").size());
        // Proposals are HR's: neither the employee nor the manager sees them.
        assertNull(exits.mine(s.worker).get("exit"));
        assertTrue(exits.list(s.boss, "all").isEmpty());

        // The contract is renewed: the proposal is cancelled and not made again for the same date.
        UUID contractId = UUID.fromString((String) contract.get("id"));
        exits.cancel(s.hr, contractId, new WithdrawInput("Contract renewed for a year"));
        assertEquals(1, exits.list(s.hr, "open").size());
        // The retirement is confirmed with the proposed day.
        Map<String, Object> retired = exits.accept(s.hr, UUID.fromString((String) retirement.get("id")), new AcceptInput(null, null, null));
        assertEquals("accepted", retired.get("status"));
        assertEquals(retireOn.toString(), retired.get("lastWorkingDay"));
        assertTrue(((List<Map<String, Object>>) retired.get("letterTemplates")).stream().anyMatch(t -> "retirement".equals(t.get("kind"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onDeathTheEmploymentEndsAtOnceWithNomineesAndGratuityWhateverTheService() {
        Setup s = setup();
        db.inTenant(s.org.id, c -> {
            Sql.update(c, "update app.employment set joining_date = ? where id = ?", TODAY.minusMonths(8), s.workerRecord);
            Object person = Sql.one(c, "select person_id from app.employment where id = ?", s.workerRecord).get("personId");
            Object member = Sql.one(c, "insert into app.family_member (tenant_id, person_id, name, relationship) values (?, ?::uuid, "
                    + "'Rukhsana Worker', 'spouse') returning id", s.org.id, person).get("id");
            Sql.update(c, "insert into app.nomination (tenant_id, person_id, scheme, family_member_id, share_pct, effective_from) "
                    + "values (?, ?::uuid, 'gratuity', ?::uuid, 100, ?)", s.org.id, person, member, TODAY.minusMonths(8));
            return null;
        });
        assertError(400, "request.invalid", () -> exits.start(s.hr, new StartInput(s.workerRecord.toString(), "death", null, null,
                TODAY.plusDays(1).toString(), null, null)));
        Map<String, Object> x = exits.start(s.hr, new StartInput(s.workerRecord.toString(), "death", null, null,
                TODAY.minusDays(2).toString(), null, null));
        UUID id = UUID.fromString((String) x.get("id"));
        assertEquals("exited", employees.get(s.hr, s.workerRecord).get("status"));
        assertEquals(TODAY.minusDays(2).toString(), x.get("formerAccessUntil"));   // no former-employee access
        List<Map<String, Object>> nominees = (List<Map<String, Object>>) x.get("nominees");
        assertEquals("Rukhsana Worker", nominees.get(0).get("name"));
        assertFalse((Boolean) x.get("canSendInterview"));
        assertFalse(((List<Map<String, Object>>) x.get("tasks")).stream().anyMatch(t -> "joiner".equals(t.get("owner"))));
        Map<String, Object> settlement = settlements.prepare(s.hr, id);
        assertTrue((Boolean) ((Map<String, Object>) settlement.get("gratuity")).get("eligible"));
        assertTrue(((List<Map<String, Object>>) settlement.get("lines")).stream().anyMatch(l -> "gratuity".equals(l.get("code"))));
        assertFalse(events(s.worker).contains("resignation_accepted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void abscondingNeedsTheShowCauseAndFinalNoticesAnsweredByNobodyBeforeTheExitIsConfirmed() {
        Setup s = setup();
        assertError(409, "exit.too_soon", () -> exits.start(s.hr, new StartInput(s.workerRecord.toString(), "absconding", null, null,
                TODAY.minusDays(3).toString(), null, null)));
        LocalDate lastSeen = TODAY.minusDays(10);
        Map<String, Object> x = exits.start(s.hr, new StartInput(s.workerRecord.toString(), "absconding", null, null, lastSeen.toString(),
                null, null));
        UUID id = UUID.fromString((String) x.get("id"));
        assertEquals("proposed", x.get("status"));
        assertEquals("absent", x.get("stage"));
        assertEquals("Send the show-cause notice first, from Letters", ((Map<String, Object>) x.get("absconding")).get("waitingFor"));
        assertError(409, "exit.absconding_steps", () -> exits.accept(s.hr, id, new AcceptInput(null, null, null)));
        List<Map<String, Object>> templates = (List<Map<String, Object>>) x.get("letterTemplates");
        assertEquals(Set.of("show_cause", "final_notice", "relieving", "experience"),
                templates.stream().map(t -> (String) t.get("kind")).collect(java.util.stream.Collectors.toSet()));
        ExitLetters letters = new ExitLetters(db, crypto);
        letters.issue(s.hr, id, templateId(templates, "show_cause"));
        String text = (String) letters.preview(s.hr, id, templateId(templates, "show_cause")).get("text");
        assertTrue(text.contains(ExitValuesDate.of(lastSeen.plusDays(1))), text);
        // The employee sees the notice in My exit.
        Map<String, Object> mine = (Map<String, Object>) exits.mine(s.worker).get("exit");
        assertEquals(1, ((List<?>) mine.get("letters")).size());
        assertError(409, "exit.absconding_steps", () -> exits.accept(s.hr, id, new AcceptInput(null, null, null)));

        // A week later, no reply: the final notice; a week after that, still none: confirmed with the last day at work.
        new ExitLetters(db, crypto, fixed(TODAY.plusDays(8))).issue(s.hr, id, templateId(templates, "final_notice"));
        ExitService later = new ExitService(db, crypto, fixed(TODAY.plusDays(16)));
        Map<String, Object> confirmed = later.accept(s.hr, id, new AcceptInput(null, null, null));
        assertEquals("accepted", confirmed.get("status"));
        assertEquals(lastSeen.toString(), confirmed.get("lastWorkingDay"));
        assertEquals("exited", employees.get(s.hr, s.workerRecord).get("status"));
    }

    // ── afterwards: former-employee access, legal hold, retention ───────

    @Test
    @SuppressWarnings("unchecked")
    void formerEmployeesKeepANarrowLoginUntilTheirAccessEndsAndTheirDataGoesAfterRetentionUnlessHeld() {
        Setup s = setup();
        LocalDate last = TODAY.minusDays(1);
        Map<String, Object> x = exits.start(s.hr, new StartInput(s.workerRecord.toString(), "termination", "performance", null,
                last.toString(), null, null));
        UUID id = UUID.fromString((String) x.get("id"));
        String token = TOKENS.get(s.worker.membershipId());
        SessionCtx former = auth.resolve(token);
        assertFalse(former.can("leave.apply"));
        Map<String, Object> mine = exits.mine(former);
        assertEquals(false, mine.get("employed"));
        assertEquals(id.toString(), ((Map<String, Object>) mine.get("exit")).get("id"));
        LocalDate until = last.plusMonths(12);
        assertEquals(until.toString(), ((Map<String, Object>) mine.get("exit")).get("formerAccessUntil"));

        // Two weeks before the access ends they are told, once; after it, the login ends.
        new ExitService(db, crypto, fixed(until.minusDays(10))).list(s.hr, "all");
        new ExitService(db, crypto, fixed(until.minusDays(9))).list(s.hr, "all");
        assertEquals(1L, names(notifications.list(former)).stream().filter("former_access_ending"::equals).count());
        new ExitService(db, crypto, fixed(until)).list(s.hr, "all");
        auth.resolve(token);   // the last day of access still works
        new ExitService(db, crypto, fixed(until.plusDays(1))).list(s.hr, "all");
        assertThrows(ApiException.class, () -> auth.resolve(token));
        assertEquals(1L, count(s.org, "select count(*) from app.audit_event where action = 'exit.access_ended'"));

        // Retention: kept while under legal hold; removed, apart from what other records need, once it is lifted.
        db.inTenant(s.org.id, c -> Sql.update(c, "update app.exit_case set settlement_state = 'paid' where id = ?", id));
        assertError(400, "request.invalid", () -> exits.legalHold(s.hr, id, new ExitService.HoldInput(true, "")));
        assertTrue((Boolean) exits.legalHold(s.hr, id, new ExitService.HoldInput(true, "Claim before the labour court")).get("legalHold"));
        ExitService years = new ExitService(db, crypto, fixed(last.plusYears(9)));
        years.list(s.hr, "all");
        assertEquals("Wasim Worker", employees.get(s.hr, s.workerRecord).get("legalName"));
        exits.legalHold(s.hr, id, new ExitService.HoldInput(false, null));
        years.list(s.hr, "all");
        Map<String, Object> record = employees.get(s.hr, s.workerRecord);
        assertEquals("Former employee", record.get("legalName"));
        assertEquals("exited", record.get("status"));
        assertEquals(last.toString(), record.get("exitDate"));
        Map<String, Object> after = exits.get(s.hr, id);
        assertNotNull(after.get("purgedAt"));
        assertNull(after.get("reasonNote"));
        assertFalse((Boolean) after.get("canHold"));
        assertError(409, "exit.purged", () -> exits.legalHold(s.hr, id, new ExitService.HoldInput(true, "Too late now")));
        assertEquals(1L, count(s.org, "select count(*) from app.audit_event where action = 'exit.data_removed'"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> line(List<Map<String, Object>> lines, String code) {
        return lines.stream().filter(l -> code.equals(l.get("code"))).findFirst().orElseThrow(() -> new AssertionError("no line " + code));
    }

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
