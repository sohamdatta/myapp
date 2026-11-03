package com.example.loginapp.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Why one month's pay differs from the month before, worked out from the two payslips alone: each earning and deduction that moved,
 * grouped under the cause that explains it. Used on the payslip ("Why pay changed") and by the run's variance check, so both say the
 * same thing. Pure: no database, no clock.
 */
final class PayChange {

    /** One earning or deduction, signed: a deduction is negative. {@code oneTime} marks an amount entered for the month. */
    record Line(String code, String name, BigDecimal signed, boolean oneTime) {}

    /** What a payslip says about the month: days, the annual pay it was worked from, its lines, and the loss of pay in words (null when there was none). */
    record Slip(BigDecimal daysPaid, BigDecimal daysInPeriod, BigDecimal annual, String compensationFrom, List<Line> lines, String lossOfPay) {}

    /** A cause and the net amount it accounts for (negative when pay fell). */
    record Cause(String text, BigDecimal amount) {}

    private static final Set<String> FOLLOW_THE_PAY = Set.of("PT", "LWF_EE", "TDS", "PF_EE", "ESI_EE");

    private PayChange() {}

    /**
     * The loss of pay of a month in words, from the days behind it: "1 day of loss of pay on 14 Mar", "2 days of loss of pay (14 Mar,
     * 15 Mar)". Days HR entered carry no date of their own. Null when there was none.
     */
    static String lossOfPayWords(List<Map<String, Object>> dates, BigDecimal entered) {
        BigDecimal total = BigDecimal.ZERO;
        List<String> shown = new java.util.ArrayList<>();
        for (Map<String, Object> d : dates == null ? List.<Map<String, Object>>of() : dates) {
            total = total.add(PayrollEngine.dec(d, "days"));
            shown.add(java.time.LocalDate.parse((String) d.get("date")).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH))
                    + (PayrollEngine.dec(d, "days").compareTo(BigDecimal.ONE) < 0 ? " (half day)" : ""));
        }
        BigDecimal extra = entered == null ? BigDecimal.ZERO : entered;
        if (total.signum() == 0 && extra.signum() == 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        if (total.signum() > 0) {
            out.append(days(total)).append(" of loss of pay ");
            out.append(shown.size() == 1 ? "on " + shown.get(0)
                    : "(" + String.join(", ", shown.subList(0, Math.min(3, shown.size()))) + (shown.size() > 3 ? " and " + (shown.size() - 3) + " more" : "") + ")");
        }
        if (extra.signum() > 0) {
            out.append(out.length() > 0 ? ", and " : "").append(days(extra)).append(" of loss of pay entered by HR");
        }
        return out.toString();
    }

    private static String days(BigDecimal n) {
        return PayrollEngine.plain(n) + (n.compareTo(BigDecimal.ONE) == 0 ? " day" : " days");
    }

    /** The causes, largest first. Amounts that cancel out are left out. */
    static List<Cause> diff(Slip before, Slip now) {
        Map<String, BigDecimal> was = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Line l : before.lines()) {
            was.put(l.code(), l.signed());
            names.put(l.code(), l.name());
        }
        Map<String, BigDecimal> is = new HashMap<>();
        Map<String, Boolean> oneTime = new HashMap<>();
        for (Line l : now.lines()) {
            is.put(l.code(), l.signed());
            names.put(l.code(), l.name());
            oneTime.put(l.code(), l.oneTime());
        }
        boolean daysChanged = now.daysPaid().compareTo(before.daysPaid()) != 0 || now.daysInPeriod().compareTo(before.daysInPeriod()) != 0;
        boolean revised = now.annual().compareTo(before.annual()) != 0;
        Map<String, BigDecimal> causes = new LinkedHashMap<>();
        Set<String> codes = new HashSet<>(was.keySet());
        codes.addAll(is.keySet());
        for (String code : codes) {
            BigDecimal d = is.getOrDefault(code, BigDecimal.ZERO).subtract(was.getOrDefault(code, BigDecimal.ZERO));
            if (d.signum() == 0) {
                continue;
            }
            String cause;
            if (Boolean.TRUE.equals(oneTime.get(code)) || (!is.containsKey(code) && was.containsKey(code) && !FOLLOW_THE_PAY.contains(code))) {
                cause = names.get(code) + (is.containsKey(code) ? " this month" : " last month, not this month");
            } else if (code.equals("TDS")) {
                cause = "Income tax re-worked for the year";
            } else if (code.equals("PT")) {
                cause = "Professional tax";
            } else if (code.equals("LWF_EE")) {
                cause = "Labour welfare fund, due in some months only";
            } else if (Set.of("PF_EE", "ESI_EE").contains(code)) {
                cause = "PF and ESI follow the wages";
            } else if (revised) {
                cause = "Pay revised from " + now.compensationFrom();
            } else if (daysChanged && now.lossOfPay() != null) {
                cause = now.lossOfPay();
            } else if (daysChanged) {
                cause = PayrollEngine.plain(now.daysPaid()) + " of " + PayrollEngine.plain(now.daysInPeriod()) + " days paid (last month "
                        + PayrollEngine.plain(before.daysPaid()) + " of " + PayrollEngine.plain(before.daysInPeriod()) + ")";
            } else {
                cause = names.get(code) + " changed";
            }
            causes.merge(cause, d, BigDecimal::add);
        }
        List<Cause> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : causes.entrySet()) {
            if (e.getValue().signum() != 0) {
                out.add(new Cause(e.getKey(), e.getValue()));
            }
        }
        out.sort((a, b) -> {
            int c = b.amount().abs().compareTo(a.amount().abs());
            return c != 0 ? c : a.text().compareTo(b.text());   // a tie reads the same every time
        });
        return out;
    }
}
