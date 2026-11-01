package com.example.loginapp.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Form 130 Part B as one A4 page: the deductor, the employee, and the year's salary computation line by line. Rupees are
 * written "Rs." because the standard PDF fonts have no rupee sign. A certificate built on tax rules that are not yet signed
 * off says so across the top and in the footer.
 *
 * The Organization's layout adds a logo, a header line, a signatory block and footer text around the figures. It cannot
 * remove or reword any figure or the statutory notes, which are not part of the layout.
 */
final class TaxCertificatePdf {

    private static final float LEFT = 48f;
    private static final float RIGHT = 547f;

    private TaxCertificatePdf() {}

    /** The logo scaled into its box at the top left, or null when there is none or the layout hides it. Done once and reused for every certificate. */
    static Pdf.Image logo(byte[] file, CertificateLayout.Layout layout) {
        return layout.showLogo() && file != null ? Pdf.image(file, LEFT, 38f, 120f, 46f) : null;
    }

    static byte[] render(Map<String, Object> b, boolean draftRules, CertificateLayout.Layout layout, Pdf.Image logo) {
        List<Pdf.Item> items = new ArrayList<>();
        float textX = logo == null ? LEFT : LEFT + logo.w() + 14;
        if (layout.headerLine() != null) {
            items.add(Pdf.Item.text(LEFT, 28, layout.headerLine(), 8, false));
        }
        // The employer block is kept clear of the form's title and subtitle on the right (about 160 points), wrapping long names and addresses.
        float room = RIGHT - 160 - textX;
        List<String> nameLines = wrap(PayrollEngine.str(b.get("employer")), room, 15, true);
        for (int i = 0; i < nameLines.size(); i++) {
            items.add(Pdf.Item.text(textX, 56 + 17 * i, nameLines.get(i), 15, true));
        }
        items.add(Pdf.Item.right(RIGHT, 56, "Form 130 Part B", 12, true));
        float y = 72 + 17 * (nameLines.size() - 1);
        items.add(Pdf.Item.right(RIGHT, 72, "Salary computation, tax year " + b.get("taxYear"), 9, false));
        if (b.get("employerAddress") != null) {
            for (String line : wrap(PayrollEngine.str(b.get("employerAddress")).replace('\n', ','), room, 9, false)) {
                items.add(Pdf.Item.text(textX, y, line, 9, false));
                y += 11;
            }
            y += 3;
        } else {
            y += 14;
        }
        items.add(Pdf.Item.text(textX, y, "TAN " + PayrollEngine.str(b.get("tan")) + (b.get("employerPan") == null ? "" : "   PAN " + b.get("employerPan")), 9, false));
        if (Boolean.TRUE.equals(b.get("sample"))) {
            y += 14;
            items.add(Pdf.Item.text(LEFT, y, "SAMPLE: the figures are made up to show the layout.", 9, true));
        }
        if (draftRules) {
            y += 14;
            items.add(Pdf.Item.text(LEFT, y, "PROVISIONAL: the income tax rules used are not yet signed off by the payroll consultant.", 9, true));
        }
        if (logo != null) {
            y = Math.max(y, logo.y() + logo.h() + 4);
        }
        y += 12;
        items.add(Pdf.Item.rule(LEFT, y, RIGHT));

        y += 18;
        String[][] person = {
                {"Employee", PayrollEngine.str(b.get("name")) + " (" + PayrollEngine.str(b.get("code")) + ")"},
                {"Designation", PayrollEngine.str(b.get("designation"))},
                {"PAN", b.get("panLast4") == null ? "-" : "ending " + b.get("panLast4")},
                {"Employed", PayrollEngine.str(b.get("joinedOn")) + (b.get("leavingOn") == null ? "" : " to " + b.get("leavingOn"))},
                {"Tax regime", "old".equals(b.get("regime")) ? "Old" : "New"},
        };
        for (String[] row : person) {
            items.add(Pdf.Item.text(LEFT, y, row[0], 9, false));
            items.add(Pdf.Item.text(LEFT + 90, y, row[1], 9, true));
            y += 14;
        }
        y += 4;
        items.add(Pdf.Item.rule(LEFT, y, RIGHT));
        y += 18;

        y = line(items, y, "Salary from this employer", b.get("salaryThisEmployer"), false);
        if (PayrollEngine.dec(b, "incomeOtherEmployers").signum() > 0) {
            y = line(items, y, "Salary from other employers this year (Form 122)", b.get("incomeOtherEmployers"), false);
        }
        y = line(items, y, "Total income", b.get("totalIncome"), true);
        y = line(items, y, "Less: deductions allowed under the " + ("old".equals(b.get("regime")) ? "old" : "new") + " regime", b.get("deductions"), false);
        y = line(items, y, "Taxable income", b.get("taxableIncome"), true);
        y = line(items, y, "Tax for the year, with rebate, surcharge and cess as the rules give them", b.get("annualTax"), true);
        y = line(items, y, "Tax deducted (this employer and the others)", b.get("taxDeducted"), false);
        BigDecimal balance = PayrollEngine.dec(b, "balance");
        y = line(items, y, balance.signum() > 0 ? "Tax still to be paid" : balance.signum() < 0 ? "Tax deducted in excess" : "Balance",
                balance.abs(), true);

        if (layout.signatoryName() != null || layout.signatoryDesignation() != null) {
            y += 12;
            items.add(Pdf.Item.text(LEFT, y, "For " + PayrollEngine.str(b.get("employer")), 9, false));
            y += 40;
            items.add(Pdf.Item.rule(LEFT, y, LEFT + 170));
            y += 12;
            if (layout.signatoryName() != null) {
                items.add(Pdf.Item.text(LEFT, y, layout.signatoryName(), 10, true));
                y += 12;
            }
            if (layout.signatoryDesignation() != null) {
                items.add(Pdf.Item.text(LEFT, y, layout.signatoryDesignation(), 9, false));
                y += 12;
            }
        }

        y += 18;
        items.add(Pdf.Item.rule(LEFT, y, RIGHT));
        y += 14;
        items.add(Pdf.Item.text(LEFT, y, "Part A, the quarterly record of tax deposited, comes from TRACES and is not part of this document.", 8, false));
        y += 11;
        items.add(Pdf.Item.text(LEFT, y, "Income and tax are the projection on the last payslip of the year; tax deducted is what the payslips took, with other employers' tax.", 8, false));

        if (layout.footerText() != null) {
            List<String> lines = wrap(layout.footerText(), RIGHT - LEFT, 8, false);
            float top = 812 - 10 * (lines.size() - 1);
            for (int i = 0; i < lines.size(); i++) {
                items.add(Pdf.Item.text(LEFT, top + 10 * i, lines.get(i), 8, false));
            }
        }
        return Pdf.page("Form 130 Part B " + b.get("taxYear") + " " + b.get("code"), items, logo);
    }

    /** Words packed into lines no wider than {@code width} points; a word wider than a line is broken where it must be. */
    private static List<String> wrap(String text, float width, float size, boolean bold) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" +")) {
            while (Pdf.width(word, size, bold) > width && word.length() > 1) {
                int cut = word.length() - 1;
                while (cut > 1 && Pdf.width(word.substring(0, cut), size, bold) > width) {
                    cut--;
                }
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current = new StringBuilder();
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String attempt = current.length() == 0 ? word : current + " " + word;
            if (current.length() > 0 && Pdf.width(attempt, size, bold) > width) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(attempt);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static float line(List<Pdf.Item> items, float y, String label, Object amount, boolean bold) {
        items.add(Pdf.Item.text(LEFT, y, label, 10, bold));
        items.add(Pdf.Item.right(RIGHT, y, PayrollEngine.rupees((BigDecimal) amount), 10, bold));
        return y + 18;
    }
}
