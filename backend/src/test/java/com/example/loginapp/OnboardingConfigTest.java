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
import java.sql.ResultSet;
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
import com.example.loginapp.core.AppliesTo;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.OnboardingConfigService;
import com.example.loginapp.core.OnboardingConfigService.CustomFieldInput;
import com.example.loginapp.core.OnboardingConfigService.FieldInput;
import com.example.loginapp.core.OnboardingConfigService.FlowInput;
import com.example.loginapp.core.OnboardingConfigService.SectionInput;
import com.example.loginapp.core.OnboardingConfigService.SettingsInput;
import com.example.loginapp.core.OnboardingConfigService.StepInput;
import com.example.loginapp.core.OnboardingDefaults;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Each Organization's onboarding configuration against a real PostgreSQL:
 * defaults, flows and their rules, field settings, custom fields and values,
 * permissions, auditing and isolation.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class OnboardingConfigTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AppliesTo.Subject INTERN = new AppliesTo.Subject(null, null, "intern", "Trainee");
    private static final AppliesTo.Subject PERMANENT = new AppliesTo.Subject(null, null, "permanent", "Engineer");

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static OnboardingConfigService config;
    private static EmployeeService employees;
    private static SessionCtx superAdmin;
    private static String url;
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
            st.execute("drop database if exists hrms_test_onboarding with (force)");
            st.execute("create database hrms_test_onboarding");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the onboarding configuration tests");

        url = "jdbc:postgresql://" + host + "/hrms_test_onboarding";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        config = new OnboardingConfigService(db);
        employees = new EmployeeService(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY));
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── defaults ────────────────────────────────────────────────────────

    @Test
    void aNewOrganizationGetsWorkingDefaultsOnce() {
        Org org = newOrg();
        Map<String, Object> s = config.settings(org.admin);
        assertEquals("manual", s.get("inviteWhen"));
        assertEquals(14, ((Number) s.get("linkValidDays")).intValue());
        assertEquals("automatic", s.get("activation"));
        assertEquals(1, ((Number) ((Map<?, ?>) s.get("notice")).get("version")).intValue());

        List<Map<String, Object>> flows = config.flows(org.admin);
        assertEquals(1, flows.size());
        assertEquals("Everyone", flows.get(0).get("name"));
        assertEquals("Everyone", flows.get(0).get("rules"));
        assertEquals(11, ((List<?>) flows.get(0).get("sections")).size());
        Map<?, ?> step = (Map<?, ?>) ((List<?>) flows.get(0).get("steps")).get(0);
        assertEquals("hr_admin", step.get("roleCode"));

        // Running the defaults again, as every read does, adds nothing.
        db.inTenant(org.id, c -> OnboardingDefaults.ensure(c, org.id));
        config.flows(org.admin);
        assertEquals(1L, count(org.id, "app.onboarding_flow"));
        assertEquals(5L, count(org.id, "app.document_type"));
        assertEquals(8L, count(org.id, "app.checklist_template_task"));
        assertEquals(10L, count(org.id, "app.message_template"));
        assertEquals(1L, count(org.id, "app.letter_template"));
        assertEquals(1L, count(org.id, "app.privacy_notice"));
    }

    // ── two Organizations, opposite choices ─────────────────────────────

    @Test
    void twoOrganizationsWithOppositeSettingsShowAnInternDifferentForms() {
        Org a = newOrg();
        Org b = newOrg();

        // A: interns get their own flow, without the bank section and with no verification; blood group is required;
        // interns are asked for their college.
        config.saveFlow(a.admin, null, new FlowInput("Interns", Map.of("employment_type", List.of("intern")),
                "Welcome to the internship", null, "Report at 10:00 to the front desk", null, 0, true,
                List.of(new SectionInput("bank", false, null, null, null, null)), List.of()));
        // A flow with rules is placed before the flow for everyone, so it is tried first.
        List<Map<String, Object>> ordered = config.flows(a.admin);
        assertEquals(List.of("Interns", "Everyone"), ordered.stream().map(f -> f.get("name")).toList());
        config.orderFlows(a.admin, List.of((String) ordered.get(1).get("id"), (String) ordered.get(0).get("id")));
        assertEquals("Everyone", ((Map<?, ?>) config.preview(a.admin, null, INTERN).get("flow")).get("name"));   // order decides
        config.orderFlows(a.admin, List.of((String) ordered.get(0).get("id"), (String) ordered.get(1).get("id")));
        config.saveFields(a.admin, null, List.of(new FieldInput("person.blood_group", "required", null, null, null)));
        config.saveCustomField(a.admin, null, new CustomFieldInput("college", "College", null, "personal", "text", null, null,
                null, null, false, false, Map.of("employment_type", List.of("intern")), "required", "joiner", 1, true));

        // B: nothing for interns, and blood group is not asked at all.
        config.saveFields(b.admin, null, List.of(new FieldInput("person.blood_group", "hidden", null, null, null)));

        Map<String, Object> aIntern = config.preview(a.admin, null, INTERN);
        Map<String, Object> bIntern = config.preview(b.admin, null, INTERN);
        assertEquals("Interns", ((Map<?, ?>) aIntern.get("flow")).get("name"));
        assertEquals("Everyone", ((Map<?, ?>) bIntern.get("flow")).get("name"));
        assertFalse(sections(aIntern).contains("bank"));
        assertTrue(sections(bIntern).contains("bank"));
        assertEquals("required", visibility(aIntern, "person.blood_group"));
        assertNull(visibility(bIntern, "person.blood_group"));
        assertEquals("required", visibility(aIntern, "custom.college"));
        assertNull(visibility(bIntern, "custom.college"));

        // In A, a permanent joiner falls to Everyone and is not asked for a college.
        Map<String, Object> aPermanent = config.preview(a.admin, null, PERMANENT);
        assertEquals("Everyone", ((Map<?, ?>) aPermanent.get("flow")).get("name"));
        assertNull(visibility(aPermanent, "custom.college"));
        assertTrue(sections(aPermanent).contains("bank"));

        // Neither sees the other's configuration.
        assertEquals(1, config.flows(b.admin).size());
        assertTrue(config.customFields(b.admin).isEmpty());
    }

    @Test
    void aFlowOverrideBeatsTheOrganizationDefault() {
        Org org = newOrg();
        UUID interns = flowId(config.saveFlow(org.admin, null, flow("Interns", Map.of("employment_type", List.of("intern")))));
        config.saveFields(org.admin, null, List.of(new FieldInput("person.marital_status", "required", "Marital status", null, null)));
        config.saveFields(org.admin, interns, List.of(new FieldInput("person.marital_status", "hidden", null, null, null)));
        assertNull(visibility(config.preview(org.admin, interns, INTERN), "person.marital_status"));
        assertEquals("required", visibility(config.preview(org.admin, null, PERMANENT), "person.marital_status"));

        // Clearing the override goes back to the Organization's setting.
        config.saveFields(org.admin, interns, List.of(new FieldInput("person.marital_status", null, null, null, null)));
        assertEquals("required", visibility(config.preview(org.admin, interns, INTERN), "person.marital_status"));
        Map<String, Object> row = config.fields(org.admin, interns).stream()
                .filter(f -> f.get("key").equals("person.marital_status")).findFirst().orElseThrow();
        assertNull(row.get("flow"));
        assertEquals("required", ((Map<?, ?>) row.get("organization")).get("visibility"));
    }

    // ── what cannot be configured away ──────────────────────────────────

    @Test
    void fixedFieldsAndSectionsAndTheCatchAllFlowAreProtected() {
        Org org = newOrg();
        assertError(409, "field.fixed", () -> config.saveFields(org.admin, null,
                List.of(new FieldInput("statutory.pan", "hidden", null, null, null))));
        assertError(409, "field.fixed", () -> config.saveFields(org.admin, null,
                List.of(new FieldInput("person.date_of_birth", "optional", null, null, null))));
        UUID everyone = flowId(config.flows(org.admin).get(0));
        assertError(409, "section.fixed", () -> config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null, null, null,
                null, null, List.of(new SectionInput("statutory", false, null, null, null, null)), null)));
        assertError(409, "onboarding.no_catch_all", () -> config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null,
                null, null, null, false, null, null)));
        assertError(409, "onboarding.no_catch_all", () -> config.saveFlow(org.admin, everyone, new FlowInput(null,
                Map.of("employment_type", List.of("permanent")), null, null, null, null, null, null, null, null)));
        assertError(400, "request.invalid", () -> config.saveFlow(org.admin, everyone, new FlowInput(null, null, null,
                "http://example.com/video", null, null, null, null, null, null)));
        // Nothing half-saved after a refusal.
        assertEquals(true, config.flows(org.admin).get(0).get("active"));
        assertEquals("Everyone", config.flows(org.admin).get(0).get("rules"));
    }

    @Test
    void verificationLevelsNeedAVerifierForEverySection() {
        Org org = newOrg();
        SessionCtx checker = join(org, "checker", List.of("employee"));
        UUID everyone = flowId(config.flows(org.admin).get(0));
        assertError(400, "request.invalid", () -> config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null, null, null,
                2, null, null, List.of(new StepInput(1, null, "role", "hr_admin", null)))));
        assertError(400, "request.invalid", () -> config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null, null, null,
                1, null, null, List.of(new StepInput(2, null, "manager", null, null)))));
        assertError(400, "request.invalid", () -> config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null, null, null,
                1, null, null, List.of(new StepInput(1, null, "role", "support_write", null)))));

        Map<String, Object> saved = config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null, null, null, 2, null, null,
                List.of(new StepInput(1, null, "manager", null, null), new StepInput(1, "bank", "user", null, checker.membershipId().toString()),
                        new StepInput(2, null, "role", "hr_admin", null))));
        assertEquals(3, ((List<?>) saved.get("steps")).size());
        // Dropping to one level drops the second level's verifiers.
        Map<String, Object> fewer = config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null, null, null, 1, null, null, null));
        assertEquals(2, ((List<?>) fewer.get("steps")).size());
        assertEquals(0, ((Number) config.saveFlow(org.admin, everyone, new FlowInput(null, null, null, null, null, null, 0, null, null, null))
                .get("verificationLevels")).intValue());
    }

    @Test
    void rulesMayOnlyNameThisOrganizationsDepartments() {
        Org a = newOrg();
        Org b = newOrg();
        String deptOfA = (String) employees.saveDepartment(a.admin, null, "Sales", null, null).get("id");
        Map<String, Object> ok = config.saveFlow(a.admin, null, flow("Sales team", Map.of("department", List.of(deptOfA))));
        assertEquals("Department Sales", ok.get("rules"));
        assertError(400, "rules.invalid", () -> config.saveFlow(b.admin, null, flow("Theirs", Map.of("department", List.of(deptOfA)))));
        assertError(400, "rules.invalid", () -> config.saveFlow(a.admin, null, flow("Bad", Map.of("grade", List.of("A")))));
    }

    @Test
    void settingsAreCheckedAndEveryChangeIsAuditedWithBeforeAndAfter() {
        Org org = newOrg();
        assertError(400, "request.invalid", () -> config.saveSettings(org.admin,
                new SettingsInput(false, "on_offer_accepted", null, null, null, null, null, null, null)));
        assertError(400, "request.invalid", () -> config.saveSettings(org.admin,
                new SettingsInput(null, null, null, 200, null, null, null, null, null)));
        assertError(400, "request.invalid", () -> config.saveSettings(org.admin,
                new SettingsInput(null, null, null, null, null, null, null, null, 3)));   // less than the legal five years
        Map<String, Object> after = config.saveSettings(org.admin,
                new SettingsInput(true, "days_before_joining", 7, 30, "manual", false, List.of("pan"), 60, 8));
        assertEquals("days_before_joining", after.get("inviteWhen"));
        assertEquals(7, ((Number) after.get("inviteDaysBefore")).intValue());
        assertEquals(List.of("pan"), after.get("rehireMatch"));

        Map<String, Object> event = users.auditLog(org.admin).stream()
                .filter(e -> e.get("action").equals("onboarding.settings_changed")).findFirst().orElseThrow();
        String detail = (String) event.get("detail");
        assertTrue(detail.contains("\"before\"") && detail.contains("\"after\"") && detail.contains("days_before_joining")
                && detail.contains("manual"), detail);

        Map<String, Object> notice = config.publishNotice(org.admin, "A new notice that explains plainly why we collect what we collect.");
        assertEquals(2, ((Number) notice.get("version")).intValue());
        assertError(400, "request.invalid", () -> config.publishNotice(org.admin, "Too short"));
    }

    @Test
    void readingNeedsEmployeeReadAcrossTheOrganizationAndChangingNeedsConfigure() {
        Org org = newOrg();
        SessionCtx emp = join(org, "emp", List.of("employee"));
        SessionCtx hr = join(org, "hr", List.of("hr_admin"));
        SessionCtx approver = join(org, "approver", List.of("payroll_approver"));
        assertError(403, "permission.denied", () -> config.flows(emp));
        assertError(403, "permission.denied", () -> config.saveFields(emp, null, List.of(new FieldInput("person.gender", "optional", null, null, null))));
        assertEquals(1, config.flows(approver).size());                                   // can read (assigned scope)
        assertError(403, "permission.denied", () -> config.saveFlow(approver, null, flow("Nope", Map.of())));
        config.saveFlow(hr, null, flow("Contractors", Map.of("employment_type", List.of("consultant"))));   // HR Admin configures

        Map<String, Object> grant = platform.requestGrant(superAdmin, org.id, "write", "Ticket 9: check the onboarding setup");
        SessionCtx support = auth.resolve(platform.startSupportSession(superAdmin, UUID.fromString((String) grant.get("id"))));
        assertEquals(2, config.flows(support).size());
        assertError(403, "permission.denied", () -> config.saveFlow(support, null, flow("Support's", Map.of())));
    }

    // ── custom fields and their values ──────────────────────────────────

    @Test
    void customFieldValuesAreCheckedAndSensitiveOnesEncrypted() throws Exception {
        Org org = newOrg();
        config.saveCustomField(org.admin, null, new CustomFieldInput("tshirt", "T-shirt size", null, "personal", "select",
                List.of("S", "M", "L"), null, null, null, false, true, null, "optional", "joiner", 0, true));
        config.saveCustomField(org.admin, null, new CustomFieldInput("passport", "Passport number", null, "statutory", "text", null,
                "^[A-Z][0-9]{7}$", null, null, true, false, null, "optional", "joiner", 0, true));
        config.saveCustomField(org.admin, null, new CustomFieldInput("college", "College", null, "personal", "text", null, null,
                null, null, false, false, Map.of("employment_type", List.of("intern")), "optional", "joiner", 0, true));
        assertError(409, "field.key_taken", () -> config.saveCustomField(org.admin, null, new CustomFieldInput("tshirt", "Again", null,
                "personal", "text", null, null, null, null, false, false, null, null, null, null, null)));
        assertError(400, "request.invalid", () -> config.saveCustomField(org.admin, null, new CustomFieldInput("secret_note", "Note", null,
                "personal", "text", null, null, null, null, true, true, null, null, null, null, null)));   // sensitive and shown to managers

        UUID id = UUID.fromString((String) employees.add(org.admin, new NewEmployee("Asha Roy", null, null, null, null, null, "permanent",
                TODAY.minusYears(1).toString(), null, null, null, "Engineer", null, null, null, null, null, null, null, null, null, null,
                null, null)).get("id"));
        employees.saveCustomValues(org.admin, id, Map.of("tshirt", "M", "custom.passport", "K1234567"));
        assertError(400, "field.invalid", () -> employees.saveCustomValues(org.admin, id, Map.of("tshirt", "XXL")));
        assertError(400, "field.invalid", () -> employees.saveCustomValues(org.admin, id, Map.of("passport", "12345")));
        assertError(400, "field.not_applicable", () -> employees.saveCustomValues(org.admin, id, Map.of("college", "IIT")));
        assertError(400, "field.unknown", () -> employees.saveCustomValues(org.admin, id, Map.of("shoe", "9")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shown = (List<Map<String, Object>>) employees.get(org.admin, id).get("customFields");
        Map<String, Object> tshirt = shown.stream().filter(f -> f.get("key").equals("custom.tshirt")).findFirst().orElseThrow();
        Map<String, Object> passport = shown.stream().filter(f -> f.get("key").equals("custom.passport")).findFirst().orElseThrow();
        assertEquals("M", tshirt.get("value"));
        assertNull(passport.get("value"));
        assertEquals(true, passport.get("hasValue"));
        assertFalse(shown.stream().anyMatch(f -> f.get("key").equals("custom.college")));   // not an intern
        assertEquals("K1234567", employees.reveal(org.admin, id, "custom.passport", "Visa application").get("value"));

        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword);
             ResultSet rs = c.createStatement().executeQuery("select value::text, value_enc from app.custom_field_value where tenant_id = '"
                     + org.id + "'")) {
            StringBuilder dump = new StringBuilder();
            while (rs.next()) {
                dump.append(rs.getString(1)).append('|');
                byte[] raw = rs.getBytes(2);
                if (raw != null) {
                    dump.append(new String(raw, StandardCharsets.ISO_8859_1));
                }
            }
            assertTrue(dump.toString().contains("\"M\""));
            assertFalse(dump.toString().contains("K1234567"));
        }

        // Clearing a value.
        java.util.Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("tshirt", null);
        employees.saveCustomValues(org.admin, id, clear);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = (List<Map<String, Object>>) employees.get(org.admin, id).get("customFields");
        assertEquals(false, after.stream().filter(f -> f.get("key").equals("custom.tshirt")).findFirst().orElseThrow().get("hasValue"));
    }

    @Test
    void aCustomFieldsKeyTypeAndSensitivityCannotChange() {
        Org org = newOrg();
        UUID field = UUID.fromString((String) config.saveCustomField(org.admin, null, new CustomFieldInput("badge", "Badge number", null,
                "employment", "text", null, null, null, null, false, false, null, null, null, null, null)).get("id"));
        assertError(400, "request.invalid", () -> config.saveCustomField(org.admin, field, new CustomFieldInput(null, null, null, null,
                "number", null, null, null, null, null, null, null, null, null, null, null)));
        assertError(400, "request.invalid", () -> config.saveCustomField(org.admin, field, new CustomFieldInput(null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, null)));
        Map<String, Object> renamed = config.saveCustomField(org.admin, field, new CustomFieldInput(null, "Access badge", null, null,
                null, null, null, null, null, null, null, null, "required", "joiner", null, null));
        assertEquals("Access badge", renamed.get("label"));
        assertEquals("hr", renamed.get("editableBy"));   // employment fields stay with HR
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "onb-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static FlowInput flow(String name, Map<String, Object> rules) {
        return new FlowInput(name, rules, null, null, null, null, null, null, null, null);
    }

    private static UUID flowId(Map<String, Object> flow) {
        return UUID.fromString((String) flow.get("id"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> sections(Map<String, Object> preview) {
        return ((List<Map<String, Object>>) preview.get("sections")).stream().map(s -> (String) s.get("section")).toList();
    }

    @SuppressWarnings("unchecked")
    private static String visibility(Map<String, Object> preview, String key) {
        for (Map<String, Object> s : (List<Map<String, Object>>) preview.get("sections")) {
            for (Map<String, Object> f : (List<Map<String, Object>>) s.get("fields")) {
                if (f.get("key").equals(key)) {
                    return (String) f.get("visibility");
                }
            }
        }
        return null;
    }

    private static long count(UUID tenantId, String table) {
        return db.inTenant(tenantId, c -> ((Number) Sql.one(c, "select count(*) as n from " + table).get("n")).longValue());
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
