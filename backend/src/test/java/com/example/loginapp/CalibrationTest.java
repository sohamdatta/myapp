package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Calibration;
import com.example.loginapp.core.Calibration.ChangeInput;
import com.example.loginapp.core.Calibration.SessionInput;
import com.example.loginapp.core.Calibration.SettingsInput;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.Goals;
import com.example.loginapp.core.Goals.GoalInput;
import com.example.loginapp.core.Goals.SetInput;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.Outcomes;
import com.example.loginapp.core.Outcomes.BatchInput;
import com.example.loginapp.core.Outcomes.OutcomeInput;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PerformanceSetup;
import com.example.loginapp.core.PerformanceSetup.CompetencyInput;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.Reviews;
import com.example.loginapp.core.Reviews.AckInput;
import com.example.loginapp.core.Reviews.CycleInput;
import com.example.loginapp.core.Reviews.EntryInput;
import com.example.loginapp.core.Reviews.PeopleInput;
import com.example.loginapp.core.Reviews.Rating;
import com.example.loginapp.core.Reviews.ReasonInput;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UserService;

/**
 * Calibration and outcomes against a real PostgreSQL: sessions, changing a rating with a reason,
 * holding the release while a session is open, outcomes from the merit table, and approval by a
 * second person that records the pay revision, the promotion and the letters. Needs PostgreSQL as
 * described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class CalibrationTest {

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
    private static Goals goals;
    private static Reviews reviews;
    private static PerformanceSetup setup;
    private static Calibration calibration;
    private static Outcomes outcomes;
    private static PayrollPay pay;
    private static SessionCtx superAdmin;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_calibration with (force)");
            st.execute("create database hrms_test_calibration");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the calibration tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_calibration";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, crypto);
        goals = new Goals(db);
        reviews = new Reviews(db, crypto);
        setup = new PerformanceSetup(db);
        calibration = new Calibration(db, crypto);
        outcomes = new Outcomes(db, crypto);
        pay = new PayrollPay(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    @Test
    void calibrationChangesARatingWithAReasonAndHoldsTheReleaseUntilClosed() {
        Setup s = setup(false);
        Run run = written(s);
        // A session takes in the written reviews; the cycle cannot be released while it is open.
        assertError(409, "cycle.state", () -> calibration.createSession(s.hr, id(reviews.createCycle(s.hr, new CycleInput("Draft cycle", null,
                null, null, null, null, null, false, null, null, null, null))), new SessionInput("Too early", null)));
        Map<String, Object> session = calibration.createSession(s.hr, run.cycle, new SessionInput("Everyone", null));
        UUID sessionId = id(session);
        assertEquals(2, ((List<?>) session.get("reviews")).size());
        assertError(409, "calibration.empty", () -> calibration.createSession(s.hr, run.cycle, new SessionInput("Again", null)));
        assertError(409, "cycle.pending", () -> reviews.release(s.hr, run.cycle));
        assertError(403, "permission.denied", () -> calibration.session(s.boss, sessionId));

        // A change needs a reason of 20 characters; the manager is told; nobody calibrates their own rating.
        assertError(400, "request.invalid", () -> calibration.change(s.hr, sessionId, run.worker, new ChangeInput(4, "Too short")));
        assertError(409, "calibration.own", () -> calibration.change(s.hr, sessionId, run.hari, new ChangeInput(4,
                "Ran the whole audit almost alone")));
        session = calibration.change(s.hr, sessionId, run.worker, new ChangeInput(4, "Led the migration that the panel compared across teams"));
        assertTrue(events(s.boss).contains("review_calibrated"));
        Map<String, Object> row = reviewIn(session, "Wasim Worker");
        assertEquals(4, ((Number) row.get("level")).intValue());
        assertEquals("Led the migration that the panel compared across teams", ((Map<?, ?>) ((List<?>) row.get("changes")).get(0)).get("reason"));
        assertEquals(1, ((Number) distribution(session, 4).get("count")).intValue());
        assertEquals(2, ((Number) distribution(session, 3).get("managerCount")).intValue(), "the managers gave two 3s");

        Map<String, Object> closed = calibration.close(s.hr, sessionId);
        assertEquals("closed", closed.get("status"));
        assertError(409, "calibration.closed", () -> calibration.change(s.hr, sessionId, run.worker, new ChangeInput(5,
                "Second thoughts after the meeting ended")));
        reviews.release(s.hr, run.cycle);
        assertEquals(4, ((Number) reviews.review(s.worker, run.worker).get("finalLevel")).intValue());
    }

    @Test
    void outcomesComeFromTheMeritTableAndAreAppliedBySomeoneElse() {
        Setup s = setup(false);
        Run run = written(s);
        @SuppressWarnings("unchecked")
        String structure = (String) ((List<Map<String, Object>>) pay.get(s.hr, s.workerRecord).get("structures")).get(0).get("id");
        pay.revise(s.hr, s.workerRecord, new PayrollPay.ReviseInput(TODAY.withDayOfMonth(1).toString(), structure, new BigDecimal("600000"),
                "Joining salary"));
        reviews.release(s.hr, run.cycle);

        // The guide adds up to 100; the merit table gives a 3 six per cent.
        assertError(400, "request.invalid", () -> calibration.saveSettings(s.hr, new SettingsInput(Map.of("5", 10, "4", 20, "3", 50, "2", 10, "1", 5),
                Map.of("5", 12, "4", 9, "3", 6, "2", 3, "1", 0))));
        assertError(409, "cycle.state", () -> outcomes.prepare(s.hr, id(reviews.createCycle(s.hr, new CycleInput("Not released", null, null, null,
                null, null, null, false, null, null, null, null)))));
        Map<String, Object> view = outcomes.prepare(s.hr, run.cycle);
        Map<String, Object> worker = outcomeOf(view, "Wasim Worker");
        assertEquals(0, new BigDecimal("6").compareTo((BigDecimal) worker.get("incrementPct")));
        assertEquals(0, new BigDecimal("636000").compareTo((BigDecimal) worker.get("newAnnual")));
        assertEquals("Senior Analyst", worker.get("newDesignation"), "the manager recommended a promotion");
        assertEquals(0, new BigDecimal("0").compareTo((BigDecimal) outcomeOf(view, "Hari Hr").get("incrementPct")), "no pay, nothing to raise");
        UUID outcome = id(worker);
        String effective = (String) worker.get("effectiveFrom");

        assertError(400, "request.invalid", () -> outcomes.update(s.hr, outcome, new OutcomeInput(new BigDecimal("8"), true, "Senior Analyst",
                null, effective, null)));   // off the table, no note
        assertError(400, "request.invalid", () -> outcomes.update(s.hr, outcome, new OutcomeInput(new BigDecimal("6"), true, "Senior Analyst",
                null, LocalDate.parse(effective).plusDays(3).toString(), null)));
        view = outcomes.update(s.hr, outcome, new OutcomeInput(new BigDecimal("8"), true, "Senior Analyst", new BigDecimal("25000"), effective,
                "Retention: market pay for the role is higher"));
        assertEquals(0, new BigDecimal("648000").compareTo((BigDecimal) outcomeOf(view, "Wasim Worker").get("newAnnual")));

        // HR sends them; HR cannot approve what HR sent; the approver sends one back, then approves.
        outcomes.submit(s.hr, run.cycle, new BatchInput(null, null));
        assertTrue(events(s.org.admin).contains("outcomes_submitted"));
        assertError(403, "permission.denied", () -> outcomes.approve(s.boss, run.cycle, new BatchInput(null, null)));
        assertError(409, "outcome.same_person", () -> outcomes.approve(s.hr, run.cycle, new BatchInput(null, null)));
        outcomes.sendBack(s.org.admin, run.cycle, new BatchInput(List.of(outcome.toString()), "Check the bonus with finance"));
        assertTrue(events(s.hr).contains("outcomes_returned"));
        assertEquals("draft", outcomeOf(outcomes.list(s.hr, run.cycle), "Wasim Worker").get("status"));
        outcomes.submit(s.hr, run.cycle, new BatchInput(List.of(outcome.toString()), null));
        view = outcomes.approve(s.org.admin, run.cycle, new BatchInput(null, null));
        assertEquals("applied", outcomeOf(view, "Wasim Worker").get("status"));
        assertEquals("draft", outcomeOf(view, "Hari Hr").get("status"), "HR's own is left for someone else to send");
        assertError(409, "outcome.own", () -> outcomes.submit(s.hr, run.cycle, new BatchInput(null, null)));
        String hariOutcome = id(outcomeOf(view, "Hari Hr")).toString();
        outcomes.submit(s.org.admin, run.cycle, new BatchInput(null, null));
        assertError(409, "outcome.own", () -> outcomes.approve(s.hr, run.cycle, new BatchInput(List.of(hariOutcome), null)));

        // The pay and the job changed from the effective date, and the letters were issued.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) pay.get(s.hr, s.workerRecord).get("history");
        assertEquals(effective, history.get(0).get("validFrom"));
        assertEquals(0, new BigDecimal("648000").compareTo((BigDecimal) history.get(0).get("annualAmount")));
        assertEquals(List.of("promotion", "increment"), outcomes.myLetters(s.worker).stream().map(l -> (String) l.get("kind")).sorted(
                java.util.Comparator.reverseOrder()).toList());
        assertError(409, "outcome.state", () -> outcomes.update(s.hr, outcome, new OutcomeInput(new BigDecimal("6"), false, null, null, effective,
                null)));
    }

    /** A started cycle with the worker's and Hari's reviews written by their managers, everyone else withdrawn, calibrating. */
    private static Run written(Setup s) {
        Map<String, Object> cycle = reviews.startCycle(s.hr, id(reviews.createCycle(s.hr, new CycleInput("Annual review", null, null, null, null,
                null, null, false, null, null, null, null))));
        UUID cycleId = id(cycle);
        UUID worker = reviewOf(cycle, "Wasim Worker");
        UUID hari = reviewOf(cycle, "Hari Hr");
        for (String other : List.of("Asha Admin", "Bina Boss", "Chitra Colleague")) {
            reviews.withdraw(s.hr, reviewOf(cycle, other), new ReasonInput("Not reviewed in this test"));
        }
        List<String> goalIds = ids(reviews.review(s.boss, worker).get("goals"));
        Map<String, String> answers = Map.of("strengths", "Careful and quick", "develop", "Take on a larger project");
        reviews.saveEntry(s.boss, worker, "manager", new EntryInput(rate(goalIds, 3, 3), List.of(), Map.copyOf(answers), null, null, true,
                "Senior Analyst", true));
        reviews.saveEntry(s.org.admin, hari, "manager", new EntryInput(List.of(), List.of(), Map.copyOf(answers), 3, null, false, null, true));
        reviews.startCalibration(s.hr, cycleId);
        return new Run(cycleId, worker, hari);
    }

    private record Run(UUID cycle, UUID worker, UUID hari) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> reviewIn(Map<String, Object> session, String name) {
        return ((List<Map<String, Object>>) session.get("reviews")).stream().filter(r -> r.get("name").equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outcomeOf(Map<String, Object> view, String name) {
        return ((List<Map<String, Object>>) view.get("outcomes")).stream().filter(r -> r.get("name").equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> distribution(Map<String, Object> session, int value) {
        return ((List<Map<String, Object>>) session.get("distribution")).stream().filter(d -> ((Number) d.get("value")).intValue() == value)
                .findFirst().orElseThrow();
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, SessionCtx hr, SessionCtx boss, SessionCtx worker, SessionCtx colleague, UUID workerRecord) {}

    /** HR, a manager, the worker (with two agreed goals, 60% and 40%) and a colleague; with competencies when asked. */
    private static Setup setup(boolean competencies) {
        String slug = "cal-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        Org org = new Org(UUID.fromString((String) created.get("id")), slug, admin);
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx boss = join(org, "boss", List.of("employee", "manager"));
        SessionCtx worker = join(org, "worker", List.of("employee"));
        SessionCtx colleague = join(org, "colleague", List.of("employee"));
        add(admin, "Asha Admin", admin, null, 1001, "Director");
        add(admin, "Hari Hr", hr, null, 1002, "HR manager");
        UUID bossRecord = add(admin, "Bina Boss", boss, null, 1003, "Engineer");
        UUID workerRecord = add(admin, "Wasim Worker", worker, bossRecord, 1004, "Analyst");
        add(admin, "Chitra Colleague", colleague, null, 1005, "Analyst");
        goals.create(worker, new GoalInput(workerRecord.toString(), null, null, "Publish the Monday dashboard", null, 60, "done", null, null, null,
                null, null));
        goals.create(worker, new GoalInput(workerRecord.toString(), null, null, "Automate the monthly reports", null, 40, "rating", null, null,
                null, null, null));
        goals.submit(worker, new SetInput(null, null, null));
        goals.agree(boss, new SetInput(workerRecord.toString(), null, null));
        if (competencies) {
            Map<String, Object> view = setup.saveCompetency(hr, null, new CompetencyInput("Ownership", null, true));
            @SuppressWarnings("unchecked")
            List<String> ids = ((List<Map<String, Object>>) view.get("competencies")).stream().map(k -> (String) k.get("id")).toList();
            setup.saveSet(hr, null, new PerformanceSetup.SetInput("Everyone", Map.of(), ids, true));
        }
        return new Setup(org, hr, boss, worker, colleague, workerRecord);
    }

    private static UUID add(SessionCtx by, String name, SessionCtx login, UUID manager, int code, String designation) {
        return UUID.fromString((String) employees.add(by, new NewEmployee(name, null, "1990-01-01", "female", null, "9876543210",
                "permanent", TODAY.minusYears(2).toString(), null, null, "E" + code, designation, null,
                manager == null ? null : manager.toString(), login.membershipId().toString(), null, null, null, null, null, null, null,
                null, null)).get("id"));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static EntryInput entry(List<Rating> goals, List<Rating> comps, Map<String, String> answers, Integer overall, String reason,
                                    boolean submit) {
        return new EntryInput(goals, comps, Map.copyOf(answers), overall, reason, false, null, submit);
    }

    private static List<Rating> rate(List<String> goalIds, int first, int second) {
        return List.of(new Rating(goalIds.get(0), first, null), new Rating(goalIds.get(1), second, null));
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Object list) {
        return ((List<Map<String, Object>>) list).stream().map(m -> (String) m.get("id")).toList();
    }

    private static UUID id(Map<String, Object> m) {
        return UUID.fromString((String) m.get("id"));
    }

    @SuppressWarnings("unchecked")
    private static UUID reviewOf(Map<String, Object> cycle, String name) {
        return UUID.fromString((String) ((List<Map<String, Object>>) cycle.get("reviews")).stream().filter(r -> r.get("name").equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no review for " + name)).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(SessionCtx ctx) {
        return ((List<Map<String, Object>>) new Notifications(db).list(ctx).get("items")).stream().map(x -> (String) x.get("event")).toList();
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(status, e.status(), e.getMessage());
        assertEquals(code, e.code(), e.getMessage());
    }

    /** Refused with any of the given statuses (the code is checked only for the first). */
    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work, int otherStatus) {
        ApiException e = assertThrows(ApiException.class, work);
        assertTrue(e.status() == status || e.status() == otherStatus, e.getMessage());
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
