package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out one employee's month from a frozen snapshot: nothing here reads the database or the
 * clock, so the same snapshot always gives the same lines. The steps follow the design:
 *
 * <ol>
 *   <li>the structure, starting figure and overrides in force;</li>
 *   <li>full-month amounts, in the order the formulas need;</li>
 *   <li>days paid, and proration of the components that prorate;</li>
 *   <li>this month's inputs;</li>
 *   <li>statutory bases, with the deemed-wage add-back;</li>
 *   <li>PF, ESI, professional tax, labour welfare fund;</li>
 *   <li>income tax, projected over the year and spread over the months left;</li>
 *   <li>net pay, and the checks that become exceptions.</li>
 * </ol>
 *
 * Config and employee are the maps written into the run's snapshot (see PayrollSnapshot.snapshot).
 */
public final class PayrollEngine {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final BigDecimal ZERO = BigDecimal.ZERO;

    private PayrollEngine() {}

    /** The facts a formula may read, with their kind, for checking formulas when they are saved. */
    public static final Map<String, Formula.Type> FACTS = Map.ofEntries(
            Map.entry("employment.type", Formula.Type.TEXT),
            Map.entry("designation", Formula.Type.TEXT),
            Map.entry("department", Formula.Type.TEXT),
            Map.entry("gender", Formula.Type.TEXT),
            Map.entry("location.state", Formula.Type.TEXT),
            Map.entry("location.metro", Formula.Type.BOOL),
            Map.entry("days.in_month", Formula.Type.NUMBER),
            Map.entry("days.paid", Formula.Type.NUMBER),
            Map.entry("days.lop", Formula.Type.NUMBER),
            Map.entry("period.month", Formula.Type.NUMBER),
            Map.entry("period.tax_month", Formula.Type.NUMBER),
            Map.entry("months_of_service", Formula.Type.NUMBER));

    /** Names in capitals that are not components but the starting figure. */
    static final Set<String> STARTING = Set.of("CTC_MONTHLY", "GROSS_MONTHLY", "CTC_ANNUAL", "GROSS_ANNUAL");

    // ── the result ──────────────────────────────────────────────────────

    public static final class Calc {
        final List<Map<String, Object>> lines = new ArrayList<>();
        final List<Map<String, Object>> exceptions = new ArrayList<>();
        final Map<String, Object> info = new LinkedHashMap<>();
        BigDecimal gross = ZERO;
        BigDecimal deductions = ZERO;
        BigDecimal employer = ZERO;
        BigDecimal net = ZERO;
        BigDecimal daysInPeriod = ZERO;
        BigDecimal daysPaid = ZERO;
        BigDecimal lopDays = ZERO;
        boolean calculated;

        public List<Map<String, Object>> lines() {
            return lines;
        }

        public List<Map<String, Object>> exceptions() {
            return exceptions;
        }

        public Map<String, Object> info() {
            return info;
        }

        public BigDecimal gross() {
            return gross;
        }

        public BigDecimal deductions() {
            return deductions;
        }

        public BigDecimal employer() {
            return employer;
        }

        public BigDecimal net() {
            return net;
        }

        public BigDecimal daysInPeriod() {
            return daysInPeriod;
        }

        public BigDecimal daysPaid() {
            return daysPaid;
        }

        public BigDecimal lopDays() {
            return lopDays;
        }

        public boolean calculated() {
            return calculated;
        }

        void exception(String code, String level, boolean acceptable, String message, String link) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("code", code);
            e.put("level", level);
            e.put("acceptable", acceptable);
            e.put("message", message);
            e.put("link", link);
            exceptions.add(e);
        }

