package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
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
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.Feedback;
import com.example.loginapp.core.Feedback.GiveInput;
import com.example.loginapp.core.Feedback.TextInput;
import com.example.loginapp.core.Goals;
import com.example.loginapp.core.Goals.CheckinInput;
import com.example.loginapp.core.Goals.GoalInput;
import com.example.loginapp.core.Goals.ReasonInput;
import com.example.loginapp.core.Goals.SetInput;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.OneToOnes;
import com.example.loginapp.core.OneToOnes.DoneInput;
import com.example.loginapp.core.OneToOnes.ItemInput;
import com.example.loginapp.core.OneToOnes.MeetingInput;
import com.example.loginapp.core.PerformanceSetup;
import com.example.loginapp.core.PerformanceSetup.CompetencyInput;
import com.example.loginapp.core.PerformanceSetup.Level;
import com.example.loginapp.core.PerformanceSetup.ScaleInput;
import com.example.loginapp.core.PerformanceTeam;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Goals, one-to-ones, feedback, the rating scale and the Team view against a real PostgreSQL.
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class GoalsTest {

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
    private static Goals goals;
    private static OneToOnes oneToOnes;
    private static Feedback feedback;
    private static PerformanceSetup setup;
    private static PerformanceTeam team;
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
            st.execute("drop database if exists hrms_test_goals with (force)");
            st.execute("create database hrms_test_goals");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the goal tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_goals";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, crypto);
        goals = new Goals(db);
        oneToOnes = new OneToOnes(db, crypto);
        feedback = new Feedback(db, crypto);
        setup = new PerformanceSetup(db);
        team = new PerformanceTeam(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── goals ───────────────────────────────────────────────────────────

    @Test
    void theEmployeeSubmitsWeightedGoalsAndTheManagerAgreesThem() {
        Setup s = setup();
        Map<String, Object> mine = goals.mine(s.worker);
        assertEquals("none", mine.get("setStatus"));
        add(s.worker, s.workerRecord, "Ship the quarterly sales report on time", 60, "done", null);
        Map<String, Object> view = add(s.worker, s.workerRecord, "Close 40 accounts", 30, "number", new BigDecimal("40"));
        assertEquals(90, view.get("totalWeight"));
        assertEquals(false, view.get("canSubmit"), "the weights must add up to 100 first");
        assertError(409, "goal.weights", () -> goals.submit(s.worker, new SetInput(null, null, null)));
        assertError(400, "request.invalid", () -> add(s.worker, s.workerRecord, "Odd weight", 7, "rating", null));
        view = add(s.worker, s.workerRecord, "Learn the new reporting tool", 10, "rating", null);
        assertEquals(true, view.get("canSubmit"));

        view = goals.submit(s.worker, new SetInput(null, null, null));
        assertEquals("submitted", view.get("setStatus"));
        assertTrue(events(s.boss).contains("goals_submitted"));
        assertError(409, "request.refused", () -> goals.agree(s.worker, new SetInput(s.workerRecord.toString(), null, null)));
        assertError(404, "not_found", () -> goals.view(s.colleague, s.workerRecord, null));

        view = goals.agree(s.boss, new SetInput(s.workerRecord.toString(), null, null));
        assertEquals("agreed", view.get("setStatus"));
        assertTrue(events(s.worker).contains("goals_agreed"));

        // Progress on a number goal is worked out from the target.
        UUID accounts = goal(view, "Close 40 accounts");
        view = goals.checkin(s.worker, accounts, new CheckinInput(null, new BigDecimal("10"), "Ten closed in the first month"));
        assertEquals(25, ((Number) find(view, "Close 40 accounts").get("progress")).intValue());
        assertError(400, "request.invalid", () -> goals.checkin(s.worker, goal(goals.mine(s.worker), "Ship the quarterly sales report on time"),
                new CheckinInput(null, new BigDecimal("3"), null)));
    }

    @Test
    void aReturnedSetIsChangedAndSentAgain() {
        Setup s = setup();
        add(s.worker, s.workerRecord, "Answer support tickets within a day", 100, "rating", null);
        goals.submit(s.worker, new SetInput(null, null, null));
        assertError(400, "request.invalid", () -> goals.returnSet(s.boss, new SetInput(s.workerRecord.toString(), null, "Too vague")));
        Map<String, Object> view = goals.returnSet(s.boss, new SetInput(s.workerRecord.toString(), null,
                "Split this into response time and customer rating"));
        assertEquals("returned", view.get("setStatus"));
        assertTrue(events(s.worker).contains("goals_returned"));
        UUID g = goal(view, "Answer support tickets within a day");
        goals.update(s.worker, g, input(s.workerRecord, "Answer support tickets within four hours", 60, "rating", null, null));
        view = add(s.worker, s.workerRecord, "Keep the customer rating above 4.5", 40, "rating", null);
        assertEquals("returned", view.get("setStatus"), "still returned until it is sent again");
        view = goals.submit(s.worker, new SetInput(null, null, null));
        assertEquals("submitted", view.get("setStatus"));
        assertNull(find(view, "Answer support tickets within four hours").get("returnNote"));
    }

    @Test
    void anAgreedGoalChangedByTheEmployeeGoesBackToTheManagerWithItsEarlierVersionKept() {
        Setup s = setup();
        add(s.worker, s.workerRecord, "Cut report time to two days", 50, "rating", null);
        add(s.worker, s.workerRecord, "Train two new joiners", 50, "rating", null);
        goals.submit(s.worker, new SetInput(null, null, null));
        Map<String, Object> view = goals.agree(s.boss, new SetInput(s.workerRecord.toString(), null, null));
        UUID g = goal(view, "Cut report time to two days");

        assertError(400, "request.invalid", () -> goals.update(s.worker, g, input(s.workerRecord, "Cut report time to one day", 50, "rating", null, null)));
        view = goals.update(s.worker, g, input(s.workerRecord, "Cut report time to one day", 50, "rating", null, "The tool now allows it"));
        Map<String, Object> changed = find(view, "Cut report time to one day");
        assertEquals("submitted", changed.get("status"));
        assertEquals(2, ((Number) changed.get("version")).intValue());
        assertEquals(1, ((List<?>) changed.get("versions")).size(), "the earlier version is kept");
        assertTrue(events(s.boss).contains("goal_changed"));

        view = goals.agree(s.boss, new SetInput(s.workerRecord.toString(), null, null));
        assertEquals("agreed", view.get("setStatus"));
        // Changed by the manager, it stays agreed.
        view = goals.update(s.boss, goal(view, "Train two new joiners"), input(s.workerRecord, "Train three new joiners", 50, "rating", null,
                "A third joiner starts in March"));
        assertEquals("agreed", find(view, "Train three new joiners").get("status"));

        // Dropping keeps the goal, counted for nothing; an agreed goal is never removed.
        assertError(409, "goal.agreed", () -> goals.delete(s.worker, g));
        assertError(409, "request.refused", () -> goals.drop(s.worker, g, new ReasonInput("Not needed any more")));
        view = goals.drop(s.boss, g, new ReasonInput("The report moved to another team"));
        assertEquals("dropped", find(view, "Cut report time to one day").get("status"));
        assertEquals(50, view.get("totalWeight"));
    }

    @Test
    void aGoalSupportsTheManagersOrTheDepartmentsGoal() {
        Setup s = setup();
        Map<String, Object> bossGoals = add(s.boss, s.bossRecord, "Grow the region by 20%", 100, "rating", null);
        goals.submit(s.boss, new SetInput(null, null, null));
        goals.agree(s.hr, new SetInput(s.bossRecord.toString(), null, null));
        UUID parent = goal(goals.view(s.boss, s.bossRecord, null), "Grow the region by 20%");
        assertTrue(goals.mine(s.worker).get("parentOptions").toString().contains("Grow the region"));
        Map<String, Object> view = goals.create(s.worker, new GoalInput(s.workerRecord.toString(), null, null, "Open two new accounts a month",
                null, 100, "rating", null, null, null, parent.toString(), null));
        assertEquals("Grow the region by 20%", find(view, "Open two new accounts a month").get("parentTitle"));
        assertError(400, "request.invalid", () -> goals.create(s.hr, new GoalInput(s.hrRecord.toString(), null, null,
                "Support a goal that is not my manager's", null, 100, "rating", null, null, null, parent.toString(), null)));
        assertTrue(((String) bossGoals.get("period")).startsWith(TODAY.getMonthValue() >= 4 ? "FY " + TODAY.getYear() : "FY " + (TODAY.getYear() - 1)));
    }

    // ── one-to-ones ─────────────────────────────────────────────────────

    @Test
    void aOneToOneHasSharedNotesBothReadAndPrivateNotesOnlyTheirWriterReads() {
        Setup s = setup();
        Map<String, Object> view = oneToOnes.create(s.boss, new MeetingInput(s.workerRecord.toString(), TODAY.toString(),
                "Agenda: the quarterly report, and training", "Seems stretched; ask about workload"));
        @SuppressWarnings("unchecked")
        UUID meeting = UUID.fromString((String) ((List<Map<String, Object>>) view.get("meetings")).get(0).get("id"));
        oneToOnes.addItem(s.boss, meeting, new ItemInput("Book the reporting course", "report"));

        Map<String, Object> theirs = oneToOnes.with(s.worker, s.bossRecord);
        assertEquals("report", theirs.get("youAre"));
        Map<String, Object> m = first(theirs);
        assertEquals("Agenda: the quarterly report, and training", m.get("sharedNotes"));
        assertNull(m.get("privateNotes"), "the manager's private notes stay the manager's");
        theirs = oneToOnes.update(s.worker, meeting, new MeetingInput(null, null, null, "Ask for the course budget"));
        assertEquals("Ask for the course budget", first(theirs).get("privateNotes"));
        assertEquals("Seems stretched; ask about workload", first(oneToOnes.with(s.boss, s.workerRecord)).get("privateNotes"));

        @SuppressWarnings("unchecked")
        UUID item = UUID.fromString((String) ((List<Map<String, Object>>) theirs.get("openItems")).get(0).get("id"));
        theirs = oneToOnes.markItem(s.worker, item, new DoneInput(true));
        assertEquals(List.of(), theirs.get("openItems"));

        assertError(404, "not_found", () -> oneToOnes.with(s.colleague, s.workerRecord));
        assertError(404, "not_found", () -> oneToOnes.with(s.hr, s.workerRecord));   // not even HR
        assertError(404, "not_found", () -> oneToOnes.create(s.colleague, new MeetingInput(s.workerRecord.toString(), null, null, null)));
        assertEquals("Bina Boss", oneToOnes.people(s.worker).get(0).get("name"), "the report's manager comes first");
    }

    @Test
    void aManagerIsRemindedWhenTheyHaveNotMetAReport() {
        Setup s = setup();
        oneToOnes.create(s.boss, new MeetingInput(s.workerRecord.toString(), TODAY.toString(), "First one", null));
        // The colleague reports to the boss too, and has never had a one-to-one.
        assertTrue(events(new Notifications(db, fixed(TODAY.plusDays(1))), s.boss).contains("one_to_one_nudge"));
        long sent = count(s, "select count(*) from app.notification where event = 'one_to_one_nudge'");
        events(new Notifications(db, fixed(TODAY.plusDays(2))), s.boss);
        assertEquals(sent, count(s, "select count(*) from app.notification where event = 'one_to_one_nudge'"), "at most once a week");
        assertTrue(count(s, "select count(*) from app.notification where event = 'one_to_one_nudge'") >= 1);
    }

    // ── feedback ────────────────────────────────────────────────────────

    @Test
    void praiseGoesToThePersonAndPrivateFeedbackOnlyToTheirManager() {
        Setup s = setup();
        Map<String, Object> praise = feedback.give(s.colleague, new GiveInput(s.workerRecord.toString(), "praise",
                "Thank you for covering my calls last week", false, null, null));
        feedback.give(s.colleague, new GiveInput(s.workerRecord.toString(), "private", "Often late to the stand-up", false, null, null));
        assertTrue(events(s.worker).contains("feedback_received"));

        List<Map<String, Object>> received = feedback.received(s.worker);
        assertEquals(1, received.size(), "the person reads praise, never private feedback");
        assertEquals("Thank you for covering my calls last week", received.get(0).get("text"));
        assertEquals("Chitra Colleague", received.get(0).get("giver"));
        assertEquals(1, feedback.about(s.boss, s.workerRecord).size(), "the manager reads the private feedback, not unshared praise");
        assertEquals(2, feedback.about(s.hr, s.workerRecord).size());

        UUID id = UUID.fromString((String) praise.get("id"));
        assertError(404, "not_found", () -> feedback.edit(s.worker, id, new TextInput("Not mine to change")));
        assertEquals("Thank you for covering my calls", feedback.edit(s.colleague, id, new TextInput("Thank you for covering my calls")).get("text"));
        db.inTenant(s.org.id, c -> Sql.update(c, "update app.feedback set created_at = now() - interval '2 days' where id = ?", id));
        assertError(409, "feedback.too_late", () -> feedback.edit(s.colleague, id, new TextInput("Changed my mind")));
        Map<String, Object> withdrawn = feedback.withdraw(s.colleague, id);
        assertNull(withdrawn.get("text"));
        assertTrue(withdrawn.get("withdrawnAt") != null, "its date stays");
        assertError(409, "request.refused", () -> feedback.give(s.worker, new GiveInput(s.workerRecord.toString(), "praise", "Well done me", false,
                null, null)));
    }

    // ── setup and the Team view ─────────────────────────────────────────

    @Test
    void aChangedScaleIsANewVersionAndCompetencySetsMatchByRules() {
        Setup s = setup();
        Map<String, Object> view = setup.get(s.hr);
        @SuppressWarnings("unchecked")
        Map<String, Object> scale = (Map<String, Object>) view.get("scale");
        assertEquals(1, ((Number) scale.get("version")).intValue());
        assertEquals(5, ((List<?>) scale.get("levels")).size());
        assertError(400, "request.invalid", () -> setup.saveScale(s.hr, new ScaleInput(List.of(new Level(1, "Low", null),
                new Level(3, "High", null), new Level(4, "Top", null)))));
        view = setup.saveScale(s.hr, new ScaleInput(List.of(new Level(1, "Below", null), new Level(2, "Meets", null),
                new Level(3, "Above", null), new Level(4, "Far above", null))));
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) view.get("scale");
        assertEquals(2, ((Number) next.get("version")).intValue());
        assertError(403, "permission.denied", () -> setup.saveScale(s.boss, new ScaleInput(List.of())));

        view = setup.saveCompetency(s.hr, null, new CompetencyInput("Ownership", "Sees work through to the end", true));
        view = setup.saveCompetency(s.hr, null, new CompetencyInput("Communication", null, true));
        assertError(409, "request.refused", () -> setup.saveCompetency(s.hr, null, new CompetencyInput("ownership", null, true)));
        @SuppressWarnings("unchecked")
        List<String> ids = ((List<Map<String, Object>>) view.get("competencies")).stream().map(k -> (String) k.get("id")).toList();
        view = setup.saveSet(s.hr, null, new PerformanceSetup.SetInput("Analysts", Map.of("designation", List.of("Analyst")), ids, true));
        assertEquals(1, ((List<?>) view.get("competencySets")).size());
    }

    @Test
    void theTeamViewShowsAManagersReportsAndEveryoneForHr() {
        Setup s = setup();
        add(s.worker, s.workerRecord, "One goal for everything", 100, "rating", null);
        goals.submit(s.worker, new SetInput(null, null, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mine = (List<Map<String, Object>>) team.team(s.boss).get("people");
        assertEquals(List.of("Chitra Colleague", "Wasim Worker"), mine.stream().map(r -> r.get("name")).toList());
        Map<String, Object> worker = mine.stream().filter(r -> r.get("name").equals("Wasim Worker")).findFirst().orElseThrow();
        assertEquals("submitted", worker.get("goalStatus"));
        assertEquals(true, worker.get("isReport"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) team.team(s.hr).get("people");
        assertEquals(4, all.size(), "everyone but HR themselves");
        assertError(403, "permission.denied", () -> team.team(s.worker));
        assertFalse(all.stream().anyMatch(r -> r.get("name").equals("Hari Hr")));
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, SessionCtx hr, SessionCtx boss, SessionCtx worker, SessionCtx colleague, UUID hrRecord, UUID bossRecord,
                         UUID workerRecord, UUID colleagueRecord) {}

    /** HR, a manager with two reports (the worker and the colleague), all permanent. */
    private static Setup setup() {
        String slug = "goals-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        Org org = new Org(UUID.fromString((String) created.get("id")), slug, admin);
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx boss = join(org, "boss", List.of("employee", "manager"));
        SessionCtx worker = join(org, "worker", List.of("employee"));
        SessionCtx colleague = join(org, "colleague", List.of("employee"));
        add(admin, "Asha Admin", admin, null, 1001);
        UUID hrRecord = add(admin, "Hari Hr", hr, null, 1002);
        UUID bossRecord = add(admin, "Bina Boss", boss, null, 1003);
        UUID workerRecord = add(admin, "Wasim Worker", worker, bossRecord, 1004);
        UUID colleagueRecord = add(admin, "Chitra Colleague", colleague, bossRecord, 1005);
        return new Setup(org, hr, boss, worker, colleague, hrRecord, bossRecord, workerRecord, colleagueRecord);
    }

    private static UUID add(SessionCtx by, String name, SessionCtx login, UUID manager, int code) {
        return UUID.fromString((String) employees.add(by, new NewEmployee(name, null, "1990-01-01", "female", null, "9876543210",
                "permanent", TODAY.minusYears(2).toString(), null, null, "E" + code, "Analyst", null,
                manager == null ? null : manager.toString(), login.membershipId().toString(), null, null, null, null, null, null, null,
                null, null)).get("id"));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static GoalInput input(UUID employment, String title, int weight, String measure, BigDecimal target, String reason) {
        return new GoalInput(employment.toString(), null, null, title, null, weight, measure, target, measure.equals("number") ? "accounts" : null,
                null, null, reason);
    }

    private static Map<String, Object> add(SessionCtx ctx, UUID employment, String title, int weight, String measure, BigDecimal target) {
        return goals.create(ctx, input(employment, title, weight, measure, target, null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(Map<String, Object> view, String title) {
        return ((List<Map<String, Object>>) view.get("goals")).stream().filter(g -> g.get("title").equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("no goal " + title));
    }

    private static UUID goal(Map<String, Object> view, String title) {
        return UUID.fromString((String) find(view, title).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Map<String, Object> view) {
        return ((List<Map<String, Object>>) view.get("meetings")).get(0);
    }

    private static List<String> events(SessionCtx ctx) {
        return events(new Notifications(db), ctx);
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(Notifications n, SessionCtx ctx) {
        return ((List<Map<String, Object>>) n.list(ctx).get("items")).stream().map(x -> (String) x.get("event")).toList();
    }

    private static long count(Setup s, String sql) {
        return db.inTenant(s.org.id, c -> (Long) Sql.one(c, sql.replace("select count(*)", "select count(*) as n")).get("n"));
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
