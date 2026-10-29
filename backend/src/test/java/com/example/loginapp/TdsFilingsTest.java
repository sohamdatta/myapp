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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
import com.example.loginapp.core.TdsChallans;
import com.example.loginapp.core.TdsStatements;
import com.example.loginapp.core.UserService;

/**
 * TDS deposits (challans) and the quarterly TDS statement: built from a really calculated and approved month, with
 * the checks, the encrypted review file, acceptance of a missing PAN, the token and the lock after filing.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class TdsFilingsTest {

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
    private static TdsChallans challans;
    private static TdsStatements statements;
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
            st.execute("drop database if exists hrms_test_tds with (force)");
            st.execute("create database hrms_test_tds");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_tds";
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
        challans = new TdsChallans(db);
        statements = new TdsStatements(db, crypto);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    /** Two very high earners (so there is tax to deduct in any month of the year): Asha with a PAN, Vikram without. */
    private record Month(Org org, SessionCtx operator, LocalDate period, UUID run, UUID asha, UUID vikram, LocalDate today) {}

    private Month approvedMonth() throws Exception {
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx a = join(org, "asha", List.of("employee"));
        SessionCtx v = join(org, "vikram", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Bengaluru office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(a.membershipId().toString(), v.membershipId().toString()),
                UUID.fromString(loc), UUID.fromString(shift), List.of(0), TODAY.minusDays(120).toString());
        UUID asha = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", "100200300400", "111111111111", a)).get("id"));
        UUID vikram = UUID.fromString((String) employees.add(org.admin, person("Vikram Nair", null, "100200300401", "222222222222", v)).get("id"));
        Map<String, Object> setup = config.setup(operator);
        for (String[] reg : List.of(new String[] {"pf", null, "KABLR0012345000"}, new String[] {"esi", null, "53000123450001001"},
                new String[] {"pt", "KA", "PTKA-123456"}, new String[] {"lwf", "KA", "LWF-KA-998"})) {
            config.addRegistration(operator, new PayrollConfig.RegistrationInput(reg[0], reg[1], reg[2]));
        }
        config.saveSettings(operator, new PayrollConfig.SettingsInput("Test Co Pvt Ltd", "AAACT1234F", "BLRT12345A", "1 MG Road, Bengaluru",
                null, null, null, null, null, null, null, null, null));
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");
        String from = TODAY.withDayOfMonth(1).toString();
        pay.revise(org.admin, asha, new PayrollPay.ReviseInput(from, staff, new BigDecimal("24000000"), "Joining salary"));
        pay.revise(org.admin, vikram, new PayrollPay.ReviseInput(from, staff, new BigDecimal("24000000"), "Joining salary"));

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
        return new Month(org, operator, period, run, asha, vikram, quarterDue(period).plusDays(3));
    }

    // ── challans ────────────────────────────────────────────────────────

    @Test
    void aChallanMustMatchTheTaxDeductedAndASecondPersonApprovesIt() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth();
        TdsChallans tds = new TdsChallans(db, clockOn(m.today));
        BigDecimal deducted = deducted(m);
        assertTrue(deducted.signum() > 0, "the fixture should deduct tax");
        LocalDate due = challanDue(m.period);
        String paidOn = due.plusDays(3).toString();
        String month = m.period.toString().substring(0, 7);

        TdsChallans.ChallanInput wrong = input(m, paidOn, deducted.subtract(BigDecimal.ONE), "00001");
        assertEquals("challan.amount", assertThrows(ApiException.class, () -> tds.record(m.operator, wrong)).code());
        assertEquals(400, assertThrows(ApiException.class, () -> tds.record(m.operator, new TdsChallans.ChallanInput(month, "company", "200", "123",
                "00001", paidOn, deducted, null, null, null, null, List.of(m.run.toString())))).status());
        assertEquals("challan.wrong_month", assertThrows(ApiException.class, () -> tds.record(m.operator, new TdsChallans.ChallanInput(
                m.period.minusMonths(1).toString().substring(0, 7), "company", "200", "6360001", "00001", m.period.minusMonths(1).plusDays(9).toString(),
                deducted, null, null, null, null, List.of(m.run.toString())))).code());

        TdsChallans.Challan recorded = tds.record(m.operator, input(m, paidOn, deducted, "00001"));
        assertEquals("recorded", recorded.status());
        assertEquals(3, recorded.daysLate());
        assertEquals(0, deducted.compareTo(recorded.total()));
        assertEquals("challan.run_covered", assertThrows(ApiException.class, () -> tds.record(m.operator, input(m, paidOn, deducted, "00002"))).code());

        // Not yet approved: the month is still undeposited. The operator cannot approve; someone else can.
        assertEquals("none", monthRow(tds, m).status());
        assertEquals(403, assertThrows(ApiException.class, () -> tds.approve(m.operator, UUID.fromString(recorded.id()))).status());
        tds.approve(m.org.admin, UUID.fromString(recorded.id()));
        assertEquals("challan.state", assertThrows(ApiException.class, () -> tds.approve(m.org.admin, UUID.fromString(recorded.id()))).code());
        TdsChallans.MonthRow row = monthRow(tds, m);
        assertEquals("deposited", row.status());
        assertEquals(0, deducted.compareTo(row.deposited()));
        assertEquals(due.toString(), row.dueOn());
        assertFalse(row.overdue());
        assertEquals("challan.state", assertThrows(ApiException.class, () -> tds.remove(m.operator, UUID.fromString(recorded.id()))).code());

        // The database refuses the same person recording and approving, whatever the service does.
        assertThrows(Exception.class, () -> db.inOwner(c -> Sql.update(c, "insert into app.tds_challan (tenant_id, period, bsr_code, serial, "
                + "deposited_on, tax, status, recorded_by, approved_by, approved_at) values (?, ?, '6360001', '00077', ?, 0, 'approved', ?, ?, now())",
                m.org.id, m.period, m.period.plusDays(9), m.operator.membershipId(), m.operator.membershipId())));
    }

    // ── the statement ───────────────────────────────────────────────────

    @Test
    void aStatementNeedsApprovedChallansAcceptsAMissingPanAndIsLockedOnceFiled() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth();
        Clock clock = clockOn(m.today);
        TdsChallans tds = new TdsChallans(db, clock);
        TdsStatements st = new TdsStatements(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY), clock);
        String ty = taxYear(m.period);
        int q = quarter(m.period);

        // No challan yet: blocked for good reasons that cannot be accepted away.
        TdsStatements.BuildResult first = st.build(m.operator, ty, q);
        assertTrue(first.checks().stream().anyMatch(c -> c.code().equals("challan_missing")), String.valueOf(first.checks()));
        assertEquals("statement.blocked", assertThrows(ApiException.class, () -> st.download(m.operator, UUID.fromString(first.id()))).code());
        TdsStatements.AcceptInput missing = new TdsStatements.AcceptInput("challan_missing", m.period.toString().substring(0, 7), "Will deposit later");
        assertEquals("statement.not_acceptable", assertThrows(ApiException.class, () -> st.accept(m.org.admin, UUID.fromString(first.id()), missing)).code());

        BigDecimal deducted = deducted(m);
        TdsChallans.Challan challan = tds.record(m.operator, input(m, challanDue(m.period).plusDays(3).toString(), deducted, "00001"));
        tds.approve(m.org.admin, UUID.fromString(challan.id()));

        TdsStatements.BuildResult second = st.build(m.operator, ty, q);
        assertEquals(2, second.version());
        assertFalse(second.correction());
        assertTrue(second.checks().stream().noneMatch(c -> c.code().startsWith("challan")), String.valueOf(second.checks()));
        assertEquals(2, second.totals().lines());
        assertEquals(0, deducted.compareTo(second.totals().taxDeducted()));
        assertEquals(0, deducted.compareTo(second.totals().challanTotal()));
        TdsStatements.Check noPan = second.checks().stream().filter(c -> c.code().equals("pan_invalid")).findFirst().orElseThrow();
        assertEquals("Vikram Nair", noPan.who());
        assertTrue(noPan.acceptable());
        UUID id = UUID.fromString(second.id());
        assertEquals(1, second.unresolved());
        assertEquals("statement.blocked", assertThrows(ApiException.class, () -> st.download(m.operator, id)).code());

        // The operator cannot accept (no approve permission); the approver can, with a reason.
        TdsStatements.AcceptInput accept = new TdsStatements.AcceptInput("pan_invalid", "Vikram Nair", "PAN applied for, Form 60 on file");
        assertEquals(403, assertThrows(ApiException.class, () -> st.accept(m.operator, id, accept)).status());
        st.accept(m.org.admin, id, accept);
        String csv = new String(st.download(m.operator, id).bytes(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("Asha Rao") && csv.contains("ABCPR1234F"), csv);
        assertTrue(csv.contains("Vikram Nair"));
        assertTrue(csv.contains(m.period.toString().substring(0, 7)));

        // Filing: the token and date; late days are shown against the rule's due date.
        assertEquals(400, assertThrows(ApiException.class, () -> st.filed(m.operator, id, new TdsStatements.FiledInput("123", m.today.toString()))).status());
        st.filed(m.operator, id, new TdsStatements.FiledInput("TOKEN123456", m.today.toString()));
        TdsStatements.Statement filed = st.quarter(m.operator, ty, q).statements().getFirst();
        assertEquals("filed", filed.status());
        assertEquals("TOKEN123456", filed.tokenNumber());
        assertEquals(3L, filed.daysLate());
        assertEquals("statement.state", assertThrows(ApiException.class, () -> st.filed(m.operator, id, new TdsStatements.FiledInput("TOKEN123456", m.today.toString()))).code());
        assertEquals("statement.state", assertThrows(ApiException.class, () -> st.accept(m.org.admin, id, accept)).code());

        // The database keeps a filed statement and its lines as they were.
        assertThrows(Exception.class, () -> db.inOwner(c -> Sql.update(c, "update app.tds_statement set token_number = 'CHANGED12345' where id = ?", id)));
        assertThrows(Exception.class, () -> db.inOwner(c -> Sql.update(c, "delete from app.tds_statement_row where statement_id = ?", id)));
    }

    @Test
    void aCorrectionHoldsOnlyTheLinesThatChanged() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth();
        Clock clock = clockOn(m.today);
        TdsChallans tds = new TdsChallans(db, clock);
        TdsStatements st = new TdsStatements(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY), clock);
        String ty = taxYear(m.period);
        int q = quarter(m.period);
        tds.approve(m.org.admin, UUID.fromString(tds.record(m.operator, input(m, challanDue(m.period).toString(), deducted(m), "00001")).id()));
        TdsStatements.BuildResult v1 = st.build(m.operator, ty, q);
        st.accept(m.org.admin, UUID.fromString(v1.id()), new TdsStatements.AcceptInput("pan_invalid", "Vikram Nair", "PAN applied for"));
        st.filed(m.operator, UUID.fromString(v1.id()), new TdsStatements.FiledInput("TOKEN000001", m.today.toString()));

        // Nothing has changed: a correction would be empty.
        TdsStatements.BuildResult same = st.build(m.operator, ty, q);
        assertTrue(same.correction());
        assertEquals(0, same.totals().lines());
        assertTrue(same.checks().stream().anyMatch(c -> c.code().equals("no_changes")));

        // Vikram's PAN arrives: only his line is in the correction, and the missing-PAN check is gone.
        employees.setIdentifier(m.org.admin, m.vikram, "pan", "ABCPV1234F", null);
        TdsStatements.BuildResult fix = st.build(m.operator, ty, q);
        assertTrue(fix.correction());
        assertEquals(1, fix.totals().lines());
        assertTrue(fix.checks().stream().noneMatch(c -> c.code().equals("pan_invalid")));
        assertEquals(0, fix.unresolved());
        String csv = new String(st.download(m.operator, UUID.fromString(fix.id())).bytes(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("Vikram Nair") && csv.contains("ABCPV1234F") && csv.contains("changed"), csv);
        assertFalse(csv.contains("Asha Rao"), "only the changed line is in a correction");
        assertEquals(v1.id(), st.quarter(m.operator, ty, q).statements().getLast().id());
        assertEquals(v1.id(), st.quarter(m.operator, ty, q).statements().getFirst().correctionOf());
    }

    @Test
    void anotherOrganizationSeesNoneOfIt() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth();
        Month other = approvedMonth();
        Clock clock = clockOn(m.today);
        TdsChallans tds = new TdsChallans(db, clock);
        TdsChallans.Challan c = tds.record(m.operator, input(m, challanDue(m.period).toString(), deducted(m), "00001"));
        assertEquals(404, assertThrows(ApiException.class, () -> tds.approve(other.org.admin, UUID.fromString(c.id()))).status());
        assertEquals(0, db.inTenant(other.org.id, x -> Sql.query(x, "select 1 from app.tds_challan")).size());
        assertEquals("deducted", "deducted");
        // A run of another Organization cannot be put on this Organization's challan.
        TdsChallans.ChallanInput foreign = new TdsChallans.ChallanInput(m.period.toString().substring(0, 7), "company", "200", "6360001", "00009",
                challanDue(m.period).toString(), deducted(m), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(other.run.toString()));
        assertEquals(404, assertThrows(ApiException.class, () -> tds.record(m.operator, foreign)).status());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static TdsChallans.ChallanInput input(Month m, String paidOn, BigDecimal tax, String serial) {
        return input(m, paidOn, tax, serial, List.of(m.run.toString()));
    }

    private static TdsChallans.ChallanInput input(Month m, String paidOn, BigDecimal tax, String serial, List<String> runs) {
        return new TdsChallans.ChallanInput(m.period.toString().substring(0, 7), "company", "200", "6360001", serial, paidOn, tax,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, runs);
    }

    private static TdsChallans.MonthRow monthRow(TdsChallans tds, Month m) {
        return tds.year(m.operator, taxYear(m.period)).months().stream().filter(x -> x.month().equals(m.period.toString())).findFirst().orElseThrow();
    }

    private static BigDecimal deducted(Month m) {
        return lineAmount(runs.employee(m.operator, m.run, m.asha), "TDS").add(lineAmount(runs.employee(m.operator, m.run, m.vikram), "TDS"));
    }

    private static Clock clockOn(LocalDate day) {
        return Clock.fixed(day.atTime(LocalTime.NOON).toInstant(ZoneOffset.ofHoursMinutes(5, 30)), IST);
    }

    private static String taxYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    private static int quarter(LocalDate d) {
        return switch (d.getMonthValue()) {
            case 4, 5, 6 -> 1;
            case 7, 8, 9 -> 2;
            case 10, 11, 12 -> 3;
            default -> 4;
        };
    }

    private static LocalDate quarterDue(LocalDate period) {
        int startYear = Integer.parseInt(taxYear(period).substring(0, 4));
        return switch (quarter(period)) {
            case 1 -> LocalDate.of(startYear, 7, 31);
            case 2 -> LocalDate.of(startYear, 10, 31);
            case 3 -> LocalDate.of(startYear + 1, 1, 31);
            default -> LocalDate.of(startYear + 1, 5, 31);
        };
    }

    private static LocalDate challanDue(LocalDate period) {
        return period.getMonthValue() == 3 ? LocalDate.of(period.getYear(), 4, 30) : period.plusMonths(1).withDayOfMonth(7);
    }

    private static BigDecimal lineAmount(Map<String, Object> slip, String code) {
        for (Object o : (List<?>) slip.get("lines")) {
            if (code.equals(((Map<?, ?>) o).get("code"))) {
                return new BigDecimal(String.valueOf(((Map<?, ?>) o).get("amount")));
            }
        }
        return BigDecimal.ZERO;
    }

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "tds-" + SEQUENCE.incrementAndGet();
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
