package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * What every Organization starts with for exits: the settings, the message wording, an exit
 * checklist, the exit letters and the exit interview questions. Written the first time anything
 * reads the Organization's exits; the settings row is the marker, so running it twice changes
 * nothing.
 */
final class ExitDefaults {

    private ExitDefaults() {}

    static final String INTERVIEW = """
            [
              {"id":"reason","type":"choice","text":"What is the main reason you are leaving?",
               "choices":["Career growth","Pay","Manager","Workload","Relocation","Higher studies","Health","Personal","Other"]},
              {"id":"manager","type":"rating","text":"How well did your manager support you?"},
              {"id":"growth","type":"rating","text":"How well could you learn and grow here?"},
              {"id":"pay","type":"rating","text":"How fair was your pay for your work?"},
              {"id":"workload","type":"rating","text":"How manageable was your workload?"},
              {"id":"culture","type":"rating","text":"How would you rate the way people work together here?"},
              {"id":"return","type":"choice","text":"Would you work here again?","choices":["Yes","Maybe","No"]},
              {"id":"change","type":"text","text":"What one thing should we change?"}
            ]""";

    private static final String[][] MESSAGES = {
            {"resignation_submitted", "Resignation from {{employee.name}}",
                    "{{employee.name}} resigned on {{exit.submitted_on}} and asks to leave on {{exit.last_working_day}}. "
                            + "Their notice ends on {{exit.notice_end}}."},
            {"resignation_recommended", "Resignation ready to accept",
                    "{{employee.name}}'s manager agreed a last working day of {{exit.last_working_day}}. It waits for HR to accept."},
            {"resignation_accepted", "Your resignation is accepted",
                    "Hello {{person.first_name}}, your resignation is accepted. Your last working day is {{exit.last_working_day}}."},
            {"resignation_withdrawn", "Resignation withdrawn", "{{employee.name}} withdrew their resignation."},
            {"withdrawal_requested", "Withdrawal asked for",
                    "{{employee.name}} asks to withdraw their resignation. Their last working day is {{exit.last_working_day}}."},
            {"withdrawal_decided", "Your request to withdraw", "Hello {{person.first_name}}, {{note}}"},
            {"exit_started", "{{employee.name}} is leaving",
                    "{{employee.name}} is leaving ({{exit.kind}}). Their last working day is {{exit.last_working_day}}."},
            {"exit_proposed", "Exit to confirm",
                    "{{employee.name}}: {{exit.kind}} on {{exit.last_working_day}}. Confirm it or cancel it."},
            {"exit_task_assigned", "New exit task", "{{task.title}} for {{employee.name}}, due {{task.due_date}}."},
            {"exit_task_overdue", "Exit task overdue", "{{task.title}} for {{employee.name}} was due {{task.due_date}}."},
            {"last_day_tomorrow", "Last working day tomorrow",
                    "{{employee.name}}'s last working day is tomorrow, {{exit.last_working_day}}."},
            {"settlement_due", "Settlement due",
                    "{{settlement.line}} for {{employee.name}} must be paid by {{settlement.due_date}}."},
            {"exit_letter_issued", "{{letter.title}}", "Hello {{person.first_name}}, your {{letter.title}} is ready in My exit."},
            {"interview_sent", "Your exit interview",
                    "Hello {{person.first_name}}, we would value a few minutes of your views before you go. The exit interview is in My exit."},
            {"former_access_ending", "Your access ends soon",
                    "Hello {{person.first_name}}, your access to your letters and settlement ends on {{exit.access_until}}. "
                            + "Download anything you want to keep."},
    };

    private static final String ACCEPTANCE = """
            {{organization.name}}

            Acceptance of resignation

            {{today}}

            Dear {{person.legal_name}},

            We have received your resignation of {{exit.submitted_on}} and accept it. Your last working day with us will be \
            {{exit.last_working_day}}.

            Please hand over your work and return any of the Organization's property before then. Your full and final \
            settlement will be made as the law requires, and your relieving and experience letters will follow once your \
            clearance is complete.

            We thank you for your work and wish you well.

            For {{organization.name}}""";

    private static final String RELIEVING = """
            {{organization.name}}

            Relieving letter

            {{today}}

            Dear {{person.legal_name}},

            This is to confirm that you have been relieved of your duties as {{job.designation}}{{job.department_with_in}} \
            at the close of business on {{exit.last_working_day}}, following your {{exit.kind}}.

            Employee code: {{employment.code}}
            Joined on: {{employment.joining_date}}

            We wish you success in the years ahead.

            For {{organization.name}}""";

    private static final String EXPERIENCE = """
            {{organization.name}}

            Experience letter

            {{today}}

            To whom it may concern

            This is to certify that {{person.legal_name}} was employed with {{organization.name}} from \
            {{employment.joining_date}} to {{exit.last_working_day}}, a period of {{employment.tenure}}.

            Positions held:
            {{employment.designations}}

            For {{organization.name}}""";

