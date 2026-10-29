package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.loginapp.core.PayrollRuns.parse;

/**
 * The Labour Codes wage check (release R5): for a month of approved payroll, how much of each person's pay counts as
 * wages, how much of the conditional allowances the engine had to add back to bring wages up to half of pay, and what
 * that added to employee and employer PF. Read-only, and read from what the run recorded: the bases on each payslip, its
 * earning lines, the run's frozen component settings and the PF lines' own wage bases. Nothing is taken from today's
 * settings, so a month shows what was paid even after setup has changed.
 *
 * Gratuity and leave encashment follow the same wage base but need their own rule data, which does not exist yet,
 * so they are not estimated here. EDLI and admin charges are not computed anywhere in payroll, so they are not in the PF figure.
 */
public final class WageCheck {

    /** A person's pay in the month. {@code pfExtra} is null for a payslip calculated before the PF wage bases were recorded. */
    public record Row(String employmentId, String code, String name, BigDecimal remuneration, BigDecimal wages, BigDecimal addedBack,
                      BigDecimal wageShare, boolean toppedUp, BigDecimal pfExtra, boolean held) {}

    public record Summary(int people, int toppedUp, BigDecimal remuneration, BigDecimal addedBack, BigDecimal pfExtra) {}

    /** A pay group whose run for the month is not approved, so its people are not in the figures. */
    public record Pending(String group, String state) {}

    public record View(String period, List<Row> rows, Summary summary, boolean draftRules, List<Pending> pending) {}

    private final Db db;

    public WageCheck(Db db) {
        this.db = db;
    }

