package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** The payroll component instalments are deducted under. Organizations that began before loans do not have it until they need it. */
final class LoanComponent {

    private LoanComponent() {}

    /** Adds {@code LOAN_EMI} to the Organization's component library when payroll is set up and it is missing. */
    static void ensure(Connection c, UUID tenantId) throws SQLException {
        PayrollDefaults.ensure(c, tenantId);
        if (Sql.exists(c, "select 1 from app.pay_component where code = ?", LoanService.COMPONENT)) {
            return;
        }
        UUID id = AuthService.uuid(Sql.one(c, "insert into app.pay_component (tenant_id, code, name, kind, statutory, position) "
                + "values (?, ?, 'Loan or advance recovery', 'deduction', false, 155) returning id", tenantId, LoanService.COMPONENT).get("id"));
        Sql.update(c, "insert into app.pay_component_version (tenant_id, component_id, valid_from, amount_type, formula, wage, esi_wage, taxable, prorate, rounding, one_time) "
                + "values (?, ?, ?::date, 'input', null, 'exclude', false, false, false, 'none', true)", tenantId, id, PayrollDefaults.START);
    }
}
