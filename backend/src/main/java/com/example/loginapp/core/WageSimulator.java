package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Labour Codes what-if (release R5): move amounts between basic and the allowances for one person and see, before
 * saving a new version of their pay, how much of it counts as wages, what the engine adds back to reach half of pay, the
 * PF that follows, the employer's cost and the take-home. The real engine works both months out on a full month of the
 * person's current pay (the proposal laid over any overrides already saved), so the answer is what a run would produce.
 * Nothing is saved or audited as a change; the call is read-only.
 *
 * Employer cost can exceed cost to company divided by twelve: the structure's employer PF is balanced against wages
 * without the add-back, while the PF deducted uses the wage base with it. The figures show both.
 * Tax and professional tax are this month's, which depend on the year so far, so they are not annualised here.
 */
public final class WageSimulator {

    private static final int MAX_COMPONENTS = 30;
    private static final BigDecimal MAX_MONTHLY = new BigDecimal("100000000");

    /** One component and the full-month amount to try for it. */
    public record Change(String code, BigDecimal amount) {}

    /** A proposal: the changes to try, for a month (this month when omitted). */
    public record Input(String month, List<Change> changes) {}

    /** One side of the comparison, for a full month. {@code pfDraft} is true when a PF line used rule data not yet signed off. */
    public record Figures(BigDecimal remuneration, BigDecimal wages, BigDecimal addedBack, BigDecimal wageShare, boolean toppedUp,
                          BigDecimal pfEmployee, BigDecimal pfEmployer, BigDecimal employerCostMonthly, BigDecimal employerCostYearly,
                          BigDecimal takeHome, boolean pfDraft) {}

    public record Result(String period, String structure, BigDecimal ctcMonthly, Figures current, Figures proposed, List<String> warnings,
                         List<Map<String, Object>> problems) {}

    private final Db db;
    private final PayrollPay pay;

    public WageSimulator(Db db, PayrollPay pay) {
        this.db = db;
        this.pay = pay;
    }

