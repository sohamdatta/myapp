package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Each employee's pay: the structure and starting figure from a date, overrides of single
 * components, and the statutory profile (PF, ESI, tax regime). HR Admins and the payroll roles
 * enter it; nobody enters their own. Every change shows its impact before it is saved: the monthly
 * breakup before and after, worked out by the same engine a run uses.
 */
public final class PayrollPay {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record ReviseInput(String validFrom, String structureId, BigDecimal annualAmount, String reason) {}

    public record OverrideInput(String componentCode, BigDecimal monthlyAmount, String validFrom, String validTo, String reason) {}

    public record ProfileInput(Boolean pfMember, Boolean pfFullWages, Boolean epsEligible, Boolean esiExempt, String taxRegime, Boolean ptExempt,
                               Boolean lwfExempt) {}

    private final Db db;
    private final Clock clock;

    public PayrollPay(Db db) {
        this(db, Clock.systemUTC());
    }

    public PayrollPay(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    static Map<String, Object> defaultProfile(String employmentType) {
        Map<String, Object> p = new LinkedHashMap<>();
        boolean pf = Set.of("permanent", "probation", "fixed_term").contains(employmentType);
        p.put("pfMember", pf);
        p.put("pfFullWages", false);
        p.put("epsEligible", pf);
        p.put("esiExempt", !pf);
        p.put("taxRegime", "new");
        p.put("ptExempt", false);
        p.put("lwfExempt", false);
        return p;
    }

    private static void canRead(SessionCtx ctx) {
        if (!ctx.inOrganization() || !(ctx.reachesEveryone("compensation.read") || ctx.reachesEveryone("payroll.prepare"))) {
            throw ApiException.denied("compensation.read");
        }
    }

    private static void canWrite(SessionCtx ctx) {
        if (!ctx.inOrganization() || !(ctx.reachesEveryone("compensation.write") || ctx.reachesEveryone("payroll.prepare"))) {
            throw ApiException.denied("compensation.write");
        }
    }

    private static void notOwn(Connection c, SessionCtx ctx, UUID employmentId) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?", employmentId);
        if (e == null) {
            throw ApiException.notFound("The employee");
        }
        if (ctx.membershipId() != null && ctx.membershipId().toString().equals(e.get("membershipId"))) {
            throw new ApiException(404, "not_found", "The employee was not found");
        }
    }

    // ── reading ─────────────────────────────────────────────────────────

    public Map<String, Object> get(SessionCtx ctx, UUID employmentId) {
        canRead(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            notOwn(c, ctx, employmentId);
            Map<String, Object> e = Sql.one(c, "select employment_type, joining_date, status from app.employment where id = ?", employmentId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("history", Sql.query(c, "select x.id, x.valid_from, x.structure_id, s.name as structure_name, s.basis, x.annual_amount, x.reason, "
                    + "x.recorded_at, " + Names.of("x.recorded_by") + " as recorded_by_name from app.compensation x "
                    + "join app.salary_structure s on s.id = x.structure_id where x.employment_id = ? and x.superseded_at is null "
                    + "order by x.valid_from desc", employmentId));
            out.put("overrides", Sql.query(c, "select o.id, o.component_code, pc.name as component_name, o.monthly_amount, o.valid_from, o.valid_to, "
                    + "o.reason from app.compensation_override o left join app.pay_component pc on pc.code = o.component_code "
                    + "where o.employment_id = ? and o.removed_at is null order by o.valid_from desc", employmentId));
            Map<String, Object> profile = Sql.one(c, "select pf_member, pf_full_wages, eps_eligible, esi_exempt, tax_regime, pt_exempt, lwf_exempt, "
                    + "updated_at from app.pay_profile where employment_id = ?", employmentId);
            out.put("profile", profile == null ? defaultProfile((String) e.get("employmentType")) : profile);
            out.put("group", Sql.one(c, "select g.id, g.name, m.valid_from from app.pay_group_member m join app.pay_group g on g.id = m.group_id "
                    + "where m.employment_id = ? and m.valid_from <= greatest(?::date, ?::date) order by m.valid_from desc limit 1", employmentId, today,
                    e.get("joiningDate")));
            out.put("structures", Sql.query(c, "select id, name, basis from app.salary_structure where active order by lower(name)"));
            out.put("components", Sql.query(c, "select code, name, kind from app.pay_component where not statutory and retired_at is null "
                    + "order by position"));
            LocalDate period = today.withDayOfMonth(1);
            if (!out.get("history").toString().equals("[]")) {
                out.put("breakup", breakup(c, employmentId, period, today, null));
            }
            out.put("canWrite", ctx.reachesEveryone("compensation.write") || ctx.reachesEveryone("payroll.prepare"));
            out.put("thisMonth", period.toString());
            return out;
        });
    }

