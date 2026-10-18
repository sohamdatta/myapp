package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
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

    /** The default rating scale: [value, label, description]. */
    static final String[][] SCALE = {
            {"5", "Outstanding", "Far beyond what the role asks, and a model for others"},
            {"4", "Exceeds expectations", "Regularly goes beyond what the role asks"},
            {"3", "Meets expectations", "Does what the role asks, well and reliably"},
            {"2", "Partly meets expectations", "Meets some of what the role asks; needs to improve in places"},
            {"1", "Does not meet expectations", "Falls short of what the role asks"},
    };

    private static final String[][] GOAL_MESSAGES = {
            {"goals_submitted", "Goals to agree", "{{employee.name}} has sent their goals for {{goals.period}} for you to agree."},
            {"goals_agreed", "Your goals are agreed", "Hello {{person.first_name}}, your goals for {{goals.period}} are agreed."},
            {"goals_returned", "Your goals need a change",
                    "Hello {{person.first_name}}, your manager returned your goals for {{goals.period}}: {{note}}"},
            {"goal_changed", "A goal changed", "{{employee.name}} changed a goal, \"{{goal.title}}\", and sent it for you to agree again."},
            {"one_to_one_nudge", "Time for a one-to-one", "You have not had a one-to-one with {{employee.name}} for a while."},
            {"feedback_received", "New feedback", "Hello {{person.first_name}}, {{giver.name}} gave you feedback. It is in My performance."},
    };

    /** Writes the defaults unless the Organization has them, and adds what later stages need to an Organization that had the earlier ones. */
    static void ensure(Connection c, UUID tenantId) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select rating_scale_id from app.perf_settings where tenant_id = ?", tenantId);
        if (s != null) {
            if (s.get("ratingScaleId") == null) {
                stageTwo(c, tenantId);
            }
            return;
        }
        // Read by another request at the same moment: the second insert finds the row and stops here.
        if (Sql.update(c, "insert into app.perf_settings (tenant_id) values (?) on conflict do nothing", tenantId) == 0) {
            return;
        }
        stageTwo(c, tenantId);
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

    /** The rating scale and the goal, one-to-one and feedback messages. The scale is the marker. */
    private static void stageTwo(Connection c, UUID tenantId) throws SQLException {
        if (Sql.one(c, "select 1 from app.perf_settings where tenant_id = ? and rating_scale_id is null for update", tenantId) == null) {
            return;   // another request did it a moment ago
        }
        UUID scale = AuthService.uuid(Sql.one(c, "insert into app.rating_scale (tenant_id, version) values (?, 1) returning id", tenantId)
                .get("id"));
        for (String[] l : SCALE) {
            Sql.update(c, "insert into app.rating_level (tenant_id, scale_id, value, label, description) values (?, ?, ?, ?, ?)",
                    tenantId, scale, Short.parseShort(l[0]), l[1], l[2]);
        }
        Sql.update(c, "update app.perf_settings set rating_scale_id = ? where tenant_id = ?", scale, tenantId);
        for (String[] m : GOAL_MESSAGES) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, event, channel) do nothing", tenantId, m[0], m[1], m[2]);
        }
    }
}
