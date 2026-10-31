package com.example.loginapp.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Form 130 Part B as one A4 page: the deductor, the employee, and the year's salary computation line by line. Rupees are
 * written "Rs." because the standard PDF fonts have no rupee sign. A certificate built on tax rules that are not yet signed
 * off says so across the top and in the footer.
 */
final class TaxCertificatePdf {

    private static final float LEFT = 48f;
    private static final float RIGHT = 547f;

    private TaxCertificatePdf() {}

    static byte[] render(Map<String, Object> b, boolean draftRules) {
        List<Pdf.Item> items = new ArrayList<>();
        float y = 56;
        items.add(Pdf.Item.text(LEFT, y, PayrollEngine.str(b.get("employer")), 15, true));
        items.add(Pdf.Item.right(RIGHT, y, "Form 130 Part B", 12, true));
        y += 16;
        if (b.get("employerAddress") != null) {
            items.add(Pdf.Item.text(LEFT, y, PayrollEngine.str(b.get("employerAddress")).replace('\n', ','), 9, false));
        }
        items.add(Pdf.Item.right(RIGHT, y, "Salary computation, tax year " + b.get("taxYear"), 9, false));
        y += 14;
        items.add(Pdf.Item.text(LEFT, y, "TAN " + PayrollEngine.str(b.get("tan")) + (b.get("employerPan") == null ? "" : "   PAN " + b.get("employerPan")), 9, false));
        if (draftRules) {
            y += 14;
            items.add(Pdf.Item.text(LEFT, y, "PROVISIONAL: the income tax rules used are not yet signed off by the payroll consultant.", 9, true));
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

        y += 18;
        items.add(Pdf.Item.rule(LEFT, y, RIGHT));
        y += 14;
        items.add(Pdf.Item.text(LEFT, y, "Part A, the quarterly record of tax deposited, comes from TRACES and is not part of this document.", 8, false));
        y += 11;
        items.add(Pdf.Item.text(LEFT, y, "Income and tax are the projection on the last payslip of the year; tax deducted is what the payslips took, with other employers' tax.", 8, false));
        return Pdf.page("Form 130 Part B " + b.get("taxYear") + " " + b.get("code"), items);
    }

    private static float line(List<Pdf.Item> items, float y, String label, Object amount, boolean bold) {
        items.add(Pdf.Item.text(LEFT, y, label, 10, bold));
        items.add(Pdf.Item.right(RIGHT, y, PayrollEngine.rupees((BigDecimal) amount), 10, bold));
        return y + 18;
    }
}
