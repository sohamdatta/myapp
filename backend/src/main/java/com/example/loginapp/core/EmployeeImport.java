package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bringing a new customer's existing staff in from a spreadsheet saved as CSV.
 * The person maps the file's columns to fields once; the mapping is
 * remembered for the Organization. Every row is checked first, with the
 * reason a row cannot be used, and nothing is written until the import is
 * confirmed. Rows already in the system (the same PAN, employee code,
 * personal email, or the same name joining on the same day) are refused, so
 * importing the same file twice adds nothing. PAN, UAN and bank accounts are
 * encrypted on the way in, as when they are typed.
 */
public final class EmployeeImport {

    /** Column of each field, by position in the file (0 is the first column). */
    public record Mapping(Boolean hasHeader, Map<String, Integer> columns, Boolean createDepartments) {}

    public record Request(String content, Mapping mapping) {}

    public record Invite(List<String> employmentIds) {}

    /** The fields a file can fill, in the order they are offered: key, label, required. */
    static final List<String[]> FIELDS = List.of(
            new String[] {"legal_name", "Full name as on PAN", "required"},
            new String[] {"joining_date", "Joining date", "required"},
            new String[] {"designation", "Designation", "required"},
            new String[] {"employee_code", "Employee code", ""},
            new String[] {"preferred_name", "Preferred name", ""},
            new String[] {"date_of_birth", "Date of birth", ""},
            new String[] {"gender", "Gender", ""},
            new String[] {"personal_email", "Personal email", ""},
            new String[] {"mobile", "Mobile", ""},
            new String[] {"employment_type", "Employment type", ""},
            new String[] {"probation_end", "Probation ends", ""},
            new String[] {"contract_end", "Contract ends", ""},
            new String[] {"department", "Department (name)", ""},
            new String[] {"manager", "Reporting manager (employee code or personal email)", ""},
            new String[] {"pan", "PAN", ""},
            new String[] {"pan_pending_reason", "Why PAN is pending", ""},
            new String[] {"aadhaar", "Aadhaar (only the last four digits are kept)", ""},
            new String[] {"uan", "UAN", ""},
            new String[] {"bank_account", "Bank account number", ""},
            new String[] {"ifsc", "IFSC", ""},
            new String[] {"bank_holder", "Account holder", ""},
            new String[] {"bank_name", "Bank name", ""},
            new String[] {"branch", "Branch", ""});