    /** A full month's lines for the employee as things stand, or with a proposed revision. */
    static Map<String, Object> breakup(Connection c, UUID employmentId, LocalDate period, LocalDate today, ReviseInput proposed) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        Map<String, Object> config = PayrollConfig.load(c, end);
        Map<String, Object> emp = PayrollRuns.employee(c, config, employmentId, period, today, null, true);
        if (proposed != null) {
            emp.put("structureId", proposed.structureId());
            emp.put("annual", proposed.annualAmount());
            emp.put("compensationFrom", proposed.validFrom());
        }
        // A month's breakup is a full month: joining and leaving dates matter only in a run.
        Map<String, Object> days = PayrollEngine.map(emp, "days");
        days.put("paid", days.get("base"));
        days.put("lop", BigDecimal.ZERO);
        PayrollEngine.Calc calc = PayrollEngine.calculate(config, emp, period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", calc.lines());
        out.put("gross", calc.gross());
        out.put("deductions", calc.deductions());
        out.put("net", calc.net());
        out.put("employerCost", calc.gross().add(calc.employer()));
        List<Map<String, Object>> problems = new ArrayList<>();
        for (Map<String, Object> x : calc.exceptions()) {
            String code = (String) x.get("code");
            if (code.equals("formula_failed") || code.equals("not_assigned") || code.equals("net_negative")) {
                problems.add(x);
            }
        }
        out.put("problems", problems);
        return out;
    }

    // ── changing ────────────────────────────────────────────────────────

