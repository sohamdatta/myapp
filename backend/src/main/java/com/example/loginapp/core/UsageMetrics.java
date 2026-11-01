package com.example.loginapp.core;

import java.util.List;

/**
 * What the usage statistics count (release U1). Each metric is one query that returns a single number for one Organization on one
 * day, or no row when there is nothing to say (a median of nothing). The counts come from records the app already keeps; nothing is
 * tracked for them, and nothing a query returns can be anything but a number.
 *
 * Every query starts from the same four inputs, in the same order: the Organization, the day's start and end (India time) and the
 * day itself. Tenant queries run inside the Organization under its own row-level security; sign-in queries run on the sign-in
 * records and mail queries on the mail outbox, both by the Organization's id, and return only a count.
 */
final class UsageMetrics {

    /** Where a query runs. */
    enum Source { TENANT, AUTH, PLATFORM }

    /**
     * @param kind how days add up into a week: count (sum), level (the last day's), median or share (the average of the days that have one)
     * @param isUse whether it shows the module being used, as opposed to its size or its health
     */
    record Metric(String code, String module, String area, String description, String kind, boolean isUse, Source source, String sql) {}

    private static final String P = "with p as (select ?::uuid tid, ?::timestamptz t0, ?::timestamptz t1, ?::date d) ";

    private static Metric tenant(String code, String module, String area, String kind, boolean use, String description, String sql) {
        return new Metric(code, module, area, description, kind, use, Source.TENANT, P + sql);
    }

    private static Metric count(String code, String module, String area, String description, String sql) {
        return tenant(code, module, area, "count", true, description, sql);
    }

    private static String median(String expression, String from) {
        return "select round((percentile_cont(0.5) within group (order by " + expression + "))::numeric, 2) v " + from;
    }

