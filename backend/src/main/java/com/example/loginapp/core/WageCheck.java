package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.loginapp.core.PayrollRuns.parse;

/**
 * The Labour Codes wage check (release R5): for a month of approved payroll, how much of each person's pay counts as
 * wages, how much of the conditional allowances the engine had to add back to bring wages up to half of pay, and what
 * that added to PF. Read-only: it reads the bases the engine already recorded on each payslip, so it shows what was
 * paid, not a second calculation.
 *
 * Gratuity and leave encashment follow the same wage base but need their own rule data, which does not exist yet,
 * so they are not estimated here.
 */
public final class WageCheck {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Row(String employmentId, String code, String name, BigDecimal remuneration, BigDecimal wages, BigDecimal addedBack,
                      BigDecimal wageShare, boolean toppedUp, BigDecimal pfExtra) {}

    public record Summary(int people, int toppedUp, BigDecimal remuneration, BigDecimal addedBack, BigDecimal pfExtra) {}

    public record View(String period, List<Row> rows, Summary summary, boolean draftRules) {}

    private final Db db;
    private final Clock clock;

    public WageCheck(Db db) {
        this(db, Clock.systemUTC());
    }

    public WageCheck(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
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
                    return new View(null, List.of(), new Summary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), false);
                }
                period = LocalDate.parse(String.valueOf(latest.get("p")));
            } else {
                try {
                    period = YearMonth.parse(month).atDay(1);
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("Give the month as yyyy-mm");
                }
            }
            LocalDate end = period.plusMonths(1).minusDays(1);
            Map<String, Object> rule = Sql.one(c, "select status, data from app.payroll_rule where statute = 'pf' and jurisdiction = 'IN' "
                    + "and valid_from <= ? and (valid_to is null or valid_to > ?)", period, period);
            Map<String, Object> pf = rule == null ? Map.of() : parse(rule.get("data"));
            BigDecimal ceiling = PayrollEngine.dec(pf, "ceiling");
            BigDecimal pfRate = PayrollEngine.dec(pf, "employeeRate").add(PayrollEngine.dec(pf, "employerRate"));
            boolean restrict = Boolean.TRUE.equals(Sql.one(c, "select pf_restrict from app.payroll_settings").get("pfRestrict"));

            List<Row> rows = new ArrayList<>();
            BigDecimal totalPay = BigDecimal.ZERO;
            BigDecimal totalAdded = BigDecimal.ZERO;
            BigDecimal totalPf = BigDecimal.ZERO;
            int toppedUp = 0;
            for (Map<String, Object> r : Sql.query(c, "select ps.employment_id, ps.info->>'code' as code, ps.info->>'name' as name, ps.info->'bases' as bases, "
                    + "(select coalesce(sum(l.amount), 0) from app.payslip_line l where l.payslip_id = ps.id and l.kind = 'earning' and not coalesce("
                    + "(select v.one_time from app.pay_component pc join app.pay_component_version v on v.component_id = pc.id where pc.code = l.code "
                    + "and v.valid_from <= ? order by v.valid_from desc limit 1), false)) as remuneration, "
                    + "exists (select 1 from app.payslip_line l where l.payslip_id = ps.id and l.code = 'PF_EE') as pf_member, "
                    + "coalesce((select pp.pf_full_wages from app.pay_profile pp where pp.employment_id = ps.employment_id), false) as full "
                    + "from app.payslip ps join app.payroll_run r on r.id = ps.run_id where r.period = ? and r.state in ('approved','paid','released') "
                    + "order by lower(ps.info->>'name'), ps.id", end, period)) {
                Map<String, Object> bases = parse(r.get("bases"));
                BigDecimal wages = PayrollEngine.dec(bases, "wages");
                BigDecimal added = PayrollEngine.dec(bases, "addedBack");
                BigDecimal pay = PayrollEngine.dec(r, "remuneration");
                BigDecimal share = pay.signum() == 0 ? BigDecimal.ZERO
                        : wages.multiply(BigDecimal.valueOf(100)).divide(pay, 1, RoundingMode.HALF_UP);
                BigDecimal pfExtra = BigDecimal.ZERO;
                if (Boolean.TRUE.equals(r.get("pfMember")) && added.signum() > 0) {
                    boolean full = Boolean.TRUE.equals(r.get("full")) || !restrict;
                    BigDecimal with = full ? wages.add(added) : wages.add(added).min(ceiling);
                    BigDecimal without = full ? wages : wages.min(ceiling);
                    pfExtra = with.subtract(without).multiply(pfRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
                boolean topped = added.signum() > 0;
                if (topped) {
                    toppedUp++;
                }
                totalPay = totalPay.add(pay);
                totalAdded = totalAdded.add(added);
                totalPf = totalPf.add(pfExtra);
                rows.add(new Row(String.valueOf(r.get("employmentId")), (String) r.get("code"), (String) r.get("name"), pay, wages, added, share,
                        topped, pfExtra));
            }
            return new View(period.toString(), rows, new Summary(rows.size(), toppedUp, totalPay, totalAdded, totalPf),
                    rule != null && "draft".equals(rule.get("status")));
        });
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }
}