    /** The breakup before and after a proposed revision, without saving anything. */
    public Map<String, Object> preview(SessionCtx ctx, UUID employmentId, ReviseInput in) {
        canWrite(ctx);
        LocalDate today = today();
        validate(in);
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            notOwn(c, ctx, employmentId);
            if (Sql.one(c, "select 1 from app.salary_structure where id = ?::uuid and active", in.structureId()) == null) {
                throw ApiException.badRequest("Choose a structure");
            }
            LocalDate from = PayrollConfig.date(in.validFrom(), "valid from");
            LocalDate period = (from.isAfter(today) ? from : today).withDayOfMonth(1);
            boolean hasPay = Sql.one(c, "select 1 from app.compensation where employment_id = ? and superseded_at is null", employmentId) != null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("period", period.toString());
            out.put("before", hasPay ? breakup(c, employmentId, period, today, null) : null);
            out.put("after", breakup(c, employmentId, period, today, in));
            return out;
        });
    }

    public Map<String, Object> revise(SessionCtx ctx, UUID employmentId, ReviseInput in) {
        canWrite(ctx);
        validate(in);
        LocalDate from = PayrollConfig.date(in.validFrom(), "valid from");
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            notOwn(c, ctx, employmentId);
            Map<String, Object> e = Sql.one(c, "select joining_date, employment_type, status from app.employment where id = ?", employmentId);
            if (!Set.of("active", "onboarding").contains(e.get("status"))) {
                throw new ApiException(409, "request.refused", "Pay is set only for current employees and joiners");
            }
            LocalDate joined = LocalDate.parse((String) e.get("joiningDate"));
            if (from.isBefore(joined)) {
                throw ApiException.badRequest("Pay cannot start before they join, on " + joined);
            }
            if (Sql.one(c, "select 1 from app.salary_structure where id = ?::uuid and active", in.structureId()) == null) {
                throw ApiException.badRequest("Choose a structure");
            }
            refuseIntoApproved(c, employmentId, from);
            Map<String, Object> check = breakup(c, employmentId, from.withDayOfMonth(1), today(), in);
            if (!PayrollEngine.list(check, "problems").isEmpty()) {
                throw new ApiException(409, "payroll.formula", String.valueOf(PayrollEngine.list(check, "problems").get(0).get("message")));
            }
            Sql.update(c, "update app.compensation set superseded_at = now() where employment_id = ? and valid_from = ? and superseded_at is null",
                    employmentId, from);
            Map<String, Object> row = Sql.one(c, "insert into app.compensation (tenant_id, employment_id, valid_from, structure_id, annual_amount, reason, "
                    + "recorded_by) values (?, ?, ?, ?::uuid, ?, ?, ?) returning id", ctx.tenantId(), employmentId, from, in.structureId(), in.annualAmount(),
                    in.reason().trim(), ctx.membershipId());
            // A first salary puts them in the first pay group, from joining, and sets their statutory profile.
            if (Sql.one(c, "select 1 from app.pay_group_member where employment_id = ?", employmentId) == null) {
                Map<String, Object> g = Sql.one(c, "select id from app.pay_group order by created_at limit 1");
                if (g != null) {
                    Sql.update(c, "insert into app.pay_group_member (tenant_id, employment_id, group_id, valid_from, created_by) values (?, ?, ?::uuid, ?, ?)",
                            ctx.tenantId(), employmentId, g.get("id"), joined, ctx.membershipId());
                }
            }
            ensureProfile(c, ctx, employmentId, (String) e.get("employmentType"));
            UserService.audit(c, ctx, "payroll.pay_revised", "employment", employmentId,
                    Map.of("from", from.toString(), "annual", in.annualAmount().toPlainString(), "reason", in.reason().trim()));
            return row;
        });
    }

    private static void validate(ReviseInput in) {
        if (in.annualAmount() == null || in.annualAmount().signum() <= 0) {
            throw ApiException.badRequest("Type the yearly amount");
        }
        if (in.annualAmount().compareTo(new BigDecimal("1000000000")) > 0) {
            throw ApiException.badRequest("That amount looks too large");
        }
        if (in.reason() == null || in.reason().trim().length() < 3) {
            throw ApiException.badRequest("Give a reason (\"Annual increment\", \"Joining salary\")");
        }
        if (in.structureId() == null || in.structureId().isBlank()) {
            throw ApiException.badRequest("Choose a structure");
        }
        PayrollConfig.id(in.structureId(), "structure");
        PayrollConfig.date(in.validFrom(), "valid from");
    }

    /** A change dated into a month already approved for this person's group needs arrears, which are not built yet. */
    private static void refuseIntoApproved(Connection c, UUID employmentId, LocalDate from) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select max(r.period) as last from app.payroll_run r join app.payslip ps on ps.run_id = r.id "
                + "where ps.employment_id = ? and r.state in ('approved','paid','released')", employmentId);
        if (r != null && r.get("last") != null) {
            LocalDate last = LocalDate.parse((String) r.get("last"));
            if (from.isBefore(last.plusMonths(1))) {
                throw new ApiException(409, "payroll.after_approved", "Their pay is approved up to " + PayrollRuns.monthWords(last)
                        + ". A change can start from " + PayrollRuns.monthWords(last.plusMonths(1))
                        + "; back-dated changes need arrears, which come in the next release");
            }
        }
    }

    private static void ensureProfile(Connection c, SessionCtx ctx, UUID employmentId, String type) throws SQLException {
        Map<String, Object> d = defaultProfile(type);
        Sql.update(c, "insert into app.pay_profile (tenant_id, employment_id, pf_member, eps_eligible, esi_exempt, updated_by) values (?, ?, ?, ?, ?, ?) "
                + "on conflict (employment_id) do nothing", ctx.tenantId(), employmentId, d.get("pfMember"), d.get("epsEligible"), d.get("esiExempt"),
                ctx.membershipId());
    }

    public Map<String, Object> addOverride(SessionCtx ctx, UUID employmentId, OverrideInput in) {
        canWrite(ctx);
        String code = PayrollConfig.upper(in.componentCode());
        if (in.monthlyAmount() == null || in.monthlyAmount().signum() < 0) {
            throw ApiException.badRequest("Type the monthly amount");
        }
        if (in.reason() == null || in.reason().trim().length() < 3) {
            throw ApiException.badRequest("Give a reason for the override");
        }
        LocalDate from = PayrollConfig.date(in.validFrom(), "from");
        LocalDate to = PayrollConfig.blank(in.validTo()) == null ? null : PayrollConfig.date(in.validTo(), "to");
        if (to != null && to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            notOwn(c, ctx, employmentId);
            if (Sql.one(c, "select 1 from app.pay_component where code = ? and not statutory and retired_at is null", code) == null) {
                throw ApiException.badRequest("Choose a component that can be overridden");
            }
            refuseIntoApproved(c, employmentId, from);
            Map<String, Object> row = Sql.one(c, "insert into app.compensation_override (tenant_id, employment_id, component_code, valid_from, valid_to, "
                    + "monthly_amount, reason, created_by) values (?, ?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), employmentId, code, from, to,
                    in.monthlyAmount(), in.reason().trim(), ctx.membershipId());
            UserService.audit(c, ctx, "payroll.override_added", "employment", employmentId,
                    Map.of("component", code, "amount", in.monthlyAmount().toPlainString(), "reason", in.reason().trim()));
            return row;
        });
    }

    public void removeOverride(SessionCtx ctx, UUID employmentId, UUID overrideId) {
        canWrite(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            notOwn(c, ctx, employmentId);
            if (Sql.update(c, "update app.compensation_override set removed_at = now() where id = ? and employment_id = ? and removed_at is null",
                    overrideId, employmentId) == 0) {
                throw ApiException.notFound("The override");
            }
            UserService.audit(c, ctx, "payroll.override_removed", "employment", employmentId, Map.of());
            return null;
        });
    }

    public Map<String, Object> saveProfile(SessionCtx ctx, UUID employmentId, ProfileInput in) {
        canWrite(ctx);
        String regime = in.taxRegime() == null ? "new" : in.taxRegime();
        if (!Set.of("new", "old").contains(regime)) {
            throw ApiException.badRequest("The tax regime is new or old");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            notOwn(c, ctx, employmentId);
            Sql.update(c, "insert into app.pay_profile (tenant_id, employment_id, pf_member, pf_full_wages, eps_eligible, esi_exempt, tax_regime, "
                            + "pt_exempt, lwf_exempt, updated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (employment_id) do update set "
                            + "pf_member = excluded.pf_member, pf_full_wages = excluded.pf_full_wages, eps_eligible = excluded.eps_eligible, "
                            + "esi_exempt = excluded.esi_exempt, tax_regime = excluded.tax_regime, pt_exempt = excluded.pt_exempt, "
                            + "lwf_exempt = excluded.lwf_exempt, updated_by = excluded.updated_by, updated_at = now()",
                    ctx.tenantId(), employmentId, !Boolean.FALSE.equals(in.pfMember()), Boolean.TRUE.equals(in.pfFullWages()),
                    !Boolean.FALSE.equals(in.epsEligible()), Boolean.TRUE.equals(in.esiExempt()), regime, Boolean.TRUE.equals(in.ptExempt()),
                    Boolean.TRUE.equals(in.lwfExempt()), ctx.membershipId());
            UserService.audit(c, ctx, "payroll.profile_changed", "employment", employmentId, Map.of("regime", regime));
            return Map.of("saved", true);
        });
    }
}
