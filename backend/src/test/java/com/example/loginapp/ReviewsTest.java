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
 * Review cycles against a real PostgreSQL: starting a cycle, the self review, the manager's review
 * with the worked-out overall rating, returning a self review, release and acknowledgement, and who
 * sees what when. Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class ReviewsTest {

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
            st.execute("drop database if exists hrms_test_reviews with (force)");
            st.execute("create database hrms_test_reviews");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the review tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_reviews";
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
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    @Test
    void aCycleRunsFromSelfReviewToTheManagersRatingToRelease() {
        Setup s = setup(true);
        Map<String, Object> cycle = reviews.createCycle(s.hr, new CycleInput("Annual review", null, null, null, TODAY.plusDays(1).toString(),
                null, 70, true, TODAY.plusDays(10).toString(), TODAY.plusDays(20).toString(), null, null));
        assertEquals("draft", cycle.get("status"));
        assertEquals(5, ((Number) cycle.get("eligibleCount")).intValue(), "everyone working here");
        UUID cycleId = id(cycle);
        assertError(400, "request.invalid", () -> reviews.createCycle(s.hr, new CycleInput("Wrong order", null, null, null, null, null, 70, true,
                TODAY.plusDays(20).toString(), TODAY.plusDays(10).toString(), null, null)));
        assertError(403, "permission.denied", () -> reviews.startCycle(s.boss, cycleId));
        cycle = reviews.startCycle(s.hr, cycleId);
        assertEquals("active", cycle.get("status"));
        assertTrue(events(s.worker).contains("review_self_open"));

        UUID review = reviewOf(cycle, "Wasim Worker");
        Map<String, Object> mine = reviews.review(s.worker, review);
        assertEquals(true, mine.get("canWriteSelf"));
        assertNull(mine.get("managerEntry"), "the employee does not see the manager's part before release");
        assertError(404, "not_found", () -> reviews.review(s.colleague, review));
        List<String> goalIds = ids(mine.get("goals"));
        assertEquals(2, goalIds.size());

        // A draft is saved; sending needs every goal rated and the required questions answered.
        reviews.saveEntry(s.worker, review, "self", entry(List.of(new Rating(goalIds.get(0), 4, "Done every Monday")), List.of(),
                Map.of(), null, null, false));
        assertError(400, "review.incomplete", () -> reviews.saveEntry(s.worker, review, "self", entry(rate(goalIds, 4, 3), List.of(), Map.of(),
                null, null, true)));
        Map<String, Object> sent = reviews.saveEntry(s.worker, review, "self", entry(rate(goalIds, 4, 3), List.of(),
                Map.of("went_well", "The Monday dashboard"), null, null, true));
        assertEquals("manager_review", sent.get("status"));
        assertTrue(events(s.boss).contains("review_manager_open"));
        assertError(409, "review.state", () -> reviews.saveEntry(s.worker, review, "self", entry(rate(goalIds, 5, 5), List.of(),
                Map.of("went_well", "Changed my mind"), null, null, false)));

        // The manager rates goals (60% and 40%) and the competency; overall = 0.7 × goals + 0.3 × competencies.
        Map<String, Object> asManager = reviews.review(s.boss, review);
        assertEquals(true, asManager.get("canWriteManager"));
        assertEquals("The Monday dashboard", ((Map<?, ?>) ((Map<?, ?>) asManager.get("self")).get("answers")).get("went_well"));
        List<String> comps = ids(asManager.get("competencies"));
        assertEquals(1, comps.size());
        Map<String, String> answers = Map.of("strengths", "Reliable and careful", "develop", "Present the numbers herself");
        assertError(400, "request.invalid", () -> reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 4, 3),
                List.of(new Rating(comps.get(0), 3, null)), answers, 5, null, true)));   // differs from the worked-out 4 with no reason
        Map<String, Object> written = reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 4, 3),
                List.of(new Rating(comps.get(0), 3, null)), answers, null, null, true));
        // goals: (4×60 + 3×40) / 100 = 3.6; overall: 3.6 × 0.7 + 3 × 0.3 = 3.42, rounded to 3
        assertEquals(0, new BigDecimal("3.42").compareTo(new BigDecimal(written.get("proposedRating").toString())));
        assertEquals(3, ((Number) written.get("managerLevel")).intValue());
        assertEquals("calibration", written.get("status"));
        assertTrue(((List<?>) written.get("why")).size() >= 4, "the steps are shown");
        assertError(409, "review.state", () -> reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 5, 5),
                List.of(new Rating(comps.get(0), 5, null)), answers, null, null, true)));

        // Release needs every review written: the others are withdrawn or moved on and written here.
        assertError(409, "cycle.state", () -> reviews.release(s.hr, cycleId));
        reviews.startCalibration(s.hr, cycleId);
        assertError(409, "cycle.pending", () -> reviews.release(s.hr, cycleId));
        Map<String, Object> current = reviews.cycle(s.hr, cycleId);
        for (String other : List.of("Asha Admin", "Hari Hr", "Bina Boss", "Chitra Colleague")) {
            reviews.withdraw(s.hr, reviewOf(current, other), new ReasonInput("Not reviewed in this test"));
        }
        Map<String, Object> released = reviews.release(s.hr, cycleId);
        assertEquals("released", released.get("status"));
        assertTrue(events(s.worker).contains("review_released"));

        mine = reviews.review(s.worker, review);
        assertEquals(3, ((Number) mine.get("finalLevel")).intValue());
        assertNotNull(mine.get("managerEntry"), "now the employee reads the manager's part");
        assertEquals("Reliable and careful", ((Map<?, ?>) ((Map<?, ?>) mine.get("managerEntry")).get("answers")).get("strengths"));
        assertNull(mine.get("overrideReason"));
        mine = reviews.acknowledge(s.worker, review, new AckInput("Thank you; I will work on presenting"));
        assertEquals("acknowledged", mine.get("status"));
        assertError(409, "review.state", () -> reviews.acknowledge(s.worker, review, new AckInput(null)));
        assertEquals("closed", reviews.closeCycle(s.hr, cycleId).get("status"));
    }

    @Test
    void aManagerCanReturnASelfReviewOnceAndOverrideTheRatingWithAReason() {
        Setup s = setup(false);
        Map<String, Object> cycle = reviews.startCycle(s.hr, id(reviews.createCycle(s.hr, new CycleInput("Half year", null, null, null, null, null,
                null, true, null, null, null, null))));
        UUID review = reviewOf(cycle, "Wasim Worker");
        List<String> goalIds = ids(reviews.review(s.worker, review).get("goals"));
        assertEquals(100, ((Number) cycle.get("goalWeight")).intValue(), "with no competency sets, goals count for everything");
        reviews.saveEntry(s.worker, review, "self", entry(rate(goalIds, 2, 2), List.of(), Map.of("went_well", "Not much"), null, null, true));
        Map<String, Object> back = reviews.returnSelf(s.boss, review, new ReasonInput("Please add what you did on the audit"));
        assertEquals("self_review", back.get("status"));
        assertTrue(events(s.worker).contains("review_self_returned"));
        assertEquals("Please add what you did on the audit", reviews.review(s.worker, review).get("selfReturnNote"));
        reviews.saveEntry(s.worker, review, "self", entry(rate(goalIds, 3, 3), List.of(), Map.of("went_well", "The audit, finished early"),
                null, null, true));
        assertError(409, "review.state", () -> reviews.returnSelf(s.boss, review, new ReasonInput("Once more, please")));

        Map<String, String> answers = Map.of("strengths", "Finished the audit early", "develop", "Plan the quarter ahead");
        assertError(400, "request.invalid", () -> reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 3, 3), List.of(), answers, 4,
                "Short", true)));
        Map<String, Object> written = reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 3, 3), List.of(), answers, 4,
                "The audit saved the company a penalty, beyond the goals", true));
        assertEquals(4, ((Number) written.get("managerLevel")).intValue());
        assertEquals("The audit saved the company a penalty, beyond the goals", written.get("overrideReason"));
        assertError(409, "request.refused", () -> reviews.saveEntry(s.worker, review, "manager", entry(rate(goalIds, 5, 5), List.of(), answers,
                5, null, true)), 404);
    }

    @Test
    void hrAddsSomeoneMissedAndMovesAReviewPastTheSelfReview() {
        Setup s = setup(false);
        Map<String, Object> cycle = reviews.startCycle(s.hr, id(reviews.createCycle(s.hr, new CycleInput("Engineering only", null, null,
                Map.of("designation", List.of("Engineer")), null, null, null, true, null, null, null, null))));
        assertEquals(1, ((List<?>) cycle.get("reviews")).size(), "only the engineer");
        UUID cycleId = id(cycle);
        assertError(400, "request.invalid", () -> reviews.addPeople(s.hr, cycleId, new PeopleInput(List.of(s.workerRecord.toString()), "")));
        cycle = reviews.addPeople(s.hr, cycleId, new PeopleInput(List.of(s.workerRecord.toString()), "Moved to engineering in March"));
        assertEquals(2, ((List<?>) cycle.get("reviews")).size());
        assertError(409, "request.refused", () -> reviews.addPeople(s.hr, cycleId, new PeopleInput(List.of(s.workerRecord.toString()),
                "Added twice by mistake")));
        UUID review = reviewOf(cycle, "Wasim Worker");
        reviews.moveOn(s.hr, review, new ReasonInput("On leave until the end of the cycle"));
        assertEquals("manager_review", reviews.review(s.boss, review).get("status"));
        assertEquals(1, reviews.toWrite(s.boss).size());
        assertEquals(1, reviews.mine(s.worker).size());
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, SessionCtx hr, SessionCtx boss, SessionCtx worker, SessionCtx colleague, UUID workerRecord) {}

    /** HR, a manager, the worker (with two agreed goals, 60% and 40%) and a colleague; with competencies when asked. */
    private static Setup setup(boolean competencies) {
        String slug = "rev-" + SEQUENCE.incrementAndGet();
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
