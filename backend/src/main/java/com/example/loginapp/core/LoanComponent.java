package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** The payroll component instalments are deducted under. Organizations that began before loans do not have it until they need it. */
final class LoanComponent {

    private LoanComponent() {}

    /** Adds {@code LOAN_EMI} and {@code LOAN_PERQ} to the Organization's component library when payroll is set up and they are missing. */
    static void ensure(Connection c, UUID tenantId) throws SQLException {
        PayrollDefaults.ensure(c, tenantId);
        add(c, tenantId, LoanService.COMPONENT, "Loan or advance recovery", "deduction", 155, "none");
        add(c, tenantId, LoanService.PERQUISITE, "Loan interest perquisite (taxable)", "info", 156, "none");
    }

    private static void add(Connection c, UUID tenantId, String code, String name, String kind, int position, String rounding) throws SQLException {
        if (Sql.exists(c, "select 1 from app.pay_component where code = ?", code)) {
            return;
        }
        UUID id = AuthService.uuid(Sql.one(c, "insert into app.pay_component (tenant_id, code, name, kind, statutory, position) "
                + "values (?, ?, ?, ?, false, ?) returning id", tenantId, code, name, kind, (short) position).get("id"));
        Sql.update(c, "insert into app.pay_component_version (tenant_id, component_id, valid_from, amount_type, formula, wage, esi_wage, taxable, prorate, rounding, one_time) "
                + "values (?, ?, ?::date, 'input', null, 'exclude', false, false, false, ?, true)", tenantId, id, PayrollDefaults.START, rounding);
    }
}
