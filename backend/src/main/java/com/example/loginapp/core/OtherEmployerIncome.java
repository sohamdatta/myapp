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

/**
 * Salary from a previous employer in the same tax year (Form 122, Part B). The employee declares it with the tax
 * already deducted; HR accepts it or returns it with what is wrong. Payroll counts it as income so far and tax paid
 * so far (and the PF and professional tax as already paid), so the months left are neither over- nor under-deducted
 * and the yearly professional tax ceiling holds across employers. Declared entries count until the window's
 * cut-over month, accepted ones from then on; a returned entry never counts.
 */
public final class OtherEmployerIncome {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Input(String taxYear, String employerName, String employerTan, Integer monthsWorked, BigDecimal taxableSalary,
                        BigDecimal pf, BigDecimal professionalTax, BigDecimal tds) {}

    public record Entry(String id, String taxYear, String employerName, String employerTan, int monthsWorked, BigDecimal taxableSalary,
                        BigDecimal pf, BigDecimal professionalTax, BigDecimal tds, String status, String reviewNote, String updatedAt) {}

    public record ReviewInput(String updatedAt) {}

    public record NoteInput(String note, String updatedAt) {}

    /** What payroll adds to the year so far for an employment. */
    record Counted(BigDecimal taxable, BigDecimal pf, BigDecimal professionalTax, BigDecimal tds, int employers, String basis) {
        static final Counted NONE = new Counted(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, "none");
    }

    private final Db db;
    private final Clock clock;

    public OtherEmployerIncome(Db db) {
        this(db, Clock.systemUTC());
    }

    public OtherEmployerIncome(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── the employee's own ──────────────────────────────────────────────

    public List<Entry> mine(SessionCtx ctx, String taxYear) {
        String ty = TaxYears.check(taxYear, TaxYears.of(today()));
        return db.inTenant(ctx.tenantId(), c -> list(c, me(c, ctx), ty));
    }

    public Entry add(SessionCtx ctx, Input in) {
        Clean v = clean(in, today());
        return db.inTenant(ctx.tenantId(), c -> {
            UUID employment = me(c, ctx);
            if (v.tan != null && Sql.one(c, "select 1 from app.tax_other_employer where employment_id = ? and tax_year = ? and employer_tan = ?",
                    employment, v.taxYear, v.tan) != null) {
                throw new ApiException(409, "tax.entry_duplicate", "That employer is already declared for this tax year; change that entry instead");
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.tax_other_employer (tenant_id, employment_id, tax_year, employer_name, employer_tan, months_worked, "
                    + "taxable_salary, pf, professional_tax, tds, declared_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), employment,
                    v.taxYear, v.name, v.tan, v.months, v.taxable, v.pf, v.pt, v.tds, ctx.membershipId()).get("id"));
            UserService.audit(c, ctx, "tax.other_employer_declared", "employment", employment, Map.of("taxYear", v.taxYear, "employer", v.name,
                    "taxable", v.taxable, "tds", v.tds));
            return one(c, id);
        });
    }

    /** An entry the employee may still change: one that is declared or was returned. A change goes back for review. */
    public Entry update(SessionCtx ctx, UUID id, Input in) {
        Clean v = clean(in, today());
        return db.inTenant(ctx.tenantId(), c -> {
            UUID employment = me(c, ctx);
            Map<String, Object> e = Sql.one(c, "select status from app.tax_other_employer where id = ? and employment_id = ? for update", id, employment);
            if (e == null) {
                throw ApiException.notFound("That entry");
            }
            if ("accepted".equals(e.get("status"))) {
                throw new ApiException(409, "tax.entry_accepted", "HR has accepted this entry; ask HR to return it if it must change");
            }
            Sql.update(c, "update app.tax_other_employer set tax_year = ?, employer_name = ?, employer_tan = ?, months_worked = ?, taxable_salary = ?, pf = ?, "
                    + "professional_tax = ?, tds = ?, status = 'declared', review_note = null, reviewed_by = null, reviewed_at = null, updated_at = now() "
                    + "where id = ?", v.taxYear, v.name, v.tan, v.months, v.taxable, v.pf, v.pt, v.tds, id);
            UserService.audit(c, ctx, "tax.other_employer_changed", "employment", employment, Map.of("taxYear", v.taxYear, "employer", v.name));
            return one(c, id);
        });
    }

    public void remove(SessionCtx ctx, UUID id) {
        db.inTenant(ctx.tenantId(), c -> {
            UUID employment = me(c, ctx);
            Map<String, Object> e = Sql.one(c, "select status from app.tax_other_employer where id = ? and employment_id = ? for update", id, employment);
            if (e == null) {
                throw ApiException.notFound("That entry");
            }
            if ("accepted".equals(e.get("status"))) {
                throw new ApiException(409, "tax.entry_accepted", "HR has accepted this entry; ask HR to return it if it must go");
            }
            Sql.update(c, "delete from app.tax_other_employer where id = ?", id);
            UserService.audit(c, ctx, "tax.other_employer_removed", "employment", employment, Map.of());
            return null;
        });
    }

    // ── HR ──────────────────────────────────────────────────────────────

    public List<Entry> employee(SessionCtx ctx, UUID employmentId, String taxYear) {
        needRead(ctx);
        String ty = TaxYears.check(taxYear, TaxYears.of(today()));
        return db.inTenant(ctx.tenantId(), c -> list(c, employmentId, ty));
    }

    /** HR accepts the figures as they stood when they looked (updatedAt), never an employee's own entry. */
    public Entry accept(SessionCtx ctx, UUID id, String updatedAt) {
        needManage(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = reviewable(c, ctx, id, updatedAt);
            Sql.update(c, "update app.tax_other_employer set status = 'accepted', review_note = null, reviewed_by = ?, reviewed_at = now() where id = ?",
                    ctx.membershipId(), id);
            Entry done = one(c, id);
            UserService.audit(c, ctx, "tax.other_employer_accepted", "employment", AuthService.uuid(e.get("employmentId")),
                    Map.of("employer", done.employerName(), "taxable", done.taxableSalary(), "tds", done.tds(), "pf", done.pf(), "pt", done.professionalTax()));
            return done;
        });
    }

