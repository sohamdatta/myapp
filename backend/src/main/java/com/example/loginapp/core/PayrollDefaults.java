package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * What every Organization's payroll starts with: the settings row, a component library (Basic,
 * HRA, a balancing Special allowance, Bonus, Incentive, Recovery, and the statutory components), a
 * Staff structure built from CTC, a Monthly staff pay group, and the payroll messages. Written the
 * first time anything reads the Organization's payroll; the settings row is the marker.
 */
final class PayrollDefaults {

    private PayrollDefaults() {}

    /** code, name, kind, statutory, position, amount type, formula, wage, ESI wage, taxable, prorate, rounding, one-time. */
    private static final Object[][] COMPONENTS = {
            {"BASIC", "Basic", "earning", false, 10, "formula", "CTC_MONTHLY * 0.5", "include", true, true, true, "nearest", false},
            {"HRA", "House rent allowance", "earning", false, 20, "formula", "BASIC * if(location.metro, 0.5, 0.4)", "conditional", true, true, true,
                    "nearest", false},
            {"SPECIAL", "Special allowance", "earning", false, 30, "formula", "CTC_MONTHLY - BASIC - HRA - PF_ER", "conditional", true, true, true,
                    "nearest", false},
            {"BONUS", "Bonus", "earning", false, 50, "input", null, "exclude", false, true, false, "nearest", true},
            {"INCENTIVE", "Incentive", "earning", false, 55, "input", null, "exclude", true, true, false, "nearest", true},
            {"PF_EE", "Provident fund", "deduction", true, 100, "statutory", null, "exclude", false, false, false, "nearest", false},
            {"ESI_EE", "ESI", "deduction", true, 110, "statutory", null, "exclude", false, false, false, "up", false},
            {"PT", "Professional tax", "deduction", true, 120, "statutory", null, "exclude", false, false, false, "none", false},
            {"LWF_EE", "Labour welfare fund", "deduction", true, 130, "statutory", null, "exclude", false, false, false, "none", false},
            {"TDS", "Income tax", "deduction", true, 140, "statutory", null, "exclude", false, false, false, "nearest", false},
            {"RECOVERY", "Recovery", "deduction", false, 150, "input", null, "exclude", false, false, false, "nearest", true},
            {"PF_ER", "Employer PF", "employer", true, 200, "statutory", null, "exclude", false, false, false, "nearest", false},
            {"ESI_ER", "Employer ESI", "employer", true, 210, "statutory", null, "exclude", false, false, false, "up", false},
            {"LWF_ER", "Employer LWF", "employer", true, 220, "statutory", null, "exclude", false, false, false, "none", false},
    };

    private static final String[][] MESSAGES = {
            {"payslip_released", "Your payslip for {{period}}",
                    "Hello {{person.first_name}}, your payslip for {{period}} is ready in My pay."},
            {"payroll_sent_back", "Payroll sent back", "{{period}} for {{group}} was sent back: {{note}}"},
            {"payroll_ready", "Payroll ready to approve", "{{period}} for {{group}} is calculated and waits for approval."},
    };

    /** The date the starting configuration is valid from: early enough for any month payroll is run for. */
    static final String START = "2025-04-01";

    static void ensure(Connection c, UUID tenantId) throws SQLException {
        if (Sql.one(c, "select 1 from app.payroll_settings where tenant_id = ?", tenantId) != null) {
            return;
        }
        if (Sql.update(c, "insert into app.payroll_settings (tenant_id) values (?) on conflict do nothing", tenantId) == 0) {
            return;
        }
        for (Object[] p : COMPONENTS) {
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.pay_component (tenant_id, code, name, kind, statutory, position) "
                    + "values (?, ?, ?, ?, ?, ?) returning id", tenantId, p[0], p[1], p[2], p[3], ((Integer) p[4]).shortValue()).get("id"));
            Sql.update(c, "insert into app.pay_component_version (tenant_id, component_id, valid_from, amount_type, formula, wage, esi_wage, "
                            + "taxable, prorate, rounding, one_time) values (?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, id, START, p[5], p[6], p[7], p[8], p[9], p[10], p[11], p[12]);
        }
        UUID staff = AuthService.uuid(Sql.one(c, "insert into app.salary_structure (tenant_id, name, basis) values (?, 'Staff', 'ctc') returning id",
                tenantId).get("id"));
        Sql.update(c, "insert into app.salary_structure_version (tenant_id, structure_id, valid_from, lines, balancing) values (?, ?, ?::date, "
                + "?::jsonb, 'SPECIAL')", tenantId, staff, START,
                "[{\"code\":\"BASIC\"},{\"code\":\"HRA\"},{\"code\":\"SPECIAL\"},{\"code\":\"PF_ER\"}]");
        Sql.update(c, "insert into app.pay_group (tenant_id, name, default_structure_id) values (?, 'Monthly staff', ?)", tenantId, staff);
        for (String[] m : MESSAGES) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, event, channel) do nothing", tenantId, m[0], m[1], m[2]);
        }
    }
}