    private static final String TERMINATION = """
            {{organization.name}}

            Termination of employment

            {{today}}

            Dear {{person.legal_name}},

            This is to inform you that your employment with {{organization.name}} as {{job.designation}} ends, with your \
            last working day on {{exit.last_working_day}}.

            Your full and final settlement will be made as the law requires. Please return any of the Organization's \
            property by your last working day.

            For {{organization.name}}""";

    private static final String RETIREMENT = """
            {{organization.name}}

            Retirement

            {{today}}

            Dear {{person.legal_name}},

            On reaching the age of retirement, you will retire from {{organization.name}} on {{exit.last_working_day}}, \
            after {{employment.tenure}} of service since {{employment.joining_date}}.

            Thank you for all your years of work with us. Your full and final settlement, including gratuity, will be made \
            as the law requires.

            For {{organization.name}}""";

    private static final String CONTRACT_END = """
            {{organization.name}}

            End of contract

            {{today}}

            Dear {{person.legal_name}},

            Your fixed-term contract as {{job.designation}} ends on {{employment.contract_end}}, and your last working day \
            will be {{exit.last_working_day}}.

            Your full and final settlement will be made as the law requires.

            For {{organization.name}}""";

    private static final String SHOW_CAUSE = """
            {{organization.name}}

            Absence without leave

            {{today}}

            Dear {{person.legal_name}},

            You have been absent from work without leave or any word since {{exit.absent_since}}. Please report to work, \
            or explain your absence in writing, by {{exit.response_by}}.

            If we do not hear from you by then, we may treat you as having left your employment.

            For {{organization.name}}""";

    private static final String FINAL_NOTICE = """
            {{organization.name}}

            Final notice

            {{today}}

            Dear {{person.legal_name}},

            We wrote to you about your absence without leave and have had no reply. Unless you report to work or contact us \
            by {{exit.response_by}}, your employment will be treated as ended, with {{exit.last_working_day}} as your last \
            working day.

            For {{organization.name}}""";

    /** Writes the defaults unless the Organization has them. */
    static void ensure(Connection c, UUID tenantId) throws SQLException {
        if (Sql.one(c, "select 1 from app.exit_settings where tenant_id = ?", tenantId) != null) {
            return;
        }
        // Read by another request at the same moment: the second insert finds the row and stops here.
        if (Sql.update(c, "insert into app.exit_settings (tenant_id, interview_questions) values (?, ?::jsonb) on conflict do nothing",
                tenantId, INTERVIEW) == 0) {
            return;
        }
        for (String[] m : MESSAGES) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, event, channel) do nothing", tenantId, m[0], m[1], m[2]);
        }
        for (String[] l : List.of(
                new String[] {"exit_acceptance", "Resignation acceptance", ACCEPTANCE},
                new String[] {"relieving", "Relieving letter", RELIEVING},
                new String[] {"experience", "Experience letter", EXPERIENCE},
                new String[] {"termination", "Termination letter", TERMINATION},
                new String[] {"retirement", "Retirement letter", RETIREMENT},
                new String[] {"contract_end", "End of contract letter", CONTRACT_END},
                new String[] {"show_cause", "Show-cause notice", SHOW_CAUSE},
                new String[] {"final_notice", "Final notice", FINAL_NOTICE})) {
            Sql.update(c, "insert into app.letter_template (tenant_id, kind, name, body, response) values (?, ?, ?, ?, 'none')",
                    tenantId, l[0], l[1], l[2]);
        }
        UUID checklist = AuthService.uuid(Sql.one(c, "insert into app.checklist_template (tenant_id, name, kind) values (?, 'Leaving', 'exit') "
                + "returning id", tenantId).get("id"));
        Object[][] tasks = {
                {"Plan the handover of work", "manager", "exit_accepted", 5},
                {"Confirm the handover is done", "manager", "last_working_day", 0},
                {"Collect the laptop and other equipment", "it", "last_working_day", 0},
                {"Turn off email and system accounts", "it", "last_working_day", 1},
                {"Collect the ID card and access card", "hr", "last_working_day", 0},
                {"Clear open advances, loans and expense claims", "finance", "last_working_day", -2},
                {"Hold the exit interview", "hr", "last_working_day", -3},
                {"Mark the date of exit for PF on the EPFO portal (and ESI, if covered)", "hr", "last_working_day", 2},
                {"Submit pending expense claims", "joiner", "last_working_day", -3},
        };
        short position = 0;
        for (Object[] t : tasks) {
            Sql.update(c, "insert into app.checklist_template_task (tenant_id, template_id, title, owner, trigger_event, offset_days, position) "
                    + "values (?, ?, ?, ?, ?, ?, ?)", tenantId, checklist, t[0], t[1], t[2], ((Integer) t[3]).shortValue(), position++);
        }
    }
}
