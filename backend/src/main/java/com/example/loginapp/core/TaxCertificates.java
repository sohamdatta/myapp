package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.loginapp.core.PayrollRuns.parse;

/**
 * Form 130 Part B (release R3): each employee's salary computation for a tax year, made from the year's approved
 * payslips and the last tax projection on them, then released by a second person to the employee's My pay.
 *
 * What it states is what payroll worked out, not a fresh computation: income, deductions, taxable income and tax for the
 * year are the last payslip's projection (so they equal it by construction), and tax deducted is what the payslips took
 * plus what other employers took (Form 122). The projection carries tax as one figure with rebate, surcharge and cess inside it,
 * so Part B shows them together. A year is built only when it is final: the tax projection on the last payslip has one month left to run (March, or the month
 * the person left). Someone with a final settlement, or two payslips in a month, is left out and told so, because those pay
 * lines are not carried here yet. Form 122 figures are taken as they stood at the last payslip: change them and build again. Part A (from TRACES) is not in this version.
 *
 * The tax rules behind the figures are draft data until the payroll consultant signs them off; a certificate says so and
 * cannot be released without the approver acknowledging it.
 */
public final class TaxCertificates {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** A certificate in the list for a tax year. */
    public record Row(String id, String employmentId, String code, String name, String taxYear, String status, boolean draftRules,
                      BigDecimal taxableIncome, BigDecimal annualTax, BigDecimal taxDeducted, String generatedAt, String releasedAt) {}

    public record Skipped(String code, String name, String reason) {}

    public record Year(String taxYear, List<Row> certificates, int released, boolean canRelease) {}

    public record BuildResult(String taxYear, int built, int alreadyReleased, List<Skipped> skipped) {}

    /** What an employee sees of their own: released certificates only. */
    public record Mine(String id, String taxYear, String fileName, String releasedAt) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public TaxCertificates(Db db, Crypto crypto) {
        this(db, crypto, Clock.system(IST));
    }

