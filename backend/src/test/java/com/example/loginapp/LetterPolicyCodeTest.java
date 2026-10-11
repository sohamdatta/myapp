package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.ChecklistService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.JoinerService;
import com.example.loginapp.core.JoinerService.NewJoiner;
import com.example.loginapp.core.LetterService;
import com.example.loginapp.core.LetterService.IssueInput;
import com.example.loginapp.core.LetterService.TemplateInput;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.Notifications.MessageInput;
import com.example.loginapp.core.NumberSeries;
import com.example.loginapp.core.NumberSeries.SeriesInput;
import com.example.loginapp.core.OnboardingConfigService;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.PolicyService;
import com.example.loginapp.core.PolicyService.PolicyInput;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Letters, the offer stage, policies, employee code series and notifications
 * against a real PostgreSQL.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class LetterPolicyCodeTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final byte[] PDF = "%PDF-1.4\n%letter\n1 0 obj <<>> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final String OFFER = "{{organization.name}}\n\nOffer of employment\n\nDear {{person.legal_name}},\n\n"
            + "We offer you the role of {{job.designation}} from {{employment.joining_date}}. Please answer by {{offer.expires_on}}.";

    private static Db db;
    private static Crypto crypto;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static EmployeeService employees;
    private static AttendanceSettingsService attendanceSettings;
    private static OnboardingConfigService config;
    private static JoinerService joiners;
    private static DocumentService documents;
    private static ChecklistService checklists;
    private static LetterService letters;
    private static PolicyService policies;
    private static Notifications notifications;
    private static NumberSeries series;
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
            st.execute("drop database if exists hrms_test_letters with (force)");
            st.execute("create database hrms_test_letters");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the letter tests");

        ownerUrl = "jdbc:postgresql://" + host + "/hrms_test_letters";
        db = new Db(source(ownerUrl, ownerUser, ownerPassword), source(ownerUrl, "app_platform", ROLE_PASSWORD),
                source(ownerUrl, "app_auth", ROLE_PASSWORD), source(ownerUrl, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, crypto);
        attendanceSettings = new AttendanceSettingsService(db);
        config = new OnboardingConfigService(db);
        joiners = new JoinerService(db, crypto);
        documents = new DocumentService(db, crypto);
        checklists = new ChecklistService(db, documents);
        letters = new LetterService(db, crypto, documents);
        policies = new PolicyService(db);
        notifications = new Notifications(db);
        series = new NumberSeries(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── letters and the offer ───────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void anOfferIsIssuedAsAPdfAndTheJoinerSeesOnlyItUntilTheyAcceptIt() {
        Setup s = setup();
        assertError(400, "template.unknown_placeholder", () -> letters.saveTemplate(s.org.admin, null,
                new TemplateInput("offer", "Offer", "Dear {{person.name}}", null, null, true)));
        Map<String, Object> offer = letters.saveTemplate(s.org.admin, null, new TemplateInput("offer", "Offer letter", OFFER, "accept", null, true));
        assertEquals("accept_or_decline", offer.get("response"));   // an offer can always be declined

        Map<String, Object> added = joiners.add(s.org.admin, joiner(s, "Om Rao", "om@mail.test", "9123400101", TODAY.plusDays(10), true));
        UUID id = UUID.fromString((String) added.get("id"));
        SessionCtx me = auth.resolve(invitations.accept((String) ((Map<String, Object>) added.get("invitation")).get("token"), PASSWORD, null));
        joiners.acceptNotice(me, UUID.fromString((String) ((Map<String, Object>) joiners.mine(me).get("notice")).get("id")));

        String preview = (String) letters.preview(s.org.admin, id, UUID.fromString((String) offer.get("id"))).get("text");
        assertTrue(preview.contains("Dear Om Rao,") && preview.contains("role of Analyst"), preview);
        letters.issue(s.org.admin, id, new IssueInput((String) offer.get("id"), 5));
        assertEquals("offered", joiners.get(s.org.admin, id).get("status"));

        // The joiner is told, sees the letter, and can do nothing else until they answer.
        List<Map<String, Object>> items = (List<Map<String, Object>>) notifications.list(me).get("items");
        assertTrue(items.stream().anyMatch(n -> n.get("event").equals("offer_issued")), items.toString());
        Map<String, Object> letter = letters.mine(me).get(0);
        assertEquals(true, letter.get("waiting"));
        assertEquals(TODAY.plusDays(5).toString(), letter.get("expiresOn"));
        byte[] pdf = documents.myContent(me, UUID.fromString((String) letter.get("documentId"))).bytes();
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(raw.startsWith("%PDF") && raw.contains("(Dear Om Rao,)"), "the PDF carries the filled letter");
        assertError(409, "onboarding.locked", () -> joiners.saveMySection(me, "personal", Map.of("person.gender", "male")));
        assertTrue(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Letter: Offer letter waiting for the joiner"));

        // After its expiry date it cannot be accepted.
        LetterService later = new LetterService(db, crypto, documents, fixed(TODAY.plusDays(6)));
        UUID letterId = UUID.fromString((String) letter.get("id"));
        assertError(409, "letter.expired", () -> later.respond(me, letterId, true));

        letters.respond(me, letterId, true);
        assertEquals("in_progress", joiners.get(s.org.admin, id).get("status"));
        assertError(409, "letter.answered", () -> letters.respond(me, letterId, false));
        joiners.saveMySection(me, "personal", Map.of("person.gender", "male"));
        assertFalse(((List<String>) joiners.get(s.org.admin, id).get("blockers")).stream().anyMatch(b -> b.startsWith("Letter")));
        assertTrue(((List<?>) documents.forJoiner(s.org.admin, id).get("others")).isEmpty());   // letters are not loose documents
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDeclinedOfferEndsTheOnboardingAndTheLogin() {
        Setup s = setup();
        Map<String, Object> offer = letters.saveTemplate(s.org.admin, null, new TemplateInput("offer", "Offer letter", OFFER, null, null, true));
        Map<String, Object> added = joiners.add(s.org.admin, joiner(s, "Pia Das", "pia@mail.test", "9123400102", TODAY.plusDays(10), true));
        UUID id = UUID.fromString((String) added.get("id"));
        String token = invitations.accept((String) ((Map<String, Object>) added.get("invitation")).get("token"), PASSWORD, null);
        SessionCtx me = auth.resolve(token);
        letters.issue(s.org.admin, id, new IssueInput((String) offer.get("id"), null));
        letters.respond(me, UUID.fromString((String) letters.mine(me).get(0).get("id")), false);
        assertEquals("offer_declined", joiners.get(s.org.admin, id).get("status"));
        assertThrows(ApiException.class, () -> auth.resolve(token));
        assertEquals(1, ((List<?>) joiners.list(s.org.admin, "withdrawn").get("joiners")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherLettersWaitForTheirAnswerAndHrCanUploadOne() {
        Setup s = setup();
        UUID id = UUID.fromString((String) joiners.add(s.org.admin, joiner(s, "Raj Iyer", null, "9123400103", TODAY.plusDays(10), false)).get("id"));
        Map<String, Object> joinerLetters = letters.forJoiner(s.org.admin, id);
        Map<String, Object> appointment = ((List<Map<String, Object>>) joinerLetters.get("templates")).stream()
                .filter(t -> t.get("kind").equals("appointment")).findFirst().orElseThrow();
        letters.issue(s.org.admin, id, new IssueInput((String) appointment.get("id"), null));
        assertTrue(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Letter: Appointment letter waiting for the joiner"));
        assertEquals("offered".equals(joiners.get(s.org.admin, id).get("status")), false);

        assertError(415, "document.type", () -> letters.upload(s.org.admin, id, "Bonus letter", "other", "none", null,
                "<html></html>".getBytes(StandardCharsets.UTF_8)));
        letters.upload(s.org.admin, id, "Bonus letter", "other", "none", null, PDF);
        assertEquals(2, ((List<?>) letters.forJoiner(s.org.admin, id).get("letters")).size());
        SessionCtx plain = join(s.org, "plain", List.of("employee"));
        assertError(403, "permission.denied", () -> letters.upload(plain, id, "X", "other", "none", null, PDF));

        // A template's new wording is a new version; issued letters keep theirs.
        UUID templateId = UUID.fromString((String) appointment.get("id"));
        Map<String, Object> changed = letters.saveTemplate(s.org.admin, templateId, new TemplateInput(null, null,
                "{{organization.name}}\n\nDear {{person.legal_name}}, welcome.", null, null, null));
        assertEquals(2, changed.get("version"));
        assertEquals(1, ((List<Map<String, Object>>) letters.forJoiner(s.org.admin, id).get("letters")).stream()
                .filter(l -> l.get("kind").equals("appointment")).findFirst().orElseThrow().get("templateVersion"));
    }

    // ── policies ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void policiesAreAcknowledgedByVersion() {
        Setup s = setup();
        Map<String, Object> conduct = policies.save(s.org.admin, null, new PolicyInput("Code of conduct",
                "Be honest, be kind, and keep what you learn here confidential.", "before_activation", null, null, true));
        policies.save(s.org.admin, null, new PolicyInput("IT use", "Lock your laptop when you step away from it.", "within_days", 30, null, true));
        policies.save(s.org.admin, null, new PolicyInput("Interns only", "Interns report to their mentor every Friday.", "before_activation",
                null, Map.of("employment_type", List.of("intern")), true));
        Map<String, Object> added = joiners.add(s.org.admin, joiner(s, "Sia Roy", "sia@mail.test", "9123400104", TODAY.plusDays(10), true));
        UUID id = UUID.fromString((String) added.get("id"));
        SessionCtx me = auth.resolve(invitations.accept((String) ((Map<String, Object>) added.get("invitation")).get("token"), PASSWORD, null));

        List<Map<String, Object>> mine = policies.mine(me);
        assertEquals(List.of("Code of conduct", "IT use"), mine.stream().map(p -> p.get("name")).toList());
        UUID conductId = UUID.fromString((String) conduct.get("id"));
        assertError(409, "onboarding.locked", () -> policies.acknowledge(me, conductId, 1));
        joiners.acceptNotice(me, UUID.fromString((String) ((Map<String, Object>) joiners.mine(me).get("notice")).get("id")));
        assertTrue(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Policy: Code of conduct not acknowledged"));
        assertFalse(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Policy: IT use not acknowledged"));

        assertError(409, "policy.changed", () -> policies.acknowledge(me, conductId, 2));
        policies.acknowledge(me, conductId, 1);
        assertFalse(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Policy: Code of conduct not acknowledged"));

        // New wording is a new version, to be acknowledged again.
        assertEquals(2, policies.save(s.org.admin, conductId, new PolicyInput(null,
                "Be honest, be kind, keep what you learn here confidential, and speak up.", null, null, null, null)).get("version"));
        assertTrue(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Policy: Code of conduct not acknowledged"));
        policies.acknowledge(me, conductId, 2);
        assertEquals(true, policies.forJoiner(s.org.admin, id).get(0).get("current"));
    }

    // ── employee codes ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void aCodeIsDrawnFromTheSeriesAtActivationAndHrIsToldWhenTheJoinerIsReady() {
        Setup s = setup();
        assertError(400, "request.invalid", () -> series.save(s.org.admin, null,
                new SeriesInput("Bad", "GLX-{YEAR}-", "", 4, 1, "yearly", null, 0, true)));
        Map<String, Object> glx = series.save(s.org.admin, null, new SeriesInput("Everyone", "GLX-{YY}-", "", 4, 1, "yearly", null, 0, true));
        String yy = String.format("%02d", TODAY.getYear() % 100);
        assertEquals("GLX-" + yy + "-0001", glx.get("example"));

        // No code typed: the employment section can be sent without one.
        NewJoiner base = joiner(s, "Tia Sen", null, "9123400105", TODAY, false);
        NewJoiner noCode = new NewJoiner(base.legalName(), null, base.mobile(), base.joiningDate(), base.employmentType(), base.designation(),
                base.departmentId(), base.managerId(), base.workLocationId(), base.shiftId(), base.weeklyOffs(), null, null, false, null, null);
        UUID id = UUID.fromString((String) joiners.add(s.org.admin, noCode).get("id"));
        assertEquals("submitted", joiners.section(s.org.admin, id, "employment").get("status"));
        makeReady(s, id);

        List<Map<String, Object>> told = (List<Map<String, Object>>) notifications.list(s.org.admin).get("items");
        assertTrue(told.stream().anyMatch(n -> n.get("event").equals("joiner_ready") && ((String) n.get("body")).contains("Tia Sen")), told.toString());

        // Joining today with nothing left: reading the joiner activates them, and the code is drawn.
        Map<String, Object> active = joiners.get(s.org.admin, id);
        assertEquals("active", active.get("status"));
        assertEquals("GLX-" + yy + "-0001", active.get("employeeCode"));
        assertEquals("GLX-" + yy + "-0002", series.example(s.org.admin, UUID.fromString((String) glx.get("id")), null, null, null, null).get("code"));
        List<Map<String, Object>> bossNotes = (List<Map<String, Object>>) notifications.list(s.boss).get("items");
        assertTrue(bossNotes.stream().anyMatch(n -> n.get("event").equals("welcome_team")));
    }

    @Test
    void twentyDrawsAtOnceGiveTwentyDifferentCodes() throws Exception {
        Setup s = setup();
        series.save(s.org.admin, null, new SeriesInput("Everyone", "P-", "", 3, 1, "never", null, 0, true));
        UUID id = UUID.fromString((String) joiners.add(s.org.admin, joiner(s, "Uma Pal", null, "9123400106", TODAY.plusDays(3), false)).get("id"));
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> NumberSeries.drawFor(db, s.org.id, id)));
        }
        Set<String> codes = new HashSet<>();
        for (Future<String> f : futures) {
            codes.add(f.get());
        }
        pool.shutdown();
        assertEquals(20, codes.size(), codes.toString());
        assertTrue(codes.contains("P-001") && codes.contains("P-020"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void manyRequestsAtOnceFromTheJoinerActivateThemOnce() throws Exception {
        Setup s = setup();
        Map<String, Object> glx = series.save(s.org.admin, null, new SeriesInput("Everyone", "R-", "", 3, 1, "never", null, 0, true));
        NewJoiner base = joiner(s, "Vani Rao", "vani@mail.test", "9123400107", TODAY, true);
        NewJoiner noCode = new NewJoiner(base.legalName(), base.personalEmail(), base.mobile(), base.joiningDate(), base.employmentType(),
                base.designation(), base.departmentId(), base.managerId(), base.workLocationId(), base.shiftId(), base.weeklyOffs(), null, null,
                true, null, null);
        Map<String, Object> added = joiners.add(s.org.admin, noCode);
        UUID id = UUID.fromString((String) added.get("id"));
        String token = invitations.accept((String) ((Map<String, Object>) added.get("invitation")).get("token"), PASSWORD, null);
        makeReady(s, id, false);

        // A page load sends several requests together; each one, signing the joiner in, finds them due.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SessionCtx>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(pool.submit(() -> {
                go.await();
                return auth.resolve(token);
            }));
        }
        go.countDown();
        for (Future<SessionCtx> call : calls) {
            call.get();
        }
        pool.shutdown();
        assertEquals(1L, count(s.org.id, "select count(*) from app.audit_event where action = 'joiner.activated' and target_id = '" + id + "'"));
        assertEquals(1L, count(s.org.id, "select count(*) from app.notification where body like 'Hello Vani, your account is now active%'"));
        assertEquals("R-001", joiners.get(s.org.admin, id).get("employeeCode"));
        assertEquals("R-002", series.example(s.org.admin, UUID.fromString((String) glx.get("id")), null, null, null, null).get("code"));
    }

    // ── notifications ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void remindersComeOnceADayAndStopWhenTheTaskIsDone() {
        Setup s = setup();
        SessionCtx it = join(s.org, "it", List.of("employee", "it_admin"));
        LocalDate joining = TODAY.plusDays(10);
        UUID id = UUID.fromString((String) joiners.add(s.org.admin, joiner(s, "Vik Rao", null, "9123400107", joining, false)).get("id"));
        // Assigned: IT is told at once.
        assertEquals(2L, ((List<Map<String, Object>>) notifications.list(it).get("items")).stream()
                .filter(n -> n.get("event").equals("task_assigned")).count());

        LocalDate due = joining.minusDays(2);   // laptop and official email
        Notifications dayBefore = new Notifications(db, fixed(due.minusDays(1)));
        assertEquals(2L, count(dayBefore.list(it), "task_due"));
        assertEquals(2L, count(dayBefore.list(it), "task_due"));   // not twice on the same day
        Notifications dayAfter = new Notifications(db, fixed(due.plusDays(1)));
        assertEquals(2L, count(dayAfter.list(it), "task_overdue"));
        assertEquals(2L, count(dayAfter.list(it), "task_overdue"));

        for (Map<String, Object> t : checklists.mine(it)) {
            checklists.done(it, UUID.fromString((String) t.get("id")));
        }
        assertEquals(2L, count(new Notifications(db, fixed(due.plusDays(5))).list(it), "task_overdue"));   // nothing new once done

        // Read: one, then all.
        Map<String, Object> list = notifications.list(it);
        int unread = ((Number) list.get("unread")).intValue();
        String first = (String) ((List<Map<String, Object>>) list.get("items")).get(0).get("id");
        assertEquals(unread - 1, notifications.read(it, List.of(first)).get("unread"));
        assertEquals(0, notifications.read(it, null).get("unread"));

        // HR words the messages; an unknown placeholder is refused, and a message switched off is not sent.
        Map<String, Object> assigned = notifications.messages(s.org.admin).stream().filter(m -> m.get("event").equals("task_assigned"))
                .findFirst().orElseThrow();
        UUID msg = UUID.fromString((String) assigned.get("id"));
        assertError(400, "template.unknown_placeholder", () -> notifications.saveMessage(s.org.admin, msg,
                new MessageInput(null, "Do {{task.name}}", null)));
        notifications.saveMessage(s.org.admin, msg, new MessageInput(null, "{{task.title}} for {{joiner.name}}", false));
        checklists.add(s.org.admin, id, new ChecklistService.NewTask("Order a chair", null, it.membershipId().toString(), null, true));
        assertEquals(2L, count(notifications.list(it), "task_assigned"));
    }

    // ── making a joiner ready ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void makeReady(Setup s, UUID id) {
        makeReady(s, id, true);
    }

    /** Fills, verifies and completes everything; reading the joiner at the end (check) activates them when due. */
    @SuppressWarnings("unchecked")
    private static void makeReady(Setup s, UUID id, boolean check) {
        joiners.saveSection(s.org.admin, id, "personal", Map.of("person.date_of_birth", "1990-02-02", "person.gender", "female"));
        joiners.saveSection(s.org.admin, id, "address", Map.of("address.current",
                Map.of("line1", "4 Park Street", "city", "Kolkata", "stateCode", "WB", "pin", "700016")));
        joiners.saveSection(s.org.admin, id, "emergency", Map.of("emergency.contacts",
                List.of(Map.of("name", "Sam", "relationship", "brother", "mobile", "9811111111"))));
        joiners.saveSection(s.org.admin, id, "bank", Map.of("bank.account_number", "123456789012", "bank.ifsc", "SBIN0001234"));
        joiners.saveSection(s.org.admin, id, "statutory", Map.of("statutory.pan", "ABCPE1234F"));
        for (String section : List.of("personal", "contact", "address", "emergency", "family", "bank", "statutory")) {
            joiners.submitSection(s.org.admin, id, section);
        }
        for (Map<String, Object> section : (List<Map<String, Object>>) joiners.get(s.org.admin, id).get("sections")) {
            if ("submitted".equals(section.get("status")) && !section.get("section").equals("documents")) {
                joiners.verify(s.org.admin, id, (String) section.get("section"), null);
            }
        }
        for (Map<String, Object> t : (List<Map<String, Object>>) documents.forJoiner(s.org.admin, id).get("types")) {
            if ("before_activation".equals(t.get("required"))) {
                String doc = (String) documents.uploadForJoiner(s.org.admin, id, (String) t.get("id"), "scan.pdf", PDF).get("id");
                documents.verify(s.org.admin, id, UUID.fromString(doc));
            }
        }
        for (Map<String, Object> t : (List<Map<String, Object>>) checklists.forJoiner(s.org.admin, id).get("tasks")) {
            if (t.get("doneAt") == null) {
                checklists.done(s.org.admin, UUID.fromString((String) t.get("id")));
            }
        }
        if (check) {
            assertEquals(List.of(), joiners.get(s.org.admin, id).get("blockers"));
        }
    }

    @SuppressWarnings("unchecked")
    private static long count(Map<String, Object> list, String event) {
        return ((List<Map<String, Object>>) list.get("items")).stream().filter(n -> n.get("event").equals(event)).count();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, String location, String shift, String department, String manager, SessionCtx boss) {}

    private static Setup setup() {
        String slug = "docs-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        Org org = new Org(UUID.fromString((String) created.get("id")), slug,
                auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null)));
        String location = (String) attendanceSettings.saveLocation(org.admin, null,
                new LocationInput("Office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) attendanceSettings.saveShift(org.admin, null,
                new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        String department = (String) employees.saveDepartment(org.admin, null, "Engineering", null, true).get("id");
        SessionCtx boss = join(org, "boss", List.of("employee", "manager"));
        String manager = (String) employees.add(org.admin, new NewEmployee("Priya Nair", null, "1985-01-01", "female", null,
                "9876543210", "permanent", TODAY.minusYears(3).toString(), null, null, "MG-1", "Engineering manager", department,
                null, boss.membershipId().toString(), null, null, null, null, null, null, null, null, null)).get("id");
        return new Setup(org, location, shift, department, manager, boss);
    }

    private static NewJoiner joiner(Setup s, String name, String email, String mobile, LocalDate joining, boolean invite) {
        return new NewJoiner(name, email, mobile, joining.toString(), "permanent", "Analyst", s.department, s.manager, s.location,
                s.shift, List.of(0), "J-" + mobile.substring(mobile.length() - 3), null, invite, null, null);
    }

    private static NewJoiner typed(NewJoiner j, String type) {
        return new NewJoiner(j.legalName(), j.personalEmail(), j.mobile(), j.joiningDate(), type, j.designation(), j.departmentId(),
                j.managerId(), j.workLocationId(), j.shiftId(), j.weeklyOffs(), j.employeeCode(), j.flowId(), j.invite(), j.rehireOf(),
                j.contractEnd());
    }

    /** The admin of whichever Organization the joiner belongs to. */
    private static SessionCtx joinersAdmin(UUID onboardingId) {
        return ADMINS.get(db.inOwner(c -> UUID.fromString((String) Sql.one(c, "select tenant_id from app.onboarding where id = ?", onboardingId).get("tenantId"))));
    }

    private static final Map<UUID, SessionCtx> ADMINS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Map<String, Object> task(List<Map<String, Object>> tasks, String title) {
        return tasks.stream().filter(t -> t.get("title").equals(title)).findFirst().orElseThrow(() -> new AssertionError("no task " + title));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        ADMINS.put(org.id, org.admin);
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static Clock fixed(LocalDate day) {
        return Clock.fixed(day.atTime(12, 0).atZone(IST).toInstant(), ZoneOffset.UTC);
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