    static final List<Metric> ALL = List.of(
            // ── adoption ────────────────────────────────────────────────
            tenant("members", "core", "Adoption", "level", false, "People with a login, at the end of the day",
                    "select count(*) v from app.membership m, p where m.joined_at < p.t1 and (m.ended_at is null or m.ended_at >= p.t1) "
                            + "and (m.suspended_at is null or m.suspended_at >= p.t1)"),
            new Metric("members_active_7d", "core", "Adoption", "People who signed in during the last 7 days", "level", false, Source.AUTH,
                    P + "select count(distinct s.membership_id) v from auth.session s, p where s.tenant_id = p.tid and s.kind = 'tenant' "
                            + "and s.impersonation_grant_id is null and s.issued_at >= p.t1 - interval '7 days' and s.issued_at < p.t1"),
            new Metric("members_active_30d", "core", "Adoption", "People who signed in during the last 30 days", "level", false, Source.AUTH,
                    P + "select count(distinct s.membership_id) v from auth.session s, p where s.tenant_id = p.tid and s.kind = 'tenant' "
                            + "and s.impersonation_grant_id is null and s.issued_at >= p.t1 - interval '30 days' and s.issued_at < p.t1"),
            new Metric("signins_web", "core", "Adoption", "People who signed in on the web, each counted once a day", "count", true, Source.AUTH,
                    P + "select count(distinct s.membership_id) v from auth.session s, p where s.tenant_id = p.tid and s.kind = 'tenant' "
                            + "and s.impersonation_grant_id is null and s.issued_at >= p.t0 and s.issued_at < p.t1"),

            // ── attendance ──────────────────────────────────────────────
            count("punches_web", "attendance", "Attendance", "Punches made on the web",
                    "select count(*) v from app.punch u, p where u.source = 'web' and u.at >= p.t0 and u.at < p.t1"),
            count("punches_device", "attendance", "Attendance", "Punches from attendance devices",
                    "select count(*) v from app.punch u, p where u.source = 'device' and u.at >= p.t0 and u.at < p.t1"),
            count("punches_other", "attendance", "Attendance", "Punches imported, entered by hand or from approved requests",
                    "select count(*) v from app.punch u, p where u.source in ('import', 'manual', 'request') and u.at >= p.t0 and u.at < p.t1"),
            tenant("attendance_days_worked_out", "attendance", "Attendance", "count", false, "People whose day has a result",
                    "select count(*) v from app.attendance_day a, p where a.work_date = p.d"),
            count("attendance_corrections", "attendance", "Attendance", "Correction and on-duty requests raised",
                    "select count(*) v from app.attendance_request r, p where r.kind in ('regularization', 'on_duty') and r.created_at >= p.t0 and r.created_at < p.t1"),
            count("wfh_requests", "attendance", "Leave and work from home", "Work from home requests raised",
                    "select count(*) v from app.attendance_request r, p where r.kind = 'work_from_home' and r.created_at >= p.t0 and r.created_at < p.t1"),

            // ── leave ───────────────────────────────────────────────────
            count("leave_requests_raised", "leave", "Leave and work from home", "Leave requests raised",
                    "select count(*) v from app.leave_request r, p where r.created_at >= p.t0 and r.created_at < p.t1"),
            count("leave_requests_approved", "leave", "Leave and work from home", "Leave requests approved",
                    "select count(*) v from app.leave_request r, p where r.status = 'approved' and r.decided_at >= p.t0 and r.decided_at < p.t1"),
            count("leave_requests_rejected", "leave", "Leave and work from home", "Leave requests rejected",
                    "select count(*) v from app.leave_request r, p where r.status = 'rejected' and r.decided_at >= p.t0 and r.decided_at < p.t1"),
            tenant("leave_hours_to_decision", "leave", "Leave and work from home", "median", false, "Median hours from a leave request to its decision",
                    median("extract(epoch from r.decided_at - r.created_at) / 3600",
                            "from app.leave_request r, p where r.status in ('approved', 'rejected') and r.decided_at >= p.t0 and r.decided_at < p.t1")),

            // ── onboarding ──────────────────────────────────────────────
            count("joiners_started", "core", "Onboarding", "Joiners started",
                    "select count(*) v from app.onboarding o, p where o.created_at >= p.t0 and o.created_at < p.t1"),
            count("joiners_activated", "core", "Onboarding", "Joiners activated",
                    "select count(*) v from app.onboarding o, p where o.activated_at >= p.t0 and o.activated_at < p.t1"),
            tenant("onboarding_days_to_activate", "core", "Onboarding", "median", false, "Median days from starting a joiner to activating them",
                    median("extract(epoch from o.activated_at - o.created_at) / 86400",
                            "from app.onboarding o, p where o.activated_at >= p.t0 and o.activated_at < p.t1")),

            // ── payroll ─────────────────────────────────────────────────
            count("payroll_runs_approved", "payroll", "Payroll", "Payroll runs approved",
                    "select count(*) v from app.payroll_run r, p where r.approved_at >= p.t0 and r.approved_at < p.t1"),
            count("payroll_employees_paid", "payroll", "Payroll", "Payslips in the runs approved that day",
                    "select count(*) v from app.payslip s join app.payroll_run r on r.id = s.run_id, p where r.approved_at >= p.t0 and r.approved_at < p.t1"),
            tenant("payroll_hours_to_approval", "payroll", "Payroll", "median", false, "Median hours from a run being created to being approved",
                    median("extract(epoch from r.approved_at - r.created_at) / 3600",
                            "from app.payroll_run r, p where r.approved_at >= p.t0 and r.approved_at < p.t1")),

            // ── tax and statutory ───────────────────────────────────────
            count("tax_declarations_submitted", "payroll", "Tax and statutory", "Tax declarations submitted",
                    "select count(*) v from app.tax_declaration t, p where t.submitted_at >= p.t0 and t.submitted_at < p.t1"),
            count("tax_proofs_uploaded", "payroll", "Tax and statutory", "Tax proofs uploaded",
                    "select count(*) v from app.tax_proof t, p where t.created_at >= p.t0 and t.created_at < p.t1"),
            count("tax_proofs_verified", "payroll", "Tax and statutory", "Tax proofs accepted by HR",
                    "select count(*) v from app.tax_declaration_item i, p where i.proof_status = 'accepted' and i.reviewed_at >= p.t0 and i.reviewed_at < p.t1"),
            count("statutory_files_generated", "payroll", "Tax and statutory", "PF, ESI and TDS statement files generated",
                    "select (select count(*) from app.statutory_filing f where f.generated_at >= p.t0 and f.generated_at < p.t1) "
                            + "+ (select count(*) from app.tds_statement s where s.generated_at >= p.t0 and s.generated_at < p.t1) v from p"),
            count("statutory_files_filed", "payroll", "Tax and statutory", "PF and ESI returns approved as filed, and TDS statements filed",
                    "select (select count(*) from app.statutory_filing f where f.status = 'filed' and f.approved_at >= p.t0 and f.approved_at < p.t1) "
                            + "+ (select count(*) from app.tds_statement s where s.filed_on = p.d) v from p"),
            count("tax_certificates_released", "payroll", "Tax and statutory", "Form 130 Part B certificates released",
                    "select count(*) v from app.tax_certificate c, p where c.released_at >= p.t0 and c.released_at < p.t1"),

            // ── exits ───────────────────────────────────────────────────
            count("exits_started", "core", "Exits", "Exits started",
                    "select count(*) v from app.exit_case x, p where x.started_at >= p.t0 and x.started_at < p.t1"),
            count("settlements_paid", "core", "Exits", "Final settlements fully paid, counted on the day their last line was paid",
                    "select count(*) v from (select l.exit_case_id from app.exit_settlement_line l group by l.exit_case_id "
                            + "having count(*) filter (where l.paid_on is null) = 0 and max(l.paid_on) = (select d from p)) x"),
            tenant("settlements_on_time_share", "core", "Exits", "share", false, "Share of settlement lines paid on or before their due date, in per cent",
                    "select round(100.0 * count(*) filter (where l.due_date is null or l.paid_on <= l.due_date) / nullif(count(*), 0), 2) v "
                            + "from app.exit_settlement_line l, p where l.paid_on = p.d"),

            // ── health ──────────────────────────────────────────────────
            tenant("device_pushes_refused", "core", "Health", "count", false, "Device punches that were refused (unknown code, in the future, unreadable)",
                    "select count(*) v from app.device_punch_log d, p where d.outcome in ('unknown_code', 'in_the_future', 'unreadable') "
                            + "and d.received_at >= p.t0 and d.received_at < p.t1"),
            new Metric("emails_failed", "core", "Health", "Emails that could not be sent", "count", false, Source.PLATFORM,
                    P + "select count(*) v from mail.outbox o, p where o.tenant_id = p.tid and o.status = 'failed' and o.created_at >= p.t0 and o.created_at < p.t1"));

    private UsageMetrics() {}
}