    public TaxCertificates(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    // ── payroll side ────────────────────────────────────────────────────

    public Year year(SessionCtx ctx, String taxYear) {
        PayrollConfig.need(ctx, "payroll.prepare");
        String year = TaxYears.check(taxYear, TaxYears.of(LocalDate.now(clock)));
        return db.inTenant(ctx.tenantId(), c -> {
            List<Row> rows = new ArrayList<>();
            int released = 0;
            for (Map<String, Object> t : Sql.query(c, "select t.id, t.employment_id, t.status, t.draft_rules, t.part_b, t.generated_at, t.released_at, "
                    + "e.employee_code from app.tax_certificate t join app.employment e on e.id = t.employment_id where t.tax_year = ? "
                    + "order by lower(t.part_b->>'name')", year)) {
                Map<String, Object> b = parse(t.get("partB"));
                boolean done = "released".equals(t.get("status"));
                released += done ? 1 : 0;
                rows.add(new Row(String.valueOf(t.get("id")), String.valueOf(t.get("employmentId")), (String) t.get("employeeCode"),
                        String.valueOf(b.get("name")), year, (String) t.get("status"), Boolean.TRUE.equals(t.get("draftRules")),
                        PayrollEngine.dec(b, "taxableIncome"), PayrollEngine.dec(b, "annualTax"), PayrollEngine.dec(b, "taxDeducted"),
                        String.valueOf(t.get("generatedAt")), t.get("releasedAt") == null ? null : String.valueOf(t.get("releasedAt"))));
            }
            return new Year(year, rows, released, ctx.reachesEveryone("payroll.approve"));
        });
    }

    /** Builds (or rebuilds, while still a draft) the Part B of everyone whose year is final. Released ones are left alone. */
    public BuildResult build(SessionCtx ctx, String taxYear) {
        PayrollConfig.need(ctx, "payroll.prepare");
        String year = TaxYears.check(taxYear, TaxYears.of(LocalDate.now(clock)));
        LocalDate from = TaxYears.start(year);
        LocalDate to = from.plusYears(1);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> settings = Sql.one(c, "select entity_name, pan, tan, address from app.payroll_settings");
            if (settings == null || settings.get("tan") == null || settings.get("entityName") == null) {
                throw new ApiException(409, "certificate.no_deductor", "Add the Organization's name and TAN under payroll settings first");
            }
            Map<String, List<Map<String, Object>>> slips = new LinkedHashMap<>();
            for (Map<String, Object> p : Sql.query(c, "select ps.id, ps.employment_id, ps.membership_id, r.period, ps.info, "
                    + "coalesce((r.snapshot->'config'->'rules'->'drafts'->>'income_tax:IN')::boolean, true) as draft_rules from app.payslip ps "
                    + "join app.payroll_run r on r.id = ps.run_id where r.state in ('approved','paid','released') and r.period >= ? and r.period < ? "
                    + "order by ps.employment_id, r.period", from, to)) {
                slips.computeIfAbsent(String.valueOf(p.get("employmentId")), k -> new ArrayList<>()).add(p);
            }
            Map<String, Map<String, Object>> tds = tdsLines(c, from, to);
            List<Skipped> skipped = new ArrayList<>();
            int built = 0;
            int kept = 0;
            for (Map.Entry<String, List<Map<String, Object>>> e : slips.entrySet()) {
                List<Map<String, Object>> list = e.getValue();
                Map<String, Object> last = list.getLast();
                Map<String, Object> info = parse(last.get("info"));
                String name = String.valueOf(info.get("name"));
                String code = (String) info.get("code");
                String problem = problem(info, LocalDate.parse(String.valueOf(last.get("period"))));
                if (problem == null) {
                    problem = unsupported(c, e.getKey(), list);
                }
                if (problem != null) {
                    skipped.add(new Skipped(code, name, problem));
                    continue;
                }
                Map<String, Object> t = tds.get(String.valueOf(last.get("id")));
                BigDecimal taxable = BigDecimal.ZERO;
                for (Map<String, Object> p : list) {
                    taxable = taxable.add(PayrollEngine.dec(PayrollEngine.map(parse(p.get("info")), "bases"), "taxable"));
                }
                Map<String, Object> partB = partB(info, settings, year, taxable, t == null ? BigDecimal.ZERO : PayrollEngine.dec(t, "amount"));
                boolean draft = Boolean.TRUE.equals(last.get("draftRules"));   // the rules the last payslip's run was frozen with
                byte[] pdf = TaxCertificatePdf.render(partB, draft);
                String file = "Form 130 Part B " + year + " " + code + ".pdf";
                UUID employment = AuthService.uuid(e.getKey());
                Map<String, Object> existing = Sql.one(c, "select status from app.tax_certificate where employment_id = ? and tax_year = ? for update",
                        employment, year);
                if (existing != null && "released".equals(existing.get("status"))) {
                    kept++;
                    continue;
                }
                Object membership = last.get("membershipId");
                if (existing == null) {
                    Sql.update(c, "insert into app.tax_certificate (tenant_id, employment_id, membership_id, tax_year, part_b, draft_rules, file_name, "
                            + "pdf_enc, generated_by) values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)", ctx.tenantId(), employment,
                            membership == null ? null : AuthService.uuid(membership), year, Json.write(partB), draft, file,
                            crypto.encrypt(ctx.tenantId(), pdf), ctx.membershipId());
                } else {
                    Sql.update(c, "update app.tax_certificate set part_b = ?::jsonb, draft_rules = ?, file_name = ?, pdf_enc = ?, generated_by = ?, "
                            + "generated_at = now() where employment_id = ? and tax_year = ?", Json.write(partB), draft, file,
                            crypto.encrypt(ctx.tenantId(), pdf), ctx.membershipId(), employment, year);
                }
                built++;
                UUID certificate = AuthService.uuid(Sql.one(c, "select id from app.tax_certificate where employment_id = ? and tax_year = ?", employment, year).get("id"));
                UserService.audit(c, ctx, "tax_certificate.built", "tax_certificate", certificate, Map.of("taxYear", year, "code", String.valueOf(code)));
            }
            UserService.audit(c, ctx, "tax_certificate.build_run", "tax_year", ctx.tenantId(), Map.of("taxYear", year, "built", built, "skipped", skipped.size()));
            return new BuildResult(year, built, kept, skipped);
        });
    }

    /** Releases one draft to its employee. A different person from the one who built it; draft tax rules need an explicit yes. */
    public Row release(SessionCtx ctx, UUID id, boolean acknowledgeDraftRules) {
        PayrollConfig.need(ctx, "payroll.approve");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> t = Sql.one(c, "select id, status, draft_rules, generated_by, tax_year from app.tax_certificate where id = ? for update", id);
            if (t == null) {
                throw ApiException.notFound("The certificate");
            }
            if ("released".equals(t.get("status"))) {
                throw new ApiException(409, "certificate.released", "This certificate is already released");
            }
            if (ctx.membershipId() != null && ctx.membershipId().toString().equals(t.get("generatedBy"))) {
                throw new ApiException(409, "certificate.same_person", "Another person must release a certificate you built");
            }
            boolean draft = Boolean.TRUE.equals(t.get("draftRules"));
            if (draft && !acknowledgeDraftRules) {
                throw new ApiException(409, "certificate.draft_rules",
                        "The tax rules behind this certificate are not yet signed off by the payroll consultant. Release only if you accept that.");
            }
            Sql.update(c, "update app.tax_certificate set status = 'released', released_by = ?, released_at = now() where id = ?", ctx.membershipId(), id);
            UserService.audit(c, ctx, "tax_certificate.released", "tax_certificate", id,
                    Map.of("taxYear", String.valueOf(t.get("taxYear")), "draftRulesAcknowledged", draft));
            return row(c, id);
        });
    }

    /** The file for payroll to check before release. */
    public DocumentService.Content file(SessionCtx ctx, UUID id) {
        PayrollConfig.need(ctx, "payroll.prepare");
        return db.inTenant(ctx.tenantId(), c -> content(c, ctx, id, null));
    }

    // ── employee side ───────────────────────────────────────────────────

    public List<Mine> mine(SessionCtx ctx) {
        requireEmployee(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Mine> out = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, "select id, tax_year, file_name, released_at from app.tax_certificate "
                    + "where membership_id = ? and status = 'released' order by tax_year desc", ctx.membershipId())) {
                out.add(new Mine(String.valueOf(t.get("id")), (String) t.get("taxYear"), (String) t.get("fileName"), String.valueOf(t.get("releasedAt"))));
            }
            return out;
        });
    }

    /** Opens their own released certificate; every opening is recorded. Anyone else's, or a draft, is not found. */
    public DocumentService.Content mine(SessionCtx ctx, UUID id) {
        requireEmployee(ctx);
        return db.inTenant(ctx.tenantId(), c -> content(c, ctx, id, ctx.membershipId()));
    }

    // ── steps ───────────────────────────────────────────────────────────

    private static void requireEmployee(SessionCtx ctx) {
        if (ctx.membershipId() == null || !"tenant".equals(ctx.kind())) {
            throw new ApiException(403, "tenant.required", "Tax certificates are for employees");
        }
    }

    private DocumentService.Content content(Connection c, SessionCtx ctx, UUID id, UUID ownerOnly) throws SQLException {
        Map<String, Object> t = ownerOnly == null
                ? Sql.one(c, "select file_name, tax_year, status from app.tax_certificate where id = ?", id)
                : Sql.one(c, "select file_name, tax_year, status from app.tax_certificate where id = ? and membership_id = ? and status = 'released'",
                        id, ownerOnly);
        if (t == null) {
            throw ApiException.notFound("The certificate");
        }
        byte[] sealed;
        try (var st = c.prepareStatement("select pdf_enc from app.tax_certificate where id = ?")) {
            st.setObject(1, id);
            try (var rs = st.executeQuery()) {
                rs.next();
                sealed = rs.getBytes(1);
            }
        }
        UserService.audit(c, ctx, "tax_certificate.viewed", "tax_certificate", id, Map.of("taxYear", String.valueOf(t.get("taxYear")), "status", String.valueOf(t.get("status"))));
        return new DocumentService.Content("application/pdf", (String) t.get("fileName"), crypto.decrypt(ctx.tenantId(), sealed));
    }

    private Row row(Connection c, UUID id) throws SQLException {
        Map<String, Object> t = Sql.one(c, "select t.id, t.employment_id, t.tax_year, t.status, t.draft_rules, t.part_b, t.generated_at, t.released_at, "
                + "e.employee_code from app.tax_certificate t join app.employment e on e.id = t.employment_id where t.id = ?", id);
        Map<String, Object> b = parse(t.get("partB"));
        return new Row(String.valueOf(t.get("id")), String.valueOf(t.get("employmentId")), (String) t.get("employeeCode"), String.valueOf(b.get("name")),
                (String) t.get("taxYear"), (String) t.get("status"), Boolean.TRUE.equals(t.get("draftRules")), PayrollEngine.dec(b, "taxableIncome"),
                PayrollEngine.dec(b, "annualTax"), PayrollEngine.dec(b, "taxDeducted"), String.valueOf(t.get("generatedAt")),
                t.get("releasedAt") == null ? null : String.valueOf(t.get("releasedAt")));
    }

    /** The TDS line of every payslip in the year, by payslip id. A month with no tax to deduct has no line. */
    private static Map<String, Map<String, Object>> tdsLines(Connection c, LocalDate from, LocalDate to) throws SQLException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> l : Sql.query(c, "select l.payslip_id, l.amount from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                + "join app.payroll_run r on r.id = ps.run_id where l.code = 'TDS' and r.state in ('approved','paid','released') "
                + "and r.period >= ? and r.period < ?", from, to)) {
            out.put(String.valueOf(l.get("payslipId")), l);
        }
        return out;
    }

    /**
     * Pay that Part B cannot state correctly yet, so no certificate is made rather than a wrong one: two payslips in one month
     * (each projection ignores the other), and a final settlement, whose tax and encashment lines are typed on the exit and are
     * not payslip lines.
     */
    private static String unsupported(Connection c, String employment, List<Map<String, Object>> slips) throws SQLException {
        java.util.Set<Object> months = new java.util.HashSet<>();
        for (Map<String, Object> p : slips) {
            if (!months.add(p.get("period"))) {
                return "They have more than one payslip in a month, which Part B cannot combine yet";
            }
        }
        if (Sql.one(c, "select 1 from app.exit_settlement_line l join app.exit_case x on x.id = l.exit_case_id where x.employment_id = ?::uuid limit 1",
                employment) != null) {
            return "They have a final settlement, whose tax and pay lines Part B does not carry yet: prepare theirs by hand";
        }
        return null;
    }

    /**
     * Why this person cannot have a certificate yet, or null. The year is final when payroll's own projection has one month
     * left to run: March, or the month the person left. Any other last payslip leaves months the projection still guesses at.
     */
    static String problem(Map<String, Object> info, LocalDate lastPeriod) {
        if (info.get("panLast4") == null) {
            return "No PAN on record: add it under People, then build again";
        }
        Map<String, Object> tax = PayrollEngine.map(info, "tax");
        if (tax.isEmpty()) {
            return "Payroll computed no income tax for them, so there is nothing to certify";
        }
        int left = PayrollEngine.intOf(tax.get("monthsLeft"));
        if (left != 1) {
            return "The year is not final: their last payslip is for " + YearMonth.from(lastPeriod) + " and the tax projection still has "
                    + left + " months to run";
        }
        return null;
    }

    private static Map<String, Object> partB(Map<String, Object> info, Map<String, Object> settings, String year, BigDecimal thisEmployer,
                                             BigDecimal lastTds) {
        Map<String, Object> tax = PayrollEngine.map(info, "tax");
        BigDecimal income = PayrollEngine.dec(tax, "income");
        BigDecimal annualTax = PayrollEngine.dec(tax, "annualTax");
        BigDecimal deducted = PayrollEngine.dec(tax, "paidSoFar").add(lastTds);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("taxYear", year);
        b.put("employer", settings.get("entityName"));
        b.put("employerAddress", settings.get("address"));
        b.put("tan", settings.get("tan"));
        b.put("employerPan", settings.get("pan"));
        b.put("name", info.get("name"));
        b.put("code", info.get("code"));
        b.put("designation", info.get("designation"));
        b.put("panLast4", info.get("panLast4"));
        b.put("joinedOn", info.get("joinedOn"));
        b.put("leavingOn", info.get("leavingOn"));
        b.put("regime", tax.get("regime"));
        b.put("salaryThisEmployer", PayrollEngine.scale(thisEmployer));
        b.put("incomeOtherEmployers", PayrollEngine.scale(income.subtract(thisEmployer).max(BigDecimal.ZERO)));
        b.put("totalIncome", PayrollEngine.scale(income));
        b.put("deductions", PayrollEngine.scale(PayrollEngine.dec(tax, "deductions")));
        b.put("claims", tax.get("claims"));
        b.put("taxableIncome", PayrollEngine.scale(PayrollEngine.dec(tax, "taxableIncome")));
        b.put("annualTax", PayrollEngine.scale(annualTax));
        b.put("taxDeducted", PayrollEngine.scale(deducted));
        b.put("balance", PayrollEngine.scale(annualTax.subtract(deducted)));
        return b;
    }
}