    public Result simulate(SessionCtx ctx, UUID employmentId, Input in) {
        PayrollPay.canRead(ctx);
        if (in == null || in.changes() == null || in.changes().isEmpty()) {
            throw ApiException.badRequest("Give at least one component and the amount to try");
        }
        if (in.changes().size() > MAX_COMPONENTS) {
            throw ApiException.badRequest("Too many components");
        }
        LocalDate today = pay.today();
        LocalDate period = period(in.month(), today);
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            PayrollPay.notOwn(c, ctx, employmentId);
            PayrollPay.FullMonth month = PayrollPay.fullMonth(c, employmentId, period, today);
            if (month == null) {
                throw ApiException.notFound("The employee");
            }
            Map<String, Object> config = month.config;
            Map<String, Object> structure = structure(config, month.emp);
            Map<String, Object> components = PayrollEngine.map(config, "components");
            check(in.changes(), structure, components);

            PayrollEngine.Calc current = PayrollEngine.calculate(config, copy(month.emp), period);
            Map<String, Object> changed = copy(month.emp);
            Map<String, Object> overrides = PayrollEngine.map(changed, "overrides");
            for (Change change : in.changes()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("amount", change.amount());
                o.put("from", period.toString());
                o.put("reason", "What-if");
                overrides.put(change.code(), o);
            }
            PayrollEngine.Calc proposed = PayrollEngine.calculate(config, changed, period);

            List<Map<String, Object>> problems = new ArrayList<>();
            for (PayrollEngine.Calc calc : List.of(current, proposed)) {
                for (Map<String, Object> x : calc.exceptions()) {
                    String code = (String) x.get("code");
                    if ((code.equals("formula_failed") || code.equals("not_assigned") || code.equals("net_negative")) && !problems.contains(x)) {
                        problems.add(x);
                    }
                }
            }
            Figures before = figures(current, components);
            Figures after = figures(proposed, components);
            BigDecimal ctc = PayrollEngine.dec(month.emp, "annual").divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            List<String> warnings = new ArrayList<>();
            if (after.remuneration().compareTo(before.remuneration()) != 0) {
                warnings.add("Total pay changes from " + PayrollEngine.rupees(before.remuneration()) + " to " + PayrollEngine.rupees(after.remuneration())
                        + " a month. To only move money between components, make the amounts add up to the same total.");
            }
            for (Map<String, Object> l : proposed.lines()) {
                if ("earning".equals(l.get("kind")) && ((BigDecimal) l.get("amount")).signum() < 0) {
                    warnings.add(l.get("name") + " would be negative (" + PayrollEngine.rupees((BigDecimal) l.get("amount"))
                            + "): the amounts tried take more than the structure's total pay allows.");
                }
            }
            if (before.pfDraft() || after.pfDraft()) {
                warnings.add("The PF rules used here are draft data, not yet signed off by the payroll consultant.");
            }
            if (after.toppedUp() && !before.toppedUp()) {
                warnings.add("This change makes the engine add conditional allowances back to reach half of pay, which raises PF.");
            }
            return new Result(period.toString(), String.valueOf(structure.get("name")), ctc, before, after, warnings, problems);
        });
    }

    // ── steps ───────────────────────────────────────────────────────────

    private static LocalDate period(String month, LocalDate today) {
        if (month == null || month.isBlank()) {
            return today.withDayOfMonth(1);
        }
        try {
            return YearMonth.parse(month).atDay(1);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the month as yyyy-mm");
        }
    }

    private static Map<String, Object> structure(Map<String, Object> config, Map<String, Object> emp) {
        Map<String, Object> structure = emp.get("structureId") == null ? null
                : PayrollEngine.map(PayrollEngine.map(config, "structures"), (String) emp.get("structureId"));
        if (structure == null) {
            throw new ApiException(409, "request.refused", "No salary is set for this person yet. Set it on their record under Pay.");
        }
        return structure;
    }

    /**
     * Only components the person's structure computes, that a formula or fixed amount sets, may be tried. A code outside the
     * structure would be ignored by the engine, a statutory one would fake the figure the balancing allowance is worked out from,
     * and a one-time or input component is not part of monthly pay.
     */
    private static void check(List<Change> changes, Map<String, Object> structure, Map<String, Object> components) {
        List<String> inStructure = new ArrayList<>();
        for (Map<String, Object> l : PayrollEngine.list(structure, "lines")) {
            inStructure.add((String) l.get("code"));
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Change change : changes) {
            String code = change == null ? null : change.code();
            BigDecimal amount = change == null ? null : change.amount();
            if (code == null || !seen.add(code)) {
                throw ApiException.badRequest("Name each component once");
            }
            if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_MONTHLY) > 0) {
                throw ApiException.badRequest("The amount for " + code + " must be between 0 and " + MAX_MONTHLY.toPlainString());
            }
            Map<String, Object> component = components.get(code) == null ? null : PayrollEngine.map(components, code);
            if (component == null || !inStructure.contains(code)) {
                throw ApiException.badRequest(code + " is not part of this person's salary structure");
            }
            if (Boolean.TRUE.equals(component.get("statutory")) || Boolean.TRUE.equals(component.get("oneTime"))
                    || "input".equals(component.get("amountType")) || !"earning".equals(component.get("kind"))) {
                throw ApiException.badRequest(code + " is not a monthly earning that can be tried here");
            }
        }
    }

    private static Figures figures(PayrollEngine.Calc calc, Map<String, Object> components) {
        BigDecimal remuneration = BigDecimal.ZERO;
        for (Map<String, Object> l : calc.lines()) {
            if ("earning".equals(l.get("kind")) && !Boolean.TRUE.equals(PayrollEngine.map(components, String.valueOf(l.get("code"))).get("oneTime"))) {
                remuneration = remuneration.add((BigDecimal) l.get("amount"));
            }
        }
        Map<String, Object> bases = PayrollEngine.map(calc.info(), "bases");
        BigDecimal wages = PayrollEngine.dec(bases, "wages");
        BigDecimal added = PayrollEngine.dec(bases, "addedBack");
        BigDecimal share = remuneration.signum() == 0 ? BigDecimal.ZERO
                : wages.multiply(BigDecimal.valueOf(100)).divide(remuneration, 1, RoundingMode.HALF_UP);
        boolean draft = false;
        for (Map<String, Object> l : calc.lines()) {
            draft |= String.valueOf(l.get("code")).startsWith("PF_") && Boolean.TRUE.equals(PayrollEngine.map(l, "trace").get("draft"));
        }
        BigDecimal cost = calc.gross().add(calc.employer());
        return new Figures(remuneration, wages, added, share, added.signum() > 0, calc.amount("PF_EE"), calc.amount("PF_ER"),
                cost, cost.multiply(BigDecimal.valueOf(12)), calc.net(), draft);
    }

    /** A deep copy, so one run cannot change the facts the other reads (the engine hands out live maps). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> m) {
        return (Map<String, Object>) Json.parse(Json.write(m));
    }
}
