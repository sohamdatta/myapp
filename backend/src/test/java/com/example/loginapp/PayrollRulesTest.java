package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import com.example.loginapp.core.Db;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PayrollConfig;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PayrollRuns;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.PayrollRules;
import com.example.loginapp.core.UserService;

/**
 * State professional tax and labour welfare fund rules managed from the platform console: versions that close each other,
 * drafts and sign-off, the checks that keep a rule readable by the engine, and who may do what.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class PayrollRulesTest {

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
    private static AttendanceSettingsService settings;
    private static PayrollConfig config;
    private static PayrollPay pay;
    private static PayrollRuns runs;
    private static PayrollRules rules;
    private static SessionCtx superAdmin;
    private static boolean reachable;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_rules with (force)");
            st.execute("create database hrms_test_rules");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        if (!reachable) {
            return;
        }
        String url = "jdbc:postgresql://" + host + "/hrms_test_rules";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        employees = new EmployeeService(db, crypto);
        settings = new AttendanceSettingsService(db);
        config = new PayrollConfig(db);
        pay = new PayrollPay(db);
        runs = new PayrollRuns(db, crypto);
        rules = new PayrollRules(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static PayrollRules.Slab slab(String from, String to, String monthly, String february) {
        return new PayrollRules.Slab(n(from), to == null ? null : n(to), n(monthly), february == null ? null : n(february));
    }

    private static PayrollRules.Input pt(String state, String from, PayrollRules.Pt pt) {
        return new PayrollRules.Input("pt", state, from, "State gazette notification, read for the test", pt, null);
    }

    private static PayrollRules.Pt twoSlabs() {
        return new PayrollRules.Pt(List.of(slab("0", "14999.99", "0", null), slab("15000", null, "200", "300")), List.of(), n("2500"));
    }

    private static SessionCtx supportUser() {
        String email = "support" + SEQUENCE.incrementAndGet() + "@platform.test";
        platform.addPlatformUser(superAdmin, email, "support", PASSWORD);
        return auth.resolve(auth.enterConsole(auth.resolve(auth.login(email, PASSWORD))));
    }

    private static void refuses(int status, String code, Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(status, e.status(), e.getMessage());
        if (code != null) {
            assertEquals(code, e.code());
        }
    }

    @Test
    void aNewVersionClosesTheOneInForceAndNeedsSignOffBeforeTheNextOne() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        LocalDate thisMonth = LocalDate.now(IST).withDayOfMonth(1);
        LocalDate next = thisMonth.plusMonths(1);
        // A state's first version may start in the past: there is nothing before it to overwrite.
        PayrollRules.Rule v1 = rules.add(superAdmin, pt("GJ", "2026-04-01", twoSlabs()));
        assertEquals("draft", v1.status());
        assertEquals(null, v1.validTo());
        assertEquals(2, v1.pt().men().size());
        assertEquals(0, n("2500").compareTo(v1.pt().yearlyCeiling()));

        // The latest version must be signed off before another is added.
        refuses(409, "rule.draft_exists", () -> rules.add(superAdmin, pt("GJ", next.toString(), twoSlabs())));
        refuses(400, null, () -> rules.signOff(superAdmin, UUID.fromString(v1.id()), new PayrollRules.SignOff("  ", "2026-10-01")));
        refuses(400, null, () -> rules.signOff(superAdmin, UUID.fromString(v1.id()), new PayrollRules.SignOff("CA Meera Iyer", LocalDate.now(IST).plusDays(1).toString())));
        PayrollRules.Rule signed = rules.signOff(superAdmin, UUID.fromString(v1.id()), new PayrollRules.SignOff("CA Meera Iyer", "2026-10-01"));
        assertEquals("signed_off", signed.status());
        assertEquals("CA Meera Iyer", signed.signedOffBy());
        refuses(409, "rule.signed_off", () -> rules.signOff(superAdmin, UUID.fromString(v1.id()), new PayrollRules.SignOff("CA Meera Iyer", "2026-10-01")));
        refuses(409, "rule.signed_off", () -> rules.edit(superAdmin, UUID.fromString(v1.id()), pt("GJ", "2026-04-01", twoSlabs())));

        // Months already past keep the rule they were paid under: a new version cannot start before this month.
        refuses(409, "rule.back_dated", () -> rules.add(superAdmin, pt("GJ", "2026-04-01", twoSlabs())));
        refuses(409, "rule.back_dated", () -> rules.add(superAdmin, pt("GJ", thisMonth.minusMonths(1).toString(), twoSlabs())));

        // A later version closes the first on its start date, and starts as a draft; a draft can be edited.
        PayrollRules.Rule v2 = rules.add(superAdmin, pt("GJ", next.toString(), new PayrollRules.Pt(List.of(slab("0", "17999.99", "0", null), slab("18000", null, "250", null)), null, null)));
        assertEquals("draft", v2.status());
        PayrollRules.Rule edited = rules.edit(superAdmin, UUID.fromString(v2.id()), new PayrollRules.Input("pt", "GJ", next.toString(), "Corrected from the gazette",
                new PayrollRules.Pt(List.of(slab("0", "17999.99", "0", null), slab("18000", null, "200", null)), List.of(), null), null));
        assertEquals(0, n("200").compareTo(edited.pt().men().get(1).monthly()));
        assertEquals("Corrected from the gazette", edited.source());
        assertEquals("draft", edited.status());
        rules.signOff(superAdmin, UUID.fromString(v2.id()), new PayrollRules.SignOff("CA Meera Iyer", LocalDate.now(IST).toString()));
        // Nothing starts at or before the latest version.
        refuses(409, "rule.version_order", () -> rules.add(superAdmin, pt("GJ", thisMonth.toString(), twoSlabs())));
        refuses(409, "rule.version_order", () -> rules.add(superAdmin, pt("GJ", next.toString(), twoSlabs())));

        List<PayrollRules.Rule> gj = rules.list(superAdmin).stream().filter(r -> r.jurisdiction().equals("GJ")).toList();
        assertEquals(List.of(v2.id(), v1.id()), gj.stream().map(PayrollRules.Rule::id).toList(), "newest first");
        assertEquals(next.toString(), gj.get(1).validTo(), "the version in force is closed where the next starts");
        assertEquals(null, gj.get(0).validTo());

        // The seeded states are still drafts, so they take no new version yet.
        refuses(409, "rule.draft_exists", () -> rules.add(superAdmin, pt("MH", next.toString(), twoSlabs())));

        // The audit trail has the sign-off, and an edit keeps what the figures were and what they became.
        assertEquals(1L, ((Number) db.inOwner(c -> Sql.one(c, "select count(*) as n from platform.platform_audit where action = 'payroll_rule.signed_off' and target_id = ?::uuid",
                v1.id()).get("n"))).longValue());
        Map<String, Object> edit = db.inOwner(c -> Sql.one(c, "select (detail->>'before')::jsonb->'men'->1->>'monthly' as before, (detail->>'after')::jsonb->'men'->1->>'monthly' as after "
                + "from platform.platform_audit where action = 'payroll_rule.edited' and target_id = ?::uuid", v2.id()));
        assertEquals("250", String.valueOf(edit.get("before")).replaceAll("\\.0+$", ""));
        assertEquals("200", String.valueOf(edit.get("after")).replaceAll("\\.0+$", ""));
    }

    @Test
    void aRuleTheEngineCouldNotReadIsRefused() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        PayrollRules.Slab zero = slab("0", "9999.99", "0", null);
        PayrollRules.Slab open = slab("10000", null, "150", null);
        for (PayrollRules.Input bad : List.of(
                pt("ZZ", "2026-04-01", twoSlabs()),                                                                                   // not a state
                pt("TN", "2026-04-15", twoSlabs()),                                                                                   // not the first of a month
                pt("TN", "soon", twoSlabs()),
                new PayrollRules.Input("pt", "TN", "2026-04-01", "ok", twoSlabs(), new PayrollRules.Lwf(n("1"), n("1"), List.of(6))),    // both kinds
                new PayrollRules.Input("pt", "TN", "2026-04-01", "x", twoSlabs(), null),                                              // no source worth the name
                new PayrollRules.Input("income_tax", "TN", "2026-04-01", "Some source", twoSlabs(), null),
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(), null, null)),
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(slab("5", "9999.99", "0", null), open), null, null)),                // does not start at 0
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, slab("10500", null, "150", null)), null, null)),               // a gap
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, slab("9000", null, "150", null)), null, null)),                // an overlap
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, slab("10000", "20000", "150", null)), null, null)),            // the last is not open
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(open, zero), null, null)),                                         // open before the end
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, slab("10000", null, "-1", null)), null, null)),               // negative
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, slab("10000", null, "150", "-5")), null, null)),
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, slab("10000", null, "150000", null)), null, null)),            // absurd
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, open), null, n("0"))),                                       // a ceiling of nothing
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(slab("0", "9999.999", "0", null), slab("10000", null, "150", null)), null, null)),   // below a paisa
                pt("TN", "2026-04-01", new PayrollRules.Pt(List.of(zero, slab("10000", null, "150.005", null)), null, null)),
                new PayrollRules.Input("lwf", "TN", "2026-04-01", "Some source", null, new PayrollRules.Lwf(n("20"), n("40"), List.of())),
                new PayrollRules.Input("lwf", "TN", "2026-04-01", "Some source", null, new PayrollRules.Lwf(n("20"), n("40"), List.of(13))),
                new PayrollRules.Input("lwf", "TN", "2026-04-01", "Some source", null, new PayrollRules.Lwf(n("20"), n("40"), List.of(6, 6))),
                new PayrollRules.Input("lwf", "TN", "2026-04-01", "Some source", null, new PayrollRules.Lwf(n("-1"), n("40"), List.of(6))),
                new PayrollRules.Input("lwf", "TN", "2026-04-01", "Some source", null, null))) {
            refuses(400, null, () -> rules.add(superAdmin, bad));
        }
        assertEquals(0, rules.list(superAdmin).stream().filter(r -> r.jurisdiction().equals("TN")).count(), "nothing half-saved");
        PayrollRules.Rule lwf = rules.add(superAdmin, new PayrollRules.Input("lwf", "TN", "2026-04-01", "State LWF notification", null,
                new PayrollRules.Lwf(n("20"), n("40"), List.of(12))));
        assertEquals(List.of(12), lwf.lwf().months());
        assertEquals(null, lwf.pt());
    }

    @Test
    void everyRuleAlreadySeededPassesTheSameChecks() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        List<PayrollRules.Rule> seeded = rules.list(superAdmin).stream().filter(r -> r.signedOffBy() == null && !r.jurisdiction().equals("GJ")
                && !r.jurisdiction().equals("TN")).toList();
        assertTrue(seeded.size() >= 6, "Maharashtra, Karnataka and Goa, for both: " + seeded.size());
        for (PayrollRules.Rule r : seeded) {
            // Saving a draft as it stands runs the whole check; the data must come back exactly as it was.
            PayrollRules.Rule again = rules.edit(superAdmin, UUID.fromString(r.id()),
                    new PayrollRules.Input(r.statute(), r.jurisdiction(), r.validFrom(), r.source(), r.pt(), r.lwf()));
            assertEquals(r.pt(), again.pt(), r.statute() + " " + r.jurisdiction());
            assertEquals(r.lwf(), again.lwf(), r.statute() + " " + r.jurisdiction());
        }
    }

    @Test
    void onlyASuperAdminChangesRulesAndSupportMayLook() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        SessionCtx support = supportUser();
        assertTrue(rules.list(support).size() >= 6);
        refuses(403, null, () -> rules.add(support, pt("KL", "2026-04-01", twoSlabs())));
        UUID any = UUID.fromString(rules.list(superAdmin).getFirst().id());
        refuses(403, null, () -> rules.signOff(support, any, new PayrollRules.SignOff("CA Meera Iyer", "2026-10-01")));
        refuses(403, null, () -> rules.edit(support, any, pt("KL", "2026-04-01", twoSlabs())));

        // An Organization's own administrator is not on the platform at all.
        SessionCtx orgAdmin = newOrg().admin;
        refuses(403, null, () -> rules.list(orgAdmin));
        refuses(403, null, () -> rules.add(orgAdmin, pt("KL", "2026-04-01", twoSlabs())));
    }

    @Test
    void theConsoleStillCannotReadAnyOrganizationsData() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        // The rule table is the only thing in schema app the console role may touch.
        for (String table : List.of("employment", "payslip", "payslip_line", "tax_certificate", "statutory_id", "person", "compensation")) {
            Boolean any = db.inOwner(c -> (Boolean) Sql.one(c, "select has_table_privilege('app_platform', 'app." + table + "', 'select,insert,update,delete') as p").get("p"));
            assertEquals(false, any, "app_platform must have no access to app." + table);
        }
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.query(c, "select * from app.payslip")));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.query(c, "select * from app.employment")));
        assertTrue(db.inPlatform(c -> Sql.query(c, "select id from app.payroll_rule")).size() >= 9);
        // And the only function in schema app it can run is the one that names the tenant being worked in (triggers cannot be called).
        List<Map<String, Object>> callable = db.inOwner(c -> Sql.query(c, "select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
                + "where n.nspname = 'app' and p.prokind = 'f' and p.prorettype <> 'trigger'::regtype and has_function_privilege('app_platform', p.oid, 'execute')"));
        assertEquals(List.of("currentTenant"), callable.stream().map(m -> m.values().iterator().next().toString()).map(x -> x.equals("current_tenant") ? "currentTenant" : x).toList());
    }

    @Test
    void theDatabaseLimitsWhatTheConsoleCanDoToTheRuleTableWhateverTheCodeSays() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        PayrollRules.Rule signed = rules.add(superAdmin, pt("TG", "2026-04-01", twoSlabs()));
        rules.signOff(superAdmin, UUID.fromString(signed.id()), new PayrollRules.SignOff("CA Meera Iyer", "2026-10-01"));
        // A signed-off row cannot be changed, only closed; a national rule or a new row that is already signed off cannot be touched at all.
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.update(c, "update app.payroll_rule set data = '{}'::jsonb where id = ?::uuid", signed.id())));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.update(c, "update app.payroll_rule set status = 'draft', signed_off_by = null, signed_off_on = null where id = ?::uuid", signed.id())));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.update(c, "update app.payroll_rule set data = '{}'::jsonb where statute = 'pf'")));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.update(c, "insert into app.payroll_rule (statute, jurisdiction, valid_from, data, source, status, signed_off_by, signed_off_on) "
                + "values ('pt', 'KL', '2026-04-01', '{}'::jsonb, 'x', 'signed_off', 'someone', '2026-10-01')")));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.update(c, "insert into app.payroll_rule (statute, jurisdiction, valid_from, data, source) "
                + "values ('income_tax', 'KL', '2026-04-01', '{}'::jsonb, 'x')")));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.update(c, "update app.payroll_rule set valid_from = '2026-01-01' where id = ?::uuid", signed.id())));
        // Closing it is allowed (that is how a new version starts), and nothing else about it moved.
        assertEquals(Integer.valueOf(1), db.inPlatform(c -> Sql.update(c, "update app.payroll_rule set valid_to = '2027-04-01' where id = ?::uuid and valid_to is null", signed.id())));
        assertThrows(Exception.class, () -> db.inPlatform(c -> Sql.update(c, "update app.payroll_rule set valid_to = '2028-04-01' where id = ?::uuid", signed.id())));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "rule-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
