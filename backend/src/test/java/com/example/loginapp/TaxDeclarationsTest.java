package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
import com.example.loginapp.core.Db;
import com.example.loginapp.core.DocumentService;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.EmployeeService.NewEmployee;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Json;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PayrollConfig;
import com.example.loginapp.core.PayrollEngine;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PayrollRuns;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.TaxDeclarations;
import com.example.loginapp.core.TaxDeclarations.ItemInput;
import com.example.loginapp.core.TaxDeclarations.WindowInput;
import com.example.loginapp.core.UserService;

/**
 * Income tax declarations and proofs: the limits on each claim and the HRA exemption, worked out
 * without a database; then, against PostgreSQL, the window, declaring, submitting, proofs, HR's
 * checks, and payroll counting declared amounts until the cut-over month and verified ones after.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class TaxDeclarationsTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);

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
    private static TaxDeclarations tax;
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
            st.execute("drop database if exists hrms_test_tax with (force)");
            st.execute("create database hrms_test_tax");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_tax";
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
        tax = new TaxDeclarations(db, new DocumentService(db, crypto));
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── the limits, without a database ──────────────────────────────────

    @Test
    void claimsAreCountedWithinTheirLimits() {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("basis", "declared");
        declared.put("sec80c", new BigDecimal("200000"));
        declared.put("nps", new BigDecimal("60000"));
        declared.put("healthSelf", new BigDecimal("30000"));
        declared.put("healthParents", new BigDecimal("40000"));
        declared.put("healthParentsSenior", true);
        declared.put("homeLoan", new BigDecimal("250000"));
        declared.put("eduLoan", new BigDecimal("30000"));
        PayrollEngine.Claims c = PayrollEngine.declarationClaims(oldRegime(), declared, new BigDecimal("21600"), new BigDecimal("450000"),
                BigDecimal.ZERO, 12);
        // ₹1.5 lakh at most for PF and the group together; NPS 50,000; health 25,000 + 40,000 (parents senior, up to 50,000);
        // home-loan interest 2 lakh; education-loan interest in full. No HRA received, so nothing exempt for rent.
        assertEquals(0, new BigDecimal("150000").compareTo((BigDecimal) c.detail().get("group")));
        assertEquals(0, new BigDecimal("50000").compareTo((BigDecimal) c.detail().get("nps")));
        assertEquals(0, new BigDecimal("65000").compareTo((BigDecimal) c.detail().get("health")));
        assertEquals(0, new BigDecimal("200000").compareTo((BigDecimal) c.detail().get("homeLoan")));
        assertEquals(0, new BigDecimal("30000").compareTo((BigDecimal) c.detail().get("eduLoan")));
        assertEquals(0, new BigDecimal("495000").compareTo(c.total()));
        assertFalse(c.missingLimits());

        // PF alone fits within the group, with room for what was declared.
        declared.put("sec80c", new BigDecimal("100000"));
        assertEquals(0, new BigDecimal("121600").compareTo((BigDecimal) PayrollEngine.declarationClaims(oldRegime(), declared,
                new BigDecimal("21600"), new BigDecimal("450000"), BigDecimal.ZERO, 12).detail().get("group")));
    }

    @Test
    void hraIsExemptAtTheLeastOfThreeAndTheCityDecidesTheRate() {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("basis", "declared");
        // ₹20,000 a month all year; basic 4.5 lakh, HRA 2.25 lakh.
        declared.put("rent", List.of(Map.of("monthly", new BigDecimal("20000"), "months", 12, "city", "Pune")));
        PayrollEngine.Claims pune = PayrollEngine.declarationClaims(oldRegime(), declared, BigDecimal.ZERO, new BigDecimal("450000"),
                new BigDecimal("225000"), 12);
        // Rent above 10% of basic: 2,40,000 - 45,000 = 1,95,000; 50% of basic 2,25,000; HRA 2,25,000. Least: 1,95,000.
        assertEquals(0, new BigDecimal("195000").compareTo((BigDecimal) pune.detail().get("hraExempt")));
        declared.put("rent", List.of(Map.of("monthly", new BigDecimal("20000"), "months", 12, "city", "Jaipur")));
        PayrollEngine.Claims jaipur = PayrollEngine.declarationClaims(oldRegime(), declared, BigDecimal.ZERO, new BigDecimal("450000"),
                new BigDecimal("225000"), 12);
        // Outside the cities taking 50%: 40% of basic, 1,80,000, is the least.
        assertEquals(0, new BigDecimal("180000").compareTo((BigDecimal) jaipur.detail().get("hraExempt")));
        // Six months of rent count against six months of basic and HRA.
        declared.put("rent", List.of(Map.of("monthly", new BigDecimal("20000"), "months", 6, "city", "Pune")));
        assertEquals(0, new BigDecimal("97500").compareTo((BigDecimal) PayrollEngine.declarationClaims(oldRegime(), declared, BigDecimal.ZERO,
                new BigDecimal("450000"), new BigDecimal("225000"), 12).detail().get("hraExempt")));
    }

    @Test
    void rulesWithoutLimitsCountOnlyPf() {
        Map<String, Object> old = oldRegime();
        old.remove("npsMax");
        Map<String, Object> declared = new LinkedHashMap<>(Map.of("basis", "declared", "sec80c", new BigDecimal("50000"),
                "nps", new BigDecimal("50000")));
        PayrollEngine.Claims c = PayrollEngine.declarationClaims(old, declared, new BigDecimal("21600"), new BigDecimal("450000"),
                BigDecimal.ZERO, 12);
        assertTrue(c.missingLimits());
        assertEquals(0, new BigDecimal("21600").compareTo(c.total()));
    }

    // ── the whole year against the database ─────────────────────────────

    @Test
    void declaredThenVerifiedAmountsFeedTheMonthsTax() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx asha = join(org, "asha", List.of("employee"));
        SessionCtx ravi = join(org, "ravi", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Pune office", "Asia/Kolkata", "MH", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(asha.membershipId().toString()), UUID.fromString(loc), UUID.fromString(shift), List.of(0),
                TODAY.minusDays(400).toString());
        UUID ashaId = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", asha)).get("id"));
        employees.add(org.admin, person("Ravi Nair", "ABCPN1234F", ravi));
        Map<String, Object> setup = config.setup(operator);
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");
        pay.revise(org.admin, ashaId, new PayrollPay.ReviseInput(TODAY.minusYears(1).toString(), staff,
                new BigDecimal("1800000"), "Joining salary"));
        pay.saveProfile(org.admin, ashaId, new PayrollPay.ProfileInput(true, false, true, false, "old", false, false));

        // Nothing can be declared before HR opens the window.
        String year = (String) tax.mine(asha, null).get("taxYear");
        LocalDate start = LocalDate.of(Integer.parseInt(year.substring(0, 4)), 4, 1);
        LocalDate end = start.plusYears(1).minusDays(1);
        assertEquals(false, tax.mine(asha, null).get("declareOpen"));
        assertError(409, "tax.declarations_closed", () -> tax.addItem(asha, ppf("100000")));

        // HR opens it; an employee cannot, and nobody is left unaware.
        assertError(403, "permission.denied", () -> tax.saveWindow(asha, window(year, null, null)));
        LocalDate from = TODAY.minusDays(1).isBefore(start) ? start : TODAY.minusDays(1);
        LocalDate until = TODAY.plusDays(10).isAfter(end) ? end : TODAY.plusDays(10);
        tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), null, null, null));
        assertTrue(events(org, asha).contains("tax_window_open"));

        // Claims: rent needs the landlord's PAN above ₹1 lakh a year; the group needs a kind.
        String firstMonth = start.toString().substring(0, 7);
        String lastMonth = end.toString().substring(0, 7);
        Map<String, Object> rentDetails = new LinkedHashMap<>(Map.of("from", firstMonth, "to", lastMonth, "city", "Pune",
                "landlordName", "S. Kulkarni", "landlordAddress", "12 Law College Road, Pune"));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("rent", null, new BigDecimal("20000"), rentDetails)));
        rentDetails.put("landlordPan", "abcpk1234f");
        tax.addItem(asha, new ItemInput("rent", null, new BigDecimal("20000"), rentDetails));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("sec80c", null, new BigDecimal("1000"), Map.of())));
        tax.addItem(asha, ppf("100000"));
        Map<String, Object> mine = tax.addItem(asha, new ItemInput("nps", null, new BigDecimal("60000"), Map.of()));
        List<?> items = (List<?>) mine.get("items");
        assertEquals(3, items.size());
        assertEquals("ABCPK1234F", ((Map<?, ?>) ((Map<?, ?>) items.get(0)).get("details")).get("landlordPan"));

        // Payroll reads nothing until Asha certifies the declaration.
        UUID run = openRun(operator);
        assertNull(taxOf(operator, run, ashaId).get("declarationBasis"));
        tax.submit(asha);
        runs.reopen(operator, run, "Declarations submitted");
        Map<String, Object> declared = taxOf(operator, run, ashaId);
        assertEquals("declared", declared.get("declarationBasis"));
        Map<?, ?> claims = (Map<?, ?>) declared.get("claims");
        assertTrue(((BigDecimal) claims.get("hraExempt")).signum() > 0, String.valueOf(claims));
        assertEquals(0, new BigDecimal("50000").compareTo((BigDecimal) claims.get("nps")));

        // Proofs are not collected yet; then they are.
        UUID rent = UUID.fromString((String) ((Map<?, ?>) items.get(0)).get("id"));
        UUID ppf = UUID.fromString((String) ((Map<?, ?>) items.get(1)).get("id"));
        UUID nps = UUID.fromString((String) ((Map<?, ?>) items.get(2)).get("id"));
        assertError(409, "tax.proofs_closed", () -> tax.addProof(asha, ppf, "ppf.pdf", PDF));
        tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), from.toString(), until.toString(), null));
        Map<String, Object> withProof = tax.addProof(asha, ppf, "PPF passbook.pdf", PDF);
        tax.addProof(asha, nps, "NPS statement.pdf", PDF);
        Map<?, ?> ppfRow = (Map<?, ?>) ((List<?>) withProof.get("items")).get(1);
        assertEquals("submitted", ppfRow.get("proofStatus"));
        UUID file = UUID.fromString((String) ((Map<?, ?>) ((List<?>) ppfRow.get("proofs")).get(0)).get("id"));

        // The file: Asha and HR may open it; another employee may not even learn it exists.
        assertEquals("%PDF", new String(tax.proof(asha, file).bytes(), 0, 4, StandardCharsets.ISO_8859_1));
        assertEquals("application/pdf", tax.proof(hr, file).contentType());
        assertError(404, "not_found", () -> tax.proof(ravi, file));

        // HR's overview puts Asha first, with two proofs to check.
        Map<String, Object> overview = tax.overview(hr, year);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) overview.get("people")).get(0);
        assertEquals("Asha Rao", first.get("name"));
        assertEquals("to_check", first.get("state"));
        assertEquals(2L, ((Number) first.get("toCheck")).longValue());

        // HR accepts the PPF at what the passbook shows, and returns the NPS statement; an employee can do neither.
        assertError(403, "permission.denied", () -> tax.accept(ravi, ppf, new BigDecimal("80000")));
        assertError(400, "request.invalid", () -> tax.accept(hr, ppf, new BigDecimal("120000")));
        tax.accept(hr, ppf, new BigDecimal("80000"));
        assertError(409, "tax.not_waiting", () -> tax.accept(hr, rent, new BigDecimal("20000")));
        tax.returnProof(hr, nps, "The statement is for last year");
        assertTrue(events(org, asha).contains("tax_proof_returned"));
        assertError(409, "tax.accepted", () -> tax.addProof(asha, ppf, "more.pdf", PDF));

        // From the cut-over month, only what HR accepted counts: the PPF at 80,000; no rent, no NPS.
        tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), from.toString(), until.toString(),
                TODAY.toString().substring(0, 7)));
        runs.reopen(operator, run, "Proofs checked");
        Map<String, Object> verified = taxOf(operator, run, ashaId);
        assertEquals("verified", verified.get("declarationBasis"));
        Map<?, ?> counted = (Map<?, ?>) verified.get("claims");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) counted.get("hraExempt")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) counted.get("nps")));
        assertTrue(((BigDecimal) verified.get("taxableIncome")).compareTo((BigDecimal) declared.get("taxableIncome")) > 0);

        // Changing a checked claim sends it back to be checked again.
        Map<String, Object> changed = tax.updateItem(asha, ppf, ppf("90000"));
        assertEquals("submitted", ((Map<?, ?>) ((List<?>) changed.get("items")).get(1)).get("proofStatus"));
        assertError(404, "not_found", () -> tax.updateItem(ravi, ppf, ppf("1")));
    }

    @Test
    void employeesKeepToTheirOwnClaimsAndTheRules() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx asha = join(org, "asha", List.of("employee"));
        SessionCtx ravi = join(org, "ravi", List.of("employee"));
        SessionCtx manager = join(org, "manager", List.of("manager", "employee"));
        SessionCtx unemployed = join(org, "guest", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Pune office", "Asia/Kolkata", "MH", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(asha.membershipId().toString()), UUID.fromString(loc), UUID.fromString(shift), List.of(0),
                TODAY.minusDays(400).toString());
        UUID ashaId = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", asha)).get("id"));
        employees.add(org.admin, person("Ravi Nair", "ABCPN1234F", ravi));
        Map<String, Object> setup = config.setup(operator);
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");
        pay.revise(org.admin, ashaId, new PayrollPay.ReviseInput(TODAY.minusYears(1).toString(), staff, new BigDecimal("1800000"), "Joining salary"));
        pay.saveProfile(org.admin, ashaId, new PayrollPay.ProfileInput(true, false, true, false, "old", false, false));
        String year = (String) tax.mine(asha, null).get("taxYear");
        LocalDate start = LocalDate.of(Integer.parseInt(year.substring(0, 4)), 4, 1);
        LocalDate end = start.plusYears(1).minusDays(1);
        LocalDate from = TODAY.minusDays(1).isBefore(start) ? start : TODAY.minusDays(1);
        LocalDate until = TODAY.plusDays(10).isAfter(end) ? end : TODAY.plusDays(10);

        // Who may use declarations at all: employees only; HR's screens need salary access, not just a team.
        assertError(409, "tax.not_employed", () -> tax.mine(unemployed, null));
        assertError(400, "request.invalid", () -> tax.mine(asha, "2026"));
        assertError(403, "permission.denied", () -> tax.overview(manager, year));
        assertError(403, "permission.denied", () -> tax.employee(manager, ashaId, year));
        assertError(404, "not_found", () -> tax.employee(hr, UUID.randomUUID(), year));

        // The window itself is checked.
        assertError(400, "request.invalid", () -> tax.saveWindow(hr, new WindowInput(year, until.toString(), from.toString(), null, null, null)));
        assertError(400, "request.invalid", () -> tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), from.toString(), null, null)));
        assertError(400, "request.invalid", () -> tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), until.toString(), from.toString(), null)));
        assertError(400, "request.invalid", () -> tax.saveWindow(hr, new WindowInput(year, from.toString(), end.plusDays(1).toString(), null, null, null)));
        assertError(400, "request.invalid", () -> tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), null, null, "2020-01")));
        assertError(400, "request.invalid", () -> tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), null, null, "Feb")));
        assertError(400, "request.invalid", () -> tax.saveWindow(hr, new WindowInput("2026", from.toString(), until.toString(), null, null, null)));
        tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), null, null, null));

        // Claims are checked: the heads, amounts, months, PANs and lengths.
        String firstMonth = start.toString().substring(0, 7);
        String lastMonth = end.toString().substring(0, 7);
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("lottery", null, new BigDecimal("1000"), Map.of())));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("nps", null, new BigDecimal("-5"), Map.of())));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("nps", null, new BigDecimal("10.555"), Map.of())));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("nps", null, new BigDecimal("100000000"), Map.of())));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("sec80c", "crypto", new BigDecimal("1000"), Map.of())));
        assertError(400, "request.invalid", () -> tax.addItem(asha, rent("April", lastMonth, null)));
        assertError(400, "request.invalid", () -> tax.addItem(asha, rent(firstMonth, "2020-13", null)));
        assertError(400, "request.invalid", () -> tax.addItem(asha, rent(lastMonth, firstMonth, null)));
        assertError(400, "request.invalid", () -> tax.addItem(asha, rent(start.minusMonths(1).toString().substring(0, 7), lastMonth, null)));
        assertError(400, "request.invalid", () -> tax.addItem(asha, rent(firstMonth, lastMonth, "NOTAPAN")));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("home_loan", null, new BigDecimal("150000"), Map.of())));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("home_loan", null, new BigDecimal("150000"),
                Map.of("lenderName", "HDFC Ltd", "lenderPan", "12345"))));
        assertError(400, "request.invalid", () -> tax.addItem(asha, new ItemInput("nps", null, new BigDecimal("1000"), Map.of("note", "x".repeat(201)))));
        // Scripts and SQL are stored as text and come back as text, never run.
        Map<String, Object> odd = tax.addItem(asha, new ItemInput("nps", null, new BigDecimal("1000"),
                Map.of("note", "<script>alert(1)</script>'; drop table app.tax_proof; --")));
        Map<?, ?> oddRow = (Map<?, ?>) ((List<?>) odd.get("items")).get(0);
        assertEquals("<script>alert(1)</script>'; drop table app.tax_proof; --", ((Map<?, ?>) oddRow.get("details")).get("note"));
        UUID oddId = UUID.fromString((String) oddRow.get("id"));

        // Every head the old regime takes, with its details.
        tax.addItem(asha, rent(firstMonth, lastMonth, "ABCPK1234F"));
        tax.addItem(asha, new ItemInput("home_loan", null, new BigDecimal("150000"),
                Map.of("lenderName", "HDFC Ltd", "lenderPan", "aaacH1234k", "lenderAddress", "Mumbai")));
        tax.addItem(asha, new ItemInput("health_self", null, new BigDecimal("20000"), Map.of("insurer", "Star Health")));
        tax.addItem(asha, new ItemInput("health_parents", null, new BigDecimal("40000"), Map.of("senior", true)));
        tax.addItem(asha, new ItemInput("edu_loan", null, new BigDecimal("30000"), Map.of("lenderName", "SBI")));
        Map<String, Object> all = tax.addItem(asha, ppf("100000"));
        assertEquals(7, ((List<?>) all.get("items")).size());
        UUID ppf = UUID.fromString((String) ((Map<?, ?>) ((List<?>) all.get("items")).get(6)).get("id"));

        // A claim keeps its kind; only its owner can change or remove it.
        assertError(400, "request.invalid", () -> tax.updateItem(asha, ppf, new ItemInput("nps", null, new BigDecimal("1000"), Map.of())));
        assertError(404, "not_found", () -> tax.removeItem(ravi, ppf));
        assertError(404, "not_found", () -> tax.removeItem(asha, UUID.randomUUID()));
        Map<String, Object> removed = tax.removeItem(asha, oddId);
        assertEquals(6, ((List<?>) removed.get("items")).size());

        // Payroll counts every head once the declaration is certified, including the senior parents' limit.
        tax.submit(asha);
        UUID run = openRun(operator);
        Map<?, ?> claims = (Map<?, ?>) taxOf(operator, run, ashaId).get("claims");
        // 20,000 for herself and all 40,000 for senior parents (their limit is 50,000, not 25,000).
        assertEquals(0, new BigDecimal("60000").compareTo((BigDecimal) claims.get("health")), String.valueOf(claims));
        assertEquals(0, new BigDecimal("150000").compareTo((BigDecimal) claims.get("homeLoan")), String.valueOf(claims));

        // Proofs: only after submitting, only files that really are PDF, JPEG or PNG, and only on your own claims.
        tax.saveWindow(hr, new WindowInput(year, from.toString(), until.toString(), from.toString(), until.toString(), null));
        assertError(409, "tax.not_submitted", () -> tax.addProof(ravi, ppf, "ppf.pdf", PDF));
        assertError(415, "document.type", () -> tax.addProof(asha, ppf, "ppf.pdf", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)));
        assertError(415, "document.type", () -> tax.addProof(asha, ppf, "ppf.exe", new byte[] { 0x4d, 0x5a, 0x00, 0x01 }));
        assertError(400, "document.empty", () -> tax.addProof(asha, ppf, "ppf.pdf", new byte[0]));
        assertError(404, "not_found", () -> tax.addProof(asha, UUID.randomUUID(), "ppf.pdf", PDF));
        Map<String, Object> proved = tax.addProof(asha, ppf, "PPF passbook.pdf", PDF);
        Map<?, ?> ppfRow = (Map<?, ?>) ((List<?>) proved.get("items")).get(5);
        UUID file = UUID.fromString((String) ((Map<?, ?>) ((List<?>) ppfRow.get("proofs")).get(0)).get("id"));
        for (int i = 1; i < 10; i++) {
            tax.addProof(asha, ppf, "page " + i + ".pdf", PDF);
        }
        assertError(409, "tax.too_many", () -> tax.addProof(asha, ppf, "page 11.pdf", PDF));

        // Removing a proof: not someone else's, not a file that is not there; the last one gone, the claim waits for none.
        assertError(404, "not_found", () -> tax.removeProof(ravi, ppf, file));
        assertError(404, "not_found", () -> tax.removeProof(asha, ppf, UUID.randomUUID()));
        Map<String, Object> fewer = tax.removeProof(asha, ppf, file);
        assertEquals(9, ((List<?>) ((Map<?, ?>) ((List<?>) fewer.get("items")).get(5)).get("proofs")).size());
        assertError(404, "not_found", () -> tax.proof(asha, file));

        // HR checks: never their own, never without a note when returning, never more than declared.
        assertError(400, "request.invalid", () -> tax.returnProof(hr, ppf, " "));
        assertError(400, "request.invalid", () -> tax.accept(hr, ppf, null));
        assertError(404, "not_found", () -> tax.accept(hr, UUID.randomUUID(), new BigDecimal("1")));
        tax.accept(hr, ppf, new BigDecimal("100000"));
        assertError(409, "tax.accepted", () -> tax.removeItem(asha, ppf));
        UUID anyProof = UUID.fromString((String) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) tax.mine(asha, null).get("items")).get(5))
                .get("proofs")).get(0)).get("id"));
        assertError(409, "tax.accepted", () -> tax.removeProof(asha, ppf, anyProof));

        // HR's own declaration is checked by someone else.
        UUID hrId = UUID.fromString((String) employees.add(org.admin, person("Hema Iyer", "ABCPI1234F", hr)).get("id"));
        pay.saveProfile(org.admin, hrId, new PayrollPay.ProfileInput(true, false, true, false, "old", false, false));
        UUID hrPpf = UUID.fromString((String) ((Map<?, ?>) ((List<?>) tax.addItem(hr, ppf("50000")).get("items")).get(0)).get("id"));
        tax.submit(hr);
        tax.addProof(hr, hrPpf, "ppf.pdf", PDF);
        assertError(409, "tax.own", () -> tax.accept(hr, hrPpf, new BigDecimal("50000")));
        tax.accept(org.admin, hrPpf, new BigDecimal("50000"));

        // Once declarations close, nothing more is added or removed.
        tax.saveWindow(hr, new WindowInput(year, start.toString(), start.toString(), from.toString(), until.toString(), null));
        if (TODAY.isAfter(start)) {
            assertError(409, "tax.declarations_closed", () -> tax.addItem(asha, ppf("1000")));
            assertError(409, "tax.declarations_closed", () -> tax.removeItem(asha, ppf));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ItemInput rent(String from, String to, String pan) {
        Map<String, Object> d = new LinkedHashMap<>(Map.of("from", from, "to", to, "city", "Pune",
                "landlordName", "S. Kulkarni", "landlordAddress", "12 Law College Road, Pune"));
        if (pan != null) {
            d.put("landlordPan", pan);
        }
        return new ItemInput("rent", null, new BigDecimal("20000"), d);
    }

    private static ItemInput ppf(String amount) {
        return new ItemInput("sec80c", "ppf", new BigDecimal(amount), Map.of());
    }

    private static WindowInput window(String year, String proofsFrom, String proofsUntil) {
        return new WindowInput(year, TODAY.toString(), TODAY.toString(), proofsFrom, proofsUntil, null);
    }

    /** Opens this month's run with Asha in it, frozen and calculated. */
    private static UUID openRun(SessionCtx operator) {
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(operator).get("groups")).get(0)).get("id");
        UUID run = UUID.fromString((String) runs.open(operator, UUID.fromString(group), TODAY.withDayOfMonth(1).toString()).get("id"));
        runs.freeze(operator, run);
        runs.calculate(operator, run);
        return run;
    }

    /** The tax projection on Asha's payslip, after freezing and calculating again. */
    private static Map<String, Object> taxOf(SessionCtx operator, UUID run, UUID employmentId) {
        if ("draft".equals(runs.get(operator, run).get("state"))) {
            runs.freeze(operator, run);
            runs.calculate(operator, run);
        }
        Map<String, Object> slip = runs.employee(operator, run, employmentId);
        return PayrollEngine.map(PayrollEngine.map(slip, "info"), "tax");
    }

    private static List<String> events(Org org, SessionCtx who) {
        return db.inTenant(org.id(), c -> Sql.query(c, "select event from app.notification where membership_id = ?", who.membershipId())
                .stream().map(r -> (String) r.get("event")).toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> oldRegime() {
        return (Map<String, Object>) Json.parse("""
            {"standardDeduction":50000,"section80cMax":150000,"npsMax":50000,
             "healthSelfMax":25000,"healthSelfSeniorMax":50000,"healthParentsMax":25000,"healthParentsSeniorMax":50000,
             "homeLoanInterestMax":200000,"hraRentFloorPct":10,"hraMetroPct":50,"hraOtherPct":40,
             "hraMetros":["Delhi","Mumbai","Kolkata","Chennai","Bengaluru","Hyderabad","Pune","Ahmedabad"],"landlordPanAbove":100000}
            """);
    }

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "tax-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static NewEmployee person(String name, String pan, SessionCtx user) {
        boolean asha = name.startsWith("Asha");
        return new NewEmployee(name, null, "1990-04-12", "female", null, "9876543210", "permanent",
                TODAY.minusYears(1).toString(), null, null, null, "Analyst", null, null, user.membershipId().toString(),
                pan, null, null, asha ? "100200300400" : null, asha ? name : null, asha ? "123456789012" : null, asha ? "HDFC0001234" : null,
                null, null);
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
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
