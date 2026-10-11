package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.ChecklistService;
import com.example.loginapp.core.ChecklistService.NewTask;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.JoinerService;
import com.example.loginapp.core.JoinerService.NewJoiner;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.OnboardingConfigService;
import com.example.loginapp.core.OnboardingConfigService.ChecklistInput;
import com.example.loginapp.core.OnboardingConfigService.DocumentTypeInput;
import com.example.loginapp.core.OnboardingConfigService.TaskInput;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Documents and checklists against a real PostgreSQL: uploads checked and
 * stored encrypted, who may open a file, verify and reject, the Documents
 * section's status, document types and checklists chosen by rules, task
 * assignees, actions, approval, overdue, and what blocks activation.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class DocumentChecklistTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final byte[] PDF = "%PDF-1.4\n%pan card scan\n1 0 obj <<>> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

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
            st.execute("drop database if exists hrms_test_documents with (force)");
            st.execute("create database hrms_test_documents");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the document and checklist tests");

        ownerUrl = "jdbc:postgresql://" + host + "/hrms_test_documents";
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
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── documents ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void documentsAreCheckedStoredEncryptedAndOpenedOnlyByThoseAllowed() throws Exception {
        Setup s = setup();
        Map<String, Object> added = joiners.add(s.org.admin, joiner(s, "Ira Bose", "ira@mail.test", "9123400001", TODAY.plusDays(10), true));
        UUID id = UUID.fromString((String) added.get("id"));
        SessionCtx me = auth.resolve(invitations.accept((String) ((Map<String, Object>) added.get("invitation")).get("token"), PASSWORD, null));
        String pan = typeId(documents.mine(me), "PAN card");
        String address = typeId(documents.mine(me), "Address proof");

        // Nothing before the notice; nothing but PDF, JPEG and PNG.
        assertError(409, "onboarding.locked", () -> documents.uploadMine(me, pan, "pan.pdf", PDF));
        joiners.acceptNotice(me, UUID.fromString((String) ((Map<String, Object>) joiners.mine(me).get("notice")).get("id")));
        assertError(415, "document.type", () -> documents.uploadMine(me, pan, "pan.pdf",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)));
        assertError(400, "request.invalid", () -> documents.uploadMine(me, UUID.randomUUID().toString(), "x.pdf", PDF));

        UUID panDoc = UUID.fromString((String) documents.uploadMine(me, pan, "My PAN.pdf", PDF).get("id"));
        assertEquals("draft", documents.mine(me).get("status"));

        // The bytes in the database are not the file; the file comes back whole to those allowed.
        try (Connection c = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword);
             ResultSet rs = c.createStatement().executeQuery("select content_enc from app.document_content where document_id = '" + panDoc + "'")) {
            assertTrue(rs.next());
            String stored = new String(rs.getBytes(1), StandardCharsets.ISO_8859_1);
            assertFalse(stored.contains("%PDF") || stored.contains("pan card scan"), "file stored in clear");
        }
        assertArrayEquals(PDF, documents.myContent(me, panDoc).bytes());
        DocumentService.Content forHr = documents.content(s.org.admin, panDoc);
        assertArrayEquals(PDF, forHr.bytes());
        assertEquals("application/pdf", forHr.contentType());
        assertEquals("My PAN.pdf", forHr.fileName());
        assertEquals(2L, count(s.org.id, "select count(*) from app.audit_event where action = 'document.viewed'"));

        // The manager, an ordinary employee, support and another Organization are told it does not exist.
        SessionCtx plain = join(s.org, "plain", List.of("employee"));
        assertError(404, "not_found", () -> documents.content(plain, panDoc));
        assertError(404, "not_found", () -> documents.content(s.boss, panDoc));
        Map<String, Object> grant = platform.requestGrant(superAdmin, s.org.id, "read_only", "Ticket 7: a joiner cannot upload");
        SessionCtx support = auth.resolve(platform.startSupportSession(superAdmin, UUID.fromString((String) grant.get("id"))));
        assertError(404, "not_found", () -> documents.content(support, panDoc));
        Setup other = setup();
        assertError(404, "not_found", () -> documents.content(other.org.admin, panDoc));

        // Address proof as a photo: the section now waits for HR.
        UUID addressDoc = UUID.fromString((String) documents.uploadMine(me, address, "bill.jpg", jpeg()).get("id"));
        assertEquals("submitted", documents.mine(me).get("status"));

        // Nobody checks their own; a rejection needs a note and sends the section back.
        assertError(409, "onboarding.own_section", () -> documents.verify(me, id, panDoc));
        assertError(403, "permission.denied", () -> documents.verify(plain, id, panDoc));
        assertError(400, "request.invalid", () -> documents.reject(s.org.admin, id, addressDoc, ""));
        documents.reject(s.org.admin, id, addressDoc, "The bill is older than three months");
        Map<String, Object> mine = documents.mine(me);
        assertEquals("returned", mine.get("status"));
        assertTrue(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Address proof: rejected, waiting for a new one"));
        assertError(409, "document.decided", () -> documents.verify(s.org.admin, id, addressDoc));

        // A new upload replaces the rejected one.
        UUID again = UUID.fromString((String) documents.uploadMine(me, address, "bill2.pdf", PDF).get("id"));
        assertEquals("superseded", db.inTenant(s.org.id, c -> Sql.one(c, "select status from app.document where id = ?", addressDoc).get("status")));
        assertEquals("submitted", documents.mine(me).get("status"));

        documents.verify(s.org.admin, id, panDoc);
        documents.verify(s.org.admin, id, again);
        assertEquals("verified", documents.mine(me).get("status"));
        List<String> blockers = (List<String>) joiners.get(s.org.admin, id).get("blockers");
        assertTrue(blockers.stream().noneMatch(b -> b.startsWith("PAN card") || b.startsWith("Address proof")), blockers.toString());
        assertError(409, "document.verified", () -> documents.uploadMine(me, pan, "pan2.pdf", PDF));
        assertError(409, "onboarding.documents", () -> joiners.verify(s.org.admin, id, "documents", null));

        // Optional documents and documents due after joining do not block.
        assertTrue(blockers.stream().noneMatch(b -> b.startsWith("Education") || b.startsWith("Photo")));
        Map<String, Object> education = ((List<Map<String, Object>>) mine.get("types")).stream()
                .filter(t -> t.get("name").equals("Education certificates")).findFirst().orElseThrow();
        assertEquals(TODAY.plusDays(40).toString(), education.get("dueOn"));

        List<String> actions = joiners.history(s.org.admin, id).stream().map(r -> (String) r.get("action")).toList();
        for (String a : List.of("joiner.document_uploaded", "joiner.document_rejected", "joiner.document_verified", "document.viewed")) {
            assertTrue(actions.contains(a), a + " missing from " + actions);
        }
    }

    @Test
    void documentTypesFollowTheirRules() {
        Setup s = setup();
        config.saveDocumentType(s.org.admin, null, new DocumentTypeInput("Internship letter from college", "before_activation", null,
                "Signed by your college", Map.of("employment_type", List.of("intern")), 10, true));
        assertError(400, "request.invalid", () -> config.saveDocumentType(s.org.admin, null,
                new DocumentTypeInput("Bad", "within_days", null, null, null, null, null)));
        SessionCtx plain = join(s.org, "plain", List.of("employee"));
        assertError(403, "permission.denied", () -> config.saveDocumentType(plain, null,
                new DocumentTypeInput("X", "optional", null, null, null, null, null)));

        UUID intern = UUID.fromString((String) joiners.add(s.org.admin, typed(joiner(s, "Om Rao", null, "9123400002", TODAY.plusDays(5), false),
                "intern")).get("id"));
        UUID permanent = UUID.fromString((String) joiners.add(s.org.admin, joiner(s, "Pia Rao", null, "9123400003", TODAY.plusDays(5), false))
                .get("id"));
        assertTrue(typeNames(intern).contains("Internship letter from college"));
        assertFalse(typeNames(permanent).contains("Internship letter from college"));
    }

    // ── checklists ──────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void tasksAreDatedAssignedAndDoneByWhoeverTheyBelongTo() {
        Setup s = setup();
        SessionCtx it = join(s.org, "it", List.of("employee", "it_admin"));
        LocalDate joining = TODAY.plusDays(10);
        Map<String, Object> added = joiners.add(s.org.admin, joiner(s, "Raj Sen", "raj@mail.test", "9123400004", joining, true));
        UUID id = UUID.fromString((String) added.get("id"));
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) checklists.forJoiner(s.org.admin, id).get("tasks");
        assertEquals(8, tasks.size());

        Map<String, Object> welcome = task(tasks, "Send the welcome note and invitation");
        assertEquals(TODAY.toString(), welcome.get("dueDate"));
        assertEquals(s.org.admin.membershipId().toString(), welcome.get("assigneeId"));   // whoever added the joiner
        Map<String, Object> laptop = task(tasks, "Prepare laptop and accounts");
        assertEquals(joining.minusDays(2).toString(), laptop.get("dueDate"));
        assertEquals(it.membershipId().toString(), laptop.get("assigneeId"));
        assertEquals(s.boss.membershipId().toString(), task(tasks, "Plan the first week").get("assigneeId"));
        assertEquals(joining.plusDays(30).toString(), task(tasks, "First-month check-in").get("dueDate"));
        assertNull(task(tasks, "Bring original documents for checking").get("assigneeId"));   // the joiner has no login yet

        // Once the joiner accepts the invitation, their task is theirs.
        SessionCtx me = auth.resolve(invitations.accept((String) ((Map<String, Object>) added.get("invitation")).get("token"), PASSWORD, null));
        tasks = (List<Map<String, Object>>) checklists.forJoiner(s.org.admin, id).get("tasks");
        assertEquals(me.membershipId().toString(), task(tasks, "Bring original documents for checking").get("assigneeId"));

        // IT, an ordinary employee otherwise, sees and ticks only its own task.
        List<Map<String, Object>> itTasks = checklists.mine(it);
        assertEquals(2, itTasks.size());   // laptop and official email
        assertEquals("Raj Sen", itTasks.get(0).get("joinerName"));
        assertEquals(2, auth.me(it).get("openTasks"));
        UUID laptopId = UUID.fromString((String) laptop.get("id"));
        assertError(404, "not_found", () -> checklists.done(it, UUID.fromString((String) welcome.get("id"))));
        assertError(403, "permission.denied", () -> checklists.skip(it, laptopId, "Has a laptop already"));
        assertEquals("done", checklists.done(it, laptopId).get("state"));
        assertError(409, "task.done", () -> checklists.done(it, laptopId));
        assertEquals(1, checklists.mine(it).size());

        // HR skips with a reason, reassigns, and adds a task of their own.
        UUID email = UUID.fromString((String) task(tasks, "Create the official email").get("id"));
        assertError(400, "request.invalid", () -> checklists.skip(s.org.admin, email, "no"));
        assertEquals("skipped", checklists.skip(s.org.admin, email, "Raj keeps his existing email").get("state"));
        UUID plan = UUID.fromString((String) task(tasks, "Plan the first week").get("id"));
        checklists.reassign(s.org.admin, plan, it.membershipId());
        assertEquals(List.of("Plan the first week"), checklists.mine(it).stream().map(t -> t.get("title")).toList());
        Map<String, Object> oneOff = checklists.add(s.org.admin, id, new NewTask("Order a desk chair", null, it.membershipId().toString(),
                joining.minusDays(1).toString(), true));
        assertEquals(true, oneOff.get("oneOff"));
        assertEquals(2, checklists.mine(it).size());

        // Required tasks due before the joining date block activation until done or skipped.
        List<String> blockers = (List<String>) joiners.get(s.org.admin, id).get("blockers");
        assertTrue(blockers.contains("Task: Issue the appointment letter"), blockers.toString());
        assertTrue(blockers.contains("Task: Order a desk chair"));
        assertFalse(blockers.contains("Task: Prepare laptop and accounts"));
        assertFalse(blockers.contains("Task: Introduce to the team"));   // due on the joining date itself
    }

    @Test
    @SuppressWarnings("unchecked")
    void templatesFollowRulesAndTasksCanNeedAFileOrAnApproval() {
        Setup s = setup();
        Map<String, Object> saved = config.saveChecklist(s.org.admin, null, new ChecklistInput("Interns", Map.of("employment_type", List.of("intern")), true,
                List.of(new TaskInput(null, "College letter on file", null, "hr", null, null, "added", 0, true, true, null, null, null, null),
                        new TaskInput(null, "Project allocated", null, "hr", null, null, "joining_date", -1, true, false, "manager", null, null, null))));
        assertEquals(2, ((List<?>) saved.get("tasks")).size());
        assertError(400, "request.invalid", () -> config.saveChecklist(s.org.admin, null, new ChecklistInput("Bad", null, true,
                List.of(new TaskInput(null, "X", null, "nobody", null, null, null, null, null, null, null, null, null, null)))));

        UUID intern = UUID.fromString((String) joiners.add(s.org.admin, typed(joiner(s, "Tia Roy", null, "9123400005", TODAY.plusDays(4), false),
                "intern")).get("id"));
        UUID permanent = UUID.fromString((String) joiners.add(s.org.admin, joiner(s, "Uma Roy", null, "9123400006", TODAY.plusDays(4), false))
                .get("id"));
        List<Map<String, Object>> internTasks = (List<Map<String, Object>>) checklists.forJoiner(s.org.admin, intern).get("tasks");
        assertEquals(10, internTasks.size());
        assertEquals(8, ((List<?>) checklists.forJoiner(s.org.admin, permanent).get("tasks")).size());

        // A task that needs a file cannot be done without one; the file goes on the joiner's record.
        UUID letter = UUID.fromString((String) task(internTasks, "College letter on file").get("id"));
        assertError(409, "task.needs_attachment", () -> checklists.done(s.org.admin, letter));
        Map<String, Object> attached = checklists.attach(s.org.admin, letter, "letter.pdf", PDF);
        assertEquals("letter.pdf", attached.get("attachmentName"));
        assertEquals("done", checklists.done(s.org.admin, letter).get("state"));
        assertTrue(((List<?>) documents.forJoiner(s.org.admin, intern).get("others")).isEmpty());   // not shown as a document of its own

        // A task with an approver waits for them, and blocks activation until approved.
        UUID project = UUID.fromString((String) task(internTasks, "Project allocated").get("id"));
        assertEquals("awaiting_approval", checklists.done(s.org.admin, project).get("state"));
        assertTrue(((List<String>) joiners.get(s.org.admin, intern).get("blockers")).contains("Task: Project allocated (waiting for approval)"));
        assertEquals(1, checklists.mine(s.boss).stream().filter(t -> t.get("title").equals("Project allocated")).count());
        assertError(403, "permission.denied", () -> checklists.approve(s.org.admin, project));
        assertEquals("done", checklists.approve(s.boss, project).get("state"));
        assertFalse(((List<String>) joiners.get(s.org.admin, intern).get("blockers")).contains("Task: Project allocated (waiting for approval)"));

        // Removing a task from the template leaves joiners who have it alone.
        Map<String, Object> tmpl = config.checklists(s.org.admin).stream().filter(t -> t.get("name").equals("Interns")).findFirst().orElseThrow();
        Map<String, Object> first = ((List<Map<String, Object>>) tmpl.get("tasks")).get(0);
        config.saveChecklist(s.org.admin, UUID.fromString((String) tmpl.get("id")), new ChecklistInput(null, null, null,
                List.of(new TaskInput((String) first.get("id"), "College letter on file", null, "hr", null, null, "added", 0, true, true,
                        null, null, null, null))));
        assertEquals(10, ((List<?>) checklists.forJoiner(s.org.admin, intern).get("tasks")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTaskIsOverdueTheDayAfterItIsDue() {
        Setup s = setup();
        UUID id = UUID.fromString((String) joiners.add(s.org.admin, joiner(s, "Vik Das", null, "9123400007", TODAY.plusDays(10), false))
                .get("id"));
        LocalDate due = TODAY.plusDays(7);   // the appointment letter: three days before joining
        Predicate<Map<String, Object>> letter = t -> t.get("title").equals("Issue the appointment letter");
        ChecklistService onDueDay = new ChecklistService(db, documents, fixed(due));
        ChecklistService dayAfter = new ChecklistService(db, documents, fixed(due.plusDays(1)));
        Map<String, Object> onTime = ((List<Map<String, Object>>) onDueDay.forJoiner(s.org.admin, id).get("tasks")).stream().filter(letter).findFirst().orElseThrow();
        Map<String, Object> late = ((List<Map<String, Object>>) dayAfter.forJoiner(s.org.admin, id).get("tasks")).stream().filter(letter).findFirst().orElseThrow();
        assertEquals(due.toString(), onTime.get("dueDate"));
        assertEquals(false, onTime.get("overdue"));
        assertEquals(true, late.get("overdue"));
        assertEquals("overdue", late.get("state"));
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

    @SuppressWarnings("unchecked")
    private static String typeId(Map<String, Object> list, String name) {
        return (String) ((List<Map<String, Object>>) list.get("types")).stream().filter(t -> t.get("name").equals(name))
                .findFirst().orElseThrow().get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> typeNames(UUID onboardingId) {
        return ((List<Map<String, Object>>) documents.forJoiner(joinersAdmin(onboardingId), onboardingId).get("types")).stream()
                .map(t -> t.get("name")).toList();
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

    private static byte[] jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
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
