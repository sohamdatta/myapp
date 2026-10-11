package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * What every Organization starts with, so it can onboard on day one and change
 * things later: settings, a privacy notice, one flow for everyone with every
 * section and one level of verification by HR Admin, five document types, a
 * checklist of eight common tasks, plain message wording and an appointment
 * letter. Written the first time anything reads the Organization's onboarding
 * configuration; the settings row is the marker, so running it twice changes
 * nothing.
 */
public final class OnboardingDefaults {

    private OnboardingDefaults() {}

    public static final String NOTICE = """
            Why we ask for this information

            We, your employer, use the details you give here to set up your employment: to pay you, \
            to deduct and file tax, provident fund and other contributions the law requires, to keep \
            the registers the labour codes require, and to reach you or someone close to you in an \
            emergency.

            Only people in HR and payroll who need them see your PAN, bank account and documents, and \
            every time anyone looks at them it is recorded. They are stored encrypted. Your manager \
            sees your job, contact details and joining date.

            Aadhaar is optional. If you give it, only its last four digits are kept.

            We keep your records for as long as the law requires after you leave, at least five years, \
            and then delete them. If you do not join, we delete what you gave us after 90 days.

            You can ask HR to see or correct what we hold about you at any time.""";

    private static final String APPOINTMENT = """
            {{organization.name}}

            Letter of appointment

            Dear {{person.legal_name}},

            We are pleased to appoint you as {{job.designation}}{{job.department_with_in}}, from \
            {{employment.joining_date}}, reporting to {{job.manager_name}}.

            Type of employment: {{employment.type}}
            Probation: {{employment.probation_text}}
            Place of work: {{job.work_location}}
            Universal Account Number (PF): {{statutory.uan_or_pending}}

            Your wages, their components and the wage period are set out in the schedule to this \
            letter. Your other terms are in the policies you acknowledge when you join.

            Please sign a copy of this letter to accept it.

            For {{organization.name}}
            {{today}}""";

    /** Writes the defaults if this Organization has none yet. Returns true when it did. */
    public static boolean ensure(Connection c, UUID tenantId) throws SQLException {
        if (Sql.one(c, "insert into app.onboarding_settings (tenant_id) values (?) on conflict do nothing returning tenant_id",
                tenantId) == null) {
            return false;
        }
        Sql.update(c, "insert into app.privacy_notice (tenant_id, version, body) values (?, 1, ?)", tenantId, NOTICE);

        UUID flow = AuthService.uuid(Sql.one(c,
                "insert into app.onboarding_flow (tenant_id, name, position, verification_levels) "
                        + "values (?, 'Everyone', 0, 1) returning id", tenantId).get("id"));
        insertSections(c, tenantId, flow);
        Sql.update(c, "insert into app.verification_step (tenant_id, flow_id, level, verifier, role_id) "
                + "select ?, ?, 1, 'role', r.id from app.role r where r.code = 'hr_admin' and r.tenant_id is null", tenantId, flow);

        String[][] documents = {
            {"PAN card", "before_activation", null, "A clear photo or scan of the front of your PAN card."},
            {"Address proof", "before_activation", null,
                "Any one: passport, voter id, driving licence, a utility bill under three months old, or a masked Aadhaar "
                        + "(the first eight digits covered)."},
            {"Education certificates", "within_days", "30", "Your highest qualification's certificate or final mark sheet."},
            {"Relieving letter", "optional", null, "From your last employer, if you have worked before."},
            {"Photo", "optional", null, "A recent passport-size photo, for your id card."},
        };
        int position = 1;
        for (String[] d : documents) {
            Sql.update(c, "insert into app.document_type (tenant_id, name, required, within_days, instructions, position) "
                    + "values (?, ?, ?, ?, ?, ?)", tenantId, d[0], d[1], d[2] == null ? null : Integer.valueOf(d[2]), d[3], position++);
        }

        UUID template = AuthService.uuid(Sql.one(c,
                "insert into app.checklist_template (tenant_id, name) values (?, 'Joining') returning id", tenantId).get("id"));
        Object[][] tasks = {
            {"Send the welcome note and invitation", "hr", "added", 0, true},
            {"Issue the appointment letter", "hr", "joining_date", -3, true},
            {"Prepare laptop and accounts", "it", "joining_date", -2, true},
            {"Create the official email", "it", "joining_date", -2, true},
            {"Plan the first week", "manager", "joining_date", -1, true},
            {"Bring original documents for checking", "joiner", "joining_date", 0, true},
            {"Introduce to the team", "manager", "joining_date", 0, true},
            {"First-month check-in", "manager", "joining_date", 30, false},
        };
        position = 1;
        for (Object[] t : tasks) {
            Sql.update(c, "insert into app.checklist_template_task (tenant_id, template_id, title, owner, trigger_event, offset_days, "
                    + "required, position) values (?, ?, ?, ?, ?, ?, ?, ?)", tenantId, template, t[0], t[1], t[2],
                    ((Integer) t[3]).shortValue(), t[4], position++);
        }

        String[][] messages = {
            {"joiner_invited", "Welcome to {{organization.name}}",
                "Hello {{person.first_name}}, we look forward to you joining on {{employment.joining_date}}. "
                        + "Use this link to fill in your details before you join: {{link}}"},
            {"offer_issued", "Your offer from {{organization.name}}",
                "Hello {{person.first_name}}, your offer letter is ready. Please read it and reply by {{offer.expires_on}}: {{link}}"},
            {"section_returned", "Please check your {{section.title}}",
                "HR has a question about your {{section.title}}: {{note}}"},
            {"reminder_joiner", "A few details are still needed",
                "Hello {{person.first_name}}, please finish your joining details before {{employment.joining_date}}: {{link}}"},
            {"task_assigned", "New task: {{task.title}}", "{{task.title}} for {{joiner.name}}, due {{task.due_date}}."},
            {"task_due", "Due soon: {{task.title}}", "{{task.title}} for {{joiner.name}} is due on {{task.due_date}}."},
            {"task_overdue", "Overdue: {{task.title}}", "{{task.title}} for {{joiner.name}} was due on {{task.due_date}}."},
            {"joiner_ready", "{{joiner.name}} is ready to join", "Everything needed for {{joiner.name}} is done and verified."},
            {"activated", "Welcome aboard", "Hello {{person.first_name}}, your account is now active. Your employee code is {{employment.code}}."},
            {"welcome_team", "{{joiner.name}} joins the team",
                "{{joiner.name}} joins as {{job.designation}} on {{employment.joining_date}}. Please make them welcome."},
        };
        for (String[] m : messages) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?)",
                    tenantId, m[0], m[1], m[2]);
        }

        Sql.update(c, "insert into app.letter_template (tenant_id, kind, name, body, response) "
                + "values (?, 'appointment', 'Appointment letter', ?, 'accept')", tenantId, APPOINTMENT);
        return true;
    }

    /**
     * Every section, in the default order, verified. Documents, policies and letters start switched
     * off: the joiner flow handles them once uploads, checklists and letters exist.
     */
    static void insertSections(Connection c, UUID tenantId, UUID flowId) throws SQLException {
        List<String> sections = FieldCatalog.SECTIONS;
        for (int i = 0; i < sections.size(); i++) {
            Sql.update(c, "insert into app.section_setting (tenant_id, flow_id, section, position, enabled) values (?, ?, ?, ?, ?)",
                    tenantId, flowId, sections.get(i), (short) (i + 1), !FieldCatalog.LATER.contains(sections.get(i)));
        }
    }
}
