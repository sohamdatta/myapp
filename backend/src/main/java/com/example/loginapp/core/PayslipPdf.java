package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The payslip as one A4 page: the employer, the employee, the days, earnings and deductions side by
 * side with this month and the year so far, net pay in figures and words, employer contributions,
 * the tax projection and why pay changed from last month. Rupees are written "Rs." because the
 * standard PDF fonts have no rupee sign.
 */
final class PayslipPdf {

    private static final float LEFT = 48f;
    private static final float RIGHT = 547f;
    private static final float MID = 300f;

    private PayslipPdf() {}

    static byte[] render(Map<String, Object> settings, String organization, Map<String, Object> ps) {
        Map<String, Object> info = PayrollEngine.map(ps, "info");
        List<Pdf.Item> items = new ArrayList<>();
        float y = 56;
        String employer = settings != null && settings.get("entityName") != null ? (String) settings.get("entityName") : organization;
        items.add(Pdf.Item.text(LEFT, y, employer, 15, true));
        if (settings != null && settings.get("address") != null) {
            y += 16;
            items.add(Pdf.Item.text(LEFT, y, ((String) settings.get("address")).replace('\n', ','), 9, false));
        }
        items.add(Pdf.Item.right(RIGHT, 56, "Payslip for " + ps.get("label"), 12, true));
        if (ps.get("paidOn") != null) {
            items.add(Pdf.Item.right(RIGHT, 72, "Paid on " + ps.get("paidOn"), 9, false));
        }
        y += 18;
        items.add(Pdf.Item.rule(LEFT, y, RIGHT));

        // The employee, in two columns.
        y += 18;
        String[][] left = {
                {"Name", PayrollEngine.str(info.get("name"))},
                {"Employee code", PayrollEngine.str(info.get("code"))},
                {"Designation", PayrollEngine.str(info.get("designation"))},
                {"Department", PayrollEngine.str(info.get("department"))},
                {"Location", PayrollEngine.str(info.get("location"))},
        };
        String[][] right = {
                {"PAN", last4(info.get("panLast4"))},
                {"UAN", last4(info.get("uanLast4"))},
                {"Bank account", last4(info.get("bankLast4"))},
                {"Joined", PayrollEngine.str(info.get("joinedOn"))},
                {"Tax regime", "old".equals(info.get("regime")) ? "Old" : "New"},
        };
        for (int i = 0; i < left.length; i++) {
            items.add(Pdf.Item.text(LEFT, y, left[i][0], 9, false));
            items.add(Pdf.Item.text(LEFT + 90, y, left[i][1], 9, true));
            items.add(Pdf.Item.text(MID + 10, y, right[i][0], 9, false));
            items.add(Pdf.Item.text(MID + 100, y, right[i][1], 9, true));
            y += 14;
        }
        y += 4;
        items.add(Pdf.Item.band(LEFT, y, RIGHT, 22));
        y += 15;
        String method = switch (PayrollEngine.str(info.get("proration"))) {
            case "working" -> "working days";
            case "fixed_30" -> "a fixed 30 days";
            default -> "calendar days";
        };
        items.add(Pdf.Item.text(LEFT + 8, y, "Days in month " + plain(ps.get("daysInPeriod")) + "     Days paid " + plain(ps.get("daysPaid"))
                + "     Loss of pay " + plain(ps.get("lopDays")) + "     Prorated by " + method, 9, false));
        y += 24;

        // Earnings and deductions side by side.
        items.add(Pdf.Item.text(LEFT, y, "Earnings", 10, true));
        items.add(Pdf.Item.right(MID - 20, y, "Amount", 9, true));
        items.add(Pdf.Item.text(MID + 10, y, "Deductions", 10, true));
        items.add(Pdf.Item.right(RIGHT, y, "Amount", 9, true));
        y += 6;
        items.add(Pdf.Item.rule(LEFT, y, RIGHT));
        y += 14;
        List<Map<String, Object>> lines = PayrollEngine.list(ps, "lines");
        List<Map<String, Object>> earnings = lines.stream().filter(l -> "earning".equals(l.get("kind"))).toList();
        List<Map<String, Object>> deductions = lines.stream().filter(l -> "deduction".equals(l.get("kind"))).toList();
        int rows = Math.max(earnings.size(), deductions.size());
        for (int i = 0; i < rows; i++) {
            if (i < earnings.size()) {
                items.add(Pdf.Item.text(LEFT, y, (String) earnings.get(i).get("name"), 9, false));
                items.add(Pdf.Item.right(MID - 20, y, money(earnings.get(i).get("amount")), 9, false));
            }
            if (i < deductions.size()) {
                items.add(Pdf.Item.text(MID + 10, y, (String) deductions.get(i).get("name"), 9, false));
                items.add(Pdf.Item.right(RIGHT, y, money(deductions.get(i).get("amount")), 9, false));
            }
            y += 14;
        }
        items.add(Pdf.Item.rule(LEFT, y - 6, RIGHT));
        y += 6;
        items.add(Pdf.Item.text(LEFT, y, "Gross earnings", 9, true));
        items.add(Pdf.Item.right(MID - 20, y, money(ps.get("gross")), 9, true));
        items.add(Pdf.Item.text(MID + 10, y, "Total deductions", 9, true));
        items.add(Pdf.Item.right(RIGHT, y, money(ps.get("deductions")), 9, true));
        y += 22;
        items.add(Pdf.Item.band(LEFT, y, RIGHT, 30));
        y += 19;
        items.add(Pdf.Item.text(LEFT + 8, y, "Net pay", 12, true));
        items.add(Pdf.Item.right(RIGHT - 8, y, money(ps.get("net")), 13, true));
        y += 24;
        items.add(Pdf.Item.text(LEFT, y, "In words: " + words(PayrollEngine.dec(ps, "net")), 9, false));
        y += 22;

        // Year to date.
        Map<String, Object> ytd = PayrollEngine.map(ps, "ytd");
        items.add(Pdf.Item.text(LEFT, y, "This tax year to date", 10, true));
        y += 14;
        items.add(Pdf.Item.text(LEFT, y, "Gross " + money(ytd.get("gross")) + "      Deductions " + money(ytd.get("deductions"))
                + "      Net " + money(ytd.get("net")), 9, false));
        y += 22;

        // Employer contributions.
        List<Map<String, Object>> employer2 = lines.stream().filter(l -> "employer".equals(l.get("kind"))).toList();
        if (!employer2.isEmpty()) {
            items.add(Pdf.Item.text(LEFT, y, "Paid by the employer, not taken from your pay", 10, true));
            y += 14;
            StringBuilder s = new StringBuilder();
            for (Map<String, Object> l : employer2) {
                s.append(s.isEmpty() ? "" : "      ").append(l.get("name")).append(" ").append(money(l.get("amount")));
            }
            items.add(Pdf.Item.text(LEFT, y, s.toString(), 9, false));
            y += 22;
        }

        // Tax projection.
        Map<String, Object> tax = PayrollEngine.map(info, "tax");
        if (!tax.isEmpty()) {
            items.add(Pdf.Item.text(LEFT, y, "Income tax for the year (" + ("old".equals(tax.get("regime")) ? "old" : "new") + " regime)", 10, true));
            y += 14;
            items.add(Pdf.Item.text(LEFT, y, "Projected income " + money(tax.get("income")) + "   Deductions " + money(tax.get("deductions"))
                    + "   Taxable " + money(tax.get("taxableIncome")) + "   Tax for the year " + money(tax.get("annualTax"))
                    + "   Deducted before this month " + money(tax.get("paidSoFar")), 8.5f, false));
            y += 22;
        }

        // Why pay changed.
        Map<String, Object> changes = PayrollEngine.map(ps, "changes");
        List<Map<String, Object>> why = PayrollEngine.list(changes, "items");
        if (!why.isEmpty()) {
            items.add(Pdf.Item.text(LEFT, y, "Why your pay changed from " + changes.get("previousLabel"), 10, true));
            y += 14;
            for (Map<String, Object> w : why.subList(0, Math.min(6, why.size()))) {
                BigDecimal a = PayrollEngine.dec(w, "amount");
                items.add(Pdf.Item.text(LEFT, y, (String) w.get("cause"), 9, false));
                items.add(Pdf.Item.right(RIGHT, y, (a.signum() > 0 ? "+" : "-") + money(a.abs()), 9, false));
                y += 13;
            }
            y += 9;
        }
        items.add(Pdf.Item.rule(LEFT, 800, RIGHT));
        items.add(Pdf.Item.text(LEFT, 814, "This is a system-generated payslip and needs no signature.", 8, false));
        return Pdf.page("Payslip " + ps.get("label"), items);
    }

