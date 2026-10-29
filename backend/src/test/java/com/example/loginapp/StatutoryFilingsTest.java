package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.example.loginapp.core.StatutoryFilings;
import com.example.loginapp.core.UserService;

/**
 * The PF ECR and ESI return files: built from a really calculated and approved month, with their pre-checks, the
 * encrypted file, the filing log and its maker-checker.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class StatutoryFilingsTest {

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
    private static StatutoryFilings filings;
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
            st.execute("drop database if exists hrms_test_filings with (force)");
            st.execute("create database hrms_test_filings");
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
        String url = "jdbc:postgresql://" + host + "/hrms_test_filings";
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
        filings = new StatutoryFilings(db, crypto);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    /** An Organization with a calculated and approved month: Asha (high pay), Ravi (low pay, ESI) and Meena (no UAN). */
    private record Month(Org org, SessionCtx operator, LocalDate period, UUID run, UUID asha, UUID ravi, UUID meena) {}

    private Month approvedMonth(boolean meenaHasUan, boolean approve) throws Exception {
        Org org = newOrg();
        SessionCtx operator = join(org, "payroll", List.of("payroll_operator"));
        SessionCtx a = join(org, "asha", List.of("employee"));
        SessionCtx r = join(org, "ravi", List.of("employee"));
        SessionCtx m = join(org, "meena", List.of("employee"));
        String loc = (String) settings.saveLocation(org.admin, null,
                new LocationInput("Bengaluru office", "Asia/Kolkata", "KA", null, null, null, null, "none", true)).get("id");
        String shift = (String) settings.saveShift(org.admin, null, new ShiftInput("General", "09:30", "18:30", 10, 480, 240, true)).get("id");
        settings.assign(org.admin, List.of(a.membershipId().toString(), r.membershipId().toString(), m.membershipId().toString()),
                UUID.fromString(loc), UUID.fromString(shift), List.of(0), TODAY.minusDays(120).toString());
        UUID asha = UUID.fromString((String) employees.add(org.admin, person("Asha Rao", "ABCPR1234F", "100200300400", "111111111111", a)).get("id"));
        UUID ravi = UUID.fromString((String) employees.add(org.admin, person("Ravi Kumar", "ABCPK1234F", "100200300401", "222222222222", r)).get("id"));
        UUID meena = UUID.fromString((String) employees.add(org.admin,
                person("Meena Iyer", "ABCPI1234F", meenaHasUan ? "100200300402" : null, "333333333333", m)).get("id"));
        employees.setIdentifier(org.admin, ravi, "esi_ip", "1234567890", null);

        Map<String, Object> setup = config.setup(operator);
        for (String[] reg : List.of(new String[] {"pf", null, "KABLR0012345000"}, new String[] {"esi", null, "53000123450001001"},
                new String[] {"pt", "KA", "PTKA-123456"}, new String[] {"lwf", "KA", "LWF-KA-998"})) {
            config.addRegistration(operator, new PayrollConfig.RegistrationInput(reg[0], reg[1], reg[2]));
        }
        String staff = (String) ((Map<?, ?>) ((List<?>) setup.get("structures")).get(0)).get("id");
        String from = TODAY.withDayOfMonth(1).toString();
        pay.revise(org.admin, asha, new PayrollPay.ReviseInput(from, staff, new BigDecimal("900000"), "Joining salary"));
        pay.revise(org.admin, ravi, new PayrollPay.ReviseInput(from, staff, new BigDecimal("216000"), "Joining salary"));
        pay.revise(org.admin, meena, new PayrollPay.ReviseInput(from, staff, new BigDecimal("600000"), "Joining salary"));

        LocalDate period = TODAY.withDayOfMonth(1).plusMonths(1);
        String group = (String) ((Map<?, ?>) ((List<?>) runs.list(operator).get("groups")).get(0)).get("id");
        UUID run = UUID.fromString((String) runs.open(operator, UUID.fromString(group), period.toString()).get("id"));
        runs.freeze(operator, run);
        runs.calculate(operator, run);
        if (approve) {
            for (Object o : (List<?>) runs.get(operator, run).get("exceptions")) {
                Map<?, ?> x = (Map<?, ?>) o;
                if ("blocking".equals(x.get("level")) && Boolean.TRUE.equals(x.get("acceptable"))) {
                    runs.accept(org.admin, run, UUID.fromString((String) x.get("id")), "Reviewed for the test");
                }
            }
            runs.approve(org.admin, run);
        }
        return new Month(org, operator, period, run, asha, ravi, meena);
    }

    // ── the PF ECR file ─────────────────────────────────────────────────

    @Test
    void theEcrHasOneElevenFieldLinePerMemberAndTiesToThePayslips() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        String month = YearMonth.from(m.period).toString();
        StatutoryFilings.BuildResult built = filings.build(m.operator, "pf_ecr", month);
        assertEquals(0, built.blocking(), String.valueOf(built.checks()));
        assertEquals(1, built.version());

        String text = new String(filings.download(m.operator, UUID.fromString(built.id())).bytes(), StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        assertEquals(3, lines.length, text);
        assertTrue(text.endsWith("\n"));

        Map<String, Object> asha = runs.employee(m.operator, m.run, m.asha);
        String[] f = lines[0].split("#~#", -1);
        assertEquals(11, f.length, lines[0]);
        assertEquals("100200300400", f[0]);
        assertEquals("Asha Rao", f[1]);
        assertEquals(whole((BigDecimal) asha.get("gross")), f[2]);
        assertEquals("15000", f[3], "EPF wages are restricted to the ceiling by default");
        assertEquals("15000", f[4]);
        assertEquals("15000", f[5]);
        assertEquals(whole(lineAmount(asha, "PF_EE")), f[6]);
        assertEquals("1250", f[7], "8.33% of 15,000 is the pension share");
        BigDecimal er = lineAmount(asha, "PF_ER");
        assertEquals(whole(er.subtract(new BigDecimal("1250"))), f[8]);
        assertEquals("0", f[9]);
        assertEquals("0", f[10]);

        // The totals tie to the run's own PF lines.
        StatutoryFilings.Totals totals = built.totals();
        assertEquals(3, totals.members());
        assertEquals(0, totals.contributions().compareTo(totals.runTotal()));
    }

    @Test
    void aMemberWithoutAUanBlocksTheFileUntilItIsFixedAndABetterVersionIsBuilt() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(false, true);
        String month = YearMonth.from(m.period).toString();
        StatutoryFilings.BuildResult first = filings.build(m.operator, "pf_ecr", month);
        assertEquals(1, first.blocking());
        assertTrue(String.valueOf(first.checks()).contains("uan_invalid"));
        assertTrue(String.valueOf(first.checks()).contains("Meena Iyer"));
        UUID firstId = UUID.fromString(first.id());
        ApiException blocked = assertThrows(ApiException.class, () -> filings.download(m.operator, firstId));
        assertEquals("filing.blocked", blocked.code());

        // HR adds the UAN; building again makes version 2 and the old version stays as it was.
        employees.setIdentifier(m.org.admin, m.meena, "uan", "100200300402", null);
        StatutoryFilings.BuildResult second = filings.build(m.operator, "pf_ecr", month);
        assertEquals(2, second.version());
        assertEquals(0, second.blocking(), String.valueOf(second.checks()));
        String text = new String(filings.download(m.operator, UUID.fromString(second.id())).bytes(), StandardCharsets.UTF_8);
        assertTrue(text.contains("100200300402#~#Meena Iyer"), text);
        assertEquals(2, filings.month(m.operator, month).filings().getFirst().versions().size());
    }

    @Test
    void theStoredFileIsEncryptedAndAnotherOrganizationCannotSeeIt() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        Month other = approvedMonth(true, true);
        StatutoryFilings.BuildResult built = filings.build(m.operator, "pf_ecr", YearMonth.from(m.period).toString());
        UUID id = UUID.fromString(built.id());
        byte[] stored = db.inOwner(c -> {
            try (var st = c.prepareStatement("select file_enc from app.statutory_filing where id = ?")) {
                st.setObject(1, id);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    return rs.getBytes(1);
                }
            }
        });
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("100200300400"), "the file is sealed at rest");
        ApiException e = assertThrows(ApiException.class, () -> filings.download(other.operator, id));
        assertEquals(404, e.status());
        assertEquals(0, db.inTenant(other.org.id, c -> Sql.query(c, "select 1 from app.statutory_filing")).size());
    }

    // ── the ESI file ────────────────────────────────────────────────────

    @Test
    void theEsiFileListsOnlyThoseCoveredAndTiesToTheRun() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        StatutoryFilings.BuildResult built = filings.build(m.operator, "esi", YearMonth.from(m.period).toString());
        assertEquals(0, built.blocking(), String.valueOf(built.checks()));
        String csv = new String(filings.download(m.operator, UUID.fromString(built.id())).bytes(), StandardCharsets.UTF_8);
        String[] lines = csv.replace("﻿", "").split("\r\n");
        assertEquals(2, lines.length, csv);   // the header and Ravi, the only person under the wage threshold
        assertTrue(lines[0].startsWith("IP Number,IP Name"));
        Map<String, Object> ravi = runs.employee(m.operator, m.run, m.ravi);
        String[] f = lines[1].split(",", -1);
        assertEquals("1234567890", f[0]);
        assertEquals("Ravi Kumar", f[1]);
        assertEquals(new BigDecimal(String.valueOf(ravi.get("daysPaid"))).setScale(0, RoundingMode.CEILING).toPlainString(), f[2]);
        assertTrue(new BigDecimal(f[3]).signum() > 0);
        assertEquals("0", f[4]);
        StatutoryFilings.Totals totals = built.totals();
        BigDecimal expected = lineAmount(ravi, "ESI_EE").add(lineAmount(ravi, "ESI_ER"));
        assertEquals(0, expected.compareTo(totals.runTotal()));
        assertEquals(0, expected.compareTo(totals.employeeShare().add(totals.employerShare())));
    }

    @Test
    void aCoveredMemberWithoutAnEsiNumberBlocksTheFile() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        db.inOwner(c -> Sql.update(c, "delete from app.statutory_id where kind = 'esi_ip' and tenant_id = ?", m.org.id));
        StatutoryFilings.BuildResult built = filings.build(m.operator, "esi", YearMonth.from(m.period).toString());
        assertEquals(1, built.blocking());
        assertTrue(String.valueOf(built.checks()).contains("ip_invalid"));
    }

    // ── what the file may be built from ─────────────────────────────────

    @Test
    void aMonthWithoutAnApprovedRunOrWithoutARegistrationCannotBeBuilt() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month calculatedOnly = approvedMonth(true, false);
        ApiException none = assertThrows(ApiException.class, () -> filings.build(calculatedOnly.operator, "pf_ecr", YearMonth.from(calculatedOnly.period).toString()));
        assertEquals("filing.no_run", none.code());

        Org bare = newOrg();
        SessionCtx operator = join(bare, "payroll", List.of("payroll_operator"));
        ApiException noReg = assertThrows(ApiException.class, () -> filings.build(operator, "esi", YearMonth.from(TODAY).toString()));
        assertEquals("filing.no_registration", noReg.code());
        assertEquals(404, assertThrows(ApiException.class, () -> filings.build(operator, "form_16", "2026-11")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> filings.build(operator, "esi", "next month")).status());
    }

    // ── the filing log ──────────────────────────────────────────────────

    @Test
    void filingIsRecordedByOnePersonApprovedByAnotherAndShowsDaysLate() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        String month = YearMonth.from(m.period).toString();
        UUID id = UUID.fromString((String) filings.build(m.operator, "pf_ecr", month).id());

        // Not before the file has been downloaded.
        StatutoryFilings.RecordInput input = new StatutoryFilings.RecordInput("TRRN-2610001", TODAY.toString(), new BigDecimal("4250"));
        assertEquals("filing.state", assertThrows(ApiException.class, () -> filings.record(m.operator, id, input)).code());
        filings.download(m.operator, id);

        assertEquals(400, assertThrows(ApiException.class, () -> filings.record(m.operator, id,
                new StatutoryFilings.RecordInput("TRRN-1", TODAY.plusDays(2).toString(), BigDecimal.TEN))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> filings.record(m.operator, id,
                new StatutoryFilings.RecordInput("", TODAY.toString(), BigDecimal.TEN))).status());
        filings.record(m.operator, id, input);

        // The operator has no approve permission at all; the person who recorded cannot approve their own entry either.
        assertEquals(403, assertThrows(ApiException.class, () -> filings.approve(m.operator, id)).status());
        filings.approve(m.org.admin, id);
        assertEquals("filing.state", assertThrows(ApiException.class, () -> filings.approve(m.org.admin, id)).code());

        StatutoryFilings.Filing version = filings.month(m.operator, month).filings().getFirst().versions().getFirst();
        assertEquals("filed", version.status());
        assertEquals("TRRN-2610001", version.referenceNumber());
        assertNotNull(version.daysLate());
        LocalDate due = YearMonth.from(m.period).plusMonths(1).atDay(15);
        assertEquals(Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(due, TODAY)), version.daysLate().longValue());

        // A person who both records and approves is stopped by the database too, not only by the service.
        UUID second = UUID.fromString((String) filings.build(m.operator, "pf_ecr", month).id());
        assertThrows(Exception.class, () -> db.inOwner(c -> Sql.update(c, "update app.statutory_filing set status = 'filed', "
                + "reference_number = 'X-123', paid_on = current_date, amount_paid = 1, recorded_by = generated_by, recorded_at = now(), "
                + "approved_by = generated_by, approved_at = now() where id = ?", second)));
    }

    @Test
    void anEsicReasonCodeCanBeChangedPerExitKindAndFallsBackToTheRule() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        List<StatutoryFilings.ReasonRow> before = filings.reasonMap(m.operator);
        assertTrue(before.contains(new StatutoryFilings.ReasonRow("retirement", 3)), String.valueOf(before));
        List<StatutoryFilings.ReasonRow> after = filings.setReason(m.operator, "retirement", 4);
        assertTrue(after.contains(new StatutoryFilings.ReasonRow("retirement", 4)));
        assertEquals(400, assertThrows(ApiException.class, () -> filings.setReason(m.operator, "retirement", 100)).status());
        assertTrue(filings.reasonMap(newOperator()).contains(new StatutoryFilings.ReasonRow("retirement", 3)), "another Organization keeps the default");
    }

    // ── what the first review found ─────────────────────────────────────

    @Test
    void aMonthCalculatedBeforeTheWageBasesWereRecordedIsBlockedNotFiledWithZeroWages() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        ownerRun(m.org.id, "update app.payslip_line set trace = trace - 'epfWages' - 'epsWages' - 'eps' - 'esiWages' where tenant_id = '" + m.org.id + "'");
        StatutoryFilings.BuildResult pf = filings.build(m.operator, "pf_ecr", YearMonth.from(m.period).toString());
        assertTrue(pf.checks().stream().anyMatch(c -> c.code().equals("calculated_before_r4")), String.valueOf(pf.checks()));
        assertTrue(pf.blocking() >= 3);
        assertEquals(0, pf.totals().members(), "nobody is written with zero wages");
        StatutoryFilings.BuildResult esi = filings.build(m.operator, "esi", YearMonth.from(m.period).toString());
        assertTrue(esi.checks().stream().anyMatch(c -> c.code().equals("calculated_before_r4")));
    }

    @Test
    void wagesThatDoNotGiveTheContributionsAreCaught() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        ownerRun(m.org.id, "update app.payslip_line set trace = jsonb_set(trace, '{epfWages}', '20000') where tenant_id = '" + m.org.id
                + "' and code = 'PF_EE' and trace ? 'epfWages'");
        StatutoryFilings.BuildResult built = filings.build(m.operator, "pf_ecr", YearMonth.from(m.period).toString());
        assertTrue(built.checks().stream().anyMatch(c -> c.code().equals("ee_mismatch") && c.level().equals("blocking")), String.valueOf(built.checks()));
    }

    @Test
    void aRuleWithoutTheReturnKeysIsRefusedInsteadOfDefaulted() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        String saved = db.inOwner(c -> String.valueOf(Sql.one(c, "select data from app.payroll_rule where statute = 'pf'").get("data")));
        try {
            ownerRun(null, "update app.payroll_rule set data = data - 'ecrSeparator' - 'dueDay' where statute = 'pf'");
            ApiException e = assertThrows(ApiException.class, () -> filings.build(m.operator, "pf_ecr", YearMonth.from(m.period).toString()));
            assertEquals("filing.rule_incomplete", e.code());
            assertTrue(e.getMessage().contains("ecrSeparator"), e.getMessage());
        } finally {
            db.inOwner(c -> Sql.update(c, "update app.payroll_rule set data = ?::jsonb where statute = 'pf'", saved));
        }
    }

    @Test
    void aPayGroupWithPeopleButNoRunBlocksTheFile() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        ownerRun(m.org.id, "with g as (insert into app.pay_group (tenant_id, name) values ('" + m.org.id + "', 'Contractors') returning id) "
                + "insert into app.pay_group_member (tenant_id, employment_id, group_id, valid_from) select '" + m.org.id + "', '" + m.asha
                + "', g.id, date '" + m.period + "' from g");
        StatutoryFilings.BuildResult built = filings.build(m.operator, "pf_ecr", YearMonth.from(m.period).toString());
        assertTrue(built.checks().stream().anyMatch(c -> c.code().equals("group_no_run") && "Contractors".equals(c.who())), String.valueOf(built.checks()));
        assertTrue(built.blocking() > 0);
    }

    @Test
    void theSameUanOrEsiNumberTwiceIsBlocked() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        ownerRun(m.org.id, "update app.statutory_id set value_enc = (select value_enc from app.statutory_id where kind = 'uan' and tenant_id = '"
                + m.org.id + "' order by created_at limit 1) where kind = 'uan' and tenant_id = '" + m.org.id + "'");
        StatutoryFilings.BuildResult built = filings.build(m.operator, "pf_ecr", YearMonth.from(m.period).toString());
        assertTrue(built.checks().stream().anyMatch(c -> c.code().equals("uan_duplicate")), String.valueOf(built.checks()));
    }

    @Test
    void onlyTheNewestVersionCanBeDownloadedAndOnlyOneFilingPerMonthIsRecorded() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        String month = YearMonth.from(m.period).toString();
        UUID v1 = UUID.fromString(filings.build(m.operator, "pf_ecr", month).id());
        UUID v2 = UUID.fromString(filings.build(m.operator, "pf_ecr", month).id());
        assertEquals("filing.superseded", assertThrows(ApiException.class, () -> filings.download(m.operator, v1)).code());
        filings.download(m.operator, v2);
        filings.record(m.operator, v2, new StatutoryFilings.RecordInput("TRRN-2610001", TODAY.toString(), new BigDecimal("4250")));
        filings.approve(m.org.admin, v2);

        // A later build for the same month cannot be recorded as a second filing.
        UUID v3 = UUID.fromString(filings.build(m.operator, "pf_ecr", month).id());
        filings.download(m.operator, v3);
        assertEquals("filing.already_filed", assertThrows(ApiException.class, () -> filings.record(m.operator, v3,
                new StatutoryFilings.RecordInput("TRRN-2610002", TODAY.toString(), new BigDecimal("4250")))).code());
        // Every step wrote an audit event, and none carries a UAN.
        List<Map<String, Object>> audit = db.inTenant(m.org.id, c -> Sql.query(c, "select action, detail from app.audit_event where action like 'filing.%'"));
        assertTrue(audit.stream().anyMatch(a -> "filing.built".equals(a.get("action"))));
        assertTrue(audit.stream().anyMatch(a -> "filing.recorded".equals(a.get("action"))));
        assertTrue(audit.stream().anyMatch(a -> "filing.approved".equals(a.get("action"))));
        assertFalse(audit.toString().contains("100200300400"));
    }

    @Test
    void twoPeopleBuildingAtOnceGetTwoVersionsNotAnError() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        String month = YearMonth.from(m.period).toString();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return filings.build(m.operator, "pf_ecr", month).version();
                }));
            }
            start.countDown();
            java.util.Set<Integer> versions = new java.util.TreeSet<>();
            for (var f : results) {
                versions.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(java.util.Set.of(1, 2), versions);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theDatabaseKeepsABuiltReturnAsItWasAndMovesItOnlyForward() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Month m = approvedMonth(true, true);
        UUID id = UUID.fromString(filings.build(m.operator, "pf_ecr", YearMonth.from(m.period).toString()).id());
        assertCheck(() -> db.inOwner(c -> Sql.update(c, "update app.statutory_filing set checks = '[]'::jsonb where id = ?", id)), "cannot be changed");
        assertCheck(() -> db.inOwner(c -> Sql.update(c, "update app.statutory_filing set totals = '{}'::jsonb where id = ?", id)), "cannot be changed");
        assertCheck(() -> db.inOwner(c -> Sql.update(c, "update app.statutory_filing set status = 'filed' where id = ?", id)), "in that order");
        assertCheck(() -> db.inOwner(c -> Sql.update(c, "delete from app.statutory_filing where id = ?", id)), "is kept");
    }

    /** The database refuses; its own wording is replaced by a generic message on the way out, so only the refusal is asserted. */
    private static void assertCheck(org.junit.jupiter.api.function.Executable work, String why) {
        assertThrows(Exception.class, work, why);
    }

    /** As the database owner, with triggers that protect approved payroll switched off for this one statement. */
    private static void ownerRun(UUID tenantId, String sql) throws Exception {
        db.inOwner(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("set local session_replication_role = replica");
                st.execute(sql);
            }
            return null;
        });
    }

    private SessionCtx newOperator() {
        return join(newOrg(), "payroll", List.of("payroll_operator"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String whole(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
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
        String slug = "filing-" + SEQUENCE.incrementAndGet();
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
