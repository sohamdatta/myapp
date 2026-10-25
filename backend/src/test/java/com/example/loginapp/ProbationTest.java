package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.ExitService;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.Probation;
import com.example.loginapp.core.Probation.CheckinInput;
import com.example.loginapp.core.Probation.DecideInput;
import com.example.loginapp.core.Probation.RecommendInput;
import com.example.loginapp.core.Probation.ReplyInput;
import com.example.loginapp.core.Probation.SettingsInput;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Probation reviews against a real PostgreSQL: check-ins and replies, the manager's recommendation,
 * HR's three decisions (confirm, extend, not confirm), who may see and write what, and reminders.
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class ProbationTest {

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
    private static Probation probation;
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
            st.execute("drop database if exists hrms_test_probation with (force)");
            st.execute("create database hrms_test_probation");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the probation tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_probation";
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
        probation = new Probation(db, crypto, exits);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── check-ins ───────────────────────────────────────────────────────

    @Test
    void aReviewStartsWithCheckInsThatTheManagerWritesAndTheEmployeeAnswers() {
        Setup s = setup();
        Map<String, Object> mine = probation.mine(s.worker);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) mine.get("probation");
        assertNotNull(p, "the worker is on probation");
        assertEquals(true, p.get("onProbation"));
        assertEquals(List.of(), p.get("checkins"), "the employee sees only check-ins that have been written");

        List<Map<String, Object>> list = probation.list(s.boss, null);
        assertEquals(1, list.size(), "the manager sees their report, and nobody else");
        Map<String, Object> row = list.get(0);
        assertEquals("Wasim Worker", row.get("name"));
        assertEquals(3L, ((Number) row.get("checkinsTotal")).longValue(), "days 30, 60 and 90 fall before the probation end");
        UUID review = UUID.fromString((String) row.get("id"));

        Map<String, Object> view = probation.get(s.boss, review);
        assertEquals(true, view.get("canWrite"));
        assertEquals(false, view.get("canDecide"), "a manager recommends; HR decides");
        UUID day30 = checkin(view, 30);
        assertError(400, "request.invalid", () -> probation.writeCheckin(s.boss, review, day30, new CheckinInput("great", "Settling in well")));
        view = probation.writeCheckin(s.boss, review, day30, new CheckinInput("on_track", "Settling in well, and asks good questions"));
        assertEquals("Settling in well, and asks good questions", find(view, 30).get("note"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seen = (List<Map<String, Object>>) ((Map<String, Object>) probation.mine(s.worker).get("probation")).get("checkins");
        assertEquals(1, seen.size());
        assertEquals("on_track", seen.get(0).get("rating"));
        assertEquals(true, seen.get(0).get("canReply"));
        probation.reply(s.worker, day30, new ReplyInput("Thank you; I would like more time with the data team"));
        assertError(409, "probation.replied", () -> probation.reply(s.worker, day30, new ReplyInput("Again")));
        assertError(409, "probation.replied",
                () -> probation.writeCheckin(s.boss, review, day30, new CheckinInput("some_concerns", "Changing my mind afterwards")));
        assertEquals("Thank you; I would like more time with the data team", find(probation.get(s.boss, review), 30).get("reply"));
        assertTrue(events(s.worker).contains("probation_checkin_written"));
    }

    @Test
    void onlyTheTeamAndHrSeeAReviewAndNobodyWritesAboutTheirOwn() {
        Setup s = setup();
        UUID review = reviewOf(s.hr, "Wasim Worker");
        assertEquals(List.of(), probation.list(s.colleague, null), "a manager with no team sees no probations");
        assertError(404, "not_found", () -> probation.get(s.colleague, review));
        assertError(403, "permission.denied", () -> probation.get(s.worker, review));   // their own is in My performance
        UUID day30 = checkin(probation.get(s.hr, review), 30);
        assertError(404, "not_found", () -> probation.writeCheckin(s.worker, review, day30, new CheckinInput("on_track", "All good, says me")));
        assertEquals(2, probation.list(s.hr, null).size(), "HR sees every probation: the worker's and HR's own");

        // HR's own probation: they can see it, but not write about it or decide it.
        UUID own = reviewOf(s.hr, "Hari Hr");
        assertEquals(false, probation.get(s.hr, own).get("canDecide"));
        assertError(409, "request.refused", () -> probation.recommend(s.hr, own, new RecommendInput("confirm", null, null)));
        assertError(409, "request.refused", () -> probation.decide(s.hr, own, new DecideInput("confirm", null, null, null)));
        probation.decide(s.org.admin, own, new DecideInput("confirm", null, null, null));
    }

    // ── recommendation and decision ─────────────────────────────────────

    @Test
    void confirmingMakesTheEmploymentPermanentAndIssuesTheConfirmationLetter() {
        Setup s = setup();
        UUID review = reviewOf(s.boss, "Wasim Worker");
        assertError(400, "request.invalid", () -> probation.recommend(s.boss, review, new RecommendInput("extend", 3, "Too short")));
        Map<String, Object> view = probation.recommend(s.boss, review, new RecommendInput("confirm", null, "Ready to be confirmed"));
        assertEquals("recommended", view.get("status"));
        assertEquals("deciding", view.get("stage"));
        assertTrue(events(s.hr).contains("probation_recommended"));

        view = probation.decide(s.hr, review, new DecideInput("confirm", null, null, null));
        assertEquals("decided", view.get("status"));
        assertEquals(probationEnd(s).plusDays(1).toString(), view.get("confirmedFrom"));
        assertNotNull(view.get("letter"), "the confirmation letter is issued");
        assertEquals("permanent", type(s, s.workerRecord));
        assertError(409, "probation.decided", () -> probation.decide(s.hr, review, new DecideInput("confirm", null, null, null)));
        assertError(409, "probation.decided", () -> probation.recommend(s.boss, review, new RecommendInput("confirm", null, null)));

        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) probation.mine(s.worker).get("probation");
        assertEquals("confirm", p.get("decision"));
        assertEquals(false, p.get("onProbation"));
        assertEquals(1, ((List<?>) p.get("letters")).size());
        assertTrue(events(s.worker).contains("probation_letter_issued"));
        assertTrue(events(s.boss).contains("probation_decided"));
        assertEquals(0, probation.list(s.boss, null).size(), "a decided probation leaves the open list");
        assertEquals(1, probation.list(s.boss, "decided").size());
    }

    @Test
    void extendingMovesTheProbationEndAndStartsANewReview() {
        Setup s = setup();
        UUID review = reviewOf(s.boss, "Wasim Worker");
        probation.recommend(s.boss, review, new RecommendInput("extend", 3, "Needs more time on the reporting work"));
        assertError(400, "request.invalid", () -> probation.decide(s.hr, review, new DecideInput("extend", null, "short", null)));
        Map<String, Object> view = probation.decide(s.hr, review, new DecideInput("extend", null,
                "Extended as the manager recommends, to finish the reporting work", null));
        LocalDate next = probationEnd(s).plusMonths(3);
        assertEquals(next.toString(), view.get("newProbationEnd"), "the recommended months are used when HR gives none");
        assertEquals(next.toString(), db.inTenant(s.org.id, c -> Sql.one(c, "select probation_end from app.employment where id = ?",
                s.workerRecord).get("probationEnd")));
        assertEquals("probation", type(s, s.workerRecord), "still on probation");

        List<Map<String, Object>> open = probation.list(s.boss, null);
        assertEquals(1, open.size());
        assertEquals(next.toString(), open.get(0).get("probationEnd"));
        assertEquals(3L, ((Number) open.get(0).get("checkinsTotal")).longValue(), "a check-in every 30 days of the extension");
        Map<String, Object> again = probation.get(s.boss, UUID.fromString((String) open.get(0).get("id")));
        assertEquals(1, ((List<?>) again.get("history")).size(), "the earlier decision shows as history");
        assertEquals(1L, count(s, "select count(*) from app.issued_letter where kind = 'probation_extension'"));
    }

    @Test
    void notConfirmingStartsATerminationForPerformance() {
        Setup s = setup();
        UUID review = reviewOf(s.hr, "Wasim Worker");
        assertError(400, "request.invalid", () -> probation.decide(s.hr, review,
                new DecideInput("not_confirm", null, "Did not meet the expectations set at joining", null)));
        LocalDate last = TODAY.plusDays(15);
        Map<String, Object> view = probation.decide(s.hr, review,
                new DecideInput("not_confirm", null, "Did not meet the expectations set at joining", last.toString()));
        assertEquals("not_confirm", view.get("decision"));
        assertNotNull(view.get("exitCaseId"));
        Map<String, Object> x = db.inTenant(s.org.id, c -> Sql.one(c, "select kind, status, reason_category, last_working_day "
                + "from app.exit_case where id = ?::uuid", view.get("exitCaseId")));
        assertEquals("termination", x.get("kind"));
        assertEquals("accepted", x.get("status"));
        assertEquals("performance", x.get("reasonCategory"));
        assertEquals(last.toString(), x.get("lastWorkingDay"));
    }

    @Test
    void aFailedExitLeavesTheProbationUndecided() {
        Setup s = setup();
        UUID review = reviewOf(s.hr, "Wasim Worker");
        exits.start(s.hr, new ExitService.StartInput(s.workerRecord.toString(), "resignation", "career", null, null, null, null));
        assertError(409, "exit.already_open", () -> probation.decide(s.hr, review,
                new DecideInput("not_confirm", null, "Did not meet the expectations set at joining", TODAY.plusDays(10).toString())));
        assertEquals("open", probation.get(s.hr, review).get("status"), "the decision and the exit happen together or not at all");
    }

    // ── reminders and settings ──────────────────────────────────────────

    @Test
    void theManagerIsAskedToRecommendAndHrIsRemindedOnceTheProbationHasEnded() {
        Setup s = setup();
        reviewOf(s.hr, "Wasim Worker");
        LocalDate end = probationEnd(s);
        assertTrue(events(new Notifications(db, fixed(end.minusDays(21))), s.boss).contains("probation_recommend_due"));
        assertTrue(events(new Notifications(db, fixed(end.minusDays(21))), s.boss).contains("probation_checkin_due"),
                "the check-ins not yet written are due");
        assertTrue(events(new Notifications(db, fixed(end.plusDays(1))), s.hr).contains("probation_overdue"));
        long overdue = count(s, "select count(*) from app.notification where event = 'probation_overdue'");
        events(new Notifications(db, fixed(end.plusDays(1))), s.hr);
        assertEquals(overdue, count(s, "select count(*) from app.notification where event = 'probation_overdue'"), "at most once a day");
        Probation later = new Probation(db, crypto, exits, fixed(end.plusDays(1)));
        assertEquals("overdue", later.list(s.hr, null).stream().filter(r -> r.get("name").equals("Wasim Worker")).findFirst()
                .orElseThrow().get("stage"));
        assertEquals("recommend", new Probation(db, crypto, exits, fixed(end.minusDays(21))).list(s.hr, null).stream()
                .filter(r -> r.get("name").equals("Wasim Worker")).findFirst().orElseThrow().get("stage"));
    }

    @Test
    void settingsChangeTheCheckInDaysForNewReviews() {
        Setup s = setup();
        Map<String, Object> saved = probation.saveSettings(s.hr, new SettingsInput(List.of(90, 45, 45), 30));
        assertEquals(List.of(45, 90), saved.get("checkinDays"));
        assertEquals(30, ((Number) saved.get("recommendDays")).intValue());
        assertError(400, "request.invalid", () -> probation.saveSettings(s.hr, new SettingsInput(List.of(0), null)));
        assertError(400, "request.invalid", () -> probation.saveSettings(s.hr, new SettingsInput(null, 3)));
        assertError(403, "permission.denied", () -> probation.saveSettings(s.boss, new SettingsInput(null, 30)));
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, SessionCtx hr, SessionCtx boss, SessionCtx worker, SessionCtx colleague, UUID workerRecord) {}

    /**
     * An Organization with HR, a manager, a worker on probation reporting to the manager (joined 80
     * days ago, probation ending in 20 days) and a colleague with no team. HR is on probation too.
     */
    private static Setup setup() {
        String slug = "prob-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        Org org = new Org(UUID.fromString((String) created.get("id")), slug, admin);
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx boss = join(org, "boss", List.of("employee", "manager"));
        SessionCtx worker = join(org, "worker", List.of("employee"));
        SessionCtx colleague = join(org, "colleague", List.of("employee", "manager"));
        add(admin, "Asha Admin", admin, null, 1001, "permanent", TODAY.minusYears(3), null);
        add(admin, "Hari Hr", hr, null, 1002, "probation", TODAY.minusDays(80), TODAY.plusDays(20));
        UUID bossRecord = add(admin, "Bina Boss", boss, null, 1003, "permanent", TODAY.minusYears(2), null);
        UUID workerRecord = add(admin, "Wasim Worker", worker, bossRecord, 1004, "probation", TODAY.minusDays(80), TODAY.plusDays(20));
        add(admin, "Chitra Colleague", colleague, null, 1005, "permanent", TODAY.minusYears(1), null);
        return new Setup(org, hr, boss, worker, colleague, workerRecord);
    }

    private static UUID add(SessionCtx by, String name, SessionCtx login, UUID manager, int code, String type, LocalDate joining,
                            LocalDate probationEnd) {
        return UUID.fromString((String) employees.add(by, new NewEmployee(name, null, "1990-01-01", "female", null, "9876543210",
                type, joining.toString(), probationEnd == null ? null : probationEnd.toString(), null, "E" + code, "Analyst", null,
                manager == null ? null : manager.toString(), login.membershipId().toString(), null, null, null, null, null, null, null,
                null, null)).get("id"));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static UUID reviewOf(SessionCtx ctx, String name) {
        return UUID.fromString((String) probation.list(ctx, "all").stream().filter(r -> r.get("name").equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no review for " + name)).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(Map<String, Object> view, int day) {
        return ((List<Map<String, Object>>) view.get("checkins")).stream().filter(k -> ((Number) k.get("day")).intValue() == day)
                .findFirst().orElseThrow(() -> new AssertionError("no check-in for day " + day));
    }

    private static UUID checkin(Map<String, Object> view, int day) {
        return UUID.fromString((String) find(view, day).get("id"));
    }

    private static LocalDate probationEnd(Setup s) {
        return TODAY.plusDays(20);
    }

    private static String type(Setup s, UUID employment) {
        return (String) db.inTenant(s.org.id, c -> Sql.one(c, "select employment_type from app.employment where id = ?", employment))
                .get("employmentType");
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
