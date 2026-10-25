package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.example.loginapp.core.ExitService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.Goals;
import com.example.loginapp.core.Goals.GoalInput;
import com.example.loginapp.core.Goals.SetInput;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.PerformanceSetup;
import com.example.loginapp.core.PerformanceSetup.CompetencyInput;
import com.example.loginapp.core.Plans;
import com.example.loginapp.core.Plans.CheckinInput;
import com.example.loginapp.core.Plans.DecideInput;
import com.example.loginapp.core.Plans.Objective;
import com.example.loginapp.core.Plans.PlanInput;
import com.example.loginapp.core.Plans.StartInput;
import com.example.loginapp.core.Plans.TextInput;
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
 * Improvement plans against a real PostgreSQL: drafting by a manager, starting by HR with the
 * letter and the check-in schedule, the employee's acknowledgement and replies, extending once, and
 * not met starting the exit. Needs PostgreSQL as described in {@link UserModuleTest}; skipped when
 * it cannot be reached.
 */
class PlansTest {

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
    private static Crypto crypto;
    private static ExitService exits;
    private static Plans plans;
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
            st.execute("drop database if exists hrms_test_plans with (force)");
            st.execute("create database hrms_test_plans");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the improvement plan tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_plans";
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
        PlansTest.crypto = crypto;
        exits = new ExitService(db, crypto);
        plans = new Plans(db, crypto, exits);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    @Test
    void aManagerDraftsHrStartsAndTheEmployeeAcknowledgesAndReplies() {
        Setup s = setup(false);
        String worker = s.workerRecord.toString();
        assertError(404, "not_found", () -> plans.create(s.colleague, plan(worker, 60, 14)));
        assertError(404, "not_found", () -> plans.create(s.worker, plan(worker, 60, 14)));
        assertError(400, "request.invalid", () -> plans.create(s.boss, new PlanInput(worker, null, "Too short", null,
                List.of(new Objective("Reports on time", "Every Monday by 10")), 60, 14)));
        assertError(400, "request.invalid", () -> plans.create(s.boss, new PlanInput(worker, null, REASON, null, List.of(), 60, 14)));
        Map<String, Object> draft = plans.create(s.boss, plan(worker, 60, 14));
        UUID id = id(draft);
        assertEquals("draft", draft.get("status"));
        assertTrue(events(s.hr).contains("plan_drafted"), "HR is told a manager drafted a plan");
        assertError(409, "plan.exists", () -> plans.create(s.boss, plan(worker, 60, 14)));
        assertNull(plans.mine(s.worker), "a draft is not shown to the employee");
        assertError(404, "not_found", () -> plans.get(s.worker, id));
        assertError(403, "permission.denied", () -> plans.start(s.boss, id, new StartInput(null)));
        assertError(400, "request.invalid", () -> plans.start(s.hr, id, new StartInput(TODAY.minusDays(1).toString())));

        Map<String, Object> started = plans.start(s.hr, id, new StartInput(TODAY.toString()));
        assertEquals("active", started.get("status"));
        assertEquals(TODAY.plusDays(60).toString(), started.get("endDate"));
        assertNotNull(started.get("letter"), "the plan letter is issued");
        assertEquals(List.of(TODAY.plusDays(14), TODAY.plusDays(28), TODAY.plusDays(42), TODAY.plusDays(56), TODAY.plusDays(60)).stream()
                .map(LocalDate::toString).toList(), dues(started));
        assertTrue(events(s.worker).contains("plan_started"));

        Map<String, Object> mine = plans.mine(s.worker);
        assertEquals(REASON, mine.get("reason"));
        assertEquals(0, ((List<?>) mine.get("checkins")).size(), "the employee sees check-ins once written");
        mine = plans.acknowledge(s.worker, id, new TextInput("I understand; I would like help with the reporting tool"));
        assertNotNull(mine.get("acknowledgedAt"));
        assertError(409, "plan.acknowledged", () -> plans.acknowledge(s.worker, id, new TextInput(null)));
        assertEquals("I understand; I would like help with the reporting tool", plans.get(s.boss, id).get("response"));

        // The first check-in is written from a week before it is due, with every objective rated.
        UUID first = checkinId(started, 0);
        assertError(409, "plan.too_early", () -> plans.checkin(s.boss, id, first, new CheckinInput(Map.of(), "Too early to say anything")));
        Plans later = new Plans(db, crypto, exits, fixed(TODAY.plusDays(14)));
        List<String> objectives = ids(started.get("objectives"));
        assertError(400, "request.invalid", () -> later.checkin(s.boss, id, first, new CheckinInput(Map.of(objectives.get(0), "on_track"),
                "Reports were on time all fortnight")));
        Map<String, Object> written = later.checkin(s.boss, id, first, new CheckinInput(Map.of(objectives.get(0), "on_track",
                objectives.get(1), "at_risk"), "Reports were on time; two errors in the March numbers"));
        assertEquals("at_risk", ((Map<?, ?>) ((Map<?, ?>) ((List<?>) written.get("checkins")).get(0)).get("ratings")).get(objectives.get(1)));
        assertTrue(events(s.worker).contains("plan_checkin_written"));
        UUID checkin = checkinId(plans.mine(s.worker), 0);
        plans.reply(s.worker, checkin, new TextInput("The errors came from the old template; fixed now"));
        assertError(409, "plan.replied", () -> later.checkin(s.boss, id, first, new CheckinInput(Map.of(objectives.get(0), "met",
                objectives.get(1), "met"), "Changing my mind after the reply")));
        assertError(409, "request.refused", () -> plans.decide(s.worker, id, new DecideInput("completed", "I met them all", null, null)), 403);
    }

