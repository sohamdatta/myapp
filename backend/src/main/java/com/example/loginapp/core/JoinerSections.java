package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reads and writes the values of one onboarding section. There is no separate
 * draft store: every save writes straight to the employee record (person,
 * addresses, contacts, family, bank, identifiers, employment, custom values),
 * and the section's status says whether it has been submitted and verified.
 * Keys are the form's field keys, such as {@code person.legal_name} or
 * {@code custom.college}; only the keys sent are changed.
 *
 * <p>Full identifiers are write-only here: reading returns their last four
 * characters under {@code masked}, never the value.
 */
final class JoinerSections {

    record Joiner(UUID tenantId, UUID onboardingId, UUID personId, UUID employmentId, LocalDate joiningDate) {}

    private static final Set<String> RELATIONSHIPS = Set.of("spouse", "son", "daughter", "father", "mother", "father_in_law",
            "mother_in_law", "brother", "sister", "other");
    private static final Set<String> SCHEMES = Set.of("pf", "gratuity", "insurance");

    private final Crypto crypto;

    JoinerSections(Crypto crypto) {
        this.crypto = crypto;
    }

    // ── reading ─────────────────────────────────────────────────────────

    /** Values and masked identifiers for a section, keyed by field key. */
    Map<String, Object> read(Connection c, Joiner j, String section) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> masked = new LinkedHashMap<>();
        Map<String, Object> p = Sql.one(c, "select * from app.person where id = ?", j.personId());
        switch (section) {
            case "employment" -> {
                Map<String, Object> e = Sql.one(c, "select e.joining_date, e.employment_type, e.probation_end, e.contract_end, "
                        + "e.employee_code, e.official_email, o.work_location_id, o.shift_id, o.weekly_offs "
                        + "from app.employment e join app.onboarding o on o.employment_id = e.id where o.id = ?", j.onboardingId());
                Map<String, Object> job = Sql.one(c, "select designation, department_id, manager_id from app.job_assignment "
                        + "where employment_id = ? order by effective_from desc, created_at desc limit 1", j.employmentId());
                values.put("employment.joining_date", e.get("joiningDate"));
                values.put("employment.employment_type", e.get("employmentType"));
                values.put("employment.probation_end", e.get("probationEnd"));
                values.put("employment.contract_end", e.get("contractEnd"));
                values.put("employment.employee_code", e.get("employeeCode"));
                values.put("employment.official_email", e.get("officialEmail"));
                values.put("employment.work_location", e.get("workLocationId"));
                values.put("employment.shift", e.get("shiftId"));
                values.put("employment.weekly_offs", ints(e.get("weeklyOffs")));
                values.put("employment.designation", job == null ? null : job.get("designation"));
                values.put("employment.department", job == null ? null : job.get("departmentId"));
                values.put("employment.manager", job == null ? null : job.get("managerId"));
            }
            case "personal" -> {
                for (String col : List.of("legal_name", "preferred_name", "date_of_birth", "gender", "marital_status", "nationality",
                        "blood_group")) {
                    values.put("person." + col, p.get(camel(col)));
                }
            }
            case "contact" -> {
                values.put("person.mobile", p.get("mobile"));
                values.put("person.personal_email", p.get("personalEmail"));
                values.put("person.alt_mobile", p.get("altMobile"));
            }
            case "address" -> {
                for (String kind : List.of("current", "permanent")) {
                    values.put("address." + kind, Sql.one(c, "select line1, line2, city, state_code, pin, country from app.person_address "
                            + "where person_id = ? and kind = ? order by effective_from desc, created_at desc limit 1", j.personId(), kind));
                }
            }
            case "emergency" -> values.put("emergency.contacts", Sql.query(c,
                    "select name, relationship, mobile from app.emergency_contact where person_id = ? order by position", j.personId()));
            case "family" -> {
                values.put("family.members", Sql.query(c, "select id, name, relationship, date_of_birth, dependent from app.family_member "
                        + "where person_id = ? and removed_at is null order by name", j.personId()));
                values.put("family.nominations", Sql.query(c, "select n.scheme, f.name as member, n.share_pct from app.nomination n "
                        + "join app.family_member f on f.id = n.family_member_id where n.person_id = ? and n.created_at = "
                        + "(select max(n2.created_at) from app.nomination n2 where n2.person_id = n.person_id and n2.scheme = n.scheme) "
                        + "order by n.scheme, f.name", j.personId()));
            }
            case "bank" -> {
                Map<String, Object> b = Sql.one(c, "select holder_name, account_last4, ifsc, bank_name, branch from app.bank_account "
                        + "where person_id = ? order by created_at desc limit 1", j.personId());
                values.put("bank.holder_name", b == null ? null : b.get("holderName"));
                values.put("bank.ifsc", b == null ? null : b.get("ifsc"));
                values.put("bank.bank_name", b == null ? null : b.get("bankName"));
                values.put("bank.branch", b == null ? null : b.get("branch"));
                if (b != null) {
                    masked.put("bank.account_number", b.get("accountLast4"));
                }
            }
            case "statutory" -> {
                for (Map<String, Object> s : Sql.query(c, "select kind, last4, pending_reason from app.statutory_id "
                        + "where person_id = ? and replaced_at is null", j.personId())) {
                    if (s.get("pendingReason") != null) {
                        values.put("statutory.pan_pending_reason", s.get("pendingReason"));
                    } else {
                        masked.put("statutory." + s.get("kind"), s.get("last4"));
                    }
                }
                Map<String, Object> regime = Sql.one(c, "select regime from app.tax_regime_choice where person_id = ? and tax_year = ?",
                        j.personId(), taxYear(j.joiningDate()));
                values.put("statutory.tax_regime", regime == null ? null : regime.get("regime"));
                Map<String, Object> o = Sql.one(c, "select previous_income from app.onboarding where id = ?", j.onboardingId());
                values.put("statutory.previous_income", o.get("previousIncome"));
            }
            default -> {
                // documents, policies and letters have no fields of their own yet
            }
        }
        for (Map<String, Object> v : Sql.query(c, "select f.field_key, f.sensitive, v.value, (v.value_enc is not null) as has_secret "
                + "from app.custom_field f join app.custom_field_value v on v.field_id = f.id "
                + "where v.person_id = ? and f.section = ?", j.personId(), section)) {
            String key = "custom." + v.get("fieldKey");
            if (Boolean.TRUE.equals(v.get("sensitive"))) {
                if (Boolean.TRUE.equals(v.get("hasSecret"))) {
                    masked.put(key, "set");
                }
            } else {
                values.put(key, v.get("value") == null ? null : Json.parse((String) v.get("value")));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", values);
        out.put("masked", masked);
        return out;
    }

    /** Required fields of a section that have no value yet, as labels. */
    @SuppressWarnings("unchecked")
    List<String> missing(Connection c, Joiner j, String section, List<Map<String, Object>> fields) throws SQLException {
        Map<String, Object> read = read(c, j, section);
        Map<String, Object> values = (Map<String, Object>) read.get("values");
        Map<String, Object> masked = (Map<String, Object>) read.get("masked");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String key = (String) f.get("key");
            if (!"required".equals(f.get("visibility")) || FieldCatalog.LATER_TYPES.contains(f.get("type"))
                    || key.equals("employment.weekly_offs")) {
                continue;
            }
            boolean has = masked.containsKey(key) || present(values.get(key))
                    || (key.equals("statutory.pan") && present(values.get("statutory.pan_pending_reason")));
            if (!has) {
                out.add((String) f.get("label"));
            }
        }
        return out;
    }

