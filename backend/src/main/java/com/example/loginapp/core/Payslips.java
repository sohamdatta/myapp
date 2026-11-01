package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.loginapp.core.PayrollRuns.monthWords;
import static com.example.loginapp.core.PayrollRuns.parse;
import static com.example.loginapp.core.PayrollRuns.taxYearStart;

/**
 * A payslip as payroll and the employee see it: its lines, the year so far, and why it changed from the
 * month before. Shared by the run's review screens, My pay and the payslip PDF.
 */
final class Payslips {

    private Payslips() {
    }

    /** A payslip with its lines, explanations and why it changed from the one before. */
    static Map<String, Object> payslip(Connection c, UUID payslipId, boolean forPayroll) throws SQLException {
        Map<String, Object> ps = Sql.one(c, "select ps.id, ps.run_id, ps.employment_id, ps.days_in_period, ps.days_paid, ps.lop_days, ps.gross, "
                + "ps.deductions, ps.net, ps.employer_cost, ps.held, ps.info, r.period, r.state, r.paid_on from app.payslip ps "
                + "join app.payroll_run r on r.id = ps.run_id where ps.id = ?", payslipId);
        if (ps == null) {
            throw ApiException.notFound("The payslip");
        }
        ps.put("info", parse(ps.get("info")));
        LocalDate period = LocalDate.parse((String) ps.get("period"));
        ps.put("label", monthWords(period));
        List<Map<String, Object>> lines = Sql.query(c, "select l.code, l.name, l.kind, l.amount, l.unrounded, l.full_month, l.trace, "
                + "r.status as rule_status from app.payslip_line l left join app.payroll_rule r on r.id = l.rule_id where l.payslip_id = ? "
                + "order by l.position, l.code", payslipId);
        for (Map<String, Object> l : lines) {
            Map<String, Object> trace = parse(l.get("trace"));
            if (!forPayroll) {
                // Employees see the reason in words, not the working of formulas and rounding.
                Map<String, Object> plain = new LinkedHashMap<>();
                for (String k : List.of("prorated", "working")) {
                    if (trace.get(k) != null) {
                        plain.put(k, trace.get(k));
                    }
                }
                trace = plain;
                l.remove("unrounded");
                l.remove("ruleStatus");
            }
            l.put("trace", trace);
        }
        ps.put("lines", lines);
        Map<String, Object> ytd = Sql.one(c, "select coalesce(sum(ps.gross), 0) as gross, coalesce(sum(ps.deductions), 0) as deductions, "
                + "coalesce(sum(ps.net), 0) as net from app.payslip ps join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ?::uuid "
                + "and r.state in ('approved','paid','released') and r.period between ? and ?", ps.get("employmentId"), taxYearStart(period), period);
        ps.put("ytd", ytd);
        ps.put("changes", whyChanged(c, ps, lines));
        return ps;
    }

    /** Each difference from the previous approved payslip, with its cause, largest first. */
    static Map<String, Object> whyChanged(Connection c, Map<String, Object> ps, List<Map<String, Object>> lines) throws SQLException {
        Map<String, Object> before = Sql.one(c, "select ps.id, ps.days_paid, ps.days_in_period, ps.net, ps.info, r.period from app.payslip ps "
                + "join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ?::uuid and r.period < ?::date and r.state in ('approved','paid','released') "
                + "order by r.period desc limit 1", ps.get("employmentId"), ps.get("period"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (before == null) {
            out.put("first", true);
            return out;
        }
        Map<String, Object> infoBefore = parse(before.get("info"));
        Map<String, Object> infoNow = PayrollEngine.map(ps, "info");
        List<PayChange.Line> was = new ArrayList<>();
        for (Map<String, Object> l : Sql.query(c, "select code, name, kind, amount from app.payslip_line where payslip_id = ?::uuid and kind in "
                + "('earning','deduction')", before.get("id"))) {
            was.add(new PayChange.Line((String) l.get("code"), (String) l.get("name"), signed(l), false));
        }
        List<PayChange.Line> now = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if (Set.of("earning", "deduction").contains(l.get("kind"))) {
                now.add(new PayChange.Line((String) l.get("code"), (String) l.get("name"), signed(l),
                        "Entered for this month".equals(PayrollEngine.map(l, "trace").get("source"))));
            }
        }
        PayChange.Slip slipBefore = new PayChange.Slip(PayrollEngine.dec(before, "daysPaid"), PayrollEngine.dec(before, "daysInPeriod"),
                PayrollEngine.dec(infoBefore, "annual"), String.valueOf(infoBefore.get("compensationFrom")), was);
        PayChange.Slip slipNow = new PayChange.Slip(PayrollEngine.dec(ps, "daysPaid"), PayrollEngine.dec(ps, "daysInPeriod"),
                PayrollEngine.dec(infoNow, "annual"), String.valueOf(infoNow.get("compensationFrom")), now);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PayChange.Cause cause : PayChange.diff(slipBefore, slipNow)) {
            items.add(Map.of("cause", cause.text(), "amount", cause.amount()));
        }
        out.put("previousLabel", monthWords(LocalDate.parse((String) before.get("period"))));
        out.put("previousNet", before.get("net"));
        out.put("difference", PayrollEngine.dec(ps, "net").subtract(PayrollEngine.dec(before, "net")));
        out.put("items", items);
        return out;
    }

    private static BigDecimal signed(Map<String, Object> l) {
        BigDecimal a = PayrollEngine.dec(l, "amount");
        return "deduction".equals(l.get("kind")) ? a.negate() : a;
    }
}
