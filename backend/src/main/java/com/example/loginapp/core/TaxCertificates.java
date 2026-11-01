package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
                      BigDecimal taxableIncome, BigDecimal annualTax, BigDecimal taxDeducted, String generatedAt, String releasedAt,
                      Integer layoutVersion) {}

    public record Skipped(String code, String name, String reason) {}

    /** {@code layoutVersion} is the layout in force; a draft built with an older one is rebuilt to pick the new one up. */
    public record Year(String taxYear, List<Row> certificates, int released, boolean canRelease, int layoutVersion) {}

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
                    + "t.layout_version, e.employee_code from app.tax_certificate t join app.employment e on e.id = t.employment_id where t.tax_year = ? "
                    + "order by lower(t.part_b->>'name')", year)) {
                Map<String, Object> b = parse(t.get("partB"));
                boolean done = "released".equals(t.get("status"));
                released += done ? 1 : 0;
                rows.add(new Row(String.valueOf(t.get("id")), String.valueOf(t.get("employmentId")), (String) t.get("employeeCode"),
                        String.valueOf(b.get("name")), year, (String) t.get("status"), Boolean.TRUE.equals(t.get("draftRules")),
                        PayrollEngine.dec(b, "taxableIncome"), PayrollEngine.dec(b, "annualTax"), PayrollEngine.dec(b, "taxDeducted"),
                        String.valueOf(t.get("generatedAt")), t.get("releasedAt") == null ? null : String.valueOf(t.get("releasedAt")),
                        t.get("layoutVersion") == null ? null : ((Number) t.get("layoutVersion")).intValue()));
            }
            return new Year(year, rows, released, ctx.reachesEveryone("payroll.approve"), CertificateLayout.current(c).version());
        });
    }

    /** Builds (or rebuilds, while still a draft) the Part B of everyone whose year is final. Released ones are left alone. */
    public BuildResult build(SessionCtx ctx, String taxYear) {
        PayrollConfig.need(ctx, "payroll.prepare");
        String year = TaxYears.check(taxYear, TaxYears.of(LocalDate.now(clock)));
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> settings = Sql.one(c, "select entity_name, pan, tan, address from app.payroll_settings");
            if (settings == null || settings.get("tan") == null || settings.get("entityName") == null) {
                throw new ApiException(409, "certificate.no_deductor", "Add the Organization's name and TAN under payroll settings first");
            }
            AnnualTax.Result year0 = AnnualTax.compute(c, year, settings);
            List<Skipped> skipped = new ArrayList<>();
            for (AnnualTax.Skip k : year0.skipped()) {
                skipped.add(new Skipped(k.code(), k.name(), k.reason()));
            }
            int built = 0;
            int kept = 0;
            // The layout in force now; a certificate keeps the version it was built with, and a released one never changes.
            CertificateLayout.Layout layout = CertificateLayout.current(c);
            Pdf.Image logo = TaxCertificatePdf.logo(CertificateLayout.logo(c), layout);   // decoded once, not once per employee
            Integer layoutVersion = layout.version() == 0 ? null : layout.version();
            for (AnnualTax.Person p : year0.people()) {
                byte[] pdf = TaxCertificatePdf.render(p.partB, p.draftRules, layout, logo);
                String file = "Form 130 Part B " + year + " " + p.code + ".pdf";
                UUID employment = AuthService.uuid(p.employmentId);
                Map<String, Object> existing = Sql.one(c, "select status from app.tax_certificate where employment_id = ? and tax_year = ? for update",
                        employment, year);
                if (existing != null && "released".equals(existing.get("status"))) {
                    kept++;
                    continue;
                }
                if (existing == null) {
                    Sql.update(c, "insert into app.tax_certificate (tenant_id, employment_id, membership_id, tax_year, part_b, draft_rules, file_name, "
                            + "pdf_enc, generated_by, layout_version) values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)", ctx.tenantId(), employment,
                            p.membershipId == null ? null : AuthService.uuid(p.membershipId), year, Json.write(p.partB), p.draftRules, file,
                            crypto.encrypt(ctx.tenantId(), pdf), ctx.membershipId(), layoutVersion);
                } else {
                    Sql.update(c, "update app.tax_certificate set part_b = ?::jsonb, draft_rules = ?, file_name = ?, pdf_enc = ?, generated_by = ?, "
                            + "layout_version = ?, generated_at = now() where employment_id = ? and tax_year = ?", Json.write(p.partB), p.draftRules, file,
                            crypto.encrypt(ctx.tenantId(), pdf), ctx.membershipId(), layoutVersion, employment, year);
                }
                built++;
                UUID certificate = AuthService.uuid(Sql.one(c, "select id from app.tax_certificate where employment_id = ? and tax_year = ?", employment, year).get("id"));
                UserService.audit(c, ctx, "tax_certificate.built", "tax_certificate", certificate, Map.of("taxYear", year, "code", String.valueOf(p.code)));
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
        Map<String, Object> t = Sql.one(c, "select t.id, t.employment_id, t.tax_year, t.status, t.draft_rules, t.part_b, t.generated_at, t.released_at, t.layout_version, "
                + "e.employee_code from app.tax_certificate t join app.employment e on e.id = t.employment_id where t.id = ?", id);
        Map<String, Object> b = parse(t.get("partB"));
        return new Row(String.valueOf(t.get("id")), String.valueOf(t.get("employmentId")), (String) t.get("employeeCode"), String.valueOf(b.get("name")),
                (String) t.get("taxYear"), (String) t.get("status"), Boolean.TRUE.equals(t.get("draftRules")), PayrollEngine.dec(b, "taxableIncome"),
                PayrollEngine.dec(b, "annualTax"), PayrollEngine.dec(b, "taxDeducted"), String.valueOf(t.get("generatedAt")),
                t.get("releasedAt") == null ? null : String.valueOf(t.get("releasedAt")),
                t.get("layoutVersion") == null ? null : ((Number) t.get("layoutVersion")).intValue());
    }
}