    /** The check for a month (yyyy-mm), or for the latest month with an approved run when none is given. */
    public View check(SessionCtx ctx, String month) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        if (!ctx.reachesEveryone("payroll.read") && !ctx.reachesEveryone("payroll.prepare")) {
            throw ApiException.denied("payroll.read");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            LocalDate period;
            if (month == null || month.isBlank()) {
                Map<String, Object> latest = Sql.one(c, "select max(period) as p from app.payroll_run where state in ('approved','paid','released')");
                if (latest == null || latest.get("p") == null) {
                    return new View(null, List.of(), new Summary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), false, List.of());
                }
                period = LocalDate.parse(String.valueOf(latest.get("p")));
            } else {
                try {
                    period = YearMonth.parse(month).atDay(1);
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("Give the month as yyyy-mm");
                }
            }
            List<Row> rows = new ArrayList<>();
            Set<String> people = new HashSet<>();
            BigDecimal totalPay = BigDecimal.ZERO;
            BigDecimal totalAdded = BigDecimal.ZERO;
            BigDecimal totalPf = BigDecimal.ZERO;
            int toppedUp = 0;
            boolean draft = false;
            for (Map<String, Object> run : Sql.query(c, "select id, snapshot from app.payroll_run where period = ? and state in ('approved','paid','released') "
                    + "order by created_at", period)) {
                Map<String, Object> components = PayrollEngine.map(PayrollEngine.map(parse(run.get("snapshot")), "config"), "components");
                Map<String, List<BigDecimal>> earnings = new LinkedHashMap<>();
                for (Map<String, Object> l : Sql.query(c, "select l.payslip_id, l.code, l.amount from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                        + "where ps.run_id = ?::uuid and l.kind = 'earning'", run.get("id"))) {
                    boolean oneTime = Boolean.TRUE.equals(PayrollEngine.map(components, String.valueOf(l.get("code"))).get("oneTime"));
                    if (!oneTime) {
                        earnings.computeIfAbsent(String.valueOf(l.get("payslipId")), k -> new ArrayList<>()).add(PayrollEngine.dec(l, "amount"));
                    }
                }
                Map<String, Map<String, Map<String, Object>>> pfLines = new LinkedHashMap<>();
                for (Map<String, Object> l : Sql.query(c, "select l.payslip_id, l.code, l.amount, l.trace from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                        + "where ps.run_id = ?::uuid and l.code in ('PF_EE', 'PF_ER')", run.get("id"))) {
                    pfLines.computeIfAbsent(String.valueOf(l.get("payslipId")), k -> new LinkedHashMap<>()).put((String) l.get("code"), l);
                }
                for (Map<String, Object> r : Sql.query(c, "select ps.id, ps.employment_id, ps.held, ps.info->>'code' as code, ps.info->>'name' as name, "
                        + "ps.info->'bases' as bases from app.payslip ps where ps.run_id = ?::uuid order by lower(ps.info->>'name'), ps.id", run.get("id"))) {
                    String payslip = String.valueOf(r.get("id"));
                    Map<String, Object> bases = parse(r.get("bases"));
                    BigDecimal wages = PayrollEngine.dec(bases, "wages");
                    BigDecimal added = PayrollEngine.dec(bases, "addedBack");
                    BigDecimal pay = earnings.getOrDefault(payslip, List.of()).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal share = pay.signum() == 0 ? BigDecimal.ZERO
                            : wages.multiply(BigDecimal.valueOf(100)).divide(pay, 1, RoundingMode.HALF_UP);
                    BigDecimal pfExtra = pfExtra(pfLines.get(payslip), wages, added);
                    draft |= pfLines.containsKey(payslip) && pfLines.get(payslip).values().stream()
                            .anyMatch(l -> Boolean.TRUE.equals(parse(l.get("trace")).get("draft")));
                    boolean topped = added.signum() > 0;
                    if (topped) {
                        toppedUp++;
                    }
                    people.add(String.valueOf(r.get("employmentId")));
                    totalPay = totalPay.add(pay);
                    totalAdded = totalAdded.add(added);
                    totalPf = totalPf.add(pfExtra == null ? BigDecimal.ZERO : pfExtra);
                    rows.add(new Row(String.valueOf(r.get("employmentId")), (String) r.get("code"), (String) r.get("name"), pay, wages, added, share,
                            topped, pfExtra, Boolean.TRUE.equals(r.get("held"))));
                }
            }
            List<Pending> pending = new ArrayList<>();
            for (Map<String, Object> g : Sql.query(c, "select g.name, r.state from app.payroll_run r join app.pay_group g on g.id = r.group_id "
                    + "where r.period = ? and r.state not in ('approved','paid','released') order by g.name", period)) {
                pending.add(new Pending((String) g.get("name"), (String) g.get("state")));
            }
            return new View(period.toString(), rows, new Summary(people.size(), toppedUp, totalPay, totalAdded, totalPf), draft, pending);
        });
    }

    /**
     * The PF that the add-back caused: what was actually deducted and paid in PF, in proportion to the part of the PF wage base
     * that came from the add-back. The base without it is the wages, unless the ceiling cut the base (then the wages up to the same
     * ceiling). Null when the lines predate the recorded wage bases.
     */
    private static BigDecimal pfExtra(Map<String, Map<String, Object>> lines, BigDecimal wages, BigDecimal added) {
        if (lines == null || lines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (added.signum() == 0) {
            return BigDecimal.ZERO;
        }
        Map<String, Object> first = lines.values().iterator().next();
        Map<String, Object> trace = parse(first.get("trace"));
        if (trace.get("epfWages") == null || trace.get("pfWage") == null) {
            return null;
        }
        BigDecimal base = PayrollEngine.dec(trace, "epfWages");
        BigDecimal pfWage = PayrollEngine.dec(trace, "pfWage");
        if (base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal without = base.compareTo(pfWage) < 0 ? wages.min(base) : wages.min(pfWage);
        BigDecimal paid = BigDecimal.ZERO;
        for (Map<String, Object> l : lines.values()) {
            paid = paid.add(PayrollEngine.dec(l, "amount"));
        }
        return paid.multiply(base.subtract(without)).divide(base, 2, RoundingMode.HALF_UP);
    }
}