    private static boolean present(Object v) {
        return v != null && !(v instanceof String s && s.isBlank()) && !(v instanceof List<?> l && l.isEmpty())
                && !(v instanceof Map<?, ?> m && m.isEmpty());
    }

    // ── writing ─────────────────────────────────────────────────────────

    /**
     * Saves the values sent for one section. {@code allowed} is every key the caller may write
     * in this section (visible, and editable by them); anything else is refused.
     */
    void save(Connection c, SessionCtx ctx, Joiner j, String section, Map<String, Object> in, Set<String> allowed,
              boolean byHr) throws SQLException {
        for (String key : in.keySet()) {
            String base = key.equals("statutory.pan_pending_reason") ? "statutory.pan"
                    : key.startsWith("bank.") && Set.of("bank.bank_name", "bank.branch").contains(key) ? "bank.ifsc" : key;
            if (!allowed.contains(base)) {
                throw new ApiException(400, "field.not_allowed", "The field " + key + " cannot be changed here");
            }
        }
        switch (section) {
            case "employment" -> saveEmployment(c, ctx, j, in);
            case "personal" -> savePersonal(c, j, in, byHr);
            case "contact" -> saveContact(c, j, in);
            case "address" -> saveAddresses(c, ctx, j, in);
            case "emergency" -> saveEmergency(c, ctx, j, in);
            case "family" -> saveFamily(c, ctx, j, in);
            case "bank" -> saveBank(c, ctx, j, in);
            case "statutory" -> saveStatutory(c, ctx, j, in);
            default -> {
                // nothing of their own yet
            }
        }
        saveCustom(c, ctx, j, section, in);
    }

