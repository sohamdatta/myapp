package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * What every Organization starts with for performance: the settings, the message wording, and the
 * confirmation and probation extension letters. Written the first time anything reads the
 * Organization's performance; the settings row is the marker, so running it twice changes nothing.
 */
final class PerformanceDefaults {

    private PerformanceDefaults() {}

    private static final String[][] MESSAGES = {
            {"probation_checkin_due", "Probation check-in due",
                    "{{employee.name}}'s probation check-in for {{probation.checkin_day}} is due on {{probation.checkin_due}}."},
            {"probation_checkin_written", "Your probation check-in",
                    "Hello {{person.first_name}}, your manager wrote your probation check-in for {{probation.checkin_day}}. "
                            + "You can read it and reply in My performance."},
            {"probation_recommend_due", "Probation ending: your recommendation",
                    "{{employee.name}}'s probation ends on {{probation.end_date}}. Recommend whether to confirm, extend or not confirm."},
            {"probation_recommended", "Probation recommendation",
                    "{{employee.name}}'s manager recommends: {{probation.recommendation}}. The probation ends on {{probation.end_date}}."},
            {"probation_overdue", "Probation decision overdue",
                    "{{employee.name}}'s probation ended on {{probation.end_date}} and has not been decided."},
            {"probation_decided", "Probation decided", "{{employee.name}}'s probation: {{probation.decision}}."},
            {"probation_letter_issued", "{{letter.title}}", "Hello {{person.first_name}}, your {{letter.title}} is ready in My performance."},
    };

    static final String CONFIRMATION = """
            {{organization.name}}

            Confirmation of employment

            {{today}}

            Dear {{person.legal_name}},

            We are pleased to confirm your employment as {{job.designation}}{{job.department_with_in}} with effect from \
            {{probation.confirmed_from}}, on the completion of your probation.

            All other terms of your appointment remain the same. We look forward to your continued contribution.

            For {{organization.name}}""";

    static final String EXTENSION = """
            {{organization.name}}

            Extension of probation

            {{today}}

            Dear {{person.legal_name}},

            Your probation as {{job.designation}}, which was to end on {{probation.end_date}}, is extended by \
            {{probation.extension_months}}, to {{probation.new_end_date}}.

            Your manager will go through the areas to work on during this time, and your probation will be reviewed again \
            before it ends. All other terms of your appointment remain the same.

            For {{organization.name}}""";

    /** Writes the defaults unless the Organization has them. */
    static void ensure(Connection c, UUID tenantId) throws SQLException {
        if (Sql.one(c, "select 1 from app.perf_settings where tenant_id = ?", tenantId) != null) {
            return;
        }
        // Read by another request at the same moment: the second insert finds the row and stops here.
        if (Sql.update(c, "insert into app.perf_settings (tenant_id) values (?) on conflict do nothing", tenantId) == 0) {
            return;
        }
        for (String[] m : MESSAGES) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, event, channel) do nothing", tenantId, m[0], m[1], m[2]);
        }
        // An Organization that already wrote its own confirmation letter keeps it.
        if (Sql.one(c, "select 1 from app.letter_template where kind = 'confirmation'") == null) {
            Sql.update(c, "insert into app.letter_template (tenant_id, kind, name, body, response) values (?, 'confirmation', "
                    + "'Confirmation letter', ?, 'none')", tenantId, CONFIRMATION);
        }
        Sql.update(c, "insert into app.letter_template (tenant_id, kind, name, body, response) values (?, 'probation_extension', "
                + "'Probation extension letter', ?, 'none')", tenantId, EXTENSION);
    }
}