    @Test
    void hrExtendsOnceThenDecidesNotMetWhichStartsTheExit() {
        Setup s = setup(false);
        UUID id = id(plans.create(s.hr, plan(s.workerRecord.toString(), 30, 14)));
        plans.start(s.hr, id, new StartInput(null));
        assertError(409, "plan.running", () -> plans.decide(s.hr, id, new DecideInput("not_met", "Objectives were not met", null,
                TODAY.plusDays(40).toString())));
        Map<String, Object> extended = plans.decide(s.hr, id, new DecideInput("extended", "Some progress; three more weeks", 20, null));
        assertEquals(TODAY.plusDays(50).toString(), extended.get("endDate"));
        assertEquals(TODAY.plusDays(30).toString(), extended.get("originalEnd"));
        assertEquals(List.of(TODAY.plusDays(14), TODAY.plusDays(44), TODAY.plusDays(50)).stream().map(LocalDate::toString).toList(), dues(extended));
        assertError(409, "plan.extended", () -> plans.decide(s.hr, id, new DecideInput("extended", "Once more, please", 20, null)));

        Plans after = new Plans(db, crypto, exits, fixed(TODAY.plusDays(51)));
        assertTrue(events(new Notifications(db, fixed(TODAY.plusDays(51))), s.hr).contains("plan_overdue"));
        assertError(409, "plan.no_checkins", () -> after.decide(s.hr, id, new DecideInput("not_met", "Objectives were not met", null,
                TODAY.plusDays(80).toString())));
        List<String> objectives = ids(extended.get("objectives"));
        after.checkin(s.boss, id, checkinId(extended, 2), new CheckinInput(Map.of(objectives.get(0), "not_met", objectives.get(1), "not_met"),
                "Neither objective was met by the end of the plan"));
        Map<String, Object> decided = after.decide(s.hr, id, new DecideInput("not_met", "Objectives were not met after the extension", null,
                TODAY.plusDays(81).toString()));
        assertEquals("not_met", decided.get("status"));
        assertNotNull(decided.get("exitCaseId"), "the exit is started");
        assertTrue(events(s.worker).contains("plan_decided"));
        assertError(409, "plan.state", () -> after.decide(s.hr, id, new DecideInput("cancelled", "Too late to cancel", null, null)));
    }

    @Test
    void aDraftIsCancelledAndANewPlanCanFollow() {
        Setup s = setup(false);
        UUID id = id(plans.create(s.boss, plan(s.workerRecord.toString(), 60, 14)));
        assertError(403, "permission.denied", () -> plans.decide(s.boss, id, new DecideInput("cancelled", "Not needed after all", null, null)));
        assertEquals("cancelled", plans.decide(s.hr, id, new DecideInput("cancelled", "Moved to a new team", null, null)).get("status"));
        assertEquals(0, plans.list(s.hr, null).size());
        assertEquals(1, plans.list(s.hr, "all").size());
        plans.create(s.boss, plan(s.workerRecord.toString(), 60, 14));
        assertEquals(1, plans.list(s.boss, null).size(), "the manager sees their team's plans");
        assertError(403, "permission.denied", () -> plans.list(s.colleague, null));
    }

    private static final String REASON = "Monthly reports have been late or wrong for three months running";

    private static PlanInput plan(String employment, int length, int every) {
        return new PlanInput(employment, null, REASON, "A session on the reporting tool, and a weekly half hour with Bina",
                List.of(new Objective("Send the monthly report on time", "By the 3rd working day, every month"),
                        new Objective("Accurate numbers", "No more than one correction a month")), length, every);
    }

    @SuppressWarnings("unchecked")
    private static List<String> dues(Map<String, Object> plan) {
        return ((List<Map<String, Object>>) plan.get("checkins")).stream().map(k -> (String) k.get("dueDate")).toList();
    }

    @SuppressWarnings("unchecked")
    private static UUID checkinId(Map<String, Object> plan, int index) {
        return UUID.fromString((String) ((List<Map<String, Object>>) plan.get("checkins")).get(index).get("id"));
    }

    private static Clock fixed(LocalDate day) {
        return Clock.fixed(day.atTime(12, 0).atZone(IST).toInstant(), ZoneOffset.UTC);
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, SessionCtx hr, SessionCtx boss, SessionCtx worker, SessionCtx colleague, UUID workerRecord) {}

    /** HR, a manager, the worker (with two agreed goals, 60% and 40%) and a colleague; with competencies when asked. */
    private static Setup setup(boolean competencies) {
        String slug = "pip-" + SEQUENCE.incrementAndGet();
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

    private static List<String> events(SessionCtx ctx) {
        return events(new Notifications(db), ctx);
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(Notifications notifications, SessionCtx ctx) {
        return ((List<Map<String, Object>>) notifications.list(ctx).get("items")).stream().map(x -> (String) x.get("event")).toList();
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
