package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
import com.example.loginapp.core.EmployeeImport;
import com.example.loginapp.core.EmployeeImport.Invite;
import com.example.loginapp.core.EmployeeImport.Mapping;
import com.example.loginapp.core.EmployeeImport.Request;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.OnboardingConfigService;
import com.example.loginapp.core.OnboardingConfigService.CustomFieldInput;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Importing existing employees from a CSV file against a real PostgreSQL:
 * the mapping guessed and remembered, every refused row explained, the same
 * file twice adding nothing, identifiers encrypted, managers and departments
 * resolved, and imported employees invited in bulk.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class EmployeeImportTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String FILE = String.join("\n",
            "Emp Code,Full Name,DOJ,Designation,Dept,Reports To,Email,Mobile,Gender,PAN,Account Number,IFSC,Blood group",
            "G001,Asha Rao,01/04/2021,Director,Leadership,,asha@mail.test,9876500001,F,ABCPR1234A,123456789012,SBIN0001234,O+",
            "G002,Bala Iyer,2022-06-15,Engineer,Engineering,G001,bala@mail.test,9876500002,Male,ABCPI1234B,,,A+",
            "G003,Chitra Das,15-Jan-2023,Engineer,Engineering,g001,chitra@mail.test,9876500003,female,,,,",
            "G004,Dev Sen,31/02/2023,Engineer,Engineering,G001,dev@mail.test,9876500004,M,,,,",
            "G005,Esha Pal,2023-03-01,Engineer,Engineering,G001,esha@mail.test,9876500005,F,ABCPR1234A,,,",
            "G002,Farah Khan,2023-03-01,Engineer,Engineering,G001,farah@mail.test,9876500006,F,,,,",
            "G007,Gita Roy,2023-03-01,Engineer,Engineering,X999,gita@mail.test,9876500007,F,,,,",
            "G008,Hari Nair,2023-03-01,,Engineering,G001,hari@mail.test,9876500008,M,ABCPN1234C,12345,SBIN0001234,",
            "G009,Indu Jain,2023-03-01,Engineer,Engineering,G001,indu@mail.test,9876500009,X,,,,",
            "G010,\"Kumar, Jay\",2023-03-01,Analyst,Finance,G001,jay@mail.test,9876500010,M,,,,B+");

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static EmployeeService employees;
    private static OnboardingConfigService config;
    private static EmployeeImport imports;
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
            st.execute("drop database if exists hrms_test_import with (force)");
            st.execute("create database hrms_test_import");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the import tests");

        ownerUrl = "jdbc:postgresql://" + host + "/hrms_test_import";
        db = new Db(source(ownerUrl, ownerUser, ownerPassword), source(ownerUrl, "app_platform", ROLE_PASSWORD),
                source(ownerUrl, "app_auth", ROLE_PASSWORD), source(ownerUrl, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, crypto);
        config = new OnboardingConfigService(db);
        imports = new EmployeeImport(db, crypto, employees);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFileIsMappedCheckedImportedOnceAndTheMappingRemembered() throws Exception {
        Org org = newOrg();
        config.saveCustomField(org.admin, null, new CustomFieldInput("blood_group_code", "Blood group", null, "personal", "select",
                List.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"), null, null, null, false, false, null, "optional", "joiner", 0, true));

        // The first look guesses each column from its header, including the Organization's own field.
        Map<String, Object> read = imports.read(org.admin, FILE);
        assertEquals(false, read.get("mappingSaved"));
        Map<String, Integer> guessed = (Map<String, Integer>) ((Map<String, Object>) read.get("mapping")).get("columns");
        assertEquals(Map.of("employee_code", 0, "legal_name", 1, "joining_date", 2, "designation", 3, "department", 4, "manager", 5,
                "personal_email", 6, "mobile", 7, "gender", 8, "pan", 9), withoutTail(guessed));
        assertEquals(10, guessed.get("bank_account"));
        assertEquals(11, guessed.get("ifsc"));
        assertEquals(12, guessed.get("custom.blood_group_code"));

        Mapping mapping = new Mapping(true, guessed, true);
        Map<String, Object> preview = imports.preview(org.admin, new Request(FILE, mapping));
        assertEquals(4, preview.get("ready"), preview.get("rows").toString());
        assertEquals(6, preview.get("refused"));
        Map<Integer, String> problems = new LinkedHashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) preview.get("rows")) {
            List<String> p = (List<String>) row.get("problems");
            problems.put((Integer) row.get("line"), p.isEmpty() ? "" : p.get(0));
        }
        assertEquals("", problems.get(2));
        assertEquals("", problems.get(3));
        assertEquals("", problems.get(4));   // the manager's code in other letters still matches
        assertTrue(problems.get(5).contains("is not a date"), problems.get(5));
        assertEquals("The same PAN is on an earlier row", problems.get(6));
        assertEquals("The employee code G002 is on an earlier row", problems.get(7));
        assertEquals("No employee with the code or email X999 to report to", problems.get(8));
        assertTrue(problems.get(9).contains("designation") || problems.get(9).contains("account"), problems.get(9));
        assertTrue(problems.get(10).startsWith("Gender \"X\""), problems.get(10));
        assertEquals("", problems.get(11));   // a quoted name with a comma
        assertTrue(((List<String>) ((List<Map<String, Object>>) preview.get("rows")).get(0).get("notes")).contains("Adds the department Leadership"));

        // Nothing is written by the preview.
        assertEquals(0L, count(org.id, "select count(*) from app.employment"));

        Map<String, Object> done = imports.run(org.admin, new Request(FILE, mapping));
        assertEquals(4, done.get("imported"));
        assertEquals(4L, count(org.id, "select count(*) from app.employment where status = 'active' and imported_at is not null"));
        assertEquals(3L, count(org.id, "select count(*) from app.department"));
        // Managers resolved within the file, the Organization's own field set, the PAN encrypted.
        List<Map<String, Object>> list = employees.list(org.admin, null, "active", null);
        Map<String, Object> bala = list.stream().filter(e -> e.get("legalName").equals("Bala Iyer")).findFirst().orElseThrow();
        assertEquals("Asha Rao", bala.get("managerName"));
        assertEquals("Engineering", bala.get("departmentName"));
        assertEquals(1L, count(org.id, "select count(*) from app.custom_field_value where value = '\"O+\"'::jsonb"));
        try (Connection c = DriverManager.getConnection(ownerUrl, ownerUser, ownerPassword);
             ResultSet rs = c.createStatement().executeQuery("select string_agg(encode(value_enc, 'escape'), '') from app.statutory_id")) {
            rs.next();
            assertFalse(rs.getString(1).contains("ABCPR1234A"), "PAN in clear");
        }

        // The same file again adds nothing: every row is now already recorded or still wrong.
        Map<String, Object> again = imports.preview(org.admin, new Request(FILE, mapping));
        assertEquals(0, again.get("ready"));
        assertError(400, "import.nothing", () -> imports.run(org.admin, new Request(FILE, mapping)));

        // The mapping is remembered by column name, so a reordered file maps itself.
        String reordered = "Full Name,Emp Code,Designation,DOJ\nLata Menon,G020,Analyst,2024-01-10";
        Map<String, Integer> remembered = (Map<String, Integer>) ((Map<String, Object>) imports.read(org.admin, reordered).get("mapping")).get("columns");
        assertEquals(0, remembered.get("legal_name"));
        assertEquals(1, remembered.get("employee_code"));
        assertEquals(3, remembered.get("joining_date"));
        assertEquals(true, imports.read(org.admin, reordered).get("mappingSaved"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importedEmployeesAreInvitedInBulkAndLinkedWhenTheyAccept() {
        Org org = newOrg();
        String file = "Name,Joining date,Designation,Email,Code\nMira Shah,2022-02-01,Analyst,mira@mail.test,M01\nNeel Bose,2022-02-01,Analyst,,M02";
        Mapping mapping = new Mapping(true, Map.of("legal_name", 0, "joining_date", 1, "designation", 2, "personal_email", 3, "employee_code", 4), true);
        imports.run(org.admin, new Request(file, mapping));

        List<Map<String, Object>> waiting = imports.uninvited(org.admin);
        assertEquals(List.of("Mira Shah"), waiting.stream().map(w -> w.get("legalName")).toList());   // Neel has no email
        List<Map<String, Object>> sent = imports.invite(org.admin, new Invite(List.of((String) waiting.get(0).get("id"))));
        assertEquals("mira@mail.test", sent.get(0).get("email"));
        assertTrue(imports.uninvited(org.admin).isEmpty());

        SessionCtx mira = auth.resolve(invitations.accept((String) sent.get(0).get("token"), PASSWORD, null));
        assertEquals(List.of("employee"), auth.me(mira).get("roles"));
        SessionCtx again = auth.resolve(reissue(org));
        assertEquals(mira.membershipId(), again.membershipId());
        assertEquals("M01", db.inTenant(org.id, c -> Sql.one(c, "select employee_code from app.membership where id = ?", mira.membershipId())
                .get("employeeCode")));
        assertEquals(1L, count(org.id, "select count(*) from app.employment where membership_id = '" + mira.membershipId() + "'"));

        SessionCtx plain = join(org, "plain", List.of("employee"));
        assertError(403, "permission.denied", () -> imports.preview(plain, new Request(file, mapping)));
        assertError(400, "request.invalid", () -> imports.preview(org.admin, new Request(file,
                new Mapping(true, Map.of("legal_name", 0), true))));
    }

    /** Signs Mira in again through a fresh login, which must find the same, already linked membership. */
    private static String reissue(Org org) {
        return auth.enterOrganization(auth.resolve(auth.login("mira@mail.test", PASSWORD)), org.id);
    }

    private static Map<String, Integer> withoutTail(Map<String, Integer> m) {
        Map<String, Integer> out = new LinkedHashMap<>(m);
        out.remove("bank_account");
        out.remove("ifsc");
        out.remove("custom.blood_group_code");
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "import-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        return new Org(UUID.fromString((String) created.get("id")), slug,
                auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null)));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
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

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