    private void saveEmployment(Connection c, SessionCtx ctx, Joiner j, Map<String, Object> in) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select employment_type, joining_date, probation_end, contract_end, employee_code, official_email "
                + "from app.employment where id = ? for update", j.employmentId());
        String type = in.containsKey("employment.employment_type") ? text(in.get("employment.employment_type")) : (String) e.get("employmentType");
        if (type == null || !AppliesTo.EMPLOYMENT_TYPES.contains(type)) {
            throw bad("Employment type must be permanent, probation, fixed_term, intern or consultant");
        }
        LocalDate joining = in.containsKey("employment.joining_date") ? date(in.get("employment.joining_date"), "Joining date")
                : LocalDate.parse((String) e.get("joiningDate"));
        if (joining == null) {
            throw bad("Enter the joining date");
        }
        LocalDate probation = in.containsKey("employment.probation_end") ? date(in.get("employment.probation_end"), "Probation end")
                : e.get("probationEnd") == null ? null : LocalDate.parse((String) e.get("probationEnd"));
        LocalDate contract = in.containsKey("employment.contract_end") ? date(in.get("employment.contract_end"), "Contract end")
                : e.get("contractEnd") == null ? null : LocalDate.parse((String) e.get("contractEnd"));
        if (type.equals("fixed_term") && contract == null) {
            throw bad("Enter when the fixed-term contract ends");
        }
        if (contract != null && contract.isBefore(joining)) {
            throw bad("The contract cannot end before the joining date");
        }
        if (probation != null && !probation.isAfter(joining)) {
            throw bad("Probation must end after the joining date");
        }
        String code = in.containsKey("employment.employee_code") ? text(in.get("employment.employee_code")) : (String) e.get("employeeCode");
        if (code != null && code.length() > 30) {
            throw bad("Keep the employee code to 30 characters");
        }
        String email = in.containsKey("employment.official_email") ? text(in.get("employment.official_email")) : (String) e.get("officialEmail");
        if (email != null) {
            email = IdChecks.email(email);
        }
        Sql.update(c, "update app.employment set employment_type = ?, joining_date = ?, probation_end = ?, contract_end = ?, "
                + "employee_code = ?, official_email = ? where id = ?", type, joining, probation, contract, code, email, j.employmentId());

        Map<String, Object> o = Sql.one(c, "select work_location_id, shift_id, weekly_offs from app.onboarding where id = ?", j.onboardingId());
        UUID location = in.containsKey("employment.work_location") ? id(in.get("employment.work_location"), "work location")
                : AuthService.uuid(o.get("workLocationId"));
        UUID shift = in.containsKey("employment.shift") ? id(in.get("employment.shift"), "shift") : AuthService.uuid(o.get("shiftId"));
        List<Integer> offs = in.containsKey("employment.weekly_offs") ? weekdays(in.get("employment.weekly_offs")) : ints(o.get("weeklyOffs"));
        if (location != null && Sql.one(c, "select 1 from app.work_location where id = ? and active", location) == null) {
            throw bad("Choose a work location that is in use");
        }
        if (shift != null && Sql.one(c, "select 1 from app.shift where id = ? and active", shift) == null) {
            throw bad("Choose a shift that is in use");
        }
        Sql.update(c, "update app.onboarding set work_location_id = ?, shift_id = ?, weekly_offs = ?::smallint[] where id = ?",
                location, shift, "{" + String.join(",", offs.stream().map(String::valueOf).toList()) + "}", j.onboardingId());

        if (in.keySet().stream().anyMatch(k -> Set.of("employment.designation", "employment.department", "employment.manager",
                "employment.joining_date").contains(k))) {
            Map<String, Object> job = Sql.one(c, "select designation, department_id, manager_id from app.job_assignment "
                    + "where employment_id = ? order by effective_from desc, created_at desc limit 1", j.employmentId());
            String designation = in.containsKey("employment.designation") ? text(in.get("employment.designation"))
                    : job == null ? null : (String) job.get("designation");
            if (designation == null) {
                throw bad("Enter the designation");
            }
            if (designation.length() > 80) {
                throw bad("Keep the designation to 80 characters");
            }
            UUID dept = in.containsKey("employment.department") ? id(in.get("employment.department"), "department")
                    : job == null ? null : AuthService.uuid(job.get("departmentId"));
            UUID manager = in.containsKey("employment.manager") ? id(in.get("employment.manager"), "manager")
                    : job == null ? null : AuthService.uuid(job.get("managerId"));
            if (dept != null && Sql.one(c, "select 1 from app.department where id = ? and active", dept) == null) {
                throw bad("Choose a department that is in use");
            }
            if (manager != null && Sql.one(c, "select 1 from app.employment where id = ? and status = 'active'", manager) == null) {
                throw new ApiException(409, "employee.manager_inactive", "The reporting manager must be an active employee");
            }
            // Before activation the job is one row from the joining date; a correction adds a newer row for the same date.
            Sql.update(c, "insert into app.job_assignment (tenant_id, employment_id, effective_from, designation, department_id, manager_id, "
                    + "reason, created_by) values (?, ?, ?, ?, ?, ?, 'Joining', ?)", j.tenantId(), j.employmentId(), joining,
                    designation, dept, manager, ctx.membershipId());
        }
    }

    private static void savePersonal(Connection c, Joiner j, Map<String, Object> in, boolean byHr) throws SQLException {
        Map<String, Object> p = Sql.one(c, "select * from app.person where id = ? for update", j.personId());
        String legal = in.containsKey("person.legal_name") ? text(in.get("person.legal_name")) : (String) p.get("legalName");
        if (legal == null) {
            throw bad("Enter your full name as on PAN");
        }
        if (legal.length() > 120) {
            throw bad("Keep the name to 120 characters");
        }
        String preferred = in.containsKey("person.preferred_name") ? text(in.get("person.preferred_name")) : (String) p.get("preferredName");
        LocalDate dob = in.containsKey("person.date_of_birth") ? date(in.get("person.date_of_birth"), "Date of birth")
                : p.get("dateOfBirth") == null ? null : LocalDate.parse((String) p.get("dateOfBirth"));
        if (dob != null && dob.plusYears(18).isAfter(j.joiningDate())) {
            if (!byHr || dob.plusYears(14).isAfter(j.joiningDate())) {
                throw new ApiException(400, "employee.too_young", byHr
                        ? "This makes the joiner under 14 on the joining date"
                        : "You need to be 18 or older on the joining date. If this is right, ask HR");
            }
        }
        String gender = in.containsKey("person.gender") ? choice(in.get("person.gender"),
                Set.of("female", "male", "transgender", "undisclosed"), "Gender") : (String) p.get("gender");
        String marital = in.containsKey("person.marital_status") ? choice(in.get("person.marital_status"),
                Set.of("single", "married", "widowed", "divorced", "separated"), "Marital status") : (String) p.get("maritalStatus");
        String nationality = in.containsKey("person.nationality") ? text(in.get("person.nationality")) : (String) p.get("nationality");
        nationality = nationality == null ? "IN" : nationality.toUpperCase();
        if (!nationality.matches("^[A-Z]{2}$")) {
            throw bad("Nationality is a two-letter country code, like IN");
        }
        String blood = in.containsKey("person.blood_group") ? choice(in.get("person.blood_group"),
                Set.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"), "Blood group") : (String) p.get("bloodGroup");
        Sql.update(c, "update app.person set legal_name = ?, preferred_name = ?, date_of_birth = ?, gender = ?, marital_status = ?, "
                + "nationality = ?, blood_group = ? where id = ?", legal, preferred, dob, gender, marital, nationality, blood, j.personId());
    }

    private static void saveContact(Connection c, Joiner j, Map<String, Object> in) throws SQLException {
        Map<String, Object> p = Sql.one(c, "select mobile, personal_email, alt_mobile from app.person where id = ? for update", j.personId());
        String mobile = in.containsKey("person.mobile") ? text(in.get("person.mobile")) : (String) p.get("mobile");
        String email = in.containsKey("person.personal_email") ? text(in.get("person.personal_email")) : (String) p.get("personalEmail");
        String alt = in.containsKey("person.alt_mobile") ? text(in.get("person.alt_mobile")) : (String) p.get("altMobile");
        if (mobile != null) {
            mobile = IdChecks.mobile(mobile);
        }
        if (email != null) {
            email = IdChecks.email(email);
        }
        if (alt != null) {
            alt = alt.replaceAll("[\\s()+-]", "");
            if (!alt.matches("^[0-9]{10,15}$")) {
                throw new ApiException(400, "employee.invalid_mobile", "Another phone needs 10 to 15 digits");
            }
        }
        Sql.update(c, "update app.person set mobile = ?, personal_email = ?, alt_mobile = ? where id = ?", mobile, email, alt, j.personId());
    }

    private static void saveAddresses(Connection c, SessionCtx ctx, Joiner j, Map<String, Object> in) throws SQLException {
        for (String kind : List.of("current", "permanent")) {
            String key = "address." + kind;
            if (!in.containsKey(key) || in.get(key) == null) {
                continue;
            }
            if (!(in.get(key) instanceof Map<?, ?> a)) {
                throw bad("An address has line1, line2, city, stateCode, pin and country");
            }
            String line1 = text(a.get("line1"));
            String city = text(a.get("city"));
            if (line1 == null || city == null) {
                throw bad("An address needs at least the first line and the city");
            }
            String country = text(a.get("country"));
            country = country == null ? "IN" : country.toUpperCase();
            String state = country.equals("IN") ? IdChecks.state(text(a.get("stateCode"))) : text(a.get("stateCode"));
            if (state == null || !state.matches("^[A-Z]{2}$")) {
                throw bad("Choose the state");
            }
            String pin = country.equals("IN") ? IdChecks.pin(text(a.get("pin"))) : text(a.get("pin"));
            Sql.update(c, "insert into app.person_address (tenant_id, person_id, kind, line1, line2, city, state_code, pin, country, "
                    + "effective_from, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", j.tenantId(), j.personId(), kind,
                    limit(line1, 120), limit(text(a.get("line2")), 120), limit(city, 60), state, pin, country,
                    j.joiningDate(), ctx.membershipId());
        }
    }

    private static void saveEmergency(Connection c, SessionCtx ctx, Joiner j, Map<String, Object> in) throws SQLException {
        if (!in.containsKey("emergency.contacts")) {
            return;
        }
        List<?> list = in.get("emergency.contacts") instanceof List<?> l ? l : List.of();
        if (list.size() > 3) {
            throw bad("Give at most three emergency contacts");
        }
        Map<String, Object> p = Sql.one(c, "select mobile from app.person where id = ?", j.personId());
        Sql.update(c, "delete from app.emergency_contact where person_id = ?", j.personId());
        int position = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw bad("Each emergency contact has a name, relationship and mobile");
            }
            String name = text(m.get("name"));
            String relationship = text(m.get("relationship"));
            String mobile = text(m.get("mobile"));
            if (name == null || relationship == null || mobile == null) {
                throw bad("Each emergency contact needs a name, how they are related to you, and a mobile number");
            }
            mobile = mobile.replaceAll("[\\s()+-]", "");
            if (!mobile.matches("^[0-9]{10,15}$")) {
                throw bad("An emergency contact's number needs 10 to 15 digits");
            }
            if (p.get("mobile") != null && mobile.endsWith((String) p.get("mobile"))) {
                throw bad("An emergency contact must be someone else, not your own number");
            }
            Sql.update(c, "insert into app.emergency_contact (tenant_id, person_id, name, relationship, mobile, position) "
                    + "values (?, ?, ?, ?, ?, ?)", j.tenantId(), j.personId(), limit(name, 120), limit(relationship, 40), mobile,
                    (short) position++);
        }
    }

    private static void saveFamily(Connection c, SessionCtx ctx, Joiner j, Map<String, Object> in) throws SQLException {
        if (in.containsKey("family.members")) {
            List<?> list = in.get("family.members") instanceof List<?> l ? l : List.of();
            if (list.size() > 20) {
                throw bad("Give at most 20 family members");
            }
            Set<String> names = new HashSet<>();
            Set<UUID> kept = new HashSet<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw bad("Each family member has a name and relationship");
                }
                String name = text(m.get("name"));
                String relationship = text(m.get("relationship"));
                if (name == null || relationship == null || !RELATIONSHIPS.contains(relationship)) {
                    throw bad("Each family member needs a name and a relationship from the list");
                }
                if (!names.add(name.toLowerCase())) {
                    throw bad("Two family members have the name " + name + "; add something to tell them apart");
                }
                LocalDate dob = date(m.get("dateOfBirth"), "A family member's date of birth");
                boolean dependent = Boolean.TRUE.equals(m.get("dependent"));
                UUID existing = m.get("id") == null ? null : id(m.get("id"), "family member");
                if (existing != null && Sql.one(c, "select 1 from app.family_member where id = ? and person_id = ? and removed_at is null",
                        existing, j.personId()) != null) {
                    Sql.update(c, "update app.family_member set name = ?, relationship = ?, date_of_birth = ?, dependent = ? where id = ?",
                            limit(name, 120), relationship, dob, dependent, existing);
                    kept.add(existing);
                } else {
                    kept.add(AuthService.uuid(Sql.one(c, "insert into app.family_member (tenant_id, person_id, name, relationship, "
                            + "date_of_birth, dependent) values (?, ?, ?, ?, ?, ?) returning id", j.tenantId(), j.personId(),
                            limit(name, 120), relationship, dob, dependent).get("id")));
                }
            }
            // Removed members are kept, marked removed, for nominations made while they were listed.
            for (Map<String, Object> r : Sql.query(c, "select id from app.family_member where person_id = ? and removed_at is null", j.personId())) {
                if (!kept.contains(AuthService.uuid(r.get("id")))) {
                    Sql.update(c, "update app.family_member set removed_at = now() where id = ?::uuid", r.get("id"));
                }
            }
        }
        if (in.containsKey("family.nominations")) {
            List<?> list = in.get("family.nominations") instanceof List<?> l ? l : List.of();
            Map<String, UUID> byName = new HashMap<>();
            for (Map<String, Object> r : Sql.query(c, "select id, name from app.family_member where person_id = ? and removed_at is null",
                    j.personId())) {
                byName.put(((String) r.get("name")).toLowerCase(), AuthService.uuid(r.get("id")));
            }
            Map<String, BigDecimal> totals = new HashMap<>();
            List<Object[]> rows = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw bad("Each nomination has a scheme, a family member and a share");
                }
                String scheme = text(m.get("scheme"));
                if (scheme == null || !SCHEMES.contains(scheme)) {
                    throw bad("A nomination is for pf, gratuity or insurance");
                }
                String member = text(m.get("member"));
                UUID memberId = member == null ? null : byName.get(member.toLowerCase());
                if (memberId == null) {
                    throw bad("Nominees must be family members you have listed");
                }
                BigDecimal share;
                try {
                    share = new BigDecimal(String.valueOf(m.get("sharePct")));
                } catch (NumberFormatException e) {
                    throw bad("A nominee's share is a percentage");
                }
                if (share.signum() <= 0 || share.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw bad("A nominee's share is more than 0 and at most 100 percent");
                }
                totals.merge(scheme, share, BigDecimal::add);
                rows.add(new Object[] {scheme, memberId, share});
            }
            for (Map.Entry<String, BigDecimal> t : totals.entrySet()) {
                if (t.getValue().compareTo(BigDecimal.valueOf(100)) != 0) {
                    throw new ApiException(400, "family.shares", "The shares for " + t.getKey().toUpperCase() + " add up to "
                            + t.getValue().stripTrailingZeros().toPlainString() + "%; they must add up to 100%");
                }
            }
            // A new set for each scheme given; earlier sets stay as history, and the newest set is the current one.
            // A scheme left out keeps the nominees it had.
            for (Object[] r : rows) {
                Sql.update(c, "insert into app.nomination (tenant_id, person_id, scheme, family_member_id, share_pct, effective_from) "
                        + "values (?, ?, ?, ?, ?, ?)", j.tenantId(), j.personId(), r[0], r[1], r[2], j.joiningDate());
            }
        }
    }

    private void saveBank(Connection c, SessionCtx ctx, Joiner j, Map<String, Object> in) throws SQLException {
        Map<String, Object> last = Sql.one(c, "select holder_name, ifsc, bank_name, branch, account_last4 from app.bank_account "
                + "where person_id = ? order by created_at desc limit 1", j.personId());
        String holder = in.containsKey("bank.holder_name") ? text(in.get("bank.holder_name")) : last == null ? null : (String) last.get("holderName");
        String ifsc = in.containsKey("bank.ifsc") ? text(in.get("bank.ifsc")) : last == null ? null : (String) last.get("ifsc");
        String bankName = in.containsKey("bank.bank_name") ? text(in.get("bank.bank_name")) : last == null ? null : (String) last.get("bankName");
        String branch = in.containsKey("bank.branch") ? text(in.get("bank.branch")) : last == null ? null : (String) last.get("branch");
        String account = text(in.get("bank.account_number"));
        if (account == null && last == null) {
            if (holder == null && ifsc == null) {
                return;   // nothing entered yet
            }
            throw bad("Enter the account number too");
        }
        if (holder == null) {
            holder = (String) Sql.one(c, "select legal_name from app.person where id = ?", j.personId()).get("legalName");
        }
        ifsc = IdChecks.ifsc(ifsc);
        byte[] sealed;
        String last4;
        if (account != null) {
            account = IdChecks.bankAccount(account);
            sealed = crypto.encrypt(j.tenantId(), account);
            last4 = IdChecks.last4(account);
        } else {
            sealed = bytes(c, "select account_enc from app.bank_account where person_id = ? order by created_at desc limit 1", j.personId());
            last4 = (String) last.get("accountLast4");
        }
        Sql.update(c, "insert into app.bank_account (tenant_id, person_id, holder_name, account_enc, account_last4, ifsc, bank_name, branch, "
                + "effective_from, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", j.tenantId(), j.personId(), limit(holder, 120),
                sealed, last4, ifsc, limit(bankName, 80), limit(branch, 80), j.joiningDate(), ctx.membershipId());
    }

    private void saveStatutory(Connection c, SessionCtx ctx, Joiner j, Map<String, Object> in) throws SQLException {
        for (String kind : List.of("pan", "aadhaar", "uan", "pf_member", "esi_ip")) {
            String key = "statutory." + kind;
            boolean pendingGiven = kind.equals("pan") && in.containsKey("statutory.pan_pending_reason");
            if (!in.containsKey(key) && !pendingGiven) {
                continue;
            }
            String raw = text(in.get(key));
            String pending = kind.equals("pan") ? text(in.get("statutory.pan_pending_reason")) : null;
            if (raw == null && pending == null) {
                if (in.containsKey(key) && in.get(key) == null && !kind.equals("pan")) {
                    Sql.update(c, "update app.statutory_id set replaced_at = now() where person_id = ? and kind = ? and replaced_at is null",
                            j.personId(), kind);
                }
                continue;   // an empty box leaves a saved number as it is
            }
            String clean = raw == null ? null : switch (kind) {
                case "pan" -> IdChecks.pan(raw);
                case "aadhaar" -> IdChecks.last4(IdChecks.aadhaar(raw));
                case "uan" -> IdChecks.uan(raw);
                case "pf_member" -> IdChecks.pfMember(raw);
                default -> IdChecks.esiIp(raw);
            };
            Sql.update(c, "update app.statutory_id set replaced_at = now() where person_id = ? and kind = ? and replaced_at is null",
                    j.personId(), kind);
            if (kind.equals("aadhaar")) {
                Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, last4, created_by) values (?, ?, 'aadhaar', ?, ?)",
                        j.tenantId(), j.personId(), clean, ctx.membershipId());
            } else if (clean == null) {
                Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, value_enc, last4, pending_reason, created_by) "
                        + "values (?, ?, 'pan', ?, 'NONE', ?, ?)", j.tenantId(), j.personId(), crypto.encrypt(j.tenantId(), ""),
                        limit(pending, 200), ctx.membershipId());
            } else {
                Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, value_enc, value_hash, last4, created_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", j.tenantId(), j.personId(), kind, crypto.encrypt(j.tenantId(), clean),
                        crypto.fingerprint(j.tenantId(), kind, clean), IdChecks.last4(clean), ctx.membershipId());
            }
        }
        if (in.containsKey("statutory.tax_regime")) {
            String regime = text(in.get("statutory.tax_regime"));
            if (regime == null) {
                Sql.update(c, "update app.tax_regime_choice set regime = 'new', chosen_at = now() where person_id = ? and tax_year = ?",
                        j.personId(), taxYear(j.joiningDate()));
            } else {
                choice(regime, Set.of("new", "old"), "Tax regime");
                Sql.update(c, "insert into app.tax_regime_choice (tenant_id, person_id, tax_year, regime) values (?, ?, ?, ?) "
                        + "on conflict (person_id, tax_year) do update set regime = excluded.regime, chosen_at = now()",
                        j.tenantId(), j.personId(), taxYear(j.joiningDate()), regime);
            }
        }
        if (in.containsKey("statutory.previous_income")) {
            Object v = in.get("statutory.previous_income");
            BigDecimal income = null;
            if (v != null && !(v instanceof String s && s.isBlank())) {
                try {
                    income = new BigDecimal(v.toString().replace(",", "").trim());
                } catch (NumberFormatException e) {
                    throw bad("Enter the previous salary as a number of rupees");
                }
                if (income.signum() < 0 || income.compareTo(new BigDecimal("9999999999")) > 0) {
                    throw bad("Enter the previous salary as a number of rupees");
                }
            }
            Sql.update(c, "update app.onboarding set previous_income = ? where id = ?", income, j.onboardingId());
        }
    }

    private void saveCustom(Connection c, SessionCtx ctx, Joiner j, String section, Map<String, Object> in) throws SQLException {
        Map<String, FieldCatalog.CustomField> byKey = new HashMap<>();
        for (FieldCatalog.CustomField f : OnboardingConfigService.customFieldDefs(c)) {
            if (f.section().equals(section)) {
                byKey.put("custom." + f.key(), f);
            }
        }
        for (Map.Entry<String, Object> e : in.entrySet()) {
            FieldCatalog.CustomField f = byKey.get(e.getKey());
            if (f == null) {
                continue;
            }
            Object clean = CustomValues.normalize(f, e.getValue());
            if (clean == null && f.sensitive() && e.getValue() == null) {
                continue;   // an empty secret box keeps what was saved
            }
            String plain = clean == null || f.sensitive() ? null : Json.write(clean);
            byte[] secret = clean == null || !f.sensitive() ? null : crypto.encrypt(j.tenantId(), Json.write(clean));
            Sql.update(c, "insert into app.custom_field_value (tenant_id, person_id, field_id, value, value_enc, updated_by) "
                            + "values (?, ?, ?, ?::jsonb, ?, ?) on conflict (person_id, field_id) do update set value = excluded.value, "
                            + "value_enc = excluded.value_enc, updated_by = excluded.updated_by, updated_at = now()",
                    j.tenantId(), j.personId(), f.id(), plain, secret, ctx.membershipId());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The Indian tax year a date falls in, as 2026-27. */
    static String taxYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    private static byte[] bytes(Connection c, String sql, Object param) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ints(Object v) {
        if (v == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            out.add(Integer.valueOf(o.toString()));
        }
        return out;
    }

    private static List<Integer> weekdays(Object v) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw bad("Weekly offs are a list of days, 0 for Sunday to 6 for Saturday");
        }
        TreeSet<Integer> out = new TreeSet<>();
        for (Object o : list) {
            int d;
            try {
                d = Integer.parseInt(o.toString().replaceAll("\\.0+$", ""));
            } catch (NumberFormatException e) {
                throw bad("Weekly offs are days 0 (Sunday) to 6 (Saturday)");
            }
            if (d < 0 || d > 6) {
                throw bad("Weekly offs are days 0 (Sunday) to 6 (Saturday)");
            }
            out.add(d);
        }
        if (out.size() > 6) {
            throw bad("Leave at least one working day in the week");
        }
        return new ArrayList<>(out);
    }

    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String limit(String v, int max) {
        if (v != null && v.length() > max) {
            throw bad("A value is longer than " + max + " characters");
        }
        return v;
    }

    private static String choice(Object v, Set<String> allowed, String label) {
        String s = text(v);
        if (s != null && !allowed.contains(s)) {
            throw bad(label + ": choose one of the listed options");
        }
        return s;
    }

    private static LocalDate date(Object v, String label) {
        String s = text(v);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw bad(label + " must be a date like 2026-10-12");
        }
    }

    private static UUID id(Object v, String what) {
        String s = text(v);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw bad("Choose the " + what);
        }
    }

    private static String camel(String col) {
        StringBuilder out = new StringBuilder();
        boolean up = false;
        for (char ch : col.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return out.toString();
    }

    private static ApiException bad(String message) {
        return new ApiException(400, "field.invalid", message);
    }
}