    /** Header names that commonly mean each field, for the first guess at a mapping. */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("legal_name", List.of("name", "fullname", "employeename", "legalname", "nameasonpan", "fullnameasonpan")),
            Map.entry("joining_date", List.of("joiningdate", "doj", "dateofjoining", "joindate", "joinedon", "startdate")),
            Map.entry("designation", List.of("designation", "title", "jobtitle", "role", "position")),
            Map.entry("employee_code", List.of("employeecode", "empcode", "code", "employeeid", "empid", "employeeno", "empno")),
            Map.entry("preferred_name", List.of("preferredname", "knownas", "nickname")),
            Map.entry("date_of_birth", List.of("dateofbirth", "dob", "birthdate", "birthday")),
            Map.entry("gender", List.of("gender", "sex")),
            Map.entry("personal_email", List.of("personalemail", "email", "emailid", "emailaddress", "personalemailid")),
            Map.entry("mobile", List.of("mobile", "mobilenumber", "phone", "phonenumber", "mobileno", "contactnumber")),
            Map.entry("employment_type", List.of("employmenttype", "type", "employeetype")),
            Map.entry("probation_end", List.of("probationend", "probationenddate", "confirmationdate")),
            Map.entry("contract_end", List.of("contractend", "contractenddate")),
            Map.entry("department", List.of("department", "dept", "departmentname")),
            Map.entry("manager", List.of("manager", "reportingmanager", "reportsto", "managercode", "manageremail", "reportingto")),
            Map.entry("pan", List.of("pan", "pannumber", "panno", "pancard")),
            Map.entry("pan_pending_reason", List.of("panpending", "panpendingreason")),
            Map.entry("aadhaar", List.of("aadhaar", "aadhar", "aadhaarnumber", "aadharnumber", "aadhaarno")),
            Map.entry("uan", List.of("uan", "uannumber", "uanno")),
            Map.entry("bank_account", List.of("bankaccount", "accountnumber", "accountno", "bankaccountnumber", "acno")),
            Map.entry("ifsc", List.of("ifsc", "ifsccode")),
            Map.entry("bank_holder", List.of("accountholder", "holdername", "accountholdername")),
            Map.entry("bank_name", List.of("bankname", "bank")),
            Map.entry("branch", List.of("branch", "branchname")));

    private static final DateTimeFormatter[] DATES = {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
        new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
        new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
        new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM uuuu").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
    };

    private final Db db;
    private final Crypto crypto;
    private final EmployeeService employees;

    public EmployeeImport(Db db, Crypto crypto, EmployeeService employees) {
        this.db = db;
        this.crypto = crypto;
        this.employees = employees;
    }

    // ── reading the file ────────────────────────────────────────────────

    /** The file's columns and first rows, the fields to map, and the mapping last used or a guess. */
    public Map<String, Object> read(SessionCtx ctx, String content) {
        requireHr(ctx);
        List<List<String>> rows = AttendanceImportService.parse(content);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> fields = fields(c);
            Map<String, Object> saved = Sql.one(c, "select mapping from app.import_mapping where kind = 'employees'");
            List<String> header = rows.get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", header);
            out.put("sample", rows.subList(1, Math.min(rows.size(), 6)));
            out.put("rowCount", rows.size());
            out.put("fields", fields);
            Map<String, Integer> remembered = saved == null ? null : fromSaved(Json.parse((String) saved.get("mapping")), header);
            out.put("mappingSaved", remembered != null && !remembered.isEmpty());
            out.put("mapping", Map.of("hasHeader", true, "columns", remembered != null && !remembered.isEmpty() ? remembered : guess(header, fields),
                    "createDepartments", true));
            return out;
        });
    }

    /** Checks every row without writing anything. */
    public Map<String, Object> preview(SessionCtx ctx, Request in) {
        requireHr(ctx);
        List<List<String>> rows = AttendanceImportService.parse(in.content());
        return db.inTenant(ctx.tenantId(), c -> summary(plan(c, ctx, rows, mapping(in)), false));
    }

    /** Imports every row that can be used, in one go, and remembers the mapping. */
    public Map<String, Object> run(SessionCtx ctx, Request in) {
        requireHr(ctx);
        List<List<String>> rows = AttendanceImportService.parse(in.content());
        Mapping m = mapping(in);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Row> plan = plan(c, ctx, rows, m);
            List<Row> good = plan.stream().filter(r -> r.problems.isEmpty()).toList();
            if (good.isEmpty()) {
                throw new ApiException(400, "import.nothing", "No row can be imported; see the reason given for each");
            }
            // Departments named in the file that do not exist yet.
            Map<String, UUID> departments = departments(c);
            for (Row r : good) {
                if (r.department != null && !departments.containsKey(r.department.toLowerCase(Locale.ROOT))) {
                    UUID d = AuthService.uuid(Sql.one(c, "insert into app.department (tenant_id, name) values (?, ?) returning id",
                            ctx.tenantId(), r.department).get("id"));
                    departments.put(r.department.toLowerCase(Locale.ROOT), d);
                }
            }
            // Managers first, so each employee's first job record can name theirs (job records are never changed).
            List<Row> ordered = new ArrayList<>();
            for (Row r : good) {
                addAfterManager(r, ordered);
            }
            for (Row r : ordered) {
                EmployeeService.NewEmployee e = r.employee;
                UUID dept = r.department == null ? null : departments.get(r.department.toLowerCase(Locale.ROOT));
                EmployeeService.NewEmployee withDept = new EmployeeService.NewEmployee(e.legalName(), e.preferredName(), e.dateOfBirth(),
                        e.gender(), e.personalEmail(), e.mobile(), e.employmentType(), e.joiningDate(), e.probationEnd(), e.contractEnd(),
                        e.employeeCode(), e.designation(), dept == null ? null : dept.toString(),
                        r.managerEmployment != null ? r.managerEmployment.toString() : r.managerRow != null ? r.managerRow.employmentId.toString() : null,
                        null, e.pan(), e.panPendingReason(),
                        e.aadhaar(), e.uan(), e.bankHolder(), e.bankAccount(), e.ifsc(), e.bankName(), e.branch());
                Map<String, Object> added = employees.insert(c, ctx, EmployeeService.check(withDept));
                r.employmentId = UUID.fromString((String) added.get("id"));
                UUID person = UUID.fromString((String) added.get("personId"));
                Sql.update(c, "update app.employment set imported_at = now() where id = ?", r.employmentId);
                for (Map.Entry<FieldCatalog.CustomField, Object> v : r.custom.entrySet()) {
                    FieldCatalog.CustomField f = v.getKey();
                    String plain = f.sensitive() ? null : Json.write(v.getValue());
                    byte[] secret = f.sensitive() ? crypto.encrypt(ctx.tenantId(), Json.write(v.getValue())) : null;
                    Sql.update(c, "insert into app.custom_field_value (tenant_id, person_id, field_id, value, value_enc, updated_by) "
                            + "values (?, ?, ?, ?::jsonb, ?, ?)", ctx.tenantId(), person, f.id(), plain, secret, ctx.membershipId());
                }
            }
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("hasHeader", m.hasHeader() == null || m.hasHeader());
            Map<String, Object> cols = new LinkedHashMap<>();
            List<String> header = rows.get(0);
            for (Map.Entry<String, Integer> e : m.columns().entrySet()) {
                cols.put(e.getKey(), Boolean.FALSE.equals(m.hasHeader()) || e.getValue() >= header.size() ? e.getValue() : header.get(e.getValue()));
            }
            saved.put("columns", cols);
            Sql.update(c, "insert into app.import_mapping (tenant_id, kind, mapping, updated_by) values (?, 'employees', ?::jsonb, ?) "
                    + "on conflict (tenant_id, kind) do update set mapping = excluded.mapping, updated_by = excluded.updated_by, updated_at = now()",
                    ctx.tenantId(), Json.write(saved), ctx.membershipId());
            UserService.audit(c, ctx, "employee.imported", "organization", null,
                    Map.of("imported", good.size(), "refused", plan.size() - good.size()));
            return summary(plan, true);
        });
    }

    // ── checking each row ───────────────────────────────────────────────

    private static final class Row {
        int line;
        String name;
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        EmployeeService.NewEmployee employee;
        String department;
        String code;
        String email;
        UUID managerEmployment;
        Row managerRow;
        Map<FieldCatalog.CustomField, Object> custom = new LinkedHashMap<>();
        UUID employmentId;
    }

    private List<Row> plan(Connection c, SessionCtx ctx, List<List<String>> rows, Mapping m) throws SQLException {
        boolean header = m.hasHeader() == null || m.hasHeader();
        Map<String, Integer> col = m.columns();
        for (String[] f : FIELDS) {
            if ("required".equals(f[2]) && col.get(f[0]) == null) {
                throw ApiException.badRequest("Choose the column for " + f[1]);
            }
        }
        Map<String, FieldCatalog.CustomField> customs = new HashMap<>();
        for (FieldCatalog.CustomField f : OnboardingConfigService.customFieldDefs(c)) {
            if (f.active()) {
                customs.put("custom." + f.key(), f);
            }
        }
        for (String key : col.keySet()) {
            if (FIELDS.stream().noneMatch(f -> f[0].equals(key)) && !customs.containsKey(key)) {
                throw ApiException.badRequest("Unknown field in the mapping: " + key);
            }
        }
        Map<String, UUID> departments = departments(c);
        boolean createDepartments = m.createDepartments() == null || m.createDepartments();
        List<Row> out = new ArrayList<>();
        Set<String> pans = new HashSet<>();
        Set<String> codes = new HashSet<>();
        Set<String> emails = new HashSet<>();
        Set<String> nameDates = new HashSet<>();
        for (int i = header ? 1 : 0; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            Row r = new Row();
            r.line = i + 1;
            java.util.function.Function<String, String> v = key -> {
                Integer index = col.get(key);
                if (index == null || index < 0 || index >= cells.size()) {
                    return null;
                }
                String s = cells.get(index).strip();
                return s.isEmpty() ? null : s;
            };
            r.name = v.apply("legal_name");
            String dob = date(v.apply("date_of_birth"), "Date of birth", r);
            String joining = date(v.apply("joining_date"), "Joining date", r);
            String probation = date(v.apply("probation_end"), "Probation end", r);
            String contract = date(v.apply("contract_end"), "Contract end", r);
            String gender = gender(v.apply("gender"), r);
            String type = type(v.apply("employment_type"), r);
            r.code = v.apply("employee_code");
            r.email = v.apply("personal_email") == null ? null : v.apply("personal_email").toLowerCase(Locale.ROOT);
            r.employee = new EmployeeService.NewEmployee(r.name, v.apply("preferred_name"), dob, gender, r.email, v.apply("mobile"), type,
                    joining, probation, contract, r.code, v.apply("designation"), null, null, null, v.apply("pan"),
                    v.apply("pan_pending_reason"), v.apply("aadhaar"), v.apply("uan"), v.apply("bank_holder"), v.apply("bank_account"),
                    v.apply("ifsc"), v.apply("bank_name"), v.apply("branch"));
            EmployeeService.Checked checked = null;
            if (r.problems.isEmpty()) {
                try {
                    checked = EmployeeService.check(r.employee);
                } catch (ApiException e) {
                    r.problems.add(e.getMessage());
                }
            }
            if (checked != null) {
                if (checked.pan() != null) {
                    if (!pans.add(checked.pan())) {
                        r.problems.add("The same PAN is on an earlier row");
                    } else if (Sql.one(c, "select 1 from app.statutory_id where kind = 'pan' and value_hash = ? and replaced_at is null",
                            crypto.fingerprint(ctx.tenantId(), "pan", checked.pan())) != null) {
                        r.problems.add("Someone with this PAN is already recorded");
                    }
                }
                if (r.code != null) {
                    if (!codes.add(r.code.toLowerCase(Locale.ROOT))) {
                        r.problems.add("The employee code " + r.code + " is on an earlier row");
                    } else if (Sql.one(c, "select 1 from app.employment where lower(employee_code) = lower(?) and status in ('onboarding','active') "
                            + "union all select 1 from app.membership where lower(employee_code) = lower(?)", r.code, r.code) != null) {
                        r.problems.add("The employee code " + r.code + " is already in use");
                    }
                }
                if (checked.email() != null) {
                    if (!emails.add(checked.email().toLowerCase(Locale.ROOT))) {
                        r.problems.add("The personal email is on an earlier row");
                    } else if (Sql.one(c, "select 1 from app.person where personal_email = ?::citext", checked.email()) != null) {
                        r.problems.add("Someone with this personal email is already recorded");
                    }
                }
                String nameDate = checked.legalName().toLowerCase(Locale.ROOT) + "|" + checked.joining();
                if (!nameDates.add(nameDate)) {
                    r.problems.add("The same name joining on the same day is on an earlier row");
                } else if (Sql.one(c, "select 1 from app.person p join app.employment e on e.person_id = p.id where lower(p.legal_name) = lower(?) "
                        + "and e.joining_date = ?", checked.legalName(), checked.joining()) != null) {
                    r.problems.add(checked.legalName() + " joining on " + checked.joining() + " is already recorded");
                }
            }
            r.department = v.apply("department");
            if (r.department != null && !departments.containsKey(r.department.toLowerCase(Locale.ROOT))) {
                if (r.department.length() > 80) {
                    r.problems.add("The department name is longer than 80 characters");
                } else if (createDepartments) {
                    r.notes.add("Adds the department " + r.department);
                } else {
                    r.problems.add("There is no department called " + r.department);
                }
            }
            for (Map.Entry<String, FieldCatalog.CustomField> e : customs.entrySet()) {
                String raw = v.apply(e.getKey());
                if (raw == null) {
                    continue;
                }
                Object value = e.getValue().dataType().equals("multi_select") ? List.of(raw.split("\\s*[;|]\\s*")) : raw;
                try {
                    Object clean = CustomValues.normalize(e.getValue(), value);
                    if (clean != null) {
                        r.custom.put(e.getValue(), clean);
                    }
                } catch (ApiException ex) {
                    r.problems.add(ex.getMessage());
                }
            }
            r.notes.add(v.apply("manager") == null ? "" : "manager:" + v.apply("manager"));
            out.add(r);
        }
        // Managers: someone already employed (by code or personal email) comes first, since a row of
        // the file with the same code is a duplicate; otherwise a usable row of this file.
        for (Row r : out) {
            String ref = r.notes.remove(r.notes.size() - 1);
            if (ref.isEmpty()) {
                continue;
            }
            String manager = ref.substring("manager:".length());
            Map<String, Object> e = Sql.one(c, "select e.id from app.employment e join app.person p on p.id = e.person_id "
                    + "where e.status = 'active' and (lower(e.employee_code) = lower(?) or p.personal_email = ?::citext) limit 1", manager, manager);
            if (e != null) {
                r.managerEmployment = AuthService.uuid(e.get("id"));
                continue;
            }
            Row inFile = out.stream().filter(o -> o != r && (manager.equalsIgnoreCase(o.code == null ? "" : o.code)
                    || manager.equalsIgnoreCase(o.email == null ? "" : o.email))).findFirst().orElse(null);
            if (inFile == null) {
                r.problems.add("No employee with the code or email " + manager + " to report to");
            } else if (!inFile.problems.isEmpty()) {
                r.problems.add("The manager " + manager + " is on row " + inFile.line + ", which cannot be imported");
            } else {
                r.managerRow = inFile;
            }
        }
        // Reporting lines within the file must not go round in a circle.
        for (Row r : out) {
            Row at = r.managerRow;
            int steps = 0;
            while (at != null && at != r && steps++ < out.size()) {
                at = at.managerRow;
            }
            if (at == r) {
                r.problems.add("The reporting lines in the file go round in a circle");
            }
        }
        // Someone reporting to a row that cannot be imported cannot be imported either.
        for (boolean changed = true; changed; ) {
            changed = false;
            for (Row r : out) {
                if (r.problems.isEmpty() && r.managerRow != null && !r.managerRow.problems.isEmpty()) {
                    r.problems.add("The manager is on row " + r.managerRow.line + ", which cannot be imported");
                    changed = true;
                }
            }
        }
        return out;
    }

    private static void addAfterManager(Row r, List<Row> ordered) {
        if (ordered.contains(r)) {
            return;
        }
        if (r.managerRow != null) {
            addAfterManager(r.managerRow, ordered);
        }
        ordered.add(r);
    }

    private static Map<String, Object> summary(List<Row> plan, boolean imported) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int ok = 0;
        for (Row r : plan) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("line", r.line);
            m.put("name", r.name);
            m.put("ok", r.problems.isEmpty());
            m.put("problems", r.problems);
            m.put("notes", r.notes);
            if (r.employmentId != null) {
                m.put("employmentId", r.employmentId.toString());
            }
            rows.add(m);
            if (r.problems.isEmpty()) {
                ok++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(imported ? "imported" : "ready", ok);
        out.put("refused", plan.size() - ok);
        out.put("rows", rows);
        return out;
    }

    // ── values ──────────────────────────────────────────────────────────

    static String date(String raw, String label, Row r) {
        if (raw == null) {
            return null;
        }
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(raw.strip(), f).toString();
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        if (r != null) {
            r.problems.add(label + " \"" + raw + "\" is not a date; write it like 2026-10-12 or 12/10/2026");
        }
        return null;
    }

    private static String gender(String raw, Row r) {
        if (raw == null) {
            return null;
        }
        String g = raw.strip().toLowerCase(Locale.ROOT);
        return switch (g) {
            case "f", "female", "woman" -> "female";
            case "m", "male", "man" -> "male";
            case "t", "transgender", "trans" -> "transgender";
            case "undisclosed", "prefer not to say", "not given", "-" -> "undisclosed";
            default -> {
                r.problems.add("Gender \"" + raw + "\" should be female, male, transgender or undisclosed");
                yield null;
            }
        };
    }

    private static String type(String raw, Row r) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return switch (t) {
            case "permanent", "full_time", "fulltime", "regular" -> "permanent";
            case "probation", "on_probation", "probationer" -> "probation";
            case "fixed_term", "contract", "contractual", "fixedterm" -> "fixed_term";
            case "intern", "internship", "trainee" -> "intern";
            case "consultant", "freelance" -> "consultant";
            default -> {
                r.problems.add("Employment type \"" + raw + "\" should be permanent, probation, fixed term, intern or consultant");
                yield null;
            }
        };
    }

    // ── mapping ─────────────────────────────────────────────────────────

    private static Mapping mapping(Request in) {
        if (in == null || in.mapping() == null || in.mapping().columns() == null) {
            throw ApiException.badRequest("Choose which column holds what");
        }
        return in.mapping();
    }

    private static List<Map<String, Object>> fields(Connection c) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] f : FIELDS) {
            out.add(Map.of("key", f[0], "label", f[1], "required", "required".equals(f[2])));
        }
        for (FieldCatalog.CustomField f : OnboardingConfigService.customFieldDefs(c)) {
            if (f.active()) {
                out.add(Map.of("key", "custom." + f.key(), "label", f.label() + " (your field)", "required", false));
            }
        }
        return out;
    }

    private static Map<String, Integer> guess(List<String> header, List<Map<String, Object>> fields) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String h = norm(header.get(i));
            for (Map<String, Object> f : fields) {
                String key = (String) f.get("key");
                List<String> names = key.startsWith("custom.")
                        ? List.of(norm(key.substring(7)), norm(((String) f.get("label")).replace(" (your field)", "")))
                        : SYNONYMS.getOrDefault(key, List.of());
                if (!out.containsKey(key) && names.contains(h)) {
                    out.put(key, i);
                    break;
                }
            }
        }
        return out;
    }

    /** A remembered mapping: by header name when the file had headers, otherwise by position. */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> fromSaved(Object saved, List<String> header) {
        if (!(saved instanceof Map<?, ?> m) || !(m.get("columns") instanceof Map<?, ?> cols)) {
            return null;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) cols).entrySet()) {
            if (e.getValue() instanceof Number n && n.intValue() < header.size()) {
                out.put(e.getKey(), n.intValue());
            } else if (e.getValue() instanceof String name) {
                for (int i = 0; i < header.size(); i++) {
                    if (header.get(i).strip().equalsIgnoreCase(name.strip())) {
                        out.put(e.getKey(), i);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, UUID> departments(Connection c) throws SQLException {
        Map<String, UUID> out = new HashMap<>();
        for (Map<String, Object> d : Sql.query(c, "select id, name from app.department where active")) {
            out.put(((String) d.get("name")).toLowerCase(Locale.ROOT), AuthService.uuid(d.get("id")));
        }
        return out;
    }

    // ── inviting imported employees ─────────────────────────────────────

    /** Active employees with a personal email and no login, who have not been invited yet or whose link expired. */
    public List<Map<String, Object>> uninvited(SessionCtx ctx) {
        requireHr(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, "select e.id, p.legal_name, p.preferred_name, p.personal_email, e.employee_code, "
                + "e.imported_at, (select max(i.created_at) from app.invitation i where i.person_id = p.id and i.revoked_at is null) as invited_at "
                + "from app.employment e join app.person p on p.id = e.person_id where e.status = 'active' and e.membership_id is null "
                + "and p.personal_email is not null and not exists (select 1 from app.membership m where m.person_id = p.id and m.status <> 'ended') "
                + "and not exists (select 1 from app.invitation i where i.person_id = p.id and i.accepted_at is null and i.revoked_at is null "
                + "  and i.expires_at > now()) "
                + "order by e.imported_at desc nulls last, p.legal_name limit 1000"));
    }

    /** Invites employees to sign in, with the Employee role. Returns each link to copy, or why someone was skipped. */
    public List<Map<String, Object>> invite(SessionCtx ctx, Invite in) {
        requireHr(ctx);
        if (in == null || in.employmentIds() == null || in.employmentIds().isEmpty()) {
            throw ApiException.badRequest("Choose at least one employee");
        }
        if (in.employmentIds().size() > 500) {
            throw ApiException.badRequest("Invite at most 500 people at a time");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            int days = ((Number) Sql.one(c, "select link_valid_days from app.onboarding_settings").get("linkValidDays")).intValue();
            List<Map<String, Object>> out = new ArrayList<>();
            for (String raw : in.employmentIds()) {
                UUID id;
                try {
                    id = UUID.fromString(raw);
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("Not a valid employee id");
                }
                Map<String, Object> e = Sql.one(c, "select e.id, e.person_id, e.membership_id, e.status, p.legal_name, p.personal_email "
                        + "from app.employment e join app.person p on p.id = e.person_id where e.id = ?", id);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employmentId", raw);
                if (e == null) {
                    row.put("problem", "Not found");
                    out.add(row);
                    continue;
                }
                row.put("name", e.get("legalName"));
                row.put("email", e.get("personalEmail"));
                if (!"active".equals(e.get("status")) || e.get("membershipId") != null || e.get("personalEmail") == null) {
                    row.put("problem", e.get("personalEmail") == null ? "No personal email" : "Already has a login, or has left");
                } else if (Sql.one(c, "select 1 from app.membership m join app.member_directory() d on d.membership_id = m.id "
                        + "where d.email = ?::citext and m.status <> 'ended'", e.get("personalEmail")) != null) {
                    row.put("problem", "A user with this email already exists; link them from the employee's page instead");
                } else {
                    String token = Secrets.newToken();
                    Instant expires = Instant.now().plus(days, ChronoUnit.DAYS);
                    Sql.update(c, "update app.invitation set revoked_at = now() where tenant_id = ? and email = ?::citext "
                            + "and accepted_at is null and revoked_at is null", ctx.tenantId(), e.get("personalEmail"));
                    Sql.update(c, "insert into app.invitation (tenant_id, email, person_id, role_ids, token_hash, invited_by, expires_at) "
                            + "select ?, ?::citext, ?::uuid, array[r.id], ?, ?, ? from app.role r where r.code = 'employee' and r.tenant_id is null",
                            ctx.tenantId(), e.get("personalEmail"), e.get("personId"), Secrets.sha256(token), ctx.membershipId(),
                            Timestamp.from(expires));
                    row.put("token", token);
                    row.put("expiresAt", expires.toString());
                }
                out.add(row);
            }
            UserService.audit(c, ctx, "employee.invited_in_bulk", "organization", null,
                    Map.of("invited", out.stream().filter(r -> r.containsKey("token")).count()));
            return out;
        });
    }

    private static void requireHr(SessionCtx ctx) {
        if (!ctx.canAcrossOrganization("employee.write") || ctx.membershipId() == null) {
            throw ApiException.denied("employee.write");
        }
    }
}