    private static String last4(Object v) {
        return v == null ? "Not recorded" : "**** " + v;
    }

    private static String plain(Object v) {
        return v == null ? "0" : new BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
    }

    static String money(Object v) {
        BigDecimal d = v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
        return PayrollEngine.rupees(d).replace("₹", "Rs. ").replace("−", "-");
    }

    private static final String[] ONES = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** Rupees in words, in the Indian system: "Sixty-six thousand four hundred and seventy-eight rupees only". */
    static String words(BigDecimal amount) {
        long n = amount.setScale(0, RoundingMode.HALF_UP).abs().longValue();
        if (n == 0) {
            return "Zero rupees only";
        }
        StringBuilder out = new StringBuilder();
        long crore = n / 10_000_000;
        n %= 10_000_000;
        long lakh = n / 100_000;
        n %= 100_000;
        long thousand = n / 1000;
        n %= 1000;
        long hundred = n / 100;
        long rest = n % 100;
        if (crore > 0) {
            out.append(below100(crore)).append(" crore ");
        }
        if (lakh > 0) {
            out.append(below100(lakh)).append(" lakh ");
        }
        if (thousand > 0) {
            out.append(below100(thousand)).append(" thousand ");
        }
        if (hundred > 0) {
            out.append(ONES[(int) hundred]).append(" hundred ");
        }
        if (rest > 0) {
            if (out.length() > 0) {
                out.append("and ");
            }
            out.append(below100(rest)).append(' ');
        }
        String s = out.toString().trim() + " rupees only";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String below100(long n) {
        if (n < 20) {
            return ONES[(int) n];
        }
        return TENS[(int) (n / 10)] + (n % 10 > 0 ? "-" + ONES[(int) (n % 10)] : "");
    }
}