    public Entry returnEntry(SessionCtx ctx, UUID id, String note, String updatedAt) {
        needManage(ctx);
        String n = note == null ? "" : note.trim();
        if (n.length() < 3 || n.length() > 400) {
            throw ApiException.badRequest("Say what is wrong with the entry, in a few words");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = reviewable(c, ctx, id, updatedAt);
            Sql.update(c, "update app.tax_other_employer set status = 'returned', review_note = ?, reviewed_by = ?, reviewed_at = now() where id = ?",
                    n, ctx.membershipId(), id);
            UserService.audit(c, ctx, "tax.other_employer_returned", "employment", AuthService.uuid(e.get("employmentId")), Map.of("note", n));
            return one(c, id);
        });
    }

    /** An entry waiting for review, as it was when the reviewer read it, and not the reviewer's own. */
    private Map<String, Object> reviewable(Connection c, SessionCtx ctx, UUID id, String updatedAt) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select t.employment_id, t.status, t.updated_at, e.membership_id from app.tax_other_employer t "
                + "join app.employment e on e.id = t.employment_id where t.id = ? for update of t", id);
        if (e == null) {
            throw ApiException.notFound("That entry");
        }
        if (ctx.membershipId().toString().equals(String.valueOf(e.get("membershipId")))) {
            throw new ApiException(403, "tax.own_entry", "Someone else in HR must review your own declaration");
        }
        if (!"declared".equals(e.get("status"))) {
            throw new ApiException(409, "tax.entry_state", "Only a declared entry can be reviewed");
        }
        if (updatedAt == null || !updatedAt.equals(String.valueOf(e.get("updatedAt")))) {
            throw new ApiException(409, "tax.entry_changed", "The employee changed this entry; read it again before reviewing");
        }
        return e;
    }

    // ── what payroll reads ──────────────────────────────────────────────

    /** The other-employer figures payroll counts for a month. */
    static Counted forPayroll(Connection c, UUID employmentId, LocalDate period) throws SQLException {
        String ty = TaxYears.of(period);
        Map<String, Object> w = Sql.one(c, "select verified_from from app.tax_window where tax_year = ?", ty);
        boolean verified = w != null && w.get("verifiedFrom") != null && !period.isBefore(LocalDate.parse((String) w.get("verifiedFrom")));
        Map<String, Object> t = Sql.one(c, "select coalesce(sum(taxable_salary), 0) as taxable, coalesce(sum(pf), 0) as pf, coalesce(sum(professional_tax), 0) as pt, "
                + "coalesce(sum(tds), 0) as tds, count(*) as n from app.tax_other_employer where employment_id = ? and tax_year = ? and "
                + (verified ? "status = 'accepted'" : "status in ('declared', 'accepted')"), employmentId, ty);
        int n = ((Number) t.get("n")).intValue();
        if (n == 0) {
            return Counted.NONE;
        }
        return new Counted(PayrollEngine.dec(t, "taxable"), PayrollEngine.dec(t, "pf"), PayrollEngine.dec(t, "pt"), PayrollEngine.dec(t, "tds"), n,
                verified ? "accepted" : "declared");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Clean(String taxYear, String name, String tan, int months, BigDecimal taxable, BigDecimal pf, BigDecimal pt, BigDecimal tds) {}

    private static Clean clean(Input in, LocalDate today) {
        if (in == null || in.employerName() == null || in.employerName().trim().length() < 2 || in.employerName().trim().length() > 160) {
            throw ApiException.badRequest("Give the previous employer's name");
        }
        String tan = in.employerTan() == null || in.employerTan().isBlank() ? null : in.employerTan().trim().toUpperCase();
        if (tan != null && !tan.matches("[A-Z]{4}[0-9]{5}[A-Z]")) {
            throw ApiException.badRequest("The TAN should look like ABCD12345E");
        }
        if (in.monthsWorked() == null || in.monthsWorked() < 1 || in.monthsWorked() > 12) {
            throw ApiException.badRequest("Months worked there is 1 to 12");
        }
        if (in.tds() != null && in.taxableSalary() != null && in.tds().compareTo(in.taxableSalary()) > 0) {
            throw ApiException.badRequest("The tax deducted cannot be more than the taxable salary");
        }
        return new Clean(TaxYears.check(in.taxYear(), TaxYears.of(today)), in.employerName().trim(), tan, in.monthsWorked(),
                money(in.taxableSalary(), "taxable salary"), money(in.pf(), "PF"), money(in.professionalTax(), "professional tax"), money(in.tds(), "tax deducted"));
    }

    private static BigDecimal money(BigDecimal v, String what) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (v.signum() < 0 || v.scale() > 2 || v.compareTo(new BigDecimal("99999999")) > 0) {
            throw ApiException.badRequest("The " + what + " must be an amount in rupees and paise, not negative");
        }
        return v.setScale(2);
    }

    private List<Entry> list(Connection c, UUID employment, String ty) throws SQLException {
        List<Entry> out = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select id from app.tax_other_employer where employment_id = ? and tax_year = ? order by created_at", employment, ty)) {
            out.add(one(c, AuthService.uuid(r.get("id"))));
        }
        return out;
    }

    private Entry one(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select id, tax_year, employer_name, employer_tan, months_worked, taxable_salary, pf, professional_tax, tds, status, "
                + "review_note, updated_at from app.tax_other_employer where id = ?", id);
        return new Entry(String.valueOf(r.get("id")), (String) r.get("taxYear"), (String) r.get("employerName"), (String) r.get("employerTan"),
                ((Number) r.get("monthsWorked")).intValue(), PayrollEngine.dec(r, "taxableSalary"), PayrollEngine.dec(r, "pf"),
                PayrollEngine.dec(r, "professionalTax"), PayrollEngine.dec(r, "tds"), (String) r.get("status"), (String) r.get("reviewNote"),
                String.valueOf(r.get("updatedAt")));
    }

    private static UUID me(Connection c, SessionCtx ctx) throws SQLException {
        if (ctx.membershipId() == null || !"tenant".equals(ctx.kind())) {
            throw new ApiException(403, "tenant.required", "Tax declarations are for employees");
        }
        Map<String, Object> e = Sql.one(c, "select id from app.employment where membership_id = ? and status = 'active' order by joining_date desc limit 1",
                ctx.membershipId());
        if (e == null) {
            throw new ApiException(409, "tax.not_employed", "Tax declarations are for people employed now");
        }
        return AuthService.uuid(e.get("id"));
    }

    private static void needRead(SessionCtx ctx) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        if (!ctx.reachesEveryone("compensation.read") && !ctx.reachesEveryone("payroll.read")) {
            throw ApiException.denied("compensation.read");
        }
    }

    private static void needManage(SessionCtx ctx) {
        needRead(ctx);
        if (!ctx.reachesEveryone("compensation.write") || ctx.membershipId() == null || ctx.isSupport()) {
            throw ApiException.denied("compensation.write");
        }
    }
}
