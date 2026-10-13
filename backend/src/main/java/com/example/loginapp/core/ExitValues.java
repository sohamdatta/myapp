package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The values exit letters and messages are filled from: the person, their current job, their
 * employment, and the exit itself. Works for any employment, whether or not the person was
 * onboarded in the app.
 */
final class ExitValues {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final Map<String, String> KINDS = Map.of("resignation", "resignation", "termination", "termination of employment",
            "end_of_contract", "end of contract", "retirement", "retirement", "death", "passing", "absconding", "absence without leave");

    private static final Map<String, String> TYPES = Map.of("permanent", "Permanent", "probation", "On probation",
            "fixed_term", "Fixed term", "intern", "Internship", "consultant", "Consultant");

    private ExitValues() {}

    /** Everything about the exit case a letter or message may say. */
    static Map<String, String> forCase(Connection c, UUID caseId) throws SQLException {
        Map<String, Object> x = Sql.one(c, "select x.*, (x.started_at at time zone 'Asia/Kolkata')::date as started_on "
                + "from app.exit_case x where x.id = ?", caseId);
        Map<String, String> v = forEmployment(c, AuthService.uuid(x.get("employmentId")), lastDay(x));
        v.put("exit.kind", KINDS.getOrDefault((String) x.get("kind"), (String) x.get("kind")));
        v.put("exit.submitted_on", date(x.get("startedOn")));
        v.put("exit.last_working_day", x.get("lastWorkingDay") != null ? date(x.get("lastWorkingDay"))
                : x.get("recommendedLastDay") != null ? date(x.get("recommendedLastDay"))
                : x.get("requestedLastDay") != null ? date(x.get("requestedLastDay")) : date(x.get("noticeEnd")));
        v.put("exit.notice_days", String.valueOf(x.get("noticeDays")));
        v.put("exit.notice_end", date(x.get("noticeEnd")));
        v.put("exit.access_until", date(x.get("formerAccessUntil")));
        v.put("exit.absent_since", x.get("requestedLastDay") == null ? "" : date(LocalDate.parse((String) x.get("requestedLastDay")).plusDays(1)));
        return v;
    }

    /** The person, job and employment, with the length of service counted to {@code until} (today when null). */
    static Map<String, String> forEmployment(Connection c, UUID employmentId, LocalDate until) throws SQLException {
        Map<String, String> v = new HashMap<>();
        Map<String, Object> org = Sql.one(c, "select app.organization_name() as name");
        v.put("organization.name", org == null ? "" : String.valueOf(org.get("name")));
        Map<String, Object> r = Sql.one(c, "select p.legal_name, p.preferred_name, e.employment_type, e.joining_date, e.probation_end, "
                + "e.contract_end, e.employee_code, j.designation, d.name as department, mp.legal_name as manager, wl.name as location "
                + "from app.employment e join app.person p on p.id = e.person_id "
                + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "left join app.department d on d.id = j.department_id "
                + "left join app.employment me on me.id = j.manager_id left join app.person mp on mp.id = me.person_id "
                + "left join lateral (select a.work_location_id from app.attendance_assignment a where a.membership_id = e.membership_id "
                + "  order by a.effective_from desc limit 1) aa on true "
                + "left join app.work_location wl on wl.id = aa.work_location_id where e.id = ?", employmentId);
        String legal = (String) r.get("legalName");
        String preferred = (String) r.get("preferredName");
        v.put("person.legal_name", legal);
        v.put("person.first_name", preferred != null ? preferred : legal == null ? "" : legal.split(" ")[0]);
        v.put("employee.name", preferred != null ? preferred : legal);
        v.put("joiner.name", preferred != null ? preferred : legal);
        v.put("job.designation", text(r.get("designation")));
        v.put("job.department", text(r.get("department")));
        v.put("job.department_with_in", r.get("department") == null ? "" : ", in the " + r.get("department") + " department");
        v.put("job.manager_name", r.get("manager") == null ? "the Organization's management" : (String) r.get("manager"));
        v.put("job.work_location", r.get("location") == null ? "as assigned" : (String) r.get("location"));
        LocalDate joining = LocalDate.parse((String) r.get("joiningDate"));
        v.put("employment.joining_date", TemplateValues.DATE.format(joining));
        v.put("employment.type", TYPES.getOrDefault((String) r.get("employmentType"), text(r.get("employmentType"))));
        v.put("employment.probation_text", r.get("probationEnd") == null ? "none" : "until " + date(r.get("probationEnd")));
        v.put("employment.contract_end", date(r.get("contractEnd")));
        v.put("employment.code", r.get("employeeCode") == null ? "" : (String) r.get("employeeCode"));
        v.put("statutory.uan_or_pending", "");
        LocalDate end = until != null ? until : LocalDate.now(IST);
        v.put("employment.tenure", tenure(joining, end));
        v.put("employment.designations", designations(c, employmentId, end));
        v.put("today", TemplateValues.DATE.format(LocalDate.now(IST)));
        v.put("link", "");
        return v;
    }

    /** "3 years 2 months", "11 months", "20 days": the time from the joining date to the last day, inclusive. */
    static String tenure(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return "";
        }
        Period p = Period.between(from, to.plusDays(1));
        List<String> parts = new ArrayList<>();
        if (p.getYears() > 0) {
            parts.add(p.getYears() + (p.getYears() == 1 ? " year" : " years"));
        }
        if (p.getMonths() > 0) {
            parts.add(p.getMonths() + (p.getMonths() == 1 ? " month" : " months"));
        }
        if (parts.isEmpty()) {
            parts.add(p.getDays() + (p.getDays() == 1 ? " day" : " days"));
        }
        return String.join(" ", parts);
    }

    /** Each designation held, oldest first, with its dates: "Analyst, 1 May 2023 to 31 March 2025". */
    private static String designations(Connection c, UUID employmentId, LocalDate end) throws SQLException {
        List<Map<String, Object>> rows = Sql.query(c, "select designation, effective_from from app.job_assignment where employment_id = ? "
                + "order by effective_from, created_at", employmentId);
        List<String> lines = new ArrayList<>();
        String current = null;
        LocalDate since = null;
        for (Map<String, Object> row : rows) {
            String d = (String) row.get("designation");
            LocalDate from = LocalDate.parse((String) row.get("effectiveFrom"));
            if (d != null && !d.equals(current)) {
                if (current != null) {
                    lines.add(current + ", " + TemplateValues.DATE.format(since) + " to " + TemplateValues.DATE.format(from.minusDays(1)));
                }
                current = d;
                since = from;
            }
        }
        if (current != null) {
            lines.add(current + ", " + TemplateValues.DATE.format(since) + " to " + TemplateValues.DATE.format(end));
        }
        return String.join("\n", lines);
    }

    private static LocalDate lastDay(Map<String, Object> x) {
        Object d = x.get("lastWorkingDay");
        return d == null ? null : LocalDate.parse((String) d);
    }

    static String date(Object iso) {
        return iso == null ? "" : TemplateValues.DATE.format(LocalDate.parse(iso.toString().substring(0, 10)));
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }
}
