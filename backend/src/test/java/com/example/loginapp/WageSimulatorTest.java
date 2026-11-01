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
import com.example.loginapp.core.WageSimulator;
import com.example.loginapp.core.UserService;

/**
 * The Labour Codes what-if: the real engine on a person whose basic is a tenth of pay (so it adds conditional allowances
 * back) with a proposal that moves money into basic, and the checks that keep a proposal honest.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class WageSimulatorTest {

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
    private static WageSimulator simulator;
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
            st.execute("drop database if exists hrms_test_wagesim with (force)");
            st.execute("create database hrms_test_wagesim");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_wagesim";
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
        simulator = new WageSimulator(db, pay);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private record Case(Org org, SessionCtx operator, UUID ravi) {}

    private Case lowBasic() throws Exception {
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx r = join(org, "ravi", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Bengaluru office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(r.membershipId().toString()), UUID.fromString(loc), UUID.fromString(shift), List.of(0),
                TODAY.minusDays(120).toString());
        UUID ravi = UUID.fromString((String) employees.add(org.admin, person("Ravi Kumar", "ABCPK1234F", "100200300401", "222222222222", r)).get("id"));
        for (String[] reg : List.of(new String[] {"pf", null, "KABLR0012345000"}, new String[] {"esi", null, "53000123450001001"},
                new String[] {"pt", "KA", "PTKA-123456"}, new String[] {"lwf", "KA", "LWF-KA-998"})) {
            config.addRegistration(operator, new PayrollConfig.RegistrationInput(reg[0], reg[1], reg[2]));
        }
        Map<String, Object> low = config.saveStructure(operator, null, new PayrollConfig.StructureInput("Low basic", "ctc", null,
                List.of(Map.of("code", "BASIC", "formula", "CTC_MONTHLY * 0.1"), Map.of("code", "HRA"), Map.of("code", "SPECIAL"), Map.of("code", "PF_ER")),
                "SPECIAL", true));
        pay.revise(org.admin, ravi, new PayrollPay.ReviseInput(TODAY.withDayOfMonth(1).toString(), String.valueOf(low.get("id")),
                new BigDecimal("600000"), "Joining salary"));
        return new Case(org, operator, ravi);
    }

    private static WageSimulator.Input basic(String amount) {
        return new WageSimulator.Input(null, List.of(new WageSimulator.Change("BASIC", new BigDecimal(amount))));
    }

    @Test
    void movingPayIntoBasicStopsTheAddBackAndTheCurrentSideMatchesThePayScreen() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = lowBasic();
        WageSimulator.Result r = simulator.simulate(k.operator, k.ravi, basic("25000"));

        // The same figures as the Pay screen's breakup, so the two cannot drift apart.
        @SuppressWarnings("unchecked")
        Map<String, Object> breakup = (Map<String, Object>) pay.get(k.operator, k.ravi).get("breakup");
        assertEquals(0, ((BigDecimal) breakup.get("net")).compareTo(r.current().takeHome()), "current side equals the breakup");
        assertEquals(0, ((BigDecimal) breakup.get("employerCost")).compareTo(r.current().employerCostMonthly()));

        // Basic is a tenth of pay today, so conditional allowances are added back to reach half.
        assertTrue(r.current().toppedUp());
        assertEquals(0, new BigDecimal("5000").compareTo(r.current().wages()), r.current().toString());
        assertEquals(0, new BigDecimal("19700").compareTo(r.current().addedBack()));
        assertEquals(0, new BigDecimal("10.1").compareTo(r.current().wageShare()));

        // With 25,000 of basic, wages are above half of pay: nothing is added back, and the shares move.
        assertFalse(r.proposed().toppedUp());
        assertEquals(0, r.proposed().addedBack().signum());
        assertEquals(0, new BigDecimal("25000").compareTo(r.proposed().wages()));
        assertTrue(r.proposed().wageShare().compareTo(new BigDecimal("50")) >= 0, r.proposed().wageShare().toPlainString());
        assertEquals(0, r.current().employerCostYearly().compareTo(r.current().employerCostMonthly().multiply(BigDecimal.valueOf(12))));
        assertEquals("Low basic", r.structure());
        assertEquals(0, new BigDecimal("50000").compareTo(r.ctcMonthly()));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("draft")), "PF rules are draft data until signed off: " + r.warnings());
    }

    @Test
    void aProposalThatCannotBeRunIsRefusedInsteadOfIgnored() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = lowBasic();
        // Statutory, outside the structure, negative, absurd and empty proposals all stop with a 400.
        for (WageSimulator.Input bad : List.of(
                new WageSimulator.Input(null, List.of(new WageSimulator.Change("PF_ER", new BigDecimal("1000")))),
                new WageSimulator.Input(null, List.of(new WageSimulator.Change("CONVEYANCE", new BigDecimal("1000")))),
                new WageSimulator.Input(null, List.of(new WageSimulator.Change("BASIC", new BigDecimal("-1")))),
                new WageSimulator.Input(null, List.of(new WageSimulator.Change("BASIC", new BigDecimal("1000000000")))),
                new WageSimulator.Input(null, List.of()),
                new WageSimulator.Input("soon", List.of(new WageSimulator.Change("BASIC", BigDecimal.TEN))))) {
            assertEquals(400, assertThrows(ApiException.class, () -> simulator.simulate(k.operator, k.ravi, bad)).status(), bad.toString());
        }
        assertEquals(400, assertThrows(ApiException.class, () -> simulator.simulate(k.operator, k.ravi, null)).status());
    }

    @Test
    void itWarnsWhenTheTotalChangesOrAnAllowanceWouldGoNegativeAndTakesAMonth() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = lowBasic();
        // Fixing the balancing allowance as well as basic leaves total pay different from today: said plainly.
        WageSimulator.Result moved = simulator.simulate(k.operator, k.ravi, new WageSimulator.Input(null,
                List.of(new WageSimulator.Change("BASIC", new BigDecimal("25000")), new WageSimulator.Change("SPECIAL", new BigDecimal("1000")))));
        assertTrue(moved.warnings().stream().anyMatch(w -> w.startsWith("Total pay changes")), moved.warnings().toString());

        // More basic than the whole structure can pay leaves the balancing allowance negative: said, not hidden.
        WageSimulator.Result over = simulator.simulate(k.operator, k.ravi, basic("60000"));
        assertTrue(over.warnings().stream().anyMatch(w -> w.contains("would be negative")), over.warnings().toString());

        // A named month is used; the month is part of the answer.
        String next = YearMonth.from(TODAY).plusMonths(1).toString();
        assertEquals(next + "-01", simulator.simulate(k.operator, k.ravi, new WageSimulator.Input(next,
                List.of(new WageSimulator.Change("BASIC", new BigDecimal("25000"))))).period());
        assertEquals(YearMonth.from(TODAY) + "-01", simulator.simulate(k.operator, k.ravi, basic("25000")).period());

        // The same component twice is a mistake, not a last-one-wins.
        assertEquals(400, assertThrows(ApiException.class, () -> simulator.simulate(k.operator, k.ravi, new WageSimulator.Input(null,
                List.of(new WageSimulator.Change("BASIC", BigDecimal.TEN), new WageSimulator.Change("BASIC", BigDecimal.ONE))))).status());
    }

    @Test
    void itSavesNothingAndIsOnlyForThoseWhoMaySeePay() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Case k = lowBasic();
        String before = counts();
        simulator.simulate(k.operator, k.ravi, basic("25000"));
        assertEquals(before, counts(), "a what-if changes no pay, override or audit row");

        // An employee cannot use it; an unknown person is not found; nor can anyone look at their own pay.
        SessionCtx plain = join(k.org, "plain", List.of("employee"));
        assertEquals(403, assertThrows(ApiException.class, () -> simulator.simulate(plain, k.ravi, basic("25000"))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> simulator.simulate(k.operator, UUID.randomUUID(), basic("25000"))).status());
        UUID own = UUID.fromString((String) employees.add(k.org.admin, person("Pia Nair", "ABCPN1234F", "100200300402", "333333333333", k.operator)).get("id"));
        assertEquals(404, assertThrows(ApiException.class, () -> simulator.simulate(k.operator, own, basic("25000"))).status());

        // Another Organization's person is not found, and a person with no pay yet is told so.
        Case other = lowBasic();
        assertEquals(404, assertThrows(ApiException.class, () -> simulator.simulate(other.operator, k.ravi, basic("25000"))).status());
        UUID nopay = UUID.fromString((String) employees.add(k.org.admin, person("Sam Roy", "ABCPR9999F", "100200300403", "444444444444",
                join(k.org, "sam", List.of("employee")))).get("id"));
        assertEquals(409, assertThrows(ApiException.class, () -> simulator.simulate(k.operator, nopay, basic("25000"))).status());
    }

    private static String counts() throws Exception {
        return db.inOwner(c -> Sql.one(c, "select (select count(*) from app.compensation) as a, (select count(*) from app.compensation_override) as b, "
                + "(select count(*) from app.audit_event) as c").toString());
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
