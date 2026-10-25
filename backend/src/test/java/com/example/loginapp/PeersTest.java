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
import com.example.loginapp.core.Feedback;
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
import com.example.loginapp.core.Reviews.PeerInput;
import com.example.loginapp.core.Reviews.ReasonInput;
import com.example.loginapp.core.Reviews.SignOffInput;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UserService;

/**
 * Peer reviews, skip-level sign-off and requested feedback against a real PostgreSQL. Needs
 * PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class PeersTest {

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
    private static Feedback feedback;
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
            st.execute("drop database if exists hrms_test_peers with (force)");
            st.execute("create database hrms_test_peers");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the peer review tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_peers";
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
        feedback = new Feedback(db, crypto);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    @Test
    void peersWriteTheirReviewsAndTheEmployeeReadsThemOnlyWhenThatKeepsThemAnonymous() {
        Setup s = setup(false);
        Map<String, Object> cycle = reviews.startCycle(s.hr, id(reviews.createCycle(s.hr, new CycleInput("Annual review", null, null, null, null,
                null, null, false, null, null, null, null, 2, true, false))));
        UUID review = reviewOf(cycle, "Wasim Worker");
        String colleague = employment(s, "Chitra Colleague");
        String hari = employment(s, "Hari Hr");
        assertError(400, "request.invalid", () -> reviews.requestPeers(s.boss, review, new PeopleInput(List.of(s.workerRecord.toString()), null)));
        assertError(404, "not_found", () -> reviews.requestPeers(s.colleague, review, new PeopleInput(List.of(hari), null)));
        Map<String, Object> asked = reviews.requestPeers(s.boss, review, new PeopleInput(List.of(colleague, hari), null));
        assertEquals(2, ((List<?>) asked.get("peers")).size());
        assertError(400, "request.invalid", () -> reviews.requestPeers(s.boss, review, new PeopleInput(List.of(employment(s, "Asha Admin")), null)));
        assertTrue(events(s.colleague).contains("peer_review_requested"));

        // Chitra writes hers; a required answer is needed to send it.
        List<Map<String, Object>> mine = reviews.peerRequests(s.colleague);
        assertEquals(1, mine.size());
        UUID request = UUID.fromString((String) mine.get(0).get("id"));
        assertError(404, "not_found", () -> reviews.peerReview(s.worker, request));
        assertEquals(2, ((List<?>) reviews.peerReview(s.colleague, request).get("questions")).size());
        assertError(400, "review.incomplete", () -> reviews.savePeer(s.colleague, request, new PeerInput(Map.of("peer_improve", "More updates"),
                true)));
        Map<String, Object> sent = reviews.savePeer(s.colleague, request, new PeerInput(Map.of("peer_strengths", "Patient when explaining numbers"),
                true));
        assertEquals("submitted", sent.get("status"));
        assertError(409, "review.state", () -> reviews.savePeer(s.colleague, request, new PeerInput(Map.of("peer_strengths", "Changed"), true)));
        UUID hariRequest = UUID.fromString((String) reviews.peerRequests(s.hr).get(0).get("id"));
        reviews.declinePeer(s.hr, hariRequest, new ReasonInput("I have not worked with Wasim"));

        // The manager reads both, with names; the decline's reason too.
        Map<String, Object> asManager = reviews.review(s.boss, review);
        Map<String, Object> chitra = peer(asManager, "Chitra Colleague");
        assertEquals("Patient when explaining numbers", ((Map<?, ?>) chitra.get("answers")).get("peer_strengths"));
        assertEquals("I have not worked with Wasim", peer(asManager, "Hari Hr").get("declinedReason"));

        List<String> goalIds = ids(asManager.get("goals"));
        reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 3, 3), List.of(), Map.of("strengths", "Careful", "develop", "Speak up"),
                null, null, true));
        assertEquals("calibration", reviews.review(s.boss, review).get("status"), "no skip-level sign-off in this cycle");
        for (String other : List.of("Asha Admin", "Hari Hr", "Bina Boss", "Chitra Colleague")) {
            reviews.withdraw(s.hr, reviewOf(cycle, other), new ReasonInput("Not reviewed in this test"));
        }
        reviews.startCalibration(s.hr, id(cycle));
        reviews.release(s.hr, id(cycle));
        // One peer review, anonymous: the employee would know who wrote it, so it is not shown.
        assertEquals(0, ((List<?>) reviews.review(s.worker, review).get("peers")).size());
    }

    @Test
    void theManagersManagerSignsOffOrSendsTheReviewBack() {
        Setup s = setup(false);
        Map<String, Object> cycle = reviews.startCycle(s.hr, id(reviews.createCycle(s.hr, new CycleInput("Annual review", null, null, null, null,
                null, null, false, null, null, null, null, 0, true, true))));
        UUID review = reviewOf(cycle, "Wasim Worker");
        List<String> goalIds = ids(reviews.review(s.boss, review).get("goals"));
        Map<String, String> answers = Map.of("strengths", "Careful", "develop", "Speak up");
        Map<String, Object> written = reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 3, 3), List.of(), answers, null, null, true));
        assertEquals("skip_level", written.get("status"));
        assertEquals("Asha Admin", written.get("skipLevelManager"));
        assertTrue(events(s.org.admin).contains("skip_level_open"));
        assertTrue(reviews.toWrite(s.org.admin).stream().anyMatch(r -> r.get("id").equals(review.toString())));
        assertError(404, "not_found", () -> reviews.signOff(s.colleague, review, new SignOffInput("agree", null)));
        assertError(404, "not_found", () -> reviews.signOff(s.boss, review, new SignOffInput("agree", null)));
        assertError(400, "request.invalid", () -> reviews.signOff(s.org.admin, review, new SignOffInput("return", "Short")));

        Map<String, Object> back = reviews.signOff(s.org.admin, review, new SignOffInput("return", "Say what the audit work was worth"));
        assertEquals("manager_review", back.get("status"));
        assertTrue(events(s.boss).contains("skip_level_returned"));
        assertEquals("Say what the audit work was worth", reviews.review(s.boss, review).get("skipLevelNote"));
        reviews.saveEntry(s.boss, review, "manager", entry(rate(goalIds, 4, 3), List.of(), Map.of("strengths", "Careful; the audit saved a penalty",
                "develop", "Speak up"), null, null, true));
        Map<String, Object> agreed = reviews.signOff(s.org.admin, review, new SignOffInput("agree", "Agreed after the audit note"));
        assertEquals("calibration", agreed.get("status"));
        assertEquals("Agreed after the audit note", ((Map<?, ?>) reviews.review(s.boss, review).get("skipLevelEntry")).get("comment"));
        assertNull(reviews.review(s.worker, review).get("skipLevelEntry"), "the employee does not read the sign-off");
    }

    @Test
    void feedbackIsAskedForAndGivenOrDeclined() {
        Setup s = setup(false);
        String colleague = employment(s, "Chitra Colleague");
        String boss = employment(s, "Bina Boss");
        List<Map<String, Object>> asked = feedback.request(s.worker, new Feedback.RequestInput(null, List.of(colleague, boss),
                "On the dashboard launch"));
        assertEquals(2, asked.size());
        assertTrue(events(s.colleague).contains("feedback_requested"));
        assertError(404, "not_found", () -> feedback.request(s.worker, new Feedback.RequestInput(colleague, List.of(boss), null)));
        assertError(400, "request.invalid", () -> feedback.request(s.worker, new Feedback.RequestInput(null, List.of(s.workerRecord.toString()),
                null)));

        List<Map<String, Object>> waiting = feedback.askedOfMe(s.colleague);
        assertEquals(1, waiting.size());
        String request = (String) waiting.get(0).get("id");
        assertError(404, "not_found", () -> feedback.give(s.colleague, new Feedback.GiveInput(boss, "praise", "Clear launch notes", false, null, null,
                request)));
        feedback.give(s.colleague, new Feedback.GiveInput(s.workerRecord.toString(), "praise", "The launch notes were clear and early", false, null,
                null, request));
        assertError(409, "request.refused", () -> feedback.give(s.colleague, new Feedback.GiveInput(s.workerRecord.toString(), "praise", "Again",
                false, null, null, request)));
        feedback.decline(s.boss, UUID.fromString((String) feedback.askedOfMe(s.boss).get(0).get("id")), new Feedback.TextInput("We talk weekly"));
        List<String> statuses = feedback.asked(s.worker).stream().map(q -> (String) q.get("status")).sorted().toList();
        assertEquals(List.of("declined", "given"), statuses);

        // A manager asks about someone in their team.
        assertEquals(1, feedback.request(s.boss, new Feedback.RequestInput(s.workerRecord.toString(), List.of(colleague), null)).size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> peer(Map<String, Object> review, String name) {
        return ((List<Map<String, Object>>) review.get("peers")).stream().filter(p -> name.equals(p.get("name"))).findFirst().orElseThrow();
    }

    private static String employment(Setup s, String name) {
        return (String) employees.list(s.org.admin, null, "all", null).stream()
                .filter(e -> name.equals(e.get("legalName")) || name.equals(e.get("name"))).findFirst().orElseThrow().get("id");
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, SessionCtx hr, SessionCtx boss, SessionCtx worker, SessionCtx colleague, UUID workerRecord) {}

    /** HR, a manager, the worker (with two agreed goals, 60% and 40%) and a colleague; with competencies when asked. */
    private static Setup setup(boolean competencies) {
        String slug = "peer-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        Org org = new Org(UUID.fromString((String) created.get("id")), slug, admin);
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx boss = join(org, "boss", List.of("employee", "manager"));
        SessionCtx worker = join(org, "worker", List.of("employee"));
        SessionCtx colleague = join(org, "colleague", List.of("employee"));
        UUID adminRecord = add(admin, "Asha Admin", admin, null, 1001, "Director");
        add(admin, "Hari Hr", hr, null, 1002, "HR manager");
        UUID bossRecord = add(admin, "Bina Boss", boss, adminRecord, 1003, "Engineer");
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
