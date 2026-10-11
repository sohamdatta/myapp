package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The values a joiner's letters and messages are filled from. */
final class TemplateValues {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Map<String, String> TYPES = Map.of("permanent", "Permanent", "probation", "On probation",
            "fixed_term", "Fixed term", "intern", "Internship", "consultant", "Consultant");

    private TemplateValues() {}

    /** Everything about the joiner a letter may say. With {@code crypto}, the UAN is given in full. */
    static Map<String, String> forJoiner(Connection c, JoinerService.Loaded l, Crypto crypto) throws SQLException {
        Map<String, String> v = new HashMap<>();
        Map<String, Object> org = Sql.one(c, "select app.organization_name() as name");
        v.put("organization.name", org == null ? "" : String.valueOf(org.get("name")));
        Map<String, Object> r = Sql.one(c, "select p.legal_name, p.preferred_name, e.employment_type, e.joining_date, e.probation_end, "
                + "e.contract_end, e.employee_code, j.designation, d.name as department, mp.legal_name as manager, wl.name as location "
                + "from app.onboarding o join app.person p on p.id = o.person_id join app.employment e on e.id = o.employment_id "
                + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "left join app.department d on d.id = j.department_id "
                + "left join app.employment me on me.id = j.manager_id left join app.person mp on mp.id = me.person_id "
                + "left join app.work_location wl on wl.id = o.work_location_id where o.id = ?", l.id());
        String legal = (String) r.get("legalName");
        v.put("person.legal_name", legal);
        String preferred = (String) r.get("preferredName");
        v.put("person.first_name", preferred != null ? preferred : legal == null ? "" : legal.split(" ")[0]);
        v.put("joiner.name", preferred != null ? preferred : legal);
        v.put("job.designation", text(r.get("designation")));
        v.put("job.department", text(r.get("department")));
        v.put("job.department_with_in", r.get("department") == null ? "" : ", in the " + r.get("department") + " department");
        v.put("job.manager_name", r.get("manager") == null ? "the Organization's management" : (String) r.get("manager"));
        v.put("job.work_location", r.get("location") == null ? "as assigned" : (String) r.get("location"));
        LocalDate joining = LocalDate.parse((String) r.get("joiningDate"));
        v.put("employment.joining_date", DATE.format(joining));
        v.put("employment.type", TYPES.getOrDefault((String) r.get("employmentType"), text(r.get("employmentType"))));
        v.put("employment.probation_text", r.get("probationEnd") == null ? "none"
                : "until " + DATE.format(LocalDate.parse((String) r.get("probationEnd"))));
        v.put("employment.contract_end", r.get("contractEnd") == null ? "" : DATE.format(LocalDate.parse((String) r.get("contractEnd"))));
        v.put("employment.code", r.get("employeeCode") == null ? "to be given on joining" : (String) r.get("employeeCode"));
        v.put("statutory.uan_or_pending", uan(c, l, crypto));
        v.put("today", DATE.format(LocalDate.now(IST)));
        return v;
    }

    private static String uan(Connection c, JoinerService.Loaded l, Crypto crypto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select value_enc, last4 from app.statutory_id where person_id = ? and kind = 'uan' "
                + "and replaced_at is null")) {
            ps.setObject(1, l.personId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "to be generated";
                }
                if (crypto == null || rs.getBytes(1) == null) {
                    return "ending " + rs.getString(2);
                }
                return crypto.decryptText(l.tenantId(), rs.getBytes(1));
            }
        }
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }
}