        BigDecimal amount(String code) {
            for (Map<String, Object> l : lines) {
                if (code.equals(l.get("code"))) {
                    return (BigDecimal) l.get("amount");
                }
            }
            return ZERO;
        }
    }

    // ── calculating ─────────────────────────────────────────────────────

    public static Calc calculate(Map<String, Object> config, Map<String, Object> emp, LocalDate period) {
        Calc out = new Calc();
        Map<String, Object> components = map(config, "components");
        Map<String, Object> settings = map(config, "settings");
        Map<String, Object> days = map(emp, "days");
        out.daysInPeriod = dec(days, "base");
        out.daysPaid = dec(days, "paid");
        out.lopDays = dec(days, "lop");
        info(out, emp);

        Map<String, Object> structure = emp.get("structureId") == null ? null : map(map(config, "structures"), (String) emp.get("structureId"));
        if (structure == null) {
            out.exception("not_assigned", "blocking", false, "No salary is set. Set it on their record under Pay.", "people");
            return out;
        }
        if (out.daysPaid.signum() <= 0) {
            out.exception("zero_days_paid", "warning", false, "Paid for 0 days this month.", null);
        }

        // 1–2. Full-month amounts, in dependency order.
        BigDecimal annual = dec(emp, "annual");
        String basis = (String) structure.get("basis");
        Map<String, BigDecimal> full = new HashMap<>();
        full.put(basis.equals("gross") ? "GROSS_MONTHLY" : "CTC_MONTHLY", annual.divide(BigDecimal.valueOf(12), MC));
        full.put(basis.equals("gross") ? "GROSS_ANNUAL" : "CTC_ANNUAL", annual);
        Map<String, Map<String, Object>> traces = new HashMap<>();
        List<Map<String, Object>> structureLines = list(structure, "lines");
        Map<String, String> formulas = new LinkedHashMap<>();
        for (Map<String, Object> l : structureLines) {
            String code = (String) l.get("code");
            Map<String, Object> c = map(components, code);
            if (c == null) {
                continue;   // retired since the structure was saved; the structure screen says so
            }
            String f = (String) l.get("formula");
            if (f == null || f.isBlank()) {
                f = (String) c.get("formula");
            }
            formulas.put(code, f);
        }
        Map<String, Object> overrides = map(emp, "overrides");
        Map<String, Object> facts = facts(emp, period);
        Formula.Env env = env(full, facts, config, emp);
        List<String> order;
        try {
            order = order(formulas, components);
        } catch (Formula.Error e) {
            out.exception("formula_failed", "blocking", false, e.getMessage(), "payroll-setup");
            return out;
        }
        for (String code : order) {
            Map<String, Object> c = map(components, code);
            Map<String, Object> trace = new LinkedHashMap<>();
            if (overrides.containsKey(code)) {
                Map<String, Object> o = map(overrides, code);
                full.put(code, dec(o, "amount"));
                trace.put("source", "Override from " + o.get("from") + ": " + o.get("reason"));
                traces.put(code, trace);
                continue;
            }
            if (Boolean.TRUE.equals(c.get("statutory"))) {
                full.put(code, statutoryFull(code, full, structureLines, components, config, emp));
                trace.put("source", "Worked out by the rule pack for a full month, to balance the structure");
                traces.put(code, trace);
                continue;
            }
            if ("input".equals(c.get("amountType"))) {
                full.put(code, ZERO);
                continue;
            }
            String f = formulas.get(code);
            try {
                Formula.Result r = Formula.parse(f).evaluate(env);
                full.put(code, r.value());
                trace.put("formula", f);
                trace.put("read", r.read());
                trace.put("source", structure.get("name") + " structure, version of " + structure.get("validFrom"));
                if (r.dividedByZero()) {
                    out.exception("formula_division", "warning", false, c.get("name") + ": a division by zero gave 0.", "payroll-setup");
                }
            } catch (Formula.Error e) {
                out.exception("formula_failed", "blocking", false, c.get("name") + " could not be worked out: " + e.getMessage() + ".",
                        "payroll-setup");
                full.put(code, ZERO);
            }
            traces.put(code, trace);
        }
        if (out.exceptions.stream().anyMatch(e -> "formula_failed".equals(e.get("code")))) {
            return out;
        }

        // 3. Proration.
        BigDecimal base = out.daysInPeriod;
        BigDecimal paid = out.daysPaid;
        BigDecimal factor = base.signum() == 0 ? ZERO : paid.divide(base, MC);
        String daysWords = plain(paid) + " of " + plain(base) + " days paid";
        int position = 0;
        for (String code : orderForPayslip(order, components)) {
            Map<String, Object> c = map(components, code);
            if (Boolean.TRUE.equals(c.get("statutory")) || "input".equals(c.get("amountType"))) {
                continue;
            }
            BigDecimal f = full.get(code);
            boolean prorate = Boolean.TRUE.equals(c.get("prorate"));
            BigDecimal raw = prorate ? f.multiply(factor, MC) : f;
            Map<String, Object> trace = traces.getOrDefault(code, new LinkedHashMap<>());
            trace.put("fullMonth", scale(f));
            if (prorate && factor.compareTo(BigDecimal.ONE) != 0) {
                trace.put("prorated", daysWords + " (" + settings.get("proration") + ")");
            }
            addLine(out, c, raw, scale(f), null, trace, position++);
        }

        // 4. This month's inputs.
        Map<String, Object> inputs = map(emp, "inputs");
        for (Map.Entry<String, Object> in : inputs.entrySet()) {
            Map<String, Object> c = map(components, in.getKey());
            if (c == null) {
                continue;
            }
            BigDecimal amount = new BigDecimal(String.valueOf(in.getValue()));
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("source", "Entered for this month");
            addLine(out, c, amount, null, null, trace, position++);
            if ("earning".equals(c.get("kind")) && amount.compareTo(dec(settings, "largeOneTime")) > 0) {
                out.exception("large_one_time", "warning", false, c.get("name") + " of " + rupees(amount) + ", above the "
                        + rupees(dec(settings, "largeOneTime")) + " threshold.", null);
            }
        }

        // 5. Statutory bases.
        BigDecimal wages = ZERO;
        BigDecimal conditional = ZERO;
        BigDecimal remuneration = ZERO;
        BigDecimal esiWage = ZERO;
        BigDecimal taxable = ZERO;
        BigDecimal esiWageFull = ZERO;
        for (Map<String, Object> l : out.lines) {
            if (!"earning".equals(l.get("kind"))) {
                continue;
            }
            Map<String, Object> c = map(components, (String) l.get("code"));
            BigDecimal a = (BigDecimal) l.get("amount");
            boolean oneTime = Boolean.TRUE.equals(c.get("oneTime"));
            if (!oneTime) {
                remuneration = remuneration.add(a);
                switch ((String) c.get("wage")) {
                    case "include" -> wages = wages.add(a);
                    case "conditional" -> conditional = conditional.add(a);
                    default -> { }
                }
            }
            if (Boolean.TRUE.equals(c.get("esiWage"))) {
                esiWage = esiWage.add(a);
                esiWageFull = esiWageFull.add(l.get("fullMonth") == null ? a : (BigDecimal) l.get("fullMonth"));
            }
            if (Boolean.TRUE.equals(c.get("taxable"))) {
                taxable = taxable.add(a);
            }
        }
        BigDecimal half = remuneration.divide(BigDecimal.valueOf(2), MC);
        BigDecimal addBack = wages.compareTo(half) < 0 ? conditional.min(half.subtract(wages)) : ZERO;
        BigDecimal pfWage = wages.add(addBack);
        Map<String, Object> bases = new LinkedHashMap<>();
        bases.put("wages", scale(wages));
        bases.put("addedBack", scale(addBack));
        bases.put("pfWage", scale(pfWage));
        bases.put("esiWage", scale(esiWage));
        bases.put("taxable", scale(taxable));
        out.info.put("bases", bases);

        // 6. PF, ESI, PT, LWF.
        Map<String, Object> rules = map(config, "rules");
        Map<String, Object> profile = map(emp, "profile");
        if (components.containsKey("PF_EE")) {
            pf(out, rules, profile, settings, components, pfWage, wages, addBack, position);
            position += 2;
        }
        esi(out, rules, profile, components, emp, esiWage, esiWageFull, paid, position);
        position += 2;
        String state = (String) emp.get("state");
        BigDecimal grossNow = sumKind(out, "earning");
        if (state == null) {
            out.exception("missing_state", "blocking", true,
                    "No work location with a state: professional tax and labour welfare fund are not deducted.", "attendance");
        } else {
            pt(out, rules, profile, components, emp, state, grossNow, period, position++);
            lwf(out, rules, profile, components, state, period, position);
            position += 2;
        }
        registrations(out, config, state);

        // 7. Income tax.
        tds(out, rules, emp, components, full, order, taxable, period, position++);

        // 8. Totals and checks.
        out.gross = sumKind(out, "earning");
        out.deductions = sumKind(out, "deduction");
        out.employer = sumKind(out, "employer");
        out.net = out.gross.subtract(out.deductions);
        out.calculated = true;
        if (out.net.signum() < 0) {
            out.exception("net_negative", "blocking", false, "Net pay would be " + rupees(out.net)
                    + ". Reduce a recovery or one-time deduction.", null);
        }
        if (!Boolean.TRUE.equals(emp.get("hasBank"))) {
            out.exception("missing_bank", "blocking", true, "No bank account: they will be left out of the bank file.", "people");
        }
        if (!Boolean.TRUE.equals(emp.get("hasPan"))) {
            out.exception("missing_pan", "blocking", true,
                    "No PAN: tax should be deducted at the higher rate and no Form 130 can be issued.", "people");
        }
        if (out.amount("PF_EE").signum() > 0 && !Boolean.TRUE.equals(emp.get("hasUan"))) {
            out.exception("uan_missing", "warning", false, "PF is deducted but there is no UAN; the PF return line would be rejected.",
                    "people");
        }
        Map<String, Object> previous = map(emp, "previous");
        if (previous.get("net") != null) {
            BigDecimal before = dec(previous, "net");
            if (before.signum() > 0) {
                BigDecimal change = out.net.subtract(before).multiply(HUNDRED).divide(before, 1, RoundingMode.HALF_UP);
                if (change.abs().compareTo(dec(settings, "variancePct")) > 0) {
                    out.exception("variance", "warning", false, "Net pay is " + change.abs().stripTrailingZeros().toPlainString() + "% "
                            + (change.signum() > 0 ? "higher" : "lower") + " than last month (threshold "
                            + dec(settings, "variancePct").stripTrailingZeros().toPlainString() + "%).", null);
                }
            }
        }
        return out;
    }

    // ── steps ───────────────────────────────────────────────────────────

    private static void info(Calc out, Map<String, Object> emp) {
        for (String k : List.of("name", "code", "designation", "department", "location", "state", "panLast4", "uanLast4", "bankLast4",
                "joinedOn", "leavingOn")) {
            out.info.put(k, emp.get(k));
        }
        out.info.put("regime", map(emp, "profile").getOrDefault("taxRegime", "new"));
    }

    static Map<String, Object> facts(Map<String, Object> emp, LocalDate period) {
        Map<String, Object> days = map(emp, "days");
        Map<String, Object> f = new HashMap<>();
        f.put("employment.type", str(emp.get("type")));
        f.put("designation", str(emp.get("designation")));
        f.put("department", str(emp.get("department")));
        f.put("gender", str(emp.get("gender")));
        f.put("location.state", str(emp.get("state")));
        f.put("location.metro", Boolean.TRUE.equals(emp.get("metro")));
        f.put("days.in_month", dec(days, "base"));
        f.put("days.paid", dec(days, "paid"));
        f.put("days.lop", dec(days, "lop"));
        f.put("period.month", BigDecimal.valueOf(period.getMonthValue()));
        f.put("period.tax_month", BigDecimal.valueOf(taxMonth(period)));
        f.put("months_of_service", emp.get("monthsOfService") == null ? ZERO : dec(emp, "monthsOfService"));
        return f;
    }

    private static Formula.Env env(Map<String, BigDecimal> full, Map<String, Object> facts, Map<String, Object> config,
                                   Map<String, Object> emp) {
        Map<String, Object> tables = map(config, "tables");
        Map<String, Object> ytd = map(map(emp, "ytd"), "components");
        return new Formula.Env() {
            @Override
            public Object get(String name) {
                if (Formula.isComponent(name)) {
                    BigDecimal v = full.get(name);
                    if (v == null) {
                        if (STARTING.contains(name)) {
                            throw new Formula.Error(name + " is not the starting figure of this structure", -1);
                        }
                        return ZERO;   // a component not in this structure counts as nothing
                    }
                    return v;
                }
                Object v = facts.get(name);
                if (v == null) {
                    throw new Formula.Error("Unknown name " + name, -1);
                }
                return v;
            }

            @Override
            public BigDecimal table(String name, String key) {
                Map<String, Object> t = map(tables, name);
                if (t.isEmpty() && !tables.containsKey(name)) {
                    throw new Formula.Error("No table called " + name, -1);
                }
                for (Map.Entry<String, Object> e : t.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(key)) {
                        return new BigDecimal(String.valueOf(e.getValue()));
                    }
                }
                throw new Formula.Error("No row for " + (key.isEmpty() ? "an empty value" : key) + " in the table " + name, -1);
            }

            @Override
            public BigDecimal ytd(String code) {
                Object v = ytd.get(code);
                return v == null ? ZERO : new BigDecimal(String.valueOf(v));
            }
        };
    }

    /**
     * The order the structure's components must be worked out in. A statutory employer component
     * (PF_ER) needs the components that are wages first. A loop is refused, naming it.
     */
    static List<String> order(Map<String, String> formulas, Map<String, Object> components) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : formulas.entrySet()) {
            String code = e.getKey();
            Map<String, Object> c = map(components, code);
            Set<String> d = new HashSet<>();
            if (Boolean.TRUE.equals(c.get("statutory"))) {
                for (String other : formulas.keySet()) {
                    if (!other.equals(code) && "include".equals(map(components, other).get("wage"))) {
                        d.add(other);
                    }
                }
            } else if (e.getValue() != null && !"input".equals(c.get("amountType"))) {
                for (String ref : Formula.parse(e.getValue()).components()) {
                    if (formulas.containsKey(ref)) {
                        d.add(ref);
                    }
                }
            }
            deps.put(code, d);
        }
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String code : deps.keySet()) {
            visit(code, deps, done, visiting, out, new ArrayList<>());
        }
        return out;
    }

    private static void visit(String code, Map<String, Set<String>> deps, Set<String> done, Set<String> visiting, List<String> out,
                              List<String> path) {
        if (done.contains(code)) {
            return;
        }
        if (visiting.contains(code)) {
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(code), path.size()));
            loop.add(code);
            throw new Formula.Error("The formulas go round in a loop: " + String.join(" uses ", loop), -1);
        }
        visiting.add(code);
        path.add(code);
        for (String d : deps.getOrDefault(code, Set.of())) {
            visit(d, deps, done, visiting, out, path);
        }
        path.remove(path.size() - 1);
        visiting.remove(code);
        done.add(code);
        out.add(code);
    }

    private static List<String> orderForPayslip(List<String> order, Map<String, Object> components) {
        List<String> out = new ArrayList<>(order);
        out.sort((a, b) -> Integer.compare(intOf(map(components, a).get("position")), intOf(map(components, b).get("position"))));
        return out;
    }

    private static BigDecimal statutoryFull(String code, Map<String, BigDecimal> full, List<Map<String, Object>> lines,
                                            Map<String, Object> components, Map<String, Object> config, Map<String, Object> emp) {
        Map<String, Object> profile = map(emp, "profile");
        if (code.equals("PF_ER")) {
            if (!Boolean.TRUE.equals(profile.get("pfMember"))) {
                return ZERO;
            }
            BigDecimal wages = ZERO;
            for (Map<String, Object> l : lines) {
                String c = (String) l.get("code");
                if ("include".equals(map(components, c).get("wage")) && full.containsKey(c)) {
                    wages = wages.add(full.get(c));
                }
            }
            Map<String, Object> pf = map(map(config, "rules"), "pf");
            BigDecimal pfBase = pfBase(wages, profile, map(config, "settings"), dec(pf, "ceiling"));
            return round(pfBase.multiply(dec(pf, "employerRate")).divide(HUNDRED, MC), "nearest");
        }
        return ZERO;   // other statutory components are not part of a structure's full month
    }

    private static BigDecimal pfBase(BigDecimal wages, Map<String, Object> profile, Map<String, Object> settings, BigDecimal ceiling) {
        boolean full = Boolean.TRUE.equals(profile.get("pfFullWages")) || !Boolean.TRUE.equals(settings.get("pfRestrict"));
        return full ? wages : wages.min(ceiling);
    }

    private static void pf(Calc out, Map<String, Object> rules, Map<String, Object> profile, Map<String, Object> settings,
                           Map<String, Object> components, BigDecimal pfWage, BigDecimal wages, BigDecimal addBack, int position) {
        Map<String, Object> pf = map(rules, "pf");
        if (!Boolean.TRUE.equals(profile.get("pfMember"))) {
            return;
        }
        BigDecimal ceiling = dec(pf, "ceiling");
        BigDecimal base = pfBase(pfWage, profile, settings, ceiling);
        BigDecimal ee = base.multiply(dec(pf, "employeeRate")).divide(HUNDRED, MC);
        BigDecimal er = base.multiply(dec(pf, "employerRate")).divide(HUNDRED, MC);
        BigDecimal eps = Boolean.TRUE.equals(profile.get("epsEligible"))
                ? round(pfWage.min(ceiling).multiply(dec(pf, "epsRate")).divide(HUNDRED, MC), "nearest") : ZERO;
        Map<String, Object> t = rule(rules, "pf", "IN");
        String wageWords = "PF wage " + rupees(pfWage) + (addBack.signum() > 0
                ? " (wages " + rupees(wages) + " plus " + rupees(addBack) + " added back so that wages are half of pay)" : "");
        t.put("working", wageWords + "; " + (base.compareTo(pfWage) < 0 ? "restricted to the " + rupees(ceiling) + " ceiling; " : "")
                + "12% of " + rupees(base));
        // The wage bases as numbers, for the PF return file (ECR), which must not be rebuilt from the words above.
        t.put("pfWage", scale(pfWage));
        t.put("epfWages", scale(base));
        addLine(out, map(components, "PF_EE"), ee, null, (String) map(rules, "ruleIds").get("pf:IN"), t, position);
        Map<String, Object> t2 = rule(rules, "pf", "IN");
        BigDecimal erRounded = round(er, "nearest");
        t2.put("working", "12% of " + rupees(base) + ": pension (EPS) " + rupees(eps) + ", provident fund " + rupees(erRounded.subtract(eps)));
        boolean epsEligible = Boolean.TRUE.equals(profile.get("epsEligible"));
        t2.put("epsEligible", epsEligible);
        t2.put("epsWages", epsEligible ? scale(pfWage.min(ceiling)) : ZERO);
        t2.put("eps", eps);
        addLine(out, map(components, "PF_ER"), er, null, (String) map(rules, "ruleIds").get("pf:IN"), t2, position + 1);
    }

    private static void esi(Calc out, Map<String, Object> rules, Map<String, Object> profile, Map<String, Object> components,
                            Map<String, Object> emp, BigDecimal wage, BigDecimal wageFull, BigDecimal paid, int position) {
        Map<String, Object> esi = map(rules, "esi");
        if (Boolean.TRUE.equals(profile.get("esiExempt")) || !components.containsKey("ESI_EE") || wage.signum() == 0) {
            return;
        }
        BigDecimal threshold = Boolean.TRUE.equals(emp.get("disability")) ? dec(esi, "thresholdDisability") : dec(esi, "threshold");
        boolean underNow = wageFull.compareTo(threshold) <= 0;
        boolean carried = Boolean.TRUE.equals(emp.get("esiCoveredInPeriod"));
        if (!underNow && !carried) {
            return;
        }
        if (!underNow) {
            out.exception("esi_crossed", "warning", false, "ESI wages of " + rupees(wageFull) + " are above " + rupees(threshold)
                    + "; ESI continues to the end of the contribution period.", null);
        }
        BigDecimal daily = paid.signum() == 0 ? ZERO : wage.divide(paid, MC);
        boolean lowWage = daily.compareTo(dec(esi, "lowWageDaily")) <= 0;
        BigDecimal ee = lowWage ? ZERO : wage.multiply(dec(esi, "employeeRate")).divide(HUNDRED, MC);
        BigDecimal er = wage.multiply(dec(esi, "employerRate")).divide(HUNDRED, MC);
        String id = (String) map(rules, "ruleIds").get("esi:IN");
        Map<String, Object> t = rule(rules, "esi", "IN");
        t.put("working", (lowWage ? "Average daily wage at or below " + rupees(dec(esi, "lowWageDaily")) + ": no employee share" : "0.75% of ESI wage "
                + rupees(wage)) + (carried && !underNow ? "; covered for the whole contribution period" : "") + "; rounded up");
        t.put("esiWages", scale(wage));   // as a number, for the ESI return file
        addLineRounded(out, map(components, "ESI_EE"), ee, "up", id, t, position);
        Map<String, Object> t2 = rule(rules, "esi", "IN");
        t2.put("working", "3.25% of ESI wage " + rupees(wage) + "; rounded up");
        t2.put("esiWages", scale(wage));
        addLineRounded(out, map(components, "ESI_ER"), er, "up", id, t2, position + 1);
    }

    private static void pt(Calc out, Map<String, Object> rules, Map<String, Object> profile, Map<String, Object> components,
                           Map<String, Object> emp, String state, BigDecimal gross, LocalDate period, int position) {
        if (!components.containsKey("PT") || Boolean.TRUE.equals(profile.get("ptExempt"))) {
            return;
        }
        Map<String, Object> pack = map(map(rules, "pt"), state);
        if (pack.isEmpty()) {
            out.exception("missing_state_pack", "blocking", false, "No professional tax rules for " + IdChecks.STATES.getOrDefault(state, state)
                    + " yet. Payroll covers Goa, Karnataka and Maharashtra.", null);
            return;
        }
        boolean woman = "female".equalsIgnoreCase(str(emp.get("gender"))) && pack.containsKey("women");
        List<Map<String, Object>> slabs = list(pack, woman ? "women" : "men");
        BigDecimal amount = ZERO;
        String words = "";
        for (Map<String, Object> s : slabs) {
            BigDecimal from = dec(s, "from");
            Object to = s.get("to");
            if (gross.compareTo(from) >= 0 && (to == null || gross.compareTo(new BigDecimal(String.valueOf(to))) <= 0)) {
                boolean feb = period.getMonthValue() == 2 && s.get("february") != null;
                amount = feb ? dec(s, "february") : dec(s, "monthly");
                words = (woman ? "Women's slab" : "Slab") + " for gross " + rupees(gross) + (feb ? ", February amount" : "");
                break;
            }
        }
        if (pack.get("yearlyCeiling") != null) {
            BigDecimal left = dec(pack, "yearlyCeiling").subtract(dec(map(emp, "ytd"), "pt")).max(ZERO);
            if (amount.compareTo(left) > 0) {
                amount = left;
                words += "; limited to the yearly ceiling";
            }
        }
        Map<String, Object> t = rule(rules, "pt", state);
        t.put("working", words + " (" + IdChecks.STATES.getOrDefault(state, state) + ")");
        addLineRounded(out, map(components, "PT"), amount, "none", (String) map(rules, "ruleIds").get("pt:" + state), t, position);
    }

    private static void lwf(Calc out, Map<String, Object> rules, Map<String, Object> profile, Map<String, Object> components,
                            String state, LocalDate period, int position) {
        if (!components.containsKey("LWF_EE") || Boolean.TRUE.equals(profile.get("lwfExempt"))) {
            return;
        }
        Map<String, Object> pack = map(map(rules, "lwf"), state);
        if (pack.isEmpty()) {
            return;
        }
        boolean due = false;
        for (Object m : list(pack, "months")) {
            if (new BigDecimal(String.valueOf(m)).intValue() == period.getMonthValue()) {
                due = true;
            }
        }
        if (!due) {
            return;
        }
        String id = (String) map(rules, "ruleIds").get("lwf:" + state);
        Map<String, Object> t = rule(rules, "lwf", state);
        t.put("working", "Due this month in " + IdChecks.STATES.getOrDefault(state, state));
        addLineRounded(out, map(components, "LWF_EE"), dec(pack, "employee"), "none", id, t, position);
        addLineRounded(out, map(components, "LWF_ER"), dec(pack, "employer"), "none", id, rule(rules, "lwf", state), position + 1);
    }

    /** One exception per statute and state that needs a registration the entity does not have. */
    private static void registrations(Calc out, Map<String, Object> config, String state) {
        List<String> have = new ArrayList<>();
        if (config.get("registrations") instanceof List<?> raw) {
            for (Object o : raw) {
                have.add(String.valueOf(o));
            }
        }
        if (out.amount("PF_EE").signum() > 0 && !have.contains("pf")) {
            out.exception("missing_registration:pf", "blocking", false, "Employees have PF but the Organization has no PF establishment code.",
                    "payroll-setup");
        }
        if (out.amount("ESI_ER").signum() > 0 && !have.contains("esi")) {
            out.exception("missing_registration:esi", "blocking", false, "Employees are covered by ESI but there is no ESI code.",
                    "payroll-setup");
        }
        if (state != null && out.amount("PT").signum() > 0 && !have.contains("pt:" + state)) {
            out.exception("missing_registration:pt:" + state, "blocking", false, "Employees in " + IdChecks.STATES.getOrDefault(state, state)
                    + " pay professional tax but there is no PT registration there.", "payroll-setup");
        }
        if (state != null && out.amount("LWF_EE").signum() > 0 && !have.contains("lwf:" + state)) {
            out.exception("missing_registration:lwf:" + state, "blocking", false, "Labour welfare fund is due in "
                    + IdChecks.STATES.getOrDefault(state, state) + " but there is no LWF registration there.", "payroll-setup");
        }
    }

    private static void tds(Calc out, Map<String, Object> rules, Map<String, Object> emp, Map<String, Object> components,
                            Map<String, BigDecimal> full, List<String> order, BigDecimal taxableNow, LocalDate period, int position) {
        if (!components.containsKey("TDS")) {
            return;
        }
        Map<String, Object> tax = map(rules, "tax");
        String regime = String.valueOf(map(emp, "profile").getOrDefault("taxRegime", "new"));
        Map<String, Object> r = map(tax, regime);
        Map<String, Object> ytd = map(emp, "ytd");
        int left = monthsLeft(period, (String) emp.get("leavingOn"));   // including this month
        BigDecimal recurring = ZERO;
        for (String code : order) {
            Map<String, Object> c = map(components, code);
            if ("earning".equals(c.get("kind")) && Boolean.TRUE.equals(c.get("taxable")) && !Boolean.TRUE.equals(c.get("oneTime"))
                    && !Boolean.TRUE.equals(c.get("statutory"))) {
                recurring = recurring.add(full.getOrDefault(code, ZERO));
            }
        }
        BigDecimal later = BigDecimal.valueOf(left - 1L);
        BigDecimal annualIncome = dec(ytd, "taxable").add(taxableNow).add(recurring.multiply(later));
        BigDecimal deductions = dec(r, "standardDeduction");
        List<String> deductionWords = new ArrayList<>(List.of("standard deduction " + rupees(dec(r, "standardDeduction"))));
        Map<String, Object> declared = map(emp, "declarations");
        Map<String, Object> claims = null;
        if (regime.equals("old")) {
            BigDecimal pt = dec(ytd, "pt").add(out.amount("PT").multiply(BigDecimal.valueOf(left)));
            deductions = deductions.add(pt);
            deductionWords.add("professional tax " + rupees(pt));
            BigDecimal pfEe = dec(ytd, "pfEe").add(out.amount("PF_EE").multiply(BigDecimal.valueOf(left)));
            if (declared.isEmpty()) {
                BigDecimal counted = pfEe.min(dec(r, "section80cMax"));
                deductions = deductions.add(counted);
                deductionWords.add("PF under 80C " + rupees(counted));
            } else {
                Map<String, BigDecimal> salary = new LinkedHashMap<>();
                for (String code : List.of("BASIC", "HRA")) {
                    salary.put(code, dec(map(ytd, "components"), code).add(out.amount(code))
                            .add(full.getOrDefault(code, ZERO).multiply(later)));
                }
                Claims counted = declarationClaims(r, declared, pfEe, salary.get("BASIC"), salary.get("HRA"),
                        payMonths(period, (String) emp.get("joinedOn"), left));
                if (counted.missingLimits()) {
                    out.exception("declaration_limits_missing", "warning", false, "The income tax rules in use carry no limits for "
                            + "declared claims, so declared claims count as nothing. Ask for the rules to be updated.", null);
                }
                deductions = deductions.add(counted.total());
                deductionWords.addAll(counted.words());
                claims = counted.detail();
            }
        }
        BigDecimal taxableIncome = annualIncome.subtract(deductions).max(ZERO);
        BigDecimal annualTax = incomeTax(r, dec(tax, "cess"), taxableIncome);
        BigDecimal paidSoFar = dec(ytd, "tds");
        BigDecimal due = annualTax.subtract(paidSoFar).max(ZERO);
        BigDecimal now = due.divide(BigDecimal.valueOf(left), MC);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("regime", regime);
        projection.put("income", scale(annualIncome));
        projection.put("deductions", scale(deductions));
        projection.put("taxableIncome", scale(taxableIncome));
        if (claims != null) {
            projection.put("declarationBasis", declared.get("basis"));
            projection.put("claims", claims);
        } else if (!declared.isEmpty()) {
            // Declared under the new regime: shown, but they change nothing.
            projection.put("declarationBasis", declared.get("basis"));
            projection.put("claimsIgnored", true);
        }
        projection.put("annualTax", scale(annualTax));
        projection.put("paidSoFar", scale(paidSoFar));
        projection.put("monthsLeft", left);
        out.info.put("tax", projection);
        Map<String, Object> t = rule(rules, "income_tax", "IN");
        t.put("working", (regime.equals("new") ? "New" : "Old") + " regime: projected income " + rupees(annualIncome)
                + " (so far " + rupees(dec(ytd, "taxable")) + ", this month " + rupees(taxableNow) + ", " + (left - 1) + " more months at "
                + rupees(recurring) + "); less " + String.join(", ", deductionWords) + "; taxable " + rupees(taxableIncome)
                + "; tax for the year " + rupees(annualTax) + ", deducted so far " + rupees(paidSoFar) + ", spread over " + left
                + (left == 1 ? " month" : " months"));
        addLineRounded(out, map(components, "TDS"), now, "nearest", (String) map(rules, "ruleIds").get("income_tax:IN"), t, position);
    }

    /** What declared claims take off the year's income: the total, each part in words, and the parts for the payslip. */
    public record Claims(BigDecimal total, List<String> words, Map<String, Object> detail, boolean missingLimits) {}

    /**
     * Declared (or verified) claims under the old regime, each within its limit from the rules:
     * HRA exempt for each span of rent at the least of the HRA received, rent above a share of basic, and
     * half (or 40%) of basic; the ₹1.5 lakh group with the employee's PF in it; NPS; health insurance for
     * the family and for parents, higher for senior citizens; home-loan interest; education-loan interest.
     * Basic and HRA are the year's projected amounts, spread evenly over the months paid.
     */
    public static Claims declarationClaims(Map<String, Object> regime, Map<String, Object> declared, BigDecimal pfEe,
                                           BigDecimal basicYear, BigDecimal hraYear, int payMonths) {
        boolean missing = regime.get("npsMax") == null;
        List<String> words = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        BigDecimal total = ZERO;

        // House rent: one span at a time, never more than the HRA received in all.
        BigDecimal months = BigDecimal.valueOf(Math.max(payMonths, 1));
        BigDecimal basicMonth = basicYear.divide(months, MC);
        BigDecimal hraMonth = hraYear.divide(months, MC);
        BigDecimal hraExempt = ZERO;
        if (!missing && hraYear.signum() > 0) {
            for (Map<String, Object> span : list(declared, "rent")) {
                BigDecimal n = dec(span, "months");
                BigDecimal rent = dec(span, "monthly").multiply(n);
                BigDecimal basic = basicMonth.multiply(n);
                boolean metro = regime.get("hraMetros") instanceof List<?> cities && cities.contains(span.get("city"));
                BigDecimal pct = metro ? dec(regime, "hraMetroPct") : dec(regime, "hraOtherPct");
                BigDecimal aboveFloor = rent.subtract(basic.multiply(dec(regime, "hraRentFloorPct")).divide(HUNDRED, MC)).max(ZERO);
                hraExempt = hraExempt.add(hraMonth.multiply(n).min(aboveFloor).min(basic.multiply(pct).divide(HUNDRED, MC)));
            }
            hraExempt = scale(hraExempt.min(hraYear));
        }
        if (hraExempt.signum() > 0) {
            total = total.add(hraExempt);
            words.add("HRA exempt " + rupees(hraExempt));
        }
        detail.put("hraExempt", hraExempt);

        // The ₹1.5 lakh group: PF first, then what was declared.
        BigDecimal group = missing ? pfEe : pfEe.add(dec(declared, "sec80c"));
        BigDecimal groupCounted = group.min(dec(regime, "section80cMax"));
        total = total.add(groupCounted);
        words.add("₹1.5 lakh group " + rupees(groupCounted) + (missing || dec(declared, "sec80c").signum() == 0 ? " (PF)"
                : " (PF " + rupees(pfEe) + " and declared " + rupees(dec(declared, "sec80c")) + ")"));
        detail.put("group", scale(groupCounted));
        if (missing) {
            detail.put("total", scale(total));
            return new Claims(total, words, detail, true);
        }

        BigDecimal nps = dec(declared, "nps").min(dec(regime, "npsMax"));
        BigDecimal health = dec(declared, "healthSelf").min(Boolean.TRUE.equals(declared.get("healthSelfSenior"))
                        ? dec(regime, "healthSelfSeniorMax") : dec(regime, "healthSelfMax"))
                .add(dec(declared, "healthParents").min(Boolean.TRUE.equals(declared.get("healthParentsSenior"))
                        ? dec(regime, "healthParentsSeniorMax") : dec(regime, "healthParentsMax")));
        BigDecimal homeLoan = dec(declared, "homeLoan").min(dec(regime, "homeLoanInterestMax"));
        BigDecimal eduLoan = dec(declared, "eduLoan");
        for (Object[] part : new Object[][] {{"nps", "NPS", nps}, {"health", "health insurance", health},
                {"homeLoan", "home-loan interest", homeLoan}, {"eduLoan", "education-loan interest", eduLoan}}) {
            BigDecimal v = scale((BigDecimal) part[2]);
            detail.put((String) part[0], v);
            if (v.signum() > 0) {
                total = total.add(v);
                words.add(part[1] + " " + rupees(v));
            }
        }
        detail.put("total", scale(total));
        return new Claims(total, words, detail, false);
    }

    /** Months of this tax year with pay: from April, or the month of joining, through the months left. */
    static int payMonths(LocalDate period, String joinedOn, int left) {
        int before = taxMonth(period) - 1;
        if (joinedOn != null) {
            LocalDate joined = LocalDate.parse(joinedOn);
            LocalDate yearStart = LocalDate.of(period.getMonthValue() >= 4 ? period.getYear() : period.getYear() - 1, 4, 1);
            if (joined.isAfter(yearStart)) {
                int notYet = (joined.getYear() - yearStart.getYear()) * 12 + joined.getMonthValue() - yearStart.getMonthValue();
                before = Math.max(0, before - notYet);
            }
        }
        return before + left;
    }

    /** Tax on a year's taxable income: slabs, the rebate with marginal relief, surcharge with marginal relief, then cess. */
    public static BigDecimal incomeTax(Map<String, Object> regime, BigDecimal cessPct, BigDecimal income) {
        BigDecimal tax = slabTax(regime, income);
        BigDecimal limit = dec(regime, "rebateLimit");
        if (income.compareTo(limit) <= 0) {
            tax = tax.subtract(tax.min(dec(regime, "rebateMax")));
        } else if (Boolean.TRUE.equals(regime.get("rebateMarginalRelief"))) {
            // New regime: tax above the rebate limit is at most the income above it.
            tax = tax.min(income.subtract(limit));
        }
        BigDecimal surcharge = ZERO;
        List<Map<String, Object>> brackets = list(regime, "surcharge");
        for (int i = brackets.size() - 1; i >= 0; i--) {
            BigDecimal above = dec(brackets.get(i), "above");
            if (income.compareTo(above) > 0) {
                surcharge = tax.multiply(dec(brackets.get(i), "rate")).divide(HUNDRED, MC);
                BigDecimal lowerRate = i == 0 ? ZERO : dec(brackets.get(i - 1), "rate");
                BigDecimal atThreshold = slabTax(regime, above);
                atThreshold = atThreshold.add(atThreshold.multiply(lowerRate).divide(HUNDRED, MC));
                BigDecimal cap = atThreshold.add(income.subtract(above));
                if (tax.add(surcharge).compareTo(cap) > 0) {
                    surcharge = cap.subtract(tax).max(ZERO);
                }
                break;
            }
        }
        BigDecimal before = tax.add(surcharge);
        return round(before.add(before.multiply(cessPct).divide(HUNDRED, MC)), "nearest");
    }

    private static BigDecimal slabTax(Map<String, Object> regime, BigDecimal income) {
        BigDecimal tax = ZERO;
        BigDecimal lower = ZERO;
        for (Map<String, Object> s : list(regime, "slabs")) {
            Object upTo = s.get("upTo");
            BigDecimal top = upTo == null ? income : new BigDecimal(String.valueOf(upTo)).min(income);
            if (top.compareTo(lower) > 0) {
                tax = tax.add(top.subtract(lower).multiply(dec(s, "rate")).divide(HUNDRED, MC));
            }
            if (upTo == null || income.compareTo(new BigDecimal(String.valueOf(upTo))) <= 0) {
                break;
            }
            lower = new BigDecimal(String.valueOf(upTo));
        }
        return tax;
    }

    /** April is month 1 of the tax year. */
    static int taxMonth(LocalDate period) {
        return (period.getMonthValue() + 8) % 12 + 1;
    }

    /** Months of the tax year left, this one included, ending early in the month someone leaves. */
    static int monthsLeft(LocalDate period, String leavingOn) {
        int left = 13 - taxMonth(period);
        if (leavingOn != null) {
            LocalDate leaving = LocalDate.parse(leavingOn);
            LocalDate yearEnd = LocalDate.of(period.getMonthValue() >= 4 ? period.getYear() + 1 : period.getYear(), 3, 31);
            if (!leaving.isAfter(yearEnd) && !leaving.isBefore(period)) {
                int months = (leaving.getYear() - period.getYear()) * 12 + leaving.getMonthValue() - period.getMonthValue() + 1;
                left = Math.min(left, Math.max(months, 1));
            }
        }
        return left;
    }

    // ── lines ───────────────────────────────────────────────────────────

    private static void addLine(Calc out, Map<String, Object> c, BigDecimal raw, BigDecimal fullMonth, String ruleId,
                                Map<String, Object> trace, int position) {
        addLineRounded(out, c, raw, (String) c.get("rounding"), ruleId, trace, position, fullMonth);
    }

    private static void addLineRounded(Calc out, Map<String, Object> c, BigDecimal raw, String rounding, String ruleId,
                                       Map<String, Object> trace, int position) {
        addLineRounded(out, c, raw, rounding, ruleId, trace, position, null);
    }

    private static void addLineRounded(Calc out, Map<String, Object> c, BigDecimal raw, String rounding, String ruleId,
                                       Map<String, Object> trace, int position, BigDecimal fullMonth) {
        BigDecimal amount = round(raw, rounding == null ? "nearest" : rounding);
        if (amount.signum() == 0 && !Boolean.TRUE.equals(c.get("showZero"))) {
            return;
        }
        trace.put("unrounded", raw.setScale(4, RoundingMode.HALF_UP));
        trace.put("rounding", rounding);
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("code", c.get("code"));
        l.put("name", c.get("name"));
        l.put("kind", c.get("kind"));
        l.put("amount", amount);
        l.put("unrounded", raw.setScale(4, RoundingMode.HALF_UP));
        l.put("fullMonth", fullMonth);
        l.put("ruleId", ruleId);
        l.put("trace", trace);
        l.put("position", intOf(c.get("position")) * 10 + position % 10);
        out.lines.add(l);
    }

    private static Map<String, Object> rule(Map<String, Object> rules, String statute, String jurisdiction) {
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Object> sources = map(rules, "sources");
        t.put("source", sources.get(statute + ":" + jurisdiction));
        t.put("draft", map(rules, "drafts").get(statute + ":" + jurisdiction));
        return t;
    }

    private static BigDecimal sumKind(Calc out, String kind) {
        BigDecimal s = ZERO;
        for (Map<String, Object> l : out.lines) {
            if (kind.equals(l.get("kind"))) {
                s = s.add((BigDecimal) l.get("amount"));
            }
        }
        return s;
    }

    static BigDecimal round(BigDecimal v, String mode) {
        return switch (mode) {
            case "up" -> v.setScale(0, RoundingMode.CEILING);
            case "down" -> v.setScale(0, RoundingMode.FLOOR);
            case "none" -> v.setScale(2, RoundingMode.HALF_UP);
            default -> v.setScale(0, RoundingMode.HALF_UP);
        };
    }

    // ── map helpers (snapshots are parsed JSON) ─────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    static BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return ZERO;
        }
        return v instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(v));
    }

    static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    static int intOf(Object v) {
        return v == null ? 0 : new BigDecimal(String.valueOf(v)).intValue();
    }

    static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** ₹68,478 or ₹68,478.50, in Indian grouping. */
    static String rupees(BigDecimal v) {
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        boolean negative = r.signum() < 0;
        r = r.abs();
        String whole = r.toBigInteger().toString();
        String paise = r.remainder(BigDecimal.ONE).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString();
        StringBuilder grouped = new StringBuilder();
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(whole.substring(whole.length() - 3));
        } else {
            grouped.append(whole);
        }
        String out = "₹" + grouped + (paise.equals("0") ? "" : "." + (paise.length() == 1 ? "0" + paise : paise));
        return negative ? "−" + out : out;
    }
}
