package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
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
 * One tax year of income tax per employee, from approved payroll: income, deductions, taxable income, tax for the year and tax
 * deducted, as the last payslip's projection and the payslips' own TDS lines state them. Form 130 Part B and the fourth
 * quarter's salary detail in the TDS statement (Form 138) both read it, so they cannot disagree.
 *
 * A person appears only when the year is final for them (the projection has one month left: March, or the month they left)
 * and their pay is fully carried on payslips. Anyone else is listed with the reason, never guessed at.
 */
final class AnnualTax {

    static final String NOT_FINAL = "The year is not final";

    private AnnualTax() {}

    /** One person's year. {@code partB} holds the figures; {@code personId} is for looking up the full PAN. */
    static final class Person {
        final String employmentId;
        final String personId;
        final Object membershipId;
        final String code;
        final String name;
        final Map<String, Object> partB;
        final boolean draftRules;

        Person(String employmentId, String personId, Object membershipId, String code, String name, Map<String, Object> partB, boolean draftRules) {
            this.employmentId = employmentId;
            this.personId = personId;
            this.membershipId = membershipId;
            this.code = code;
            this.name = name;
            this.partB = partB;
            this.draftRules = draftRules;
        }
    }

    /** Someone left out, and why. {@code notFinal} means only that the year's last month is not approved yet. */
    record Skip(String employmentId, String code, String name, String reason, boolean notFinal) {}

    record Result(List<Person> people, List<Skip> skipped) {}

    static Result compute(Connection c, String year, Map<String, Object> settings) throws SQLException {
        LocalDate from = TaxYears.start(year);
        LocalDate to = from.plusYears(1);
        Map<String, List<Map<String, Object>>> slips = new LinkedHashMap<>();
        for (Map<String, Object> p : Sql.query(c, "select ps.id, ps.employment_id, ps.membership_id, e.person_id, r.period, ps.info, "
                + "coalesce((r.snapshot->'config'->'rules'->'drafts'->>'income_tax:IN')::boolean, true) as draft_rules from app.payslip ps "
                + "join app.employment e on e.id = ps.employment_id "
                + "join app.payroll_run r on r.id = ps.run_id where r.state in ('approved','paid','released') and r.period >= ? and r.period < ? "
                + "order by ps.employment_id, r.period", from, to)) {
            slips.computeIfAbsent(String.valueOf(p.get("employmentId")), k -> new ArrayList<>()).add(p);
        }
        Map<String, Map<String, Object>> tds = tdsLines(c, from, to);
        Set<String> settled = new HashSet<>();   // everyone with a final settlement, in one query rather than one per person
        for (Map<String, Object> x : Sql.query(c, "select distinct x.employment_id from app.exit_settlement_line l join app.exit_case x on x.id = l.exit_case_id")) {
            settled.add(String.valueOf(x.get("employmentId")));
        }
        List<Person> people = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : slips.entrySet()) {
            List<Map<String, Object>> list = e.getValue();
            Map<String, Object> last = list.getLast();
            Map<String, Object> info = parse(last.get("info"));
            String name = String.valueOf(info.get("name"));
            String code = (String) info.get("code");
            String problem = problem(info, LocalDate.parse(String.valueOf(last.get("period"))));
            boolean notFinal = problem != null && problem.startsWith(NOT_FINAL);
            if (problem == null) {
                problem = unsupported(settled.contains(e.getKey()), list);
            }
            if (problem != null) {
                skipped.add(new Skip(e.getKey(), code, name, problem, notFinal));
                continue;
            }
            Map<String, Object> t = tds.get(String.valueOf(last.get("id")));
            BigDecimal taxable = BigDecimal.ZERO;
            for (Map<String, Object> p : list) {
                taxable = taxable.add(PayrollEngine.dec(PayrollEngine.map(parse(p.get("info")), "bases"), "taxable"));
            }
            Map<String, Object> partB = partB(info, settings, year, taxable, t == null ? BigDecimal.ZERO : PayrollEngine.dec(t, "amount"));
            people.add(new Person(e.getKey(), String.valueOf(last.get("personId")), last.get("membershipId"), code, name, partB,
                    Boolean.TRUE.equals(last.get("draftRules"))));   // the rules the last payslip's run was frozen with
        }
        return new Result(people, skipped);
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
    private static String unsupported(boolean hasSettlement, List<Map<String, Object>> slips) {
        Set<Object> months = new HashSet<>();
        for (Map<String, Object> p : slips) {
            if (!months.add(p.get("period"))) {
                return "They have more than one payslip in a month, which Part B cannot combine yet";
            }
        }
        if (hasSettlement) {
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
            return NOT_FINAL + ": their last payslip is for " + YearMonth.from(lastPeriod) + " and the tax projection still has "
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
