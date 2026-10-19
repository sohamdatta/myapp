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

    private static final String[][] REVIEW_MESSAGES = {
            {"review_self_open", "Your self review is open",
                    "Hello {{person.first_name}}, your self review for {{review.cycle}} is open until {{review.due}}. It is in My performance."},
            {"review_manager_open", "A review to write",
                    "{{employee.name}}'s review for {{review.cycle}} is ready for you to write, by {{review.due}}."},
            {"review_due", "Review due soon", "The {{review.cycle}} review of {{employee.name}} is due on {{review.due}}."},
            {"review_self_returned", "Your self review was returned",
                    "Hello {{person.first_name}}, your manager returned your self review for {{review.cycle}}: {{note}}"},
            {"review_released", "Your review is ready",
                    "Hello {{person.first_name}}, your review for {{review.cycle}} is ready to read in My performance."},
    };

    private static final String[][] OUTCOME_MESSAGES = {
            {"review_calibrated", "A rating changed in calibration",
                    "The {{review.cycle}} rating of {{employee.name}} was changed in calibration to {{review.rating}}."},
            {"outcomes_submitted", "Outcomes to approve", "Outcomes for {{review.cycle}} are waiting for your approval: {{note}}"},
            {"outcomes_returned", "Outcomes returned", "Outcomes for {{review.cycle}} were returned: {{note}}"},
    };

    static final String INCREMENT = """
            {{organization.name}}

            Revision of salary

            {{today}}

            Dear {{person.legal_name}},

            Following your performance review for {{review.cycle}}, in which you were rated {{review.rating}}, we are pleased \
            to revise your salary by {{outcome.increment_pct}}, to {{outcome.new_annual}} a year, with effect from \
            {{outcome.effective_from}}.

            All other terms of your employment remain the same. Thank you for your contribution.

            For {{organization.name}}""";

    static final String PROMOTION = """
            {{organization.name}}

            Promotion

            {{today}}

            Dear {{person.legal_name}},

            We are pleased to promote you to {{outcome.new_designation}}{{job.department_with_in}} with effect from \
            {{outcome.effective_from}}, following your performance review for {{review.cycle}}.

            Your manager will go through what the new role asks of you. Congratulations.

            For {{organization.name}}""";

    private static final String[][] PLAN_MESSAGES = {
            {"plan_drafted", "An improvement plan to start", "A draft improvement plan for {{employee.name}} is waiting for HR to start it."},
            {"plan_started", "Your improvement plan",
                    "Hello {{person.first_name}}, an improvement plan starts for you on {{plan.start_date}} and runs to {{plan.end_date}}. "
                            + "Please read it in My performance; you can respond there."},
            {"plan_checkin_due", "Improvement plan check-in due",
                    "A check-in on {{employee.name}}'s improvement plan is due on {{plan.checkin_due}}."},
            {"plan_checkin_written", "A check-in on your plan",
                    "Hello {{person.first_name}}, your manager wrote a check-in on your improvement plan. It is in My performance."},
            {"plan_overdue", "Improvement plan to decide",
                    "{{employee.name}}'s improvement plan ended on {{plan.end_date}} and has not been decided."},
            {"plan_decided", "Improvement plan decided", "{{employee.name}}'s improvement plan: {{plan.decision}}."},
    };

    static final String PLAN = """
            {{organization.name}}

            Performance improvement plan

            {{today}}

            Dear {{person.legal_name}},

            We have discussed with you where your work as {{job.designation}} is falling short of what the role needs. This \
            letter sets out a performance improvement plan from {{plan.start_date}} to {{plan.end_date}}.

            What needs to improve, and how it will be measured:
            {{plan.objectives}}

            {{job.manager_name}} will check in with you {{plan.checkin_every}}. The full plan, with the support we will give \
            you, is in My performance, where you can respond to it.

            If the objectives are met, the plan closes. If they are not met by {{plan.end_date}}, further action may follow, \
            which may include ending your employment.

            For {{organization.name}}""";

    /** The questions of the default review form. */
    static final String QUESTIONS = """
            [
              {"id":"went_well","for":"self","type":"text","text":"What went well for you this period?","required":true},
              {"id":"differently","for":"self","type":"text","text":"What would you do differently?","required":false},
              {"id":"support","for":"self","type":"text","text":"What support would help you next period?","required":false},
              {"id":"strengths","for":"manager","type":"text","text":"Their strengths, with examples","required":true},
              {"id":"develop","for":"manager","type":"text","text":"What they should work on next","required":true},
              {"id":"peer_strengths","for":"peer","type":"text","text":"What do they do well?","required":true},
              {"id":"peer_improve","for":"peer","type":"text","text":"What could they do better?","required":false}
            ]""";

    /** Writes the defaults unless the Organization has them, and adds what later stages need to an Organization that had the earlier ones. */
    static void ensure(Connection c, UUID tenantId) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select rating_scale_id, defaults_stage, (select count(*) from app.review_form) as forms "
                + "from app.perf_settings where tenant_id = ?", tenantId);
        if (s != null) {
            if (s.get("ratingScaleId") == null) {
                stageTwo(c, tenantId);
            }
            if (((Number) s.get("forms")).intValue() == 0) {
                stageThree(c, tenantId);
            }
            if (((Number) s.get("defaultsStage")).intValue() < 4) {
                stageFour(c, tenantId);
            }
            if (((Number) s.get("defaultsStage")).intValue() < 5) {
                stageFive(c, tenantId);
            }
            return;
        }
        // Read by another request at the same moment: the second insert finds the row and stops here.
        if (Sql.update(c, "insert into app.perf_settings (tenant_id) values (?) on conflict do nothing", tenantId) == 0) {
            return;
        }
        stageTwo(c, tenantId);
        stageThree(c, tenantId);
        stageFour(c, tenantId);
        stageFive(c, tenantId);
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

    /** The increment and promotion letters and the calibration and outcome messages. defaults_stage is the marker. */
    private static void stageFour(Connection c, UUID tenantId) throws SQLException {
        if (Sql.one(c, "select 1 from app.perf_settings where tenant_id = ? and defaults_stage < 4 for update", tenantId) == null) {
            return;   // another request did it a moment ago
        }
        Sql.update(c, "insert into app.letter_template (tenant_id, kind, name, body, response) values (?, 'increment', "
                + "'Increment letter', ?, 'none')", tenantId, INCREMENT);
        Sql.update(c, "insert into app.letter_template (tenant_id, kind, name, body, response) values (?, 'promotion', "
                + "'Promotion letter', ?, 'none')", tenantId, PROMOTION);
        for (String[] m : OUTCOME_MESSAGES) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, event, channel) do nothing", tenantId, m[0], m[1], m[2]);
        }
        Sql.update(c, "update app.perf_settings set defaults_stage = 4 where tenant_id = ?", tenantId);
    }

    /** The improvement plan letter and messages. defaults_stage is the marker. */
    private static void stageFive(Connection c, UUID tenantId) throws SQLException {
        if (Sql.one(c, "select 1 from app.perf_settings where tenant_id = ? and defaults_stage < 5 for update", tenantId) == null) {
            return;   // another request did it a moment ago
        }
        Sql.update(c, "insert into app.letter_template (tenant_id, kind, name, body, response) values (?, 'improvement_plan', "
                + "'Performance improvement plan', ?, 'none')", tenantId, PLAN);
        for (String[] m : PLAN_MESSAGES) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, event, channel) do nothing", tenantId, m[0], m[1], m[2]);
        }
        Sql.update(c, "update app.perf_settings set defaults_stage = 5 where tenant_id = ?", tenantId);
    }

    /** The default review form and the review messages. A review form is the marker. */
    private static void stageThree(Connection c, UUID tenantId) throws SQLException {
        Sql.one(c, "select 1 from app.perf_settings where tenant_id = ? for update", tenantId);
        if (Sql.one(c, "select 1 from app.review_form limit 1") != null) {
            return;   // another request did it a moment ago
        }
        UUID form = AuthService.uuid(Sql.one(c, "insert into app.review_form (tenant_id, name) values (?, 'Standard review') returning id",
                tenantId).get("id"));
        Sql.update(c, "insert into app.review_form_version (tenant_id, form_id, version, questions) values (?, ?, 1, ?::jsonb)",
                tenantId, form, QUESTIONS);
        for (String[] m : REVIEW_MESSAGES) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, event, channel) do nothing", tenantId, m[0], m[1], m[2]);
        }
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
