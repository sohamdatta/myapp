package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The payslip as one A4 page: the employer (with the Organization's logo when it has one), the employee, the days,
 * earnings and deductions side by side, net pay in figures and words, the tax year to date, employer contributions,
 * the tax projection and why pay changed from last month. Rupees are written "Rs." because the standard PDF fonts
 * have no rupee sign. Colours follow the app: the brand indigo for headings and net pay, slate greys for the rest.
 */
final class PayslipPdf {

    private static final float LEFT = 48f;
    private static final float RIGHT = 547f;
    private static final float GAP = 14f;
    private static final float HALF = (RIGHT - LEFT - GAP) / 2;
    private static final float ROW = 17f;

    private static final int ACCENT = 0x4F4AC0;
    private static final int ACCENT_SOFT = 0xEEEEFC;
    private static final int INK = 0x1E293B;
    private static final int MUTED = 0x64748B;
    private static final int BORDER = 0xE2E8F0;
    private static final int HEAD = 0xF8FAFC;
    private static final int GOOD = 0x15803D;
    private static final int BAD = 0xB91C1C;
    private static final int WHITE = 0xFFFFFF;

    private PayslipPdf() {}

    /** The logo scaled into its box at the top left, or null when the Organization has none. */
    static Pdf.Image logo(byte[] file) {
        return file == null ? null : Pdf.image(file, LEFT, 30f, 110f, 40f);
    }

    static byte[] render(Map<String, Object> settings, String organization, Map<String, Object> ps) {
        return render(settings, organization, ps, null);
    }

    static byte[] render(Map<String, Object> settings, String organization, Map<String, Object> ps, Pdf.Image logo) {
        Map<String, Object> info = PayrollEngine.map(ps, "info");
        List<Pdf.Item> items = new ArrayList<>();

        // A thin brand bar across the top of the page.
        items.add(Pdf.Item.band(0, 0, Pdf.PAGE_WIDTH, 5).color(ACCENT));

        // The employer on the left, beside its logo; the payslip's month on the right.
        String employer = settings != null && settings.get("entityName") != null ? (String) settings.get("entityName") : organization;
        float textX = logo == null ? LEFT : LEFT + logo.w() + 14;
        float room = RIGHT - 130 - textX;
        float y = 46;
        for (String line : wrap(employer, room, 14, true)) {
            items.add(Pdf.Item.text(textX, y, line, 14, true).color(INK));
            y += 16;
        }
        if (settings != null && settings.get("address") != null) {
            String address = ((String) settings.get("address")).replaceAll("\\s*\\n\\s*", ", ");
            for (String line : wrap(address, room, 8.5f, false)) {
                items.add(Pdf.Item.text(textX, y, line, 8.5f, false).color(MUTED));
                y += 11;
            }
        }
        items.add(Pdf.Item.right(RIGHT, 40, "PAYSLIP", 8, true).color(ACCENT));
        items.add(Pdf.Item.right(RIGHT, 58, PayrollEngine.str(ps.get("label")), 15, true).color(INK));
        if (ps.get("paidOn") != null) {
            items.add(Pdf.Item.right(RIGHT, 72, "Paid on " + date(ps.get("paidOn")), 8.5f, false).color(MUTED));
        }
        y = Math.max(y, 80);
        if (logo != null) {
            y = Math.max(y, logo.y() + logo.h() + 8);
        }
        y += 6;
        items.add(Pdf.Item.rule(LEFT, y, RIGHT).color(BORDER));

        // The employee, in two columns inside a box.
        y += 14;
        String[][] left = {
                {"Employee name", orDash(info.get("name"))},
                {"Employee code", orDash(info.get("code"))},
                {"Designation", orDash(info.get("designation"))},
                {"Department", orDash(info.get("department"))},
                {"Location", orDash(info.get("location"))},
        };
        String[][] right = {
                {"PAN", last4(info.get("panLast4"))},
                {"UAN", last4(info.get("uanLast4"))},
                {"Bank account", last4(info.get("bankLast4"))},
                {"Date of joining", orDash(date(info.get("joinedOn")))},
                {"Tax regime", "old".equals(info.get("regime")) ? "Old regime" : "New regime"},
        };
        float boxTop = y;
        float boxH = left.length * 15 + 14;
        items.add(Pdf.Item.box(LEFT, boxTop, RIGHT, boxH).color(BORDER));
        y += 18;
        float col2 = LEFT + HALF + GAP;
        for (int i = 0; i < left.length; i++) {
            items.add(Pdf.Item.text(LEFT + 12, y, left[i][0], 8.5f, false).color(MUTED));
            items.add(Pdf.Item.text(LEFT + 100, y, fit(left[i][1], HALF - 104, 8.5f, true), 8.5f, true).color(INK));
            items.add(Pdf.Item.text(col2 + 12, y, right[i][0], 8.5f, false).color(MUTED));
            items.add(Pdf.Item.text(col2 + 100, y, fit(right[i][1], HALF - 104, 8.5f, true), 8.5f, true).color(INK));
            y += 15;
        }
        y = boxTop + boxH + 12;

        // The days, as four small cells.
        String method = switch (PayrollEngine.str(info.get("proration"))) {
            case "working" -> "Working days";
            case "fixed_30" -> "Fixed 30 days";
            default -> "Calendar days";
        };
        String[][] days = {
                {"DAYS IN MONTH", plain(ps.get("daysInPeriod"))},
                {"DAYS PAID", plain(ps.get("daysPaid"))},
                {"LOSS OF PAY DAYS", plain(ps.get("lopDays"))},
                {"PRORATED BY", method},
        };
        float cell = (RIGHT - LEFT - 3 * 8) / 4;
        for (int i = 0; i < days.length; i++) {
            float x = LEFT + i * (cell + 8);
            items.add(Pdf.Item.band(x, y, x + cell, 36).color(HEAD));
            items.add(Pdf.Item.box(x, y, x + cell, 36).color(BORDER));
            items.add(Pdf.Item.text(x + 10, y + 13, days[i][0], 6.5f, true).color(MUTED));
            items.add(Pdf.Item.text(x + 10, y + 28, days[i][1], 11, true).color(INK));
        }
        y += 36 + 14;

        // Earnings and deductions side by side, with their totals level.
        List<Map<String, Object>> lines = PayrollEngine.list(ps, "lines");
        List<String[]> earnings = rows(lines, "earning");
        List<String[]> deductions = rows(lines, "deduction");
        int n = Math.max(earnings.size(), deductions.size());
        table(items, LEFT, LEFT + HALF, y, "EARNINGS", "AMOUNT", earnings, n, new String[] {"Gross earnings", money(ps.get("gross"))});
        y = table(items, col2, RIGHT, y, "DEDUCTIONS", "AMOUNT", deductions, n, new String[] {"Total deductions", money(ps.get("deductions"))});
        y += 12;

        // Net pay, the figure people look for, in a brand band with the amount in words.
        items.add(Pdf.Item.band(LEFT, y, RIGHT, 52).color(ACCENT));
        items.add(Pdf.Item.text(LEFT + 14, y + 19, "NET PAY FOR " + PayrollEngine.str(ps.get("label")).toUpperCase(java.util.Locale.ROOT), 8, true).color(WHITE));
        items.add(Pdf.Item.text(LEFT + 14, y + 38, fit(words(PayrollEngine.dec(ps, "net")), RIGHT - LEFT - 190, 9, false), 9, false).color(WHITE));
        items.add(Pdf.Item.right(RIGHT - 14, y + 33, money(ps.get("net")), 18, true).color(WHITE));
        items.add(Pdf.Item.right(RIGHT - 14, y + 45, "Gross " + money(ps.get("gross")) + " less deductions " + money(ps.get("deductions")), 7, false).color(WHITE));
        y += 52 + 14;

        // The tax year to date beside the employer's contributions.
        Map<String, Object> ytd = PayrollEngine.map(ps, "ytd");
        List<String[]> ytdRows = List.of(
                new String[] {"Gross earnings", money(ytd.get("gross"))},
                new String[] {"Deductions", money(ytd.get("deductions"))});
        List<Map<String, Object>> employerLines = lines.stream().filter(l -> "employer".equals(l.get("kind"))).toList();
        List<String[]> employerRows = rows(lines, "employer");
        BigDecimal employerTotal = BigDecimal.ZERO;
        for (Map<String, Object> l : employerLines) {
            employerTotal = employerTotal.add(PayrollEngine.dec(l, "amount"));
        }
        if (employerRows.isEmpty()) {
            y = table(items, LEFT, RIGHT, y, "THIS TAX YEAR TO DATE", "AMOUNT", ytdRows, 2, new String[] {"Net pay", money(ytd.get("net"))});
        } else {
            int m = Math.max(2, employerRows.size());
            table(items, LEFT, LEFT + HALF, y, "THIS TAX YEAR TO DATE", "AMOUNT", ytdRows, m, new String[] {"Net pay", money(ytd.get("net"))});
            y = table(items, col2, RIGHT, y, "PAID BY THE EMPLOYER, ON TOP OF PAY", "AMOUNT", employerRows, m,
                    new String[] {"Total", money(employerTotal)});
        }
        y += 12;

        // The income tax projection, as label and value pairs in two columns.
        Map<String, Object> tax = PayrollEngine.map(info, "tax");
        if (!tax.isEmpty()) {
            List<String[]> pairs = new ArrayList<>(List.of(
                    new String[] {"Projected income", money(tax.get("income"))},
                    new String[] {"Deductions allowed", money(tax.get("deductions"))},
                    new String[] {"Taxable income", money(tax.get("taxableIncome"))},
                    new String[] {"Tax for the year", money(tax.get("annualTax"))},
                    new String[] {"Deducted before this month", money(tax.get("paidSoFar"))}));
            if (tax.get("monthsLeft") != null) {
                pairs.add(new String[] {"Months left to spread over", plain(tax.get("monthsLeft"))});
            }
            String regime = "old".equals(tax.get("regime")) ? "OLD" : "NEW";
            int rowsNeeded = (pairs.size() + 1) / 2;
            float h = 20 + rowsNeeded * ROW + 4;
            items.add(Pdf.Item.box(LEFT, y, RIGHT, h).color(BORDER));
            items.add(Pdf.Item.band(LEFT, y, RIGHT, 20).color(ACCENT_SOFT));
            items.add(Pdf.Item.text(LEFT + 10, y + 13.5f, "INCOME TAX FOR THE YEAR, PROJECTED (" + regime + " REGIME)", 7, true).color(ACCENT));
            float ry = y + 20 + 12;
            for (int i = 0; i < pairs.size(); i++) {
                float x0 = i % 2 == 0 ? LEFT : col2;
                float x1 = i % 2 == 0 ? LEFT + HALF : RIGHT;
                float rowY = ry + (i / 2) * ROW;
                items.add(Pdf.Item.text(x0 + 10, rowY, pairs.get(i)[0], 8.5f, false).color(MUTED));
                items.add(Pdf.Item.right(x1 - 10, rowY, pairs.get(i)[1], 8.5f, true).color(INK));
            }
            y += h + 12;
        }

        // Why pay changed from last month.
        Map<String, Object> changes = PayrollEngine.map(ps, "changes");
        List<Map<String, Object>> why = PayrollEngine.list(changes, "items");
        if (!why.isEmpty()) {
            List<Map<String, Object>> shown = why.subList(0, Math.min(6, why.size()));
            float h = 20 + shown.size() * ROW + 4;
            items.add(Pdf.Item.box(LEFT, y, RIGHT, h).color(BORDER));
            items.add(Pdf.Item.band(LEFT, y, RIGHT, 20).color(ACCENT_SOFT));
            items.add(Pdf.Item.text(LEFT + 10, y + 13.5f, ("WHY YOUR PAY CHANGED FROM " + changes.get("previousLabel")).toUpperCase(java.util.Locale.ROOT), 7, true)
                    .color(ACCENT));
            if (changes.get("previousNet") != null) {
                items.add(Pdf.Item.right(RIGHT - 10, y + 13.5f, money(changes.get("previousNet")) + "  to  " + money(ps.get("net")), 7.5f, true).color(ACCENT));
            }
            float ry = y + 20 + 12;
            for (Map<String, Object> w : shown) {
                BigDecimal a = PayrollEngine.dec(w, "amount");
                items.add(Pdf.Item.text(LEFT + 10, ry, fit((String) w.get("cause"), RIGHT - LEFT - 120, 8.5f, false), 8.5f, false).color(INK));
                items.add(Pdf.Item.right(RIGHT - 10, ry, (a.signum() >= 0 ? "+ " : "- ") + money(a.abs()), 8.5f, true).color(a.signum() >= 0 ? GOOD : BAD));
                ry += ROW;
            }
            y += h + 12;
        }

        items.add(Pdf.Item.rule(LEFT, 800, RIGHT).color(BORDER));
        items.add(Pdf.Item.text(LEFT, 814, "This is a system-generated payslip and needs no signature.", 7.5f, false).color(MUTED));
        items.add(Pdf.Item.right(RIGHT, 814, fit(employer, 220, 7.5f, false) + "  |  " + PayrollEngine.str(ps.get("label")), 7.5f, false).color(MUTED));
        return Pdf.page("Payslip " + ps.get("label"), items, logo);
    }

    /** The lines of one kind as name and amount. */
    private static List<String[]> rows(List<Map<String, Object>> lines, String kind) {
        List<String[]> out = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if (kind.equals(l.get("kind"))) {
                out.add(new String[] {PayrollEngine.str(l.get("name")), money(l.get("amount"))});
            }
        }
        return out;
    }

    /**
     * A boxed table from x0 to x1 with its top at y: a tinted heading row, {@code slots} body rows (blank ones keep two
     * tables side by side the same height) and a total row. Returns the y just below it.
     */
    private static float table(List<Pdf.Item> items, float x0, float x1, float y, String title, String amountHead, List<String[]> rows, int slots,
                               String[] total) {
        float h = 20 + Math.max(slots, 1) * ROW + 22;
        items.add(Pdf.Item.box(x0, y, x1, h).color(BORDER));
        items.add(Pdf.Item.band(x0, y, x1, 20).color(ACCENT_SOFT));
        items.add(Pdf.Item.text(x0 + 10, y + 13.5f, title, 7, true).color(ACCENT));
        items.add(Pdf.Item.right(x1 - 10, y + 13.5f, amountHead, 7, true).color(ACCENT));
        float ry = y + 20;
        for (int i = 0; i < Math.max(slots, 1); i++) {
            if (i < rows.size()) {
                items.add(Pdf.Item.text(x0 + 10, ry + 12, fit(rows.get(i)[0], x1 - x0 - 100, 8.5f, false), 8.5f, false).color(INK));
                items.add(Pdf.Item.right(x1 - 10, ry + 12, rows.get(i)[1], 8.5f, false).color(INK));
            }
            ry += ROW;
            if (i < Math.max(slots, 1) - 1 && i < rows.size() - 1) {
                items.add(Pdf.Item.rule(x0 + 10, ry, x1 - 10).color(0xF1F5F9));
            }
        }
        items.add(Pdf.Item.band(x0 + 0.5f, ry, x1 - 0.5f, 21.5f).color(HEAD));
        items.add(Pdf.Item.rule(x0, ry, x1).color(BORDER));
        items.add(Pdf.Item.text(x0 + 10, ry + 14.5f, total[0], 8.5f, true).color(INK));
        items.add(Pdf.Item.right(x1 - 10, ry + 14.5f, total[1], 8.5f, true).color(INK));
        return y + h;
    }

    /** A value, or a dash when it was not recorded. */
    private static String orDash(Object v) {
        String s = PayrollEngine.str(v).strip();
        return s.isEmpty() ? "-" : s;
    }

    /** Words wrapped to a width. */
    private static List<String> wrap(String text, float width, float size, boolean bold) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String word : PayrollEngine.str(text).split(" +")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (Pdf.width(candidate, size, bold) <= width || line.isEmpty()) {
                line = candidate;
            } else {
                out.add(line);
                line = word;
            }
        }
        if (!line.isEmpty()) {
            out.add(line);
        }
        return out;
    }

    /** Text cut with an ellipsis-like "..." when it would run past a width. */
    private static String fit(String text, float width, float size, boolean bold) {
        String s = PayrollEngine.str(text);
        if (Pdf.width(s, size, bold) <= width) {
            return s;
        }
        while (s.length() > 1 && Pdf.width(s + "...", size, bold) > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s.strip() + "...";
    }

    /** An ISO date as "31 Oct 2026"; anything else as it is. */
    private static String date(Object v) {
        String s = PayrollEngine.str(v);
        try {
            return java.time.LocalDate.parse(s).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
        } catch (java.time.format.DateTimeParseException e) {
            return s;
        }
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
