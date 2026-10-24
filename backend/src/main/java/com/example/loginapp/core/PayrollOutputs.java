package com.example.loginapp.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.example.loginapp.core.PayrollRuns.money;
import static com.example.loginapp.core.PayrollRuns.monthWords;
import static com.example.loginapp.core.PayrollRuns.parse;
import static com.example.loginapp.core.Payslips.payslip;

/**
 * What a calculated run produces as files: the salary register, the HDFC bank file, the accounting
 * journal and the payslip PDF. Every value written to a CSV is guarded so a spreadsheet never runs it.
 */
final class PayrollOutputs {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private PayrollOutputs() {
    }

    static DocumentService.Content csv(String name, List<List<String>> rows) {
        StringBuilder out = new StringBuilder("﻿");
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String v : row) {
                String s = v == null ? "" : v;
                if (s.startsWith("=") || s.startsWith("+") || s.startsWith("@")) {
                    s = "'" + s;   // never a formula when opened in a spreadsheet
                }
                cells.add(s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
            }
            out.append(String.join(",", cells)).append("\r\n");
        }
        return new DocumentService.Content("text/csv; charset=utf-8", name, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    static List<List<String>> register(Connection c, UUID runId) throws SQLException {
        List<Map<String, Object>> slips = Sql.query(c, "select id, info, days_in_period, days_paid, lop_days, gross, deductions, net, employer_cost, held "
                + "from app.payslip where run_id = ? order by lower(info->>'name')", runId);
        List<Map<String, Object>> columns = Sql.query(c, "select distinct l.code, l.name, l.kind, min(l.position) as position from app.payslip_line l "
                + "join app.payslip ps on ps.id = l.payslip_id where ps.run_id = ? group by l.code, l.name, l.kind order by min(l.position)", runId);
        List<List<String>> rows = new ArrayList<>();
        List<String> head = new ArrayList<>(List.of("Employee code", "Name", "Designation", "Department", "State", "Days in month", "Days paid",
                "Loss of pay"));
        for (Map<String, Object> col : columns) {
            head.add(col.get("name") + ("employer".equals(col.get("kind")) ? " (employer)" : ""));
        }
        head.addAll(List.of("Gross", "Deductions", "Net pay", "Employer cost", "Held"));
        rows.add(head);
        for (Map<String, Object> s : slips) {
            Map<String, Object> info = parse(s.get("info"));
            Map<String, String> amounts = new HashMap<>();
            for (Map<String, Object> l : Sql.query(c, "select code, amount from app.payslip_line where payslip_id = ?::uuid", s.get("id"))) {
                amounts.put((String) l.get("code"), money(l.get("amount")));
            }
            List<String> row = new ArrayList<>(List.of(PayrollEngine.str(info.get("code")), PayrollEngine.str(info.get("name")),
                    PayrollEngine.str(info.get("designation")), PayrollEngine.str(info.get("department")), PayrollEngine.str(info.get("state")),
                    PayrollEngine.plain(PayrollEngine.dec(s, "daysInPeriod")), PayrollEngine.plain(PayrollEngine.dec(s, "daysPaid")),
                    PayrollEngine.plain(PayrollEngine.dec(s, "lopDays"))));
            for (Map<String, Object> col : columns) {
                row.add(amounts.getOrDefault((String) col.get("code"), "0.00"));
            }
            BigDecimal employerOnly = PayrollEngine.dec(s, "employerCost");
            row.addAll(List.of(money(s.get("gross")), money(s.get("deductions")), money(s.get("net")),
                    money(PayrollEngine.dec(s, "gross").add(employerOnly)), Boolean.TRUE.equals(s.get("held")) ? "Yes" : ""));
            rows.add(row);
        }
        return rows;
    }

    /**
     * A bulk payment file for HDFC Bank: one row per employee paid, with what a bulk salary upload carries.
     * The exact column order and header follow the customer's HDFC corporate banking layout, which we
     * still need; until then this is a column-mapped CSV with a control total in the last row.
     */
    static List<List<String>> bankFile(Connection c, Crypto crypto, UUID tenantId, UUID runId, LocalDate period, LocalDate valueDate)
            throws SQLException {
        Map<String, Object> s = Sql.one(c, "select debit_account, entity_name from app.payroll_settings");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Transaction type", "Beneficiary account number", "IFSC", "Beneficiary name", "Amount", "Value date", "Narration",
                "Employee code", "Debit account"));
        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        String narration = "Salary " + period.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));
        // Sql leaves bytea out on purpose; the sealed account numbers are read here and opened one by one.
        try (var st = c.prepareStatement("select ps.net, ps.info->>'code' as code, b.holder_name, b.account_enc, b.ifsc "
                + "from app.payslip ps join app.employment e on e.id = ps.employment_id join lateral (select * from app.bank_account b "
                + "where b.person_id = e.person_id and b.effective_from <= ? order by b.effective_from desc, b.created_at desc limit 1) b on true "
                + "where ps.run_id = ? and not ps.held and ps.net > 0 order by lower(ps.info->>'name')")) {
            st.setObject(1, period.plusMonths(1).minusDays(1));
            st.setObject(2, runId);
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    String ifsc = rs.getString("ifsc");
                    String account = crypto.decryptText(tenantId, rs.getBytes("account_enc"));
                    BigDecimal net = rs.getBigDecimal("net");
                    rows.add(List.of(ifsc.startsWith("HDFC") ? "I" : "N", account, ifsc, rs.getString("holder_name"), money(net),
                            valueDate.format(DMY), narration, PayrollEngine.str(rs.getString("code")), PayrollEngine.str(s.get("debitAccount"))));
                    count++;
                    total = total.add(net);
                }
            }
        }
        rows.add(List.of("Total", count + " payments", "", "", money(total), "", "", "", ""));
        return rows;
    }

    /** Debits for pay and employer contributions, credits for what is payable, by ledger and department. */
    static List<List<String>> journal(Connection c, UUID runId, LocalDate period) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Date", "Ledger", "Cost centre", "Debit", "Credit", "Narration"));
        String narration = "Payroll " + monthWords(period);
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Map<String, Object> g : Sql.query(c, "select l.kind, l.code, l.name, coalesce(ps.info->>'department', 'No department') as dept, "
                + "sum(l.amount) as amount, (select v.ledger_code from app.pay_component pc join app.pay_component_version v on v.component_id = pc.id "
                + "  where pc.code = l.code and v.valid_from <= ? order by v.valid_from desc limit 1) as ledger "
                + "from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id where ps.run_id = ? "
                + "group by l.kind, l.code, l.name, dept order by min(l.position), dept", end, runId)) {
            String kind = (String) g.get("kind");
            BigDecimal a = PayrollEngine.dec(g, "amount");
            String ledger = g.get("ledger") == null ? (String) g.get("name") : (String) g.get("ledger");
            switch (kind) {
                case "earning" -> {
                    rows.add(List.of(end.format(DMY), ledger, (String) g.get("dept"), money(a), "", narration));
                    debits = debits.add(a);
                }
                case "employer" -> {
                    rows.add(List.of(end.format(DMY), ledger + " expense", (String) g.get("dept"), money(a), "", narration));
                    rows.add(List.of(end.format(DMY), ledger + " payable", (String) g.get("dept"), "", money(a), narration));
                    debits = debits.add(a);
                    credits = credits.add(a);
                }
                case "deduction" -> {
                    rows.add(List.of(end.format(DMY), ledger + " payable", (String) g.get("dept"), "", money(a), narration));
                    credits = credits.add(a);
                }
                default -> { }
            }
        }
        for (Map<String, Object> g : Sql.query(c, "select coalesce(info->>'department', 'No department') as dept, sum(net) as net from app.payslip "
                + "where run_id = ? group by dept order by dept", runId)) {
            rows.add(List.of(end.format(DMY), "Salary payable", (String) g.get("dept"), "", money(g.get("net")), narration));
            credits = credits.add(PayrollEngine.dec(g, "net"));
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalStateException("The payroll journal does not balance: " + debits + " against " + credits);
        }
        rows.add(List.of("", "Total", "", money(debits), money(credits), ""));
        return rows;
    }

    static DocumentService.Content pdf(Connection c, UUID payslipId, String org) throws SQLException {
        Map<String, Object> ps = payslip(c, payslipId, false);
        Map<String, Object> s = Sql.one(c, "select entity_name, address from app.payroll_settings");
        byte[] bytes = PayslipPdf.render(s, org, ps);
        String month = LocalDate.parse((String) ps.get("period")).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return new DocumentService.Content("application/pdf", "Payslip " + month + ".pdf", bytes);
    }
}
