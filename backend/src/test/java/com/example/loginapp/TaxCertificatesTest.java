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
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.Json;
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
import com.example.loginapp.core.TaxCertificates;
import com.example.loginapp.core.UserService;

/**
 * Form 130 Part B: built from really calculated and approved months, held back until the year is final, frozen
 * with the payslips' own projection, released by a second person, and opened only by its employee.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class TaxCertificatesTest {

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
    private static TaxCertificates certificates;
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
            st.execute("drop database if exists hrms_test_taxcert with (force)");
            st.execute("create database hrms_test_taxcert");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_taxcert";
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
        certificates = new TaxCertificates(db, crypto);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    /** The March that ends the tax year we are in (this month when it is March): the final month of the year. */
    private static final LocalDate MARCH = TODAY.getMonthValue() <= 3 ? LocalDate.of(TODAY.getYear(), 3, 1) : LocalDate.of(TODAY.getYear() + 1, 3, 1);
    private static final String YEAR = TODAY.getMonthValue() <= 3 ? (TODAY.getYear() - 1) + "-" + String.format("%02d", TODAY.getYear() % 100)
            : TODAY.getYear() + "-" + String.format("%02d", (TODAY.getYear() + 1) % 100);

    private record Case(Org org, SessionCtx operator, SessionCtx asha, SessionCtx ravi, UUID ashaEmployment) {}

    /** An Organization with two paid people and a PAN-less third, and the TDS statement's deductor filled in. */
    private Case organization() throws Exception {
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx a = join(org, "asha", List.of("employee"));
        SessionCtx r = join(org, "ravi", List.of("employee"));
        SessionCtx n = join(org, "nopan", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Bengaluru office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(a.membershipId().toString(), r.membershipId().toString(), n.membershipId().toString()),
                UUID.fromString(loc), UUID.fromString(shift), List.of(0), TODAY.minusDays(120).toString());
        UUID asha = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", "100200300400", "111111111111", a)).get("id"));
        UUID ravi = UUID.fromString((String) employees.add(org.admin, person("Ravi Kumar", "ABCPK1234F", "100200300401", "222222222222", r)).get("id"));
        UUID nopan = UUID.fromString((String) employees.add(org.admin, person("Nita Das", null, "100200300402", "333333333333", n)).get("id"));
        Map<String, Object> setup = config.setup(operator);
        for (String[] reg : List.of(new String[] {"pf", null, "KABLR0012345000"}, new String[] {"esi", null, "53000123450001001"},
                new String[] {"pt", "KA", "PTKA-123456"}, new String[] {"lwf", "KA", "LWF-KA-998"})) {
            config.addRegistration(operator, new PayrollConfig.RegistrationInput(reg[0], reg[1], reg[2]));
        }
        db.inOwner(c -> Sql.update(c, "update app.payroll_settings set entity_name = 'Acme Pvt Ltd', pan = 'AAACA1234A', tan = 'BLRA12345B', "
                + "address = '1 MG Road, Bengaluru' where tenant_id = ?", org.id));
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");
        String from = TODAY.withDayOfMonth(1).toString();
        for (UUID e : List.of(asha, ravi, nopan)) {
            pay.revise(org.admin, e, new PayrollPay.ReviseInput(from, staff, new BigDecimal("36000000"), "Joining salary"));
        }
        return new Case(org, operator, a, r, asha);
    }

    /** Calculates and approves a month; with {@code finish} it is also paid and released, which the next month's run waits for. */
    private void approve(Case k, LocalDate period, boolean finish) throws Exception {
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(k.operator).get("groups")).get(0)).get("id");
        UUID run = UUID.fromString((String) runs.open(k.operator, UUID.fromString(group), period.toString()).get("id"));
        runs.freeze(k.operator, run);
        runs.calculate(k.operator, run);
        for (Object o : (List<?>) runs.get(k.operator, run).get("exceptions")) {
            Map<?, ?> x = (Map<?, ?>) o;
            if ("blocking".equals(x.get("level")) && Boolean.TRUE.equals(x.get("acceptable"))) {
                runs.accept(k.org.admin, run, UUID.fromString((String) x.get("id")), "Reviewed for the test");
            }
        }
        runs.approve(k.org.admin, run);
        if (finish) {
            runs.markPaid(k.org.admin, run, new PayrollRuns.PaidInput(null, "UTR000123"));
            Object state = db.inOwner(c -> Sql.one(c, "select state from app.payroll_run where id = ?", run).get("state"));
            if ("paid".equals(state)) {
                runs.release(k.org.admin, run);
            }
        }
    }

    /** The last tax projection on a person's payslips for the year, and the sum of their taxable pay and TDS. */
    private Map<String, Object> payslipFacts(Case k, UUID employment) {
        return db.inOwner(c -> {
            Map<String, Object> last = Sql.one(c, "select ps.info->'tax' as tax from app.payslip ps join app.payroll_run r on r.id = ps.run_id "
                    + "where ps.employment_id = ? order by r.period desc limit 1", employment);
            Map<String, Object> sums = Sql.one(c, "select coalesce(sum((ps.info->'bases'->>'taxable')::numeric), 0) as taxable from app.payslip ps "
                    + "where ps.employment_id = ?", employment);
            Map<String, Object> tds = Sql.one(c, "select coalesce(sum(l.amount), 0) as tds from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                    + "where ps.employment_id = ? and l.code = 'TDS'", employment);
            return Map.of("tax", (Map<?, ?>) Json.parse(String.valueOf(last.get("tax"))), "taxable", new BigDecimal(String.valueOf(sums.get("taxable"))),
                    "tds", new BigDecimal(String.valueOf(tds.get("tds"))));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void partBStatesTheYearsLastProjectionAndWhatWasDeductedAndWaitsForAnApprover() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        if (!TODAY.withDayOfMonth(1).equals(MARCH)) {
            approve(k, TODAY.withDayOfMonth(1), true);
        }
        approve(k, MARCH, false);

        TaxCertificates.BuildResult built = certificates.build(k.org.admin, YEAR);
        assertEquals(2, built.built(), built.toString());
        assertEquals(0, built.alreadyReleased());
        // The person with no PAN gets none, and is told why.
        assertEquals(1, built.skipped().size());
        assertEquals("Nita Das", built.skipped().getFirst().name());
        assertTrue(built.skipped().getFirst().reason().contains("PAN"), built.skipped().toString());

        TaxCertificates.Year year = certificates.year(k.org.admin, YEAR);
        assertEquals(2, year.certificates().size());
        TaxCertificates.Row asha = year.certificates().stream().filter(r -> r.name().equals("Asha Rao")).findFirst().orElseThrow();
        assertEquals("draft", asha.status());
        assertTrue(asha.draftRules(), "the tax rules are draft data until the consultant signs them off");

        // Part B equals what payroll worked out: the last projection, tax deducted and the pay summed over the year.
        Map<String, Object> facts = payslipFacts(k, k.ashaEmployment);
        Map<?, ?> tax = (Map<?, ?>) facts.get("tax");
        assertEquals(0, new BigDecimal(String.valueOf(tax.get("annualTax"))).compareTo(asha.annualTax()), "annual tax is the projection's");
        assertEquals(0, new BigDecimal(String.valueOf(tax.get("taxableIncome"))).compareTo(asha.taxableIncome()));
        assertEquals(0, ((BigDecimal) facts.get("tds")).compareTo(asha.taxDeducted()), "deducted is the TDS the payslips took");
        assertTrue(asha.annualTax().signum() > 0, "a 3.6 crore salary pays tax even over one or two months");
        Map<String, Object> partB = db.inOwner(c -> (Map<String, Object>) Json.parse(String.valueOf(Sql.one(c, "select part_b from app.tax_certificate where id = ?::uuid", asha.id()).get("partB"))));
        assertEquals(0, ((BigDecimal) facts.get("taxable")).compareTo(new BigDecimal(String.valueOf(partB.get("salaryThisEmployer")))),
                "this employer's salary is summed over every month of the year");
        assertEquals("Acme Pvt Ltd", partB.get("employer"));
        assertEquals("BLRA12345B", partB.get("tan"));
        assertEquals("234F", partB.get("panLast4"), "the PAN is never written in full");

        // Nobody but payroll sees a draft; the employee sees nothing until it is released.
        assertEquals(0, certificates.mine(k.asha).size());
        assertEquals(404, assertThrows(ApiException.class, () -> certificates.mine(k.asha, UUID.fromString(asha.id()))).status());

        // Payroll opens a draft to check it, another Organization cannot, and building a draft again replaces it in place.
        assertEquals("application/pdf", certificates.file(k.operator, UUID.fromString(asha.id())).contentType());
        Case other = organization();
        assertEquals(404, assertThrows(ApiException.class, () -> certificates.file(other.org.admin, UUID.fromString(asha.id()))).status());
        assertEquals(2, certificates.build(k.operator, YEAR).built(), "a draft is rebuilt, not duplicated");
        assertEquals(2, certificates.year(k.org.admin, YEAR).certificates().size());
        assertEquals(403, assertThrows(ApiException.class, () -> certificates.release(k.operator, UUID.fromString(asha.id()), true)).status(),
                "preparing payroll is not approving it");

        // Release: not by the person who built it, and not on draft rules without an explicit yes.
        certificates.build(k.org.admin, YEAR);   // the administrator is now the one who built them
        assertEquals(409, assertThrows(ApiException.class, () -> certificates.release(k.org.admin, UUID.fromString(asha.id()), true)).status(),
                "the builder cannot release");
        SessionCtx approver = join(k.org, "approver", List.of("payroll_approver"));
        assertEquals(403, assertThrows(ApiException.class, () -> certificates.build(k.asha, YEAR)).status());
        ApiException draft = assertThrows(ApiException.class, () -> certificates.release(approver, UUID.fromString(asha.id()), false));
        assertEquals(409, draft.status());
        assertEquals("certificate.draft_rules", draft.code());
        TaxCertificates.Row released = certificates.release(approver, UUID.fromString(asha.id()), true);
        assertEquals("released", released.status());
        assertEquals(409, assertThrows(ApiException.class, () -> certificates.release(approver, UUID.fromString(asha.id()), true)).status());

        // The employee opens their own, and it is recorded; nobody else's, and nothing before release.
        assertEquals(1, certificates.mine(k.asha).size());
        DocumentService.Content pdf = certificates.mine(k.asha, UUID.fromString(asha.id()));
        assertEquals("application/pdf", pdf.contentType());
        assertTrue(new String(pdf.bytes(), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF"));
        assertEquals(2L, ((Number) db.inOwner(c -> Sql.one(c, "select count(*) as n from app.audit_event where action = 'tax_certificate.viewed' and target_id = ?::uuid",
                asha.id()).get("n"))).longValue());
        // (two openings are on record: payroll's check of the draft, and the employee's own)
        assertEquals(404, assertThrows(ApiException.class, () -> certificates.mine(k.ravi, UUID.fromString(asha.id()))).status());
        assertEquals(0, certificates.mine(k.ravi).size(), "Ravi's own is still a draft");

        // Building again leaves a released certificate alone, and the database refuses to change or delete it.
        TaxCertificates.BuildResult again = certificates.build(k.org.admin, YEAR);
        assertEquals(1, again.built());
        assertEquals(1, again.alreadyReleased());
        assertThrows(Exception.class, () -> db.inOwner(c -> Sql.update(c, "update app.tax_certificate set draft_rules = false where id = ?::uuid", asha.id())));
        assertThrows(Exception.class, () -> db.inOwner(c -> Sql.update(c, "delete from app.tax_certificate where id = ?::uuid", asha.id())));
    }

    @Test
    void aYearThatIsNotFinalYetGetsNoCertificateAndEachOrganizationSeesItsOwn() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = organization();
        Case other = organization();
        if (TODAY.withDayOfMonth(1).equals(MARCH)) {
            return;   // in March the current month is the final month, so there is no "not final" case to build
        }
        approve(k, TODAY.withDayOfMonth(1), false);
        TaxCertificates.BuildResult built = certificates.build(k.org.admin, YEAR);
        assertEquals(0, built.built());
        assertEquals(2, built.skipped().stream().filter(s -> s.reason().contains("not final")).count(), built.toString());
        assertEquals(0, certificates.year(k.org.admin, YEAR).certificates().size());
        assertEquals(0, certificates.year(other.org.admin, YEAR).certificates().size());
        assertEquals(403, assertThrows(ApiException.class, () -> certificates.year(k.asha, YEAR)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> certificates.year(k.org.admin, "26-27")).status());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "wage-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static NewEmployee person(String name, String pan, String uan, String account, SessionCtx user) {
        return new NewEmployee(name, null, "1995-04-12", "female", null, "9876543210", "permanent",
                TODAY.minusYears(1).toString(), null, null, null, "Analyst", null, null, user.membershipId().toString(),
                pan, null, null, uan, name, account, "HDFC0001234", null, null);
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
