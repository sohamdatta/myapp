package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.ChecklistService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.JoinerService;
import com.example.loginapp.core.JoinerService.NewJoiner;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.OnboardingConfigService;
import com.example.loginapp.core.OnboardingConfigService.SettingsInput;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * The joiner flow against a real PostgreSQL: HR adds and invites a joiner, the joiner
 * accepts the notice and fills their sections, HR returns and verifies them, and the
 * joiner becomes an employee. Also the rules around it: who may edit, verify and see,
 * rehire matching, withdrawal and blockers.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class JoinerFlowTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PAN = "ABCPE1234F";
    private static final String ACCOUNT = "123456789012";

    private static Db db;
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
    static final byte[] PDF = "%PDF-1.4\n%test\n1 0 obj <<>> endobj\ntrailer <<>>\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
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
            st.execute("drop database if exists hrms_test_joiner with (force)");
            st.execute("create database hrms_test_joiner");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the joiner tests");

        ownerUrl = "jdbc:postgresql://" + host + "/hrms_test_joiner";
        db = new Db(source(ownerUrl, ownerUser, ownerPassword), source(ownerUrl, "app_platform", ROLE_PASSWORD),
                source(ownerUrl, "app_auth", ROLE_PASSWORD), source(ownerUrl, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        employees = new EmployeeService(db, crypto);
        attendanceSettings = new AttendanceSettingsService(db);
        config = new OnboardingConfigService(db);
        joiners = new JoinerService(db, crypto);
        documents = new DocumentService(db, crypto);
        checklists = new ChecklistService(db, documents);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── the whole journey ───────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void aJoinerIsAddedInvitedFillsTheirDetailsIsVerifiedAndBecomesAnEmployee() throws Exception {
        Setup s = setup();
        Map<String, Object> added = joiners.add(s.org.admin, joiner(s, "Meera Iyer", "meera@mail.test", "9123456780", TODAY, true));
        UUID id = UUID.fromString((String) added.get("id"));
        Map<String, Object> invitation = (Map<String, Object>) added.get("invitation");
        assertEquals("meera@mail.test", invitation.get("email"));

        // HR gave everything the Employment section needs, so it went straight for verification.
        assertEquals("submitted", sectionStatus(s.org.admin, id, "employment"));
        Map<String, Object> list = joiners.list(s.org.admin, null);
        assertEquals(1, ((List<?>) list.get("joiners")).size());
        assertEquals("joiner", ((List<Map<String, Object>>) list.get("joiners")).get(0).get("waitingOn"));

        // The joiner accepts the invitation and signs in with the Joiner role only.
        String token = invitations.accept((String) invitation.get("token"), PASSWORD, null);
        SessionCtx me = auth.resolve(token);
        assertEquals(List.of("joiner"), auth.me(me).get("roles"));
        assertError(403, "permission.denied", () -> joiners.list(me, null));

        // Nothing can be filled before the notice is accepted.
        assertError(409, "onboarding.locked", () -> joiners.saveMySection(me, "personal", Map.of("person.gender", "female")));
        Map<String, Object> mine = joiners.mine(me);
        assertEquals("invited", mine.get("status"));
        assertEquals("Analyst", mine.get("designation"));
        assertFalse((Boolean) mine.get("noticeAccepted"));
        UUID notice = UUID.fromString((String) ((Map<String, Object>) mine.get("notice")).get("id"));
        assertError(409, "onboarding.notice_changed", () -> joiners.acceptNotice(me, UUID.randomUUID()));
        joiners.acceptNotice(me, notice);
        assertEquals("in_progress", joiners.mine(me).get("status"));

        // The joiner cannot touch what HR fills, nor fields of another section.
        assertError(409, "onboarding.locked", () -> joiners.saveMySection(me, "employment", Map.of("employment.designation", "CEO")));
        assertError(400, "field.not_allowed", () -> joiners.saveMySection(me, "personal", Map.of("employment.designation", "CEO")));

        // Personal: required fields are checked on sending; an adult only.
        assertError(400, "onboarding.missing", () -> joiners.submitMySection(me, "personal"));
        assertError(400, "employee.too_young", () -> joiners.saveMySection(me, "personal",
                Map.of("person.date_of_birth", TODAY.minusYears(17).toString())));
        joiners.saveMySection(me, "personal", Map.of("person.date_of_birth", "1998-05-01", "person.gender", "female",
                "person.blood_group", "O+"));
        assertEquals("submitted", joiners.submitMySection(me, "personal").get("status"));
        assertError(409, "onboarding.locked", () -> joiners.saveMySection(me, "personal", Map.of("person.gender", "male")));

        // Contact and address.
        joiners.saveMySection(me, "contact", Map.of("person.alt_mobile", "9000000001"));
        joiners.submitMySection(me, "contact");
        joiners.saveMySection(me, "address", Map.of("address.current",
                Map.of("line1", "12 Lake Road", "city", "Bengaluru", "stateCode", "KA", "pin", "560001")));
        joiners.submitMySection(me, "address");

        // Emergency contact: someone else's number.
        assertError(400, "field.invalid", () -> joiners.saveMySection(me, "emergency", Map.of("emergency.contacts",
                List.of(Map.of("name", "Me", "relationship", "self", "mobile", "9123456780")))));
        joiners.saveMySection(me, "emergency", Map.of("emergency.contacts",
                List.of(Map.of("name", "Ravi Iyer", "relationship", "father", "mobile", "9988776655"))));
        joiners.submitMySection(me, "emergency");

        // Family: nominees are listed members and shares add up to 100.
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("family.members", List.of(Map.of("name", "Ravi Iyer", "relationship", "father"),
                Map.of("name", "Lata Iyer", "relationship", "mother")));
        family.put("family.nominations", List.of(Map.of("scheme", "pf", "member", "Ravi Iyer", "sharePct", 60)));
        assertError(400, "family.shares", () -> joiners.saveMySection(me, "family", family));
        family.put("family.nominations", List.of(Map.of("scheme", "pf", "member", "Ravi Iyer", "sharePct", 60),
                Map.of("scheme", "pf", "member", "Lata Iyer", "sharePct", 40)));
        joiners.saveMySection(me, "family", family);
        Map<String, Object> familyView = joiners.mySection(me, "family");
        assertEquals(2, ((List<?>) ((Map<String, Object>) familyView.get("values")).get("family.nominations")).size());
        joiners.submitMySection(me, "family");

        // Bank and statutory: stored encrypted, shown as the last four.
        joiners.saveMySection(me, "bank", Map.of("bank.holder_name", "Meera Iyer", "bank.account_number", ACCOUNT,
                "bank.ifsc", "SBIN0001234"));
        Map<String, Object> bank = joiners.submitMySection(me, "bank");
        assertEquals("9012", ((Map<String, Object>) bank.get("masked")).get("bank.account_number"));
        joiners.saveMySection(me, "statutory", Map.of("statutory.pan", PAN, "statutory.tax_regime", "old"));
        Map<String, Object> statutory = joiners.submitMySection(me, "statutory");
        assertEquals("234F", ((Map<String, Object>) statutory.get("masked")).get("statutory.pan"));
        assertFalse(statutory.toString().contains(PAN));
        assertFalse(dump(s.org.id).contains(PAN), "PAN in clear");
        assertFalse(dump(s.org.id).contains(ACCOUNT), "account number in clear");

        // Nobody verifies their own details; activation waits for every section.
        assertError(409, "onboarding.own_section", () -> joiners.verify(me, id, "personal", null));
        ApiException blocked = assertThrows(ApiException.class, () -> joiners.activate(s.org.admin, id));
        assertEquals("onboarding.blocked", blocked.code());
        assertTrue(blocked.getMessage().contains("Personal details: waiting to be verified"), blocked.getMessage());

        // HR returns the bank section with a note; the joiner fixes it and sends it again.
        assertError(400, "request.invalid", () -> joiners.returnSection(s.org.admin, id, "bank", ""));
        joiners.returnSection(s.org.admin, id, "bank", "The IFSC is for a different branch");
        Map<String, Object> returned = joiners.mySection(me, "bank");
        assertEquals("returned", returned.get("status"));
        assertEquals("The IFSC is for a different branch", returned.get("note"));
        assertEquals(true, returned.get("canEdit"));
        joiners.saveMySection(me, "bank", Map.of("bank.ifsc", "HDFC0000123"));
        joiners.submitMySection(me, "bank");
        assertEquals("9012", ((Map<String, Object>) joiners.mySection(me, "bank").get("masked")).get("bank.account_number"));

        // Verify everything; a section can only be verified once it is waiting.
        for (Map<String, Object> section : (List<Map<String, Object>>) joiners.get(s.org.admin, id).get("sections")) {
            if (!section.get("section").equals("documents")) {
                joiners.verify(s.org.admin, id, (String) section.get("section"), null);
            }
        }
        assertTrue(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("PAN card: not uploaded yet"));
        documentsAndTasks(s, id);
        assertError(409, "onboarding.not_submitted", () -> joiners.verify(s.org.admin, id, "personal", null));
        Map<String, Object> history = joiners.section(s.org.admin, id, "bank");
        assertEquals(2, ((List<?>) history.get("reviews")).size());

        // The joining date is today, so activation is immediate.
        assertEquals(true, joiners.activate(s.org.admin, id).get("activated"));
        SessionCtx employee = auth.resolve(token);
        assertEquals(List.of("employee"), auth.me(employee).get("roles"));
        assertEquals("active", joiners.get(s.org.admin, id).get("status"));
        assertError(409, "onboarding.closed", () -> joiners.saveMySection(employee, "personal", Map.of("person.gender", "male")));
        assertEquals(1L, count(s.org.id, "select count(*) from app.attendance_assignment where membership_id = '"
                + employee.membershipId() + "' and effective_from = '" + TODAY + "'"));
        assertEquals("JN-0", db.inTenant(s.org.id, c -> Sql.one(c, "select employee_code from app.membership where id = ?",
                employee.membershipId()).get("employeeCode")));
        // The record is the employee's now.
        Map<String, Object> record = employees.get(s.org.admin, employmentId(s.org.id, id));
        assertEquals("Meera Iyer", record.get("legalName"));
        assertEquals("active", record.get("status"));

        List<String> actions = joiners.history(s.org.admin, id).stream().map(r -> (String) r.get("action")).toList();
        for (String a : List.of("joiner.added", "joiner.invited", "joiner.notice_accepted", "joiner.section_submitted",
                "joiner.section_returned", "joiner.section_verified", "joiner.activated")) {
            assertTrue(actions.contains(a), a + " missing from " + actions);
        }
    }

    // ── HR filling in, activation on the joining date, PAN ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void hrCanFillEverySectionAndActivationBeforeTheJoiningDateWaitsForIt() {
        Setup s = setup();
        LocalDate joining = TODAY.plusDays(10);
        UUID id = UUID.fromString((String) joiners.add(s.org.admin,
                joiner(s, "Kabir Shah", null, "9123456781", joining, false)).get("id"));
        assertError(400, "request.invalid", () -> joiners.invite(s.org.admin, id));   // no personal email

        fillAsHr(s, id, true);
        // HR may record a joiner as young as 14 (an apprentice, say), but the joiner themselves may not.
        joiners.saveSection(s.org.admin, id, "personal", Map.of("person.date_of_birth", joining.minusYears(16).toString()));
        assertError(400, "employee.too_young", () -> joiners.saveSection(s.org.admin, id, "personal",
                Map.of("person.date_of_birth", joining.minusYears(13).toString())));
        joiners.submitSection(s.org.admin, id, "personal");
        verifyAll(s, id);

        Map<String, Object> scheduled = joiners.activate(s.org.admin, id);
        assertEquals(false, scheduled.get("activated"));
        assertEquals(joining.toString(), scheduled.get("on"));
        assertEquals("draft", joiners.get(s.org.admin, id).get("status"));

        // Correcting a verified section sends it back through verification.
        joiners.saveSection(s.org.admin, id, "contact", Map.of("person.alt_mobile", "9000000002"));
        assertEquals("draft", sectionStatus(s.org.admin, id, "contact"));
        assertTrue(((List<String>) joiners.get(s.org.admin, id).get("blockers")).contains("Contact: waiting for the joiner"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPanPendingReasonLetsTheSectionBeSentButCanBlockActivation() {
        Setup s = setup();
        UUID id = UUID.fromString((String) joiners.add(s.org.admin,
                joiner(s, "Nisha Rao", null, "9123456782", TODAY, false)).get("id"));
        // Manual activation, so verifying everything does not activate the joiner on the spot.
        config.saveSettings(s.org.admin, new SettingsInput(null, null, null, null, "manual", null, null, null, null));
        fillAsHr(s, id, false);
        joiners.saveSection(s.org.admin, id, "statutory", Map.of("statutory.pan_pending_reason", "Applied on 1 October"));
        joiners.submitSection(s.org.admin, id, "statutory");
        verifyAll(s, id);
        assertEquals(List.of(), joiners.get(s.org.admin, id).get("blockers"));
        assertEquals("draft", joiners.get(s.org.admin, id).get("status"));

        config.saveSettings(s.org.admin, new SettingsInput(null, null, null, null, null, false, null, null, null));
        List<String> blockers = (List<String>) joiners.get(s.org.admin, id).get("blockers");
        assertEquals(1, blockers.size());
        assertTrue(blockers.get(0).startsWith("PAN"));
        assertError(409, "onboarding.blocked", () -> joiners.activate(s.org.admin, id));

        joiners.saveSection(s.org.admin, id, "statutory", Map.of("statutory.pan", PAN));
        joiners.submitSection(s.org.admin, id, "statutory");
        joiners.verify(s.org.admin, id, "statutory", null);
        assertEquals(true, joiners.activate(s.org.admin, id).get("activated"));
    }

    @Test
    void aFixedTermJoinerNeedsAContractEnd() {
        Setup s = setup();
        NewJoiner base = joiner(s, "Tara Das", null, "9123456783", TODAY.plusDays(5), false);
        NewJoiner fixed = new NewJoiner(base.legalName(), null, base.mobile(), base.joiningDate(), "fixed_term", base.designation(),
                base.departmentId(), base.managerId(), base.workLocationId(), base.shiftId(), base.weeklyOffs(), "JN-3", null, false,
                null, null);
        assertError(400, "request.invalid", () -> joiners.add(s.org.admin, fixed));
        NewJoiner withEnd = new NewJoiner(fixed.legalName(), null, fixed.mobile(), fixed.joiningDate(), "fixed_term",
                fixed.designation(), fixed.departmentId(), fixed.managerId(), fixed.workLocationId(), fixed.shiftId(),
                fixed.weeklyOffs(), "JN-3", null, false, null, TODAY.plusMonths(6).toString());
        UUID id = UUID.fromString((String) joiners.add(s.org.admin, withEnd).get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) joiners.section(s.org.admin, id, "employment").get("values");
        assertEquals(TODAY.plusMonths(6).toString(), values.get("employment.contract_end"));
    }

    // ── who can see and verify ──────────────────────────────────────────

    @Test
    void verifiersAndOtherPeopleSeeOnlyWhatTheyShould() {
        Setup s = setup();
        UUID id = UUID.fromString((String) joiners.add(s.org.admin,
                joiner(s, "Arjun Mehta", null, "9123456784", TODAY.plusDays(3), false)).get("id"));
        SessionCtx hr = join(s.org, "hr", List.of("hr_admin"));
        SessionCtx plain = join(s.org, "plain", List.of("employee"));

        // An HR Admin is the default verifier; an ordinary employee is told the joiner does not exist.
        assertEquals(true, joiners.section(hr, id, "employment").get("canVerify"));
        joiners.verify(hr, id, "employment", "Checked against the offer");
        assertEquals("verified", sectionStatus(s.org.admin, id, "employment"));
        assertError(404, "not_found", () -> joiners.get(plain, id));
        assertError(403, "permission.denied", () -> joiners.list(plain, null));
        assertError(403, "permission.denied", () -> joiners.add(plain, joiner(s, "X Y", null, null, TODAY, false)));

        // Another Organization cannot see this joiner at all.
        Setup other = setup();
        assertError(404, "not_found", () -> joiners.get(other.org.admin, id));
    }

    // ── rehire and withdrawal ───────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void aMatchingEarlierPersonIsOfferedAsARehireAndAWithdrawnJoinerLosesAccess() {
        Setup s = setup();
        Map<String, Object> first = joiners.add(s.org.admin, joiner(s, "Dev Kumar", "dev@mail.test", "9123456785", TODAY.plusDays(7), true));
        UUID id = UUID.fromString((String) first.get("id"));
        String token = invitations.accept((String) ((Map<String, Object>) first.get("invitation")).get("token"), PASSWORD, null);
        SessionCtx dev = auth.resolve(token);

        assertError(400, "request.invalid", () -> joiners.withdraw(s.org.admin, id, "no"));
        joiners.withdraw(s.org.admin, id, "Accepted another offer");
        assertThrows(ApiException.class, () -> auth.resolve(token));
        assertEquals("withdrawn", joiners.get(s.org.admin, id).get("status"));
        assertError(409, "onboarding.closed", () -> joiners.invite(s.org.admin, id));
        assertEquals(0, ((List<?>) joiners.list(s.org.admin, null).get("joiners")).size());
        assertEquals(1, ((List<?>) joiners.list(s.org.admin, "withdrawn").get("joiners")).size());

        // Added again with the same email: HR is asked whether this is the same person.
        NewJoiner again = joiner(s, "Dev Kumar", "dev@mail.test", "9000011111", TODAY.plusDays(14), false);
        Map<String, Object> check = joiners.add(s.org.admin, again);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) check.get("possibleRehire");
        assertEquals(1, candidates.size());
        assertEquals("withdrawn", candidates.get(0).get("lastStatus"));
        String personId = (String) candidates.get(0).get("personId");
        NewJoiner rehire = new NewJoiner(again.legalName(), again.personalEmail(), again.mobile(), again.joiningDate(),
                again.employmentType(), again.designation(), again.departmentId(), again.managerId(), again.workLocationId(),
                again.shiftId(), again.weeklyOffs(), again.employeeCode(), null, false, personId, null);
        UUID second = UUID.fromString((String) joiners.add(s.org.admin, rehire).get("id"));
        assertEquals(personId, joiners.get(s.org.admin, second).get("personId"));
        assertNotNull(dev);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private record Setup(Org org, String location, String shift, String department, String manager) {}

    private static Setup setup() {
        String slug = "joiners-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        Org org = new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
        String location = (String) attendanceSettings.saveLocation(org.admin, null,
                new LocationInput("Office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) attendanceSettings.saveShift(org.admin, null,
                new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        String department = (String) employees.saveDepartment(org.admin, null, "Engineering", null, true).get("id");
        String manager = (String) employees.add(org.admin, new NewEmployee("Priya Nair", null, "1985-01-01", "female", null,
                "9876543210", "permanent", TODAY.minusYears(3).toString(), null, null, "MG-1", "Engineering manager", department,
                null, null, null, null, null, null, null, null, null, null, null)).get("id");
        return new Setup(org, location, shift, department, manager);
    }

    private static NewJoiner joiner(Setup s, String name, String email, String mobile, LocalDate joining, boolean invite) {
        return new NewJoiner(name, email, mobile, joining.toString(), "permanent", "Analyst", s.department, s.manager, s.location,
                s.shift, List.of(0, 6), "JN-" + (mobile == null ? "x" : mobile.substring(mobile.length() - 1)), null, invite, null, null);
    }

    /** Fills every joiner section as HR and sends each, leaving statutory out when asked. */
    private static void fillAsHr(Setup s, UUID id, boolean withStatutory) {
        UUID a = s.org.admin.membershipId();
        assertNotNull(a);
        joiners.saveSection(s.org.admin, id, "personal", Map.of("person.date_of_birth", "1990-02-02", "person.gender", "male"));
        joiners.saveSection(s.org.admin, id, "address", Map.of("address.current",
                Map.of("line1", "4 Park Street", "city", "Kolkata", "stateCode", "WB", "pin", "700016")));
        joiners.saveSection(s.org.admin, id, "emergency", Map.of("emergency.contacts",
                List.of(Map.of("name", "Sam", "relationship", "brother", "mobile", "9811111111"))));
        joiners.saveSection(s.org.admin, id, "bank", Map.of("bank.account_number", ACCOUNT, "bank.ifsc", "SBIN0001234"));
        joiners.saveSection(s.org.admin, id, "family", Map.of());
        joiners.saveSection(s.org.admin, id, "contact", Map.of());
        if (withStatutory) {
            joiners.saveSection(s.org.admin, id, "statutory", Map.of("statutory.pan", PAN));
        }
        for (String section : List.of("personal", "contact", "address", "emergency", "family", "bank")) {
            joiners.submitSection(s.org.admin, id, section);
        }
        if (withStatutory) {
            joiners.submitSection(s.org.admin, id, "statutory");
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyAll(Setup s, UUID id) {
        for (Map<String, Object> section : (List<Map<String, Object>>) joiners.get(s.org.admin, id).get("sections")) {
            if ("submitted".equals(section.get("status")) && !section.get("section").equals("documents")) {
                joiners.verify(s.org.admin, id, (String) section.get("section"), null);
            }
        }
        documentsAndTasks(s, id);
    }

    /** HR uploads and verifies the documents needed before activation, and ticks every task. */
    @SuppressWarnings("unchecked")
    private static void documentsAndTasks(Setup s, UUID id) {
        for (Map<String, Object> t : (List<Map<String, Object>>) documents.forJoiner(s.org.admin, id).get("types")) {
            if ("before_activation".equals(t.get("required")) && ((List<?>) t.get("documents")).isEmpty()) {
                String doc = (String) documents.uploadForJoiner(s.org.admin, id, (String) t.get("id"), "scan.pdf", PDF).get("id");
                documents.verify(s.org.admin, id, UUID.fromString(doc));
            }
        }
        for (Map<String, Object> t : (List<Map<String, Object>>) checklists.forJoiner(s.org.admin, id).get("tasks")) {
            if (t.get("doneAt") == null) {
                checklists.done(s.org.admin, UUID.fromString((String) t.get("id")));
            }
        }
    }

    private static String sectionStatus(SessionCtx ctx, UUID id, String section) {
        return (String) joiners.section(ctx, id, section).get("status");
    }

    private static UUID employmentId(UUID tenantId, UUID onboardingId) {
        return db.inTenant(tenantId, c -> UUID.fromString((String) Sql.one(c, "select employment_id from app.onboarding where id = ?",
                onboardingId).get("employmentId")));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String email = name + "@" + org.slug + ".test";
        String invitation = (String) users.invite(org.admin, email, roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static String dump(UUID tenantId) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection c = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword)) {
            for (String table : List.of("person", "statutory_id", "bank_account", "audit_event", "onboarding")) {
                try (ResultSet rs = c.createStatement().executeQuery("select * from app." + table + " where tenant_id = '" + tenantId + "'")) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        for (int i = 1; i <= n; i++) {
                            out.append(rs.getString(i)).append('|');
                        }
                    }
                }
            }
        }
        return out.toString();
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
