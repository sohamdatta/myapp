package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.example.loginapp.core.Formula;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Json;
import com.example.loginapp.core.LeaveAdminService;
import com.example.loginapp.core.LeaveAdminService.TypeInput;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PayrollConfig;
import com.example.loginapp.core.PayrollEngine;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PayrollRuns;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Payroll: the formula language, income tax, the worked example from the design, and a whole month
 * run against a real PostgreSQL (setup, pay, unpaid leave, freeze, calculate, maker-checker approval,
 * locking, bank file, journal, release and My pay).
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class PayrollTest {

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
    private static LeaveService leave;
    private static LeaveAdminService leaveAdmin;
    private static PayrollConfig config;
    private static PayrollPay pay;
    private static PayrollRuns runs;
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
            st.execute("drop database if exists hrms_test_payroll with (force)");
            st.execute("create database hrms_test_payroll");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        if (!reachable) {
            return;   // the pure tests below still run
        }
        String url = "jdbc:postgresql://" + host + "/hrms_test_payroll";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        employees = new EmployeeService(db, crypto);
        settings = new AttendanceSettingsService(db);
        leave = new LeaveService(db);
        leaveAdmin = new LeaveAdminService(db);
        config = new PayrollConfig(db);
        pay = new PayrollPay(db);
        runs = new PayrollRuns(db, crypto);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── the formula language ────────────────────────────────────────────

    @Test
    void formulasParseCheckAndEvaluate() {
        Map<String, Formula.Type> facts = PayrollEngine.FACTS;
        Set<String> codes = Set.of("BASIC", "HRA", "CTC_MONTHLY");
        Formula f = Formula.parse("BASIC * if(location.metro, 0.5, 0.4)");
        f.check(codes, facts);
        assertEquals(Set.of("BASIC"), f.components());
        Formula.Result r = f.evaluate(env(Map.of("BASIC", new BigDecimal("37500"), "location.metro", false)));
        assertEquals(0, new BigDecimal("15000").compareTo(r.value()));
        assertEquals(new BigDecimal("37500"), r.read().get("BASIC"));

        Formula g = Formula.parse("case(designation = \"Analyst\", 1600, designation = \"Manager\", 2400, 0) + round_up(17.2, 10)");
        g.check(codes, facts);
        assertEquals(0, new BigDecimal("1620").compareTo(g.evaluate(env(Map.of("designation", "analyst"))).value()));
        assertEquals(0, new BigDecimal("0").compareTo(Formula.parse("BASIC / 0").evaluate(env(Map.of("BASIC", BigDecimal.TEN))).value()));
        assertTrue(Formula.parse("BASIC / 0").evaluate(env(Map.of("BASIC", BigDecimal.TEN))).dividedByZero());

        assertFormulaError("BASIC + ", "ends too soon");
        assertFormulaError("Basic * 2", "Component codes are in capitals");
        assertFormulaError("MEAL * 2", "No component called MEAL");
        assertFormulaError("BASIC + location.metro", "works on numbers only");
        assertFormulaError("if(BASIC, 1, 2)", "yes-or-no condition");
        assertFormulaError("location.metro", "must give an amount");
        assertFormulaError("sqrt(BASIC)", "Unknown function sqrt");
        assertFormulaError("min(BASIC)", "min takes 2 to 20 values");
    }

    private static void assertFormulaError(String formula, String words) {
        Formula.Error e = assertThrows(Formula.Error.class, () -> Formula.parse(formula).check(Set.of("BASIC"), PayrollEngine.FACTS));
        assertTrue(e.getMessage().contains(words), e.getMessage());
    }

    private static Formula.Env env(Map<String, Object> values) {
        return new Formula.Env() {
            @Override
            public Object get(String name) {
                Object v = values.get(name);
                if (v == null) {
                    throw new IllegalArgumentException(name);
                }
                return v;
            }

            @Override
            public BigDecimal table(String name, String key) {
                return BigDecimal.ZERO;
            }

            @Override
            public BigDecimal ytd(String code) {
                return BigDecimal.ZERO;
            }
        };
    }

    // ── the worked example of the design, from a snapshot alone ─────────

    @Test
    void theWorkedExampleGivesTheDesignsFigures() {
        Map<String, Object> cfg = baseConfig();
        Map<String, Object> emp = asha(29, 2, "KA");
        PayrollEngine.Calc calc = PayrollEngine.calculate(cfg, emp, LocalDate.of(2026, 10, 1));
        assertEquals(List.of(), calc.exceptions().stream().filter(x -> "blocking".equals(x.get("level"))).toList());
        assertEquals(new BigDecimal("35081"), amount(calc, "BASIC"));
        assertEquals(new BigDecimal("14032"), amount(calc, "HRA"));
        assertEquals(new BigDecimal("19365"), amount(calc, "SPECIAL"));
        assertEquals(0, new BigDecimal("68478").compareTo(calc.gross()));
        assertEquals(new BigDecimal("1800"), amount(calc, "PF_EE"));
        assertEquals(new BigDecimal("1800"), amount(calc, "PF_ER"));
        assertEquals(0, new BigDecimal("200").compareTo(amount(calc, "PT")));
        assertEquals(BigDecimal.ZERO, amount(calc, "ESI_EE"));
        assertEquals(BigDecimal.ZERO, amount(calc, "TDS"));
        assertEquals(0, new BigDecimal("66478").compareTo(calc.net()));
        // The same snapshot always gives the same lines.
        assertEquals(Json.write(calc.lines()), Json.write(PayrollEngine.calculate(cfg, emp, LocalDate.of(2026, 10, 1)).lines()));

        // In February the Karnataka slab takes 300, and in Maharashtra a woman earning this much pays it too.
        assertEquals(0, new BigDecimal("300").compareTo(amount(PayrollEngine.calculate(cfg, asha(28, 0, "KA"), LocalDate.of(2027, 2, 1)), "PT")));
        Map<String, Object> mh = asha(31, 0, "MH");
        mh.put("gender", "female");
        assertEquals(0, new BigDecimal("200").compareTo(amount(PayrollEngine.calculate(cfg, mh, LocalDate.of(2026, 10, 1)), "PT")));
        // Labour welfare fund is due in Karnataka in December only.
        PayrollEngine.Calc december = PayrollEngine.calculate(cfg, asha(31, 0, "KA"), LocalDate.of(2026, 12, 1));
        assertEquals(0, new BigDecimal("50").compareTo(amount(december, "LWF_EE")));
        assertEquals(0, new BigDecimal("100").compareTo(amount(december, "LWF_ER")));
        // A state with no rules is refused, not guessed.
        PayrollEngine.Calc tn = PayrollEngine.calculate(cfg, asha(31, 0, "TN"), LocalDate.of(2026, 10, 1));
        assertTrue(tn.exceptions().stream().anyMatch(x -> "missing_state_pack".equals(x.get("code"))));
    }

    @Test
    void lowPayIsCoveredByEsiAndHighPayIsTaxed() {
        Map<String, Object> cfg = baseConfig();
        Map<String, Object> low = asha(31, 0, "KA");
        low.put("annual", new BigDecimal("240000"));   // 20,000 a month
        PayrollEngine.Calc calc = PayrollEngine.calculate(cfg, low, LocalDate.of(2026, 10, 1));
        BigDecimal gross = calc.gross();
        assertEquals(gross.multiply(new BigDecimal("0.0075")).setScale(0, RoundingMode.CEILING), amount(calc, "ESI_EE"));
        assertEquals(gross.multiply(new BigDecimal("0.0325")).setScale(0, RoundingMode.CEILING), amount(calc, "ESI_ER"));

        Map<String, Object> high = asha(31, 0, "KA");
        high.put("annual", new BigDecimal("3000000"));   // 30 lakh CTC
        PayrollEngine.Calc taxed = PayrollEngine.calculate(cfg, high, LocalDate.of(2026, 10, 1));
        assertTrue(amount(taxed, "TDS").signum() > 0);
        assertTrue(String.valueOf(taxed.info().get("tax")).contains("annualTax"));
    }

    @Test
    void incomeTaxFollowsTheSlabsRebateAndMarginalRelief() {
        @SuppressWarnings("unchecked")
        Map<String, Object> tax = (Map<String, Object>) PayrollEngine.map(PayrollEngine.map(baseConfig(), "rules"), "tax");
        Map<String, Object> regime = PayrollEngine.map(tax, "new");
        assertEquals(0, BigDecimal.ZERO.compareTo(PayrollEngine.incomeTax(regime, new BigDecimal("4"), new BigDecimal("1200000"))));
        // 12.5 lakh: tax 67,500 but no more than the 50,000 above 12 lakh; plus 4% cess.
        assertEquals(0, new BigDecimal("52000").compareTo(PayrollEngine.incomeTax(regime, new BigDecimal("4"), new BigDecimal("1250000"))));
        // 13 lakh: 75,000, below the 1 lakh above the limit; plus cess.
        assertEquals(0, new BigDecimal("78000").compareTo(PayrollEngine.incomeTax(regime, new BigDecimal("4"), new BigDecimal("1300000"))));
        Map<String, Object> old = PayrollEngine.map(tax, "old");
        // Old regime, 10 lakh: 12,500 + 1,00,000 = 1,12,500 plus cess.
        assertEquals(0, new BigDecimal("117000").compareTo(PayrollEngine.incomeTax(old, new BigDecimal("4"), new BigDecimal("1000000"))));
    }

    // ── a whole month against the database ──────────────────────────────

    @Test
    void aMonthIsRunApprovedByASecondPersonPaidAndReleased() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx asha = join(org, "asha", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Bengaluru office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(asha.membershipId().toString(), hr.membershipId().toString()), UUID.fromString(loc),
                UUID.fromString(shift), List.of(0), TODAY.minusDays(120).toString());
        UUID ashaId = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", asha)).get("id"));
        UUID hrId = UUID.fromString((String) employees.add(org.admin, person("Harish Rao", "ABCPH1234F", hr)).get("id"));

        // Setup: the defaults exist, the entity's registrations are added.
        Map<String, Object> setup = config.setup(operator);
        assertTrue(((List<?>) setup.get("components")).size() >= 14);
        assertEquals("Staff", ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("name"));
        for (String[] r : List.of(new String[] {"pf", null, "KABLR0012345000"}, new String[] {"esi", null, "53000123450001001"},
                new String[] {"pt", "KA", "PTKA-123456"}, new String[] {"lwf", "KA", "LWF-KA-998"})) {
            config.addRegistration(operator, new PayrollConfig.RegistrationInput(r[0], r[1], r[2]));
        }
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");

        // A structure whose formulas go round in a loop is refused, naming the loop.
        ApiException loop = assertThrows(ApiException.class, () -> config.saveStructure(operator, null, new PayrollConfig.StructureInput("Loop", "ctc",
                null, List.of(Map.of("code", "BASIC", "formula", "HRA * 2"), Map.of("code", "HRA", "formula", "BASIC / 2")), null, true)));
        assertTrue(loop.getMessage().contains("loop"), loop.getMessage());

        // HR enters pay, with a preview first; nobody enters their own.
        LocalDate period = TODAY.withDayOfMonth(1).plusMonths(1);
        Map<String, Object> preview = pay.preview(hr, ashaId, new PayrollPay.ReviseInput(period.toString(), staff, new BigDecimal("900000"), "Joining salary"));
        assertEquals(null, preview.get("before"));
        assertEquals(0, new BigDecimal("73200").compareTo((BigDecimal) PayrollEngine.map(preview, "after").get("gross")));
        pay.revise(hr, ashaId, new PayrollPay.ReviseInput(TODAY.withDayOfMonth(1).toString(), staff, new BigDecimal("900000"), "Joining salary"));
        assertError(404, "not_found", () -> pay.revise(hr, hrId, new PayrollPay.ReviseInput(TODAY.withDayOfMonth(1).toString(), staff,
                new BigDecimal("1200000"), "My own raise")));
        pay.revise(org.admin, hrId, new PayrollPay.ReviseInput(TODAY.withDayOfMonth(1).toString(), staff, new BigDecimal("1200000"), "Joining salary"));

        // Two days of unpaid leave next month.
        UUID unpaid = UUID.fromString((String) leaveAdmin.saveType(org.admin, null,
                new TypeInput("Leave without pay", "regular", false, "none", BigDecimal.ZERO, true, null, "lapse", "unpaid", false, null, true)).get("id"));
        LocalDate tue = period.with(TemporalAdjusters.firstInMonth(DayOfWeek.TUESDAY));
        UUID request = UUID.fromString((String) leave.apply(asha, unpaid, tue.toString(), tue.plusDays(1).toString(), null, "Family wedding").get("id"));
        leave.decide(org.admin, request, true, null);

        // The run: open, add a bonus, freeze, calculate.
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(operator).get("groups")).get(0)).get("id");
        UUID run = UUID.fromString((String) runs.open(operator, UUID.fromString(group), period.toString()).get("id"));
        runs.addInput(operator, run, new PayrollRuns.InputInput(hrId.toString(), "BONUS", new BigDecimal("10000"), "Festival bonus"));
        runs.freeze(operator, run);
        Map<String, Object> totals = runs.calculate(operator, run);
        assertEquals(2, ((Number) totals.get("people")).intValue());
        Map<String, Object> slip = runs.employee(operator, run, ashaId);
        int days = period.lengthOfMonth();
        assertEquals(0, new BigDecimal(days - 2).compareTo((BigDecimal) slip.get("daysPaid")));
        BigDecimal basic = new BigDecimal("37500").multiply(BigDecimal.valueOf(days - 2)).divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP);
        assertEquals(0, basic.compareTo(lineAmount(slip, "BASIC")));
        assertEquals(0, new BigDecimal("1800").compareTo(lineAmount(slip, "PF_EE")));
        BigDecimal pt = period.getMonthValue() == 2 ? new BigDecimal("300") : new BigDecimal("200");
        assertEquals(0, pt.compareTo(lineAmount(slip, "PT")));
        BigDecimal lwf = period.getMonthValue() == 12 ? new BigDecimal("50") : BigDecimal.ZERO;
        assertEquals(0, ((BigDecimal) slip.get("gross")).subtract(new BigDecimal("1800")).subtract(pt).subtract(lwf)
                .compareTo((BigDecimal) slip.get("net")));
        Map<?, ?> basicTrace = (Map<?, ?>) lineOf(slip, "BASIC").get("trace");
        assertEquals("CTC_MONTHLY * 0.5", basicTrace.get("formula"));
        assertTrue(String.valueOf(basicTrace.get("prorated")).contains((days - 2) + " of " + days));

        // Harish has no bank account or PAN in this test: blocking exceptions that the approver must accept.
        List<?> exceptions = (List<?>) runs.get(operator, run).get("exceptions");
        assertTrue(exceptions.stream().anyMatch(x -> "missing_bank".equals(((Map<?, ?>) x).get("code"))));

        // Maker-checker: whoever calculated cannot approve; the operator has no approve permission at all.
        assertError(403, "permission.denied", () -> runs.approve(operator, run));
        assertError(409, "payroll.blocked", () -> runs.approve(org.admin, run));
        for (Object o : exceptions) {
            Map<?, ?> x = (Map<?, ?>) o;
            if ("blocking".equals(x.get("level")) && Boolean.TRUE.equals(x.get("acceptable"))) {
                runs.accept(org.admin, run, UUID.fromString((String) x.get("id")), "Paid by cheque this month");
            }
        }
        runs.approve(org.admin, run);
        assertError(409, "payroll.not_draft", () -> runs.addInput(operator, run, new PayrollRuns.InputInput(ashaId.toString(), "BONUS",
                BigDecimal.ONE, null)));
        assertThrows(Exception.class, () -> db.inTenant(org.id, c -> Sql.update(c, "update app.payslip set net = 1 where run_id = ?", run)));
        assertError(409, "payroll.after_approved", () -> pay.revise(hr, ashaId, new PayrollPay.ReviseInput(period.toString(), staff,
                new BigDecimal("950000"), "Correction")));

        // Outputs: the bank file carries the full account number and the net; the journal balances.
        String bank = new String(runs.output(org.admin, run, "bank").bytes(), StandardCharsets.UTF_8);
        assertTrue(bank.contains("123456789012"), bank);
        assertTrue(bank.contains(PayrollRunsMoney.of((BigDecimal) slip.get("net"))), bank);
        assertFalse(bank.contains("Harish"), "no bank account, so not in the file");
        String journal = new String(runs.output(org.admin, run, "journal").bytes(), StandardCharsets.UTF_8);
        assertTrue(journal.contains("Salary payable"));
        assertTrue(new String(runs.output(operator, run, "register").bytes(), StandardCharsets.UTF_8).contains("Asha Rao"));

        // Paid and released: Asha sees her payslip, with its PDF, and nothing before release.
        assertTrue(((List<?>) runs.myPay(asha).get("payslips")).isEmpty());
        runs.markPaid(org.admin, run, new PayrollRuns.PaidInput(null, "UTR-PAYROLL-001"));
        runs.release(operator, run);
        List<?> mine = (List<?>) runs.myPay(asha).get("payslips");
        assertEquals(1, mine.size());
        UUID payslip = UUID.fromString((String) ((Map<?, ?>) mine.get(0)).get("id"));
        Map<String, Object> seen = runs.myPayslip(asha, payslip);
        assertFalse(String.valueOf(seen.get("lines")).contains("unrounded"));
        byte[] pdf = runs.myPayslipPdf(asha, payslip).bytes();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.ISO_8859_1));
        assertError(404, "not_found", () -> runs.myPayslip(hr, payslip));
    }

    @Test
    void heldPayIsWorkedOutButDoesNotBlockTheRun() {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx ravi = join(org, "ravi", List.of("employee"));
        UUID raviId = UUID.fromString((String) employees.add(org.admin, person("Ravi Nair", "ABCPN1234F", ravi)).get("id"));
        config.setup(operator);
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(operator).get("groups")).get(0)).get("id");
        config.addMembers(operator, UUID.fromString(group), List.of(), null);
        LocalDate period = TODAY.withDayOfMonth(1);
        UUID run = UUID.fromString((String) runs.open(operator, UUID.fromString(group), period.toString()).get("id"));
        runs.freeze(operator, run);

        // No salary set: a blocking exception that cannot be accepted.
        runs.calculate(operator, run);
        assertEquals(1L, ((Number) runs.get(operator, run).get("openBlocking")).longValue());

        // Held after the inputs are frozen, and recalculated: the problem stays visible, as a warning on held pay.
        runs.hold(operator, run, raviId, "Salary letter not signed yet", true);
        runs.calculate(operator, run);
        Map<String, Object> after = runs.get(operator, run);
        assertEquals(0L, ((Number) after.get("openBlocking")).longValue());
        Map<?, ?> x = (Map<?, ?>) ((List<?>) after.get("exceptions")).stream()
                .filter(e -> "not_assigned".equals(((Map<?, ?>) e).get("code"))).findFirst().orElseThrow();
        assertEquals("warning", x.get("level"));
        assertTrue(String.valueOf(x.get("message")).startsWith("Pay held."), String.valueOf(x.get("message")));
        assertEquals(1, ((List<?>) after.get("holds")).size());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The default configuration, as a frozen snapshot would carry it, built without a database. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseConfig() {
        String json = """
            {"settings":{"proration":"calendar","pfRestrict":true,"variancePct":10,"largeOneTime":25000,"metroLocations":[]},
             "components":{
               "BASIC":{"code":"BASIC","name":"Basic","kind":"earning","statutory":false,"position":10,"amountType":"formula","formula":"CTC_MONTHLY * 0.5","wage":"include","esiWage":true,"taxable":true,"prorate":true,"rounding":"nearest","oneTime":false},
               "HRA":{"code":"HRA","name":"House rent allowance","kind":"earning","statutory":false,"position":20,"amountType":"formula","formula":"BASIC * if(location.metro, 0.5, 0.4)","wage":"conditional","esiWage":true,"taxable":true,"prorate":true,"rounding":"nearest","oneTime":false},
               "SPECIAL":{"code":"SPECIAL","name":"Special allowance","kind":"earning","statutory":false,"position":30,"amountType":"formula","formula":"CTC_MONTHLY - BASIC - HRA - PF_ER","wage":"conditional","esiWage":true,"taxable":true,"prorate":true,"rounding":"nearest","oneTime":false},
               "PF_EE":{"code":"PF_EE","name":"Provident fund","kind":"deduction","statutory":true,"position":100,"amountType":"statutory","wage":"exclude","rounding":"nearest"},
               "ESI_EE":{"code":"ESI_EE","name":"ESI","kind":"deduction","statutory":true,"position":110,"amountType":"statutory","wage":"exclude","rounding":"up"},
               "PT":{"code":"PT","name":"Professional tax","kind":"deduction","statutory":true,"position":120,"amountType":"statutory","wage":"exclude","rounding":"none"},
               "LWF_EE":{"code":"LWF_EE","name":"Labour welfare fund","kind":"deduction","statutory":true,"position":130,"amountType":"statutory","wage":"exclude","rounding":"none"},
               "TDS":{"code":"TDS","name":"Income tax","kind":"deduction","statutory":true,"position":140,"amountType":"statutory","wage":"exclude","rounding":"nearest"},
               "PF_ER":{"code":"PF_ER","name":"Employer PF","kind":"employer","statutory":true,"position":200,"amountType":"statutory","wage":"exclude","rounding":"nearest"},
               "ESI_ER":{"code":"ESI_ER","name":"Employer ESI","kind":"employer","statutory":true,"position":210,"amountType":"statutory","wage":"exclude","rounding":"up"},
               "LWF_ER":{"code":"LWF_ER","name":"Employer LWF","kind":"employer","statutory":true,"position":220,"amountType":"statutory","wage":"exclude","rounding":"none"}},
             "structures":{"s1":{"id":"s1","name":"Staff","basis":"ctc","validFrom":"2025-04-01","balancing":"SPECIAL",
               "lines":[{"code":"BASIC"},{"code":"HRA"},{"code":"SPECIAL"},{"code":"PF_ER"}]}},
             "tables":{},
             "registrations":["pf","esi","pt:KA","lwf:KA","pt:MH","lwf:MH"],
             "rules":{
               "pf":{"employeeRate":12,"employerRate":12,"epsRate":8.33,"ceiling":15000},
               "esi":{"employeeRate":0.75,"employerRate":3.25,"threshold":21000,"thresholdDisability":25000,"lowWageDaily":176},
               "pt":{"KA":{"men":[{"from":0,"to":24999.99,"monthly":0},{"from":25000,"to":null,"monthly":200,"february":300}],"yearlyCeiling":2500},
                     "MH":{"men":[{"from":0,"to":7500,"monthly":0},{"from":7500.01,"to":10000,"monthly":175},{"from":10000.01,"to":null,"monthly":200,"february":300}],
                           "women":[{"from":0,"to":25000,"monthly":0},{"from":25000.01,"to":null,"monthly":200,"february":300}],"yearlyCeiling":2500}},
               "lwf":{"KA":{"employee":50,"employer":100,"months":[12]},"MH":{"employee":25,"employer":75,"months":[6,12]}},
               "tax":{"cess":4,
                 "new":{"standardDeduction":75000,"slabs":[{"upTo":400000,"rate":0},{"upTo":800000,"rate":5},{"upTo":1200000,"rate":10},{"upTo":1600000,"rate":15},{"upTo":2000000,"rate":20},{"upTo":2400000,"rate":25},{"upTo":null,"rate":30}],"rebateLimit":1200000,"rebateMax":60000,"rebateMarginalRelief":true,"surcharge":[{"above":5000000,"rate":10},{"above":10000000,"rate":15},{"above":20000000,"rate":25}]},
                 "old":{"standardDeduction":50000,"slabs":[{"upTo":250000,"rate":0},{"upTo":500000,"rate":5},{"upTo":1000000,"rate":20},{"upTo":null,"rate":30}],"rebateLimit":500000,"rebateMax":12500,"section80cMax":150000,"surcharge":[{"above":5000000,"rate":10}]}},
               "ruleIds":{},"sources":{},"drafts":{}}}
            """;
        return (Map<String, Object>) Json.parse(json);
    }

    private static Map<String, Object> asha(int paid, int lop, String state) {
        Map<String, Object> emp = new LinkedHashMap<>();
        emp.put("employmentId", UUID.randomUUID().toString());
        emp.put("name", "Asha");
        emp.put("type", "permanent");
        emp.put("gender", "male");
        emp.put("state", state);
        emp.put("metro", false);
        emp.put("structureId", "s1");
        emp.put("annual", new BigDecimal("900000"));
        emp.put("overrides", new LinkedHashMap<>());
        emp.put("inputs", new LinkedHashMap<>());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("pfMember", true);
        profile.put("epsEligible", true);
        profile.put("taxRegime", "new");
        emp.put("profile", profile);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("base", new BigDecimal(paid + lop));
        d.put("paid", new BigDecimal(paid));
        d.put("lop", new BigDecimal(lop));
        emp.put("days", d);
        emp.put("ytd", Map.of("taxable", BigDecimal.ZERO, "tds", BigDecimal.ZERO, "pt", BigDecimal.ZERO, "pfEe", BigDecimal.ZERO));
        emp.put("hasBank", true);
        emp.put("hasPan", true);
        emp.put("hasUan", true);
        return emp;
    }

    private static BigDecimal amount(PayrollEngine.Calc calc, String code) {
        for (Map<String, Object> l : calc.lines()) {
            if (code.equals(l.get("code"))) {
                return (BigDecimal) l.get("amount");
            }
        }
        return BigDecimal.ZERO;
    }

    private static Map<?, ?> lineOf(Map<String, Object> slip, String code) {
        for (Object o : (List<?>) slip.get("lines")) {
            if (code.equals(((Map<?, ?>) o).get("code"))) {
                return (Map<?, ?>) o;
            }
        }
        throw new AssertionError("no line " + code);
    }

    private static BigDecimal lineAmount(Map<String, Object> slip, String code) {
        for (Object o : (List<?>) slip.get("lines")) {
            if (code.equals(((Map<?, ?>) o).get("code"))) {
                return (BigDecimal) ((Map<?, ?>) o).get("amount");
            }
        }
        return BigDecimal.ZERO;
    }

    /** Money as the CSV files write it. */
    private static final class PayrollRunsMoney {
        static String of(BigDecimal v) {
            return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "payroll-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin, name + "@" + org.slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static NewEmployee person(String name, String pan, SessionCtx user) {
        boolean bank = name.startsWith("Asha");
        return new NewEmployee(name, null, "1995-04-12", "female", null, "9876543210", "permanent",
                TODAY.minusYears(1).toString(), null, null, null, "Analyst", null, null, user.membershipId().toString(),
                bank ? pan : null, null, null, bank ? "100200300400" : null,
                bank ? name : null, bank ? "123456789012" : null, bank ? "HDFC0001234" : null, null, null);
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
