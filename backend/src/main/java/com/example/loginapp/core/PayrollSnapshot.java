package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.loginapp.core.PayrollRuns.parse;
import static com.example.loginapp.core.PayrollRuns.taxYearStart;

/**
 * Freezing a month: who is in the pay group, and everything the engine needs about each of them
 * (pay, profile, inputs, work location, days paid, identity and the tax year so far).
 */
final class PayrollSnapshot {

    private PayrollSnapshot() {
    }

    /** Who is in a pay group for a month: in the group on the month's last day, joined by then, not gone before it began. */
    static List<Map<String, Object>> members(Connection c, UUID groupId, LocalDate period) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        return Sql.query(c, "select e.id, coalesce(p.preferred_name, p.legal_name) as name, e.employee_code, j.designation "
                + "from app.pay_group_member m join app.employment e on e.id = m.employment_id join app.person p on p.id = e.person_id "
                + "left join lateral (select designation from app.job_assignment j where j.employment_id = e.id and j.effective_from <= ? "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "where m.group_id = ? and m.valid_from = (select max(m2.valid_from) from app.pay_group_member m2 where m2.employment_id = e.id "
                + "  and m2.valid_from <= ?) and e.joining_date <= ? and (e.status = 'active' or (e.status = 'exited' and e.exit_date >= ?)) "
                // Someone leaving before the month starts is settled by their exit, not paid in it.
                + "and not exists (select 1 from app.exit_case x where x.employment_id = e.id and x.status = 'accepted' and x.last_working_day < ?) "
                + "order by lower(coalesce(p.preferred_name, p.legal_name))", end, groupId, end, end, period, period);
    }

    static Map<String, Object> snapshot(Connection c, Crypto crypto, UUID groupId, UUID runId, LocalDate period, LocalDate today) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        Map<String, Object> config = PayrollConfig.load(c, end);
        List<Map<String, Object>> employees = new ArrayList<>();
        for (Map<String, Object> m : members(c, groupId, period)) {
            Map<String, Object> e = employee(c, config, AuthService.uuid(m.get("id")), period, today, runId, false);
            if (e != null) {
                employees.add(e);
            }
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("period", period.toString());
        snap.put("frozenOn", today.toString());
        snap.put("config", config);
        snap.put("employees", employees);
        @SuppressWarnings("unchecked")
        Map<String, Object> normal = (Map<String, Object>) Json.parse(Json.write(snap));
        return normal;
    }

    /**
     * Everything the engine needs about one employee for a month. With a run, inputs and holds come
     * from it; without one (a preview), the month is taken as fully paid unless leave says otherwise.
     */
    static Map<String, Object> employee(Connection c, Map<String, Object> config, UUID employmentId, LocalDate period, LocalDate today, UUID runId,
                                        boolean preview) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        Map<String, Object> e = Sql.one(c, "select e.id, e.membership_id, e.person_id, e.employee_code, e.employment_type, e.joining_date, e.exit_date, "
                + "e.status, coalesce(p.preferred_name, p.legal_name) as name, p.legal_name, p.gender, j.designation, d.name as department "
                + "from app.employment e join app.person p on p.id = e.person_id "
                + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id and j.effective_from <= greatest(?::date, e.joining_date) "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true left join app.department d on d.id = j.department_id "
                + "where e.id = ?", end, employmentId);
        if (e == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employmentId", e.get("id"));
        out.put("membershipId", e.get("membershipId"));
        out.put("name", e.get("name"));
        out.put("code", e.get("employeeCode"));
        out.put("designation", e.get("designation"));
        out.put("department", e.get("department"));
        out.put("type", e.get("employmentType"));
        out.put("gender", e.get("gender"));
        LocalDate joined = LocalDate.parse((String) e.get("joiningDate"));
        out.put("joinedOn", joined.toString());
        LocalDate leaving = e.get("exitDate") == null ? null : LocalDate.parse((String) e.get("exitDate"));
        Map<String, Object> exit = Sql.one(c, "select last_working_day from app.exit_case where employment_id = ? and status = 'accepted' "
                + "and last_working_day is not null order by accepted_at desc limit 1", employmentId);
        if (leaving == null && exit != null) {
            leaving = LocalDate.parse((String) exit.get("lastWorkingDay"));
        }
        out.put("leavingOn", leaving == null ? null : leaving.toString());
        out.put("monthsOfService", ChronoUnit.MONTHS.between(joined, end.plusDays(1)));

        Map<String, Object> comp = Sql.one(c, "select structure_id, annual_amount, valid_from, reason from app.compensation where employment_id = ? "
                + "and superseded_at is null and valid_from <= ? order by valid_from desc limit 1", employmentId, end);
        out.put("structureId", comp == null ? null : comp.get("structureId"));
        out.put("annual", comp == null ? BigDecimal.ZERO : comp.get("annualAmount"));
        out.put("compensationFrom", comp == null ? null : comp.get("validFrom"));
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map<String, Object> o : Sql.query(c, "select component_code, monthly_amount, valid_from, reason from app.compensation_override "
                + "where employment_id = ? and removed_at is null and valid_from <= ? and (valid_to is null or valid_to >= ?) order by valid_from",
                employmentId, end, period)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("amount", o.get("monthlyAmount"));
            v.put("from", o.get("validFrom"));
            v.put("reason", o.get("reason"));
            overrides.put((String) o.get("componentCode"), v);
        }
        out.put("overrides", overrides);
        Map<String, Object> profile = Sql.one(c, "select pf_member, pf_full_wages, eps_eligible, esi_exempt, tax_regime, pt_exempt, lwf_exempt "
                + "from app.pay_profile where employment_id = ?", employmentId);
        out.put("profile", profile == null ? PayrollPay.defaultProfile((String) e.get("employmentType")) : profile);

        Map<String, Object> inputs = new LinkedHashMap<>();
        boolean held = false;
        if (runId != null) {
            for (Map<String, Object> i : Sql.query(c, "select component_code, sum(amount) as amount from app.run_input where run_id = ? "
                    + "and employment_id = ? group by component_code", runId, employmentId)) {
                inputs.put((String) i.get("componentCode"), i.get("amount"));
            }
            held = Sql.one(c, "select 1 from app.pay_hold where run_id = ? and employment_id = ?", runId, employmentId) != null;
        }
        // Loss-of-pay days entered for the month are not a pay component: they go to the day count below.
        Object enteredLop = inputs.remove(PayrollRuns.LOP_DAYS);
        out.put("inputs", inputs);
        out.put("held", held);

        // Where they work: the state drives PT, LWF and the metro HRA.
        UUID membership = e.get("membershipId") == null ? null : AuthService.uuid(e.get("membershipId"));
        LocalDate lastDay = leaving != null && leaving.isBefore(end) ? leaving : end;
        AttendanceDays.Assigned a = membership == null ? null : AttendanceDays.assigned(c, membership, lastDay);
        if (a != null) {
            Map<String, Object> loc = Sql.one(c, "select state_code from app.work_location where id = ?", a.locationId());
            out.put("location", a.locationName());
            out.put("state", loc == null ? null : loc.get("stateCode"));
            List<?> metro = (List<?>) PayrollEngine.map(config, "settings").getOrDefault("metroLocations", List.of());
            out.put("metro", metro.stream().anyMatch(x -> String.valueOf(x).equals(a.locationId().toString())));
        } else {
            out.put("location", null);
            out.put("state", null);
            out.put("metro", false);
        }

        out.put("days", days(c, config, membership, a, joined, leaving, period, today, preview,
                enteredLop == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(enteredLop))));

        // Identity and bank, for the checks and the payslip (last four only).
        UUID person = AuthService.uuid(e.get("personId"));
        Map<String, Object> pan = Sql.one(c, "select last4 from app.statutory_id where person_id = ? and kind = 'pan' and replaced_at is null "
                + "and pending_reason is null limit 1", person);
        Map<String, Object> uan = Sql.one(c, "select last4 from app.statutory_id where person_id = ? and kind = 'uan' and replaced_at is null limit 1", person);
        Map<String, Object> bank = Sql.one(c, "select account_last4 from app.bank_account where person_id = ? and effective_from <= greatest(?::date, ?::date) "
                + "order by effective_from desc, created_at desc limit 1", person, end, joined);
        out.put("hasPan", pan != null);
        out.put("panLast4", pan == null ? null : pan.get("last4"));
        out.put("hasUan", uan != null);
        out.put("uanLast4", uan == null ? null : uan.get("last4"));
        out.put("hasBank", bank != null);
        out.put("bankLast4", bank == null ? null : bank.get("accountLast4"));

        // This tax year so far, from approved months (imported history comes with the switching kit).
        LocalDate yearStart = taxYearStart(period);
        Map<String, Object> ytd = new LinkedHashMap<>();
        BigDecimal taxable = BigDecimal.ZERO;
        for (Map<String, Object> p : Sql.query(c, "select ps.info from app.payslip ps join app.payroll_run r on r.id = ps.run_id "
                + "where ps.employment_id = ? and r.state in ('approved','paid','released') and r.period >= ? and r.period < ?", employmentId, yearStart, period)) {
            taxable = taxable.add(PayrollEngine.dec(PayrollEngine.map(parse(p.get("info")), "bases"), "taxable"));
        }
        ytd.put("taxable", taxable);
        Map<String, Object> byCode = new LinkedHashMap<>();
        for (Map<String, Object> l : Sql.query(c, "select l.code, sum(l.amount) as amount from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                + "join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ? and r.state in ('approved','paid','released') "
                + "and r.period >= ? and r.period < ? group by l.code", employmentId, yearStart, period)) {
            byCode.put((String) l.get("code"), l.get("amount"));
        }
        ytd.put("components", byCode);
        ytd.put("tds", byCode.getOrDefault("TDS", BigDecimal.ZERO));
        ytd.put("pt", byCode.getOrDefault("PT", BigDecimal.ZERO));
        ytd.put("pfEe", byCode.getOrDefault("PF_EE", BigDecimal.ZERO));
        out.put("ytd", ytd);

        // ESI coverage carries to the end of the contribution period (April to September, October to March).
        LocalDate esiStart = period.getMonthValue() >= 10 ? period.withMonth(10)
                : period.getMonthValue() >= 4 ? period.withMonth(4) : period.minusYears(1).withMonth(10);
        out.put("esiCoveredInPeriod", Sql.one(c, "select 1 from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                + "join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ? and r.state in ('approved','paid','released') "
                + "and r.period >= ? and r.period < ? and l.code in ('ESI_EE','ESI_ER') and l.amount > 0 limit 1", employmentId, esiStart, period) != null);

        Map<String, Object> previous = Sql.one(c, "select ps.net from app.payslip ps join app.payroll_run r on r.id = ps.run_id "
                + "where ps.employment_id = ? and r.state in ('approved','paid','released') and r.period < ? order by r.period desc limit 1", employmentId, period);
        out.put("previous", previous == null ? Map.of() : previous);
        return out;
    }

    /**
     * Days in the month, days paid and loss of pay. Unpaid leave counts as loss of pay; absence
     * without leave too when the Organization says so. Days not yet come count as worked. Loss-of-pay
     * days HR entered for the month are added; without the Attendance module they are the only source.
     */
    static Map<String, Object> days(Connection c, Map<String, Object> config, UUID membership, AttendanceDays.Assigned a, LocalDate joined,
                                    LocalDate leaving, LocalDate period, LocalDate today, boolean preview, BigDecimal enteredLop)
            throws SQLException {
        boolean attendanceOn = Modules.on(c, Modules.ATTENDANCE);
        Map<String, Object> settings = PayrollEngine.map(config, "settings");
        String method = String.valueOf(settings.getOrDefault("proration", "calendar"));
        boolean absentIsLop = Boolean.TRUE.equals(settings.get("absenceIsLop"));
        LocalDate end = period.plusMonths(1).minusDays(1);
        LocalDate from = joined.isAfter(period) ? joined : period;
        LocalDate to = leaving != null && leaving.isBefore(end) ? leaving : end;
        BigDecimal lop = BigDecimal.ZERO;
        if (attendanceOn && membership != null && !to.isBefore(from)) {
            Map<String, Map<String, Object>> rows = new HashMap<>();
            if (!preview && !from.isAfter(today)) {
                for (Map<String, Object> d : AttendanceDays.monthOf(c, (UUID) app(c), membership, java.time.YearMonth.from(period), today)) {
                    rows.put((String) d.get("workDate"), d);
                }
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                Map<String, Object> row = rows.get(d.toString());
                if (row != null) {
                    if (Boolean.TRUE.equals(row.get("leaveUnpaid"))) {
                        lop = lop.add(PayrollEngine.dec(row, "leaveDays"));
                    } else if (absentIsLop && "absent".equals(row.get("status"))) {
                        lop = lop.add(BigDecimal.ONE);
                    } else if (absentIsLop && "half_day".equals(row.get("status")) && PayrollEngine.dec(row, "leaveDays").signum() == 0) {
                        lop = lop.add(new BigDecimal("0.5"));
                    }
                } else {
                    AttendanceDays.Assigned at = AttendanceDays.assigned(c, membership, d);
                    boolean off = at != null && AttendanceDays.dayOff(c, at, d) != null;
                    DayCalculator.Leave leave = LeaveService.leaveOn(c, membership, d, off);
                    if (leave != null && leave.unpaid()) {
                        lop = lop.add(leave.half() == null ? BigDecimal.ONE : new BigDecimal("0.5"));
                    }
                }
            }
        }
        int inMonth = end.getDayOfMonth();
        long notEmployed = inMonth - (to.isBefore(from) ? 0 : ChronoUnit.DAYS.between(from, to) + 1);
        BigDecimal base;
        BigDecimal paid;
        String used = method;
        if (method.equals("working") && a != null) {
            int all = 0;
            int employed = 0;
            for (LocalDate d = period; !d.isAfter(end); d = d.plusDays(1)) {
                AttendanceDays.Assigned at = AttendanceDays.assigned(c, membership, d);
                boolean off = at == null || AttendanceDays.dayOff(c, at, d) != null;
                if (!off) {
                    all++;
                    if (!d.isBefore(from) && !d.isAfter(to)) {
                        employed++;
                    }
                }
            }
            base = BigDecimal.valueOf(all);
            paid = BigDecimal.valueOf(employed).subtract(lop);
        } else if (method.equals("fixed_30")) {
            base = BigDecimal.valueOf(30);
            paid = BigDecimal.valueOf(30).subtract(BigDecimal.valueOf(notEmployed)).subtract(lop);
        } else {
            used = "calendar";
            base = BigDecimal.valueOf(inMonth);
            paid = base.subtract(BigDecimal.valueOf(notEmployed)).subtract(lop);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", used);
        out.put("base", base);
        out.put("paid", paid.subtract(enteredLop).max(BigDecimal.ZERO));
        out.put("lop", lop.add(enteredLop));
        out.put("lopEntered", enteredLop);
        out.put("notEmployed", notEmployed);
        return out;
    }

    /** The Organization being worked in, for helpers that take its id. */
    private static Object app(Connection c) throws SQLException {
        return AuthService.uuid(Sql.one(c, "select app.current_tenant() as t").get("t"));
    }
}
