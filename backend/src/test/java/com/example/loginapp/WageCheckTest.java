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
import com.example.loginapp.core.WageCheck;
import com.example.loginapp.core.UserService;

/**
 * The Labour Codes wage check: read from a really calculated and approved month, for a person whose basic is half of
 * pay and for one whose basic is a tenth of it (so the engine adds conditional allowances back).
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class WageCheckTest {

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
    private static WageCheck wageCheck;
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
            st.execute("drop database if exists hrms_test_wagecheck with (force)");
            st.execute("create database hrms_test_wagecheck");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_wagecheck";
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
        wageCheck = new WageCheck(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    private record Month(Org org, SessionCtx operator, SessionCtx asha, LocalDate period) {}

    private Month approvedMonth() throws Exception {
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx a = join(org, "asha", List.of("employee"));
        SessionCtx r = join(org, "ravi", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Bengaluru office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(a.membershipId().toString(), r.membershipId().toString()),
                UUID.fromString(loc), UUID.fromString(shift), List.of(0), TODAY.minusDays(120).toString());
        UUID asha = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", "100200300400", "111111111111", a)).get("id"));
        UUID ravi = UUID.fromString((String) employees.add(org.admin, person("Ravi Kumar", "ABCPK1234F", "100200300401", "222222222222", r)).get("id"));
        Map<String, Object> setup = config.setup(operator);
        for (String[] reg : List.of(new String[] {"pf", null, "KABLR0012345000"}, new String[] {"esi", null, "53000123450001001"},
                new String[] {"pt", "KA", "PTKA-123456"}, new String[] {"lwf", "KA", "LWF-KA-998"})) {
            config.addRegistration(operator, new PayrollConfig.RegistrationInput(reg[0], reg[1], reg[2]));
        }
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");
        // A structure whose basic is a tenth of cost to company, so most of the pay is conditional allowances.
        Map<String, Object> low = config.saveStructure(operator, null, new PayrollConfig.StructureInput("Low basic", "ctc", null,
                List.of(Map.of("code", "BASIC", "formula", "CTC_MONTHLY * 0.1"), Map.of("code", "HRA"), Map.of("code", "SPECIAL"), Map.of("code", "PF_ER")),
                "SPECIAL", true));
        String lowId = String.valueOf(low.get("id"));
        String from = TODAY.withDayOfMonth(1).toString();
        pay.revise(org.admin, asha, new PayrollPay.ReviseInput(from, staff, new BigDecimal("600000"), "Joining salary"));
        pay.revise(org.admin, ravi, new PayrollPay.ReviseInput(from, lowId, new BigDecimal("600000"), "Joining salary"));
        LocalDate period = TODAY.withDayOfMonth(1).plusMonths(1);
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(operator).get("groups")).get(0)).get("id");
        UUID run = UUID.fromString((String) runs.open(operator, UUID.fromString(group), period.toString()).get("id"));
        runs.freeze(operator, run);
        runs.calculate(operator, run);
        for (Object o : (List<?>) runs.get(operator, run).get("exceptions")) {
            Map<?, ?> x = (Map<?, ?>) o;
            if ("blocking".equals(x.get("level")) && Boolean.TRUE.equals(x.get("acceptable"))) {
                runs.accept(org.admin, run, UUID.fromString((String) x.get("id")), "Reviewed for the test");
            }
        }
        runs.approve(org.admin, run);
        return new Month(org, operator, a, period);
    }

    @Test
    void theCheckShowsWhatWasAddedBackToReachHalfOfPayAndWhatItAddedToPf() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth();
        WageCheck.View view = wageCheck.check(m.operator, null);
        assertEquals(m.period.toString(), view.period(), "the latest approved month when none is named");
        assertEquals(2, view.rows().size());
        assertTrue(view.draftRules());

        WageCheck.Row asha = view.rows().stream().filter(r -> r.name().equals("Asha Rao")).findFirst().orElseThrow();
        assertFalse(asha.toppedUp());
        assertEquals(0, asha.addedBack().signum());
        assertTrue(asha.wageShare().compareTo(new BigDecimal("50")) >= 0, asha.wageShare().toPlainString());

        // 50,000 a month less the 600 employer PF the structure's balancing allowance sees (PF on the 5,000 of basic,
        // before the add-back) is 49,400 of pay. Basic is 5,000 (10.1%), half of pay is 24,700, so 19,700 of the
        // conditional allowances count as wages. PF is on at most 15,000: 5,000 became 15,000, 10,000 more,
        // at 12% from each side.
        WageCheck.Row ravi = view.rows().stream().filter(r -> r.name().equals("Ravi Kumar")).findFirst().orElseThrow();
        assertTrue(ravi.toppedUp());
        assertEquals(0, new BigDecimal("49400").compareTo(ravi.remuneration()), ravi.toString());
        assertEquals(0, new BigDecimal("5000").compareTo(ravi.wages()));
        assertEquals(0, new BigDecimal("19700").compareTo(ravi.addedBack()));
        assertEquals(0, new BigDecimal("10.1").compareTo(ravi.wageShare()));
        assertEquals(0, new BigDecimal("2400").compareTo(ravi.pfExtra()));

        assertEquals(2, view.summary().people());
        assertEquals(1, view.summary().toppedUp());
        assertEquals(0, new BigDecimal("19700").compareTo(view.summary().addedBack()));
        assertEquals(0, new BigDecimal("2400").compareTo(view.summary().pfExtra()));

        // The same month named, a month without a run, and a bad month.
        assertEquals(2, wageCheck.check(m.operator, YearMonth.from(m.period).toString()).rows().size());
        assertEquals(0, wageCheck.check(m.operator, YearMonth.from(m.period).plusMonths(5).toString()).rows().size());
        assertEquals(400, assertThrows(ApiException.class, () -> wageCheck.check(m.operator, "soon")).status());
    }

    @Test
    void onlyPayrollPeopleSeeItAndEachOrganizationSeesItsOwn() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth();
        Month other = approvedMonth();
        assertEquals(403, assertThrows(ApiException.class, () -> wageCheck.check(m.asha, null)).status());
        assertEquals(2, wageCheck.check(other.operator, null).rows().size());
        Org empty = newOrg();
        assertEquals(0, wageCheck.check(empty.admin, null).rows().size());
        assertEquals(null, wageCheck.check(empty.admin, null).period());
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
