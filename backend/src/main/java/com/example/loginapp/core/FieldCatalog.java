package com.example.loginapp.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every built-in field of the onboarding form, and how an Organization's
 * settings turn it into the form a joiner sees. Pure: the caller loads the
 * settings; nothing here reads the database.
 *
 * <p>The effective form for a flow is, field by field: the catalogue default,
 * then the Organization's setting, then the flow's override; then the
 * Organization's own (custom) fields whose rules match the joiner. Fields the
 * record cannot exist without are fixed as required whatever the settings say.
 */
public final class FieldCatalog {

    public static final List<String> SECTIONS = List.of(
            "employment", "personal", "contact", "address", "emergency", "family", "bank", "statutory", "documents",
            "policies", "letters");

    /** The record cannot exist without these sections, so they cannot be switched off. */
    public static final Set<String> ALWAYS_ON = Set.of("employment", "personal", "statutory");

    /** Sections the joiner flow does not handle yet: they arrive with documents (milestone 4) and letters (milestone 5). */
    public static final Set<String> LATER = Set.of("documents", "policies", "letters");

    /** Fields of these types arrive with uploads (milestone 4); until then they are not asked for. */
    public static final Set<String> LATER_TYPES = Set.of("file");

    /** Sections custom fields can be placed in (policies and letters have no fields of their own). */
    public static final List<String> FIELD_SECTIONS = SECTIONS.subList(0, 9);

    public static final Map<String, String> SECTION_TITLES = Map.ofEntries(
            Map.entry("employment", "Employment"), Map.entry("personal", "Personal details"),
            Map.entry("contact", "Contact"), Map.entry("address", "Addresses"),
            Map.entry("emergency", "Emergency contact"), Map.entry("family", "Family and nominees"),
            Map.entry("bank", "Bank account"), Map.entry("statutory", "PAN, Aadhaar and PF"),
            Map.entry("documents", "Documents"), Map.entry("policies", "Policies"), Map.entry("letters", "Letters"));

    public static final Set<String> VISIBILITIES = Set.of("hidden", "optional", "required");

    public record Field(String key, String section, String label, String type, String visibility, boolean fixed,
                        String editableBy, List<String> options, String help) {}

    public record SectionSetting(String section, boolean enabled, int position, String title, String intro, boolean verify) {}

    /** A visibility of null keeps what the layer below says. */
    public record FieldSetting(String key, String visibility, String label, String help, String editableBy) {}

    public record CustomField(UUID id, String key, String label, String help, String section, String dataType,
                              List<String> options, String pattern, BigDecimal min, BigDecimal max, boolean sensitive,
                              boolean managerCanSee, AppliesTo appliesTo, String visibility, String editableBy, int position,
                              boolean active) {}

    private static final List<String> TYPES = List.of("permanent", "probation", "fixed_term", "intern", "consultant");

    private static final List<Field> FIELDS = List.of(
            // Employment: filled by HR.
            hr("employment.joining_date", "employment", "Joining date", "date", "required", true, null),
            hr("employment.designation", "employment", "Designation", "text", "required", false, null),
            hr("employment.department", "employment", "Department", "department", "required", false, null),
            hr("employment.manager", "employment", "Reporting manager", "employee", "required", false, null),
            hr("employment.employment_type", "employment", "Employment type", "select", "required", false, TYPES),
            hr("employment.probation_end", "employment", "Probation ends", "date", "optional", false, null),
            hr("employment.contract_end", "employment", "Contract ends", "date", "optional", false, null),
            hr("employment.work_location", "employment", "Work location", "work_location", "required", false, null),
            hr("employment.shift", "employment", "Shift", "shift", "required", false, null),
            hr("employment.weekly_offs", "employment", "Weekly offs", "weekdays", "required", false, null),
            hr("employment.employee_code", "employment", "Employee code", "text", "required", false, null),
            hr("employment.official_email", "employment", "Official email", "email", "optional", false, null),
            // Personal.
            joiner("person.legal_name", "personal", "Full name as on PAN", "text", "required", true, null,
                    "Exactly as printed on your PAN card"),
            joiner("person.preferred_name", "personal", "Preferred name", "text", "optional", false, null, null),
            joiner("person.date_of_birth", "personal", "Date of birth", "date", "required", true, null, null),
            joiner("person.gender", "personal", "Gender", "select", "required", false,
                    List.of("female", "male", "transgender", "undisclosed"), null),
            joiner("person.marital_status", "personal", "Marital status", "select", "optional", false,
                    List.of("single", "married", "widowed", "divorced", "separated"), null),
            joiner("person.nationality", "personal", "Nationality", "country", "optional", false, null, null),
            joiner("person.blood_group", "personal", "Blood group", "select", "optional", false,
                    List.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"), null),
            joiner("person.photo", "personal", "Photo", "file", "optional", false, null, null),
            // Contact.
            joiner("person.mobile", "contact", "Mobile", "phone", "required", false, null, null),
            joiner("person.personal_email", "contact", "Personal email", "email", "optional", false, null, null),
            joiner("person.alt_mobile", "contact", "Another phone", "phone", "optional", false, null, null),
            // Addresses.
            joiner("address.current", "address", "Current address", "address", "required", false, null, null),
            joiner("address.permanent", "address", "Permanent address", "address", "optional", false, null,
                    "Leave empty if it is the same as your current address"),
            // Emergency contact.
            joiner("emergency.contacts", "emergency", "Emergency contacts", "contacts", "required", false, null,
                    "Someone other than you, whom we can call in an emergency"),
            // Family and nominees.
            joiner("family.members", "family", "Family members", "family", "optional", false, null, null),
            joiner("family.nominations", "family", "Nominees", "nominations", "optional", false, null,
                    "Who receives your PF, gratuity and insurance; shares for each must add up to 100%"),
            // Bank.
            joiner("bank.holder_name", "bank", "Account holder", "text", "required", false, null, null),
            joiner("bank.account_number", "bank", "Account number", "bank_account", "required", false, null, null),
            joiner("bank.ifsc", "bank", "IFSC", "ifsc", "required", false, null, null),
            joiner("bank.proof", "bank", "Cancelled cheque or passbook page", "file", "required", false, null, null),
            // Statutory.
            joiner("statutory.pan", "statutory", "PAN", "pan", "required", true, null,
                    "Needed to deduct tax at the normal rate. If you have applied for one, say so"),
            joiner("statutory.aadhaar", "statutory", "Aadhaar", "aadhaar", "optional", false, null,
                    "Optional. Only the last four digits are kept"),
            joiner("statutory.uan", "statutory", "UAN", "uan", "optional", false, null, "If you have had PF before"),
            joiner("statutory.pf_member", "statutory", "Previous PF member id", "pf_member", "optional", false, null, null),
            joiner("statutory.esi_ip", "statutory", "ESI insurance number", "esi_ip", "optional", false, null, null),
            joiner("statutory.tax_regime", "statutory", "Income tax regime", "select", "optional", false,
                    List.of("new", "old"), "The new regime applies unless you choose the old one"),
            joiner("statutory.previous_income", "statutory", "Salary from a previous employer this year", "number",
                    "optional", false, null, "So tax this year is worked out on your whole income"));

    private static final Map<String, Field> BY_KEY;

    static {
        Map<String, Field> m = new LinkedHashMap<>();
        FIELDS.forEach(f -> m.put(f.key(), f));
        BY_KEY = Map.copyOf(m);
    }

    private FieldCatalog() {}

    public static List<Field> fields() {
        return FIELDS;
    }

    public static Field field(String key) {
        return BY_KEY.get(key);
    }

    /** Refuses a setting that would hide a fixed field, or let the joiner edit what only HR fills. */
    public static FieldSetting check(FieldSetting s) {
        Field f = BY_KEY.get(s.key());
        if (f == null) {
            throw new ApiException(400, "field.unknown", "Unknown field: " + s.key());
        }
        String visibility = s.visibility() == null ? null : s.visibility().trim();
        if (visibility != null && !VISIBILITIES.contains(visibility)) {
            throw new ApiException(400, "field.invalid", "Visibility must be hidden, optional or required");
        }
        if (f.fixed() && visibility != null && !visibility.equals("required")) {
            throw new ApiException(409, "field.fixed", f.label() + " is always required");
        }
        String editableBy = s.editableBy() == null || s.editableBy().isBlank() ? null : s.editableBy().trim();
        if (editableBy != null && !Set.of("joiner", "hr").contains(editableBy)) {
            throw new ApiException(400, "field.invalid", "Editable by must be joiner or hr");
        }
        if (f.section().equals("employment") && "joiner".equals(editableBy)) {
            throw new ApiException(409, "field.fixed", "Employment details are filled by HR");
        }
        return new FieldSetting(f.key(), visibility, clean(s.label(), 80), clean(s.help(), 300), editableBy);
    }

    /** One field's effective setting: catalogue, then the Organization, then the flow. */
    public static Map<String, Object> effective(Field f, FieldSetting org, FieldSetting flow) {
        String visibility = f.visibility();
        String label = f.label();
        String help = f.help();
        String editableBy = f.editableBy();
        for (FieldSetting s : new FieldSetting[] {org, flow}) {
            if (s == null) {
                continue;
            }
            if (s.visibility() != null && !f.fixed()) {
                visibility = s.visibility();
            }
            if (s.label() != null) {
                label = s.label();
            }
            if (s.help() != null) {
                help = s.help();
            }
            if (s.editableBy() != null && !f.section().equals("employment")) {
                editableBy = s.editableBy();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", f.key());
        out.put("label", label);
        out.put("help", help);
        out.put("type", f.type());
        out.put("visibility", visibility);
        out.put("editableBy", editableBy);
        out.put("fixed", f.fixed());
        out.put("custom", false);
        if (f.options() != null) {
            out.put("options", f.options());
        }
        return out;
    }

    /**
     * The form a joiner matching {@code subject} sees in a flow: enabled sections in order, each
     * with its visible fields. A subject of {@link AppliesTo.Subject#UNKNOWN} keeps only custom
     * fields that apply to everyone.
     */
    public static List<Map<String, Object>> form(List<SectionSetting> sections, Map<String, FieldSetting> org,
                                                 Map<String, FieldSetting> flow, List<CustomField> custom,
                                                 AppliesTo.Subject subject) {
        List<SectionSetting> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator.comparingInt(SectionSetting::position).thenComparing(s -> SECTIONS.indexOf(s.section())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SectionSetting s : ordered) {
            if (!s.enabled() && !ALWAYS_ON.contains(s.section())) {
                continue;
            }
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Field f : FIELDS) {
                if (!f.section().equals(s.section())) {
                    continue;
                }
                Map<String, Object> e = effective(f, org.get(f.key()), flow.get(f.key()));
                if (!"hidden".equals(e.get("visibility"))) {
                    fields.add(e);
                }
            }
            custom.stream()
                    .filter(c -> c.active() && c.section().equals(s.section()) && c.appliesTo().matches(subject))
                    .sorted(Comparator.comparingInt(CustomField::position).thenComparing(CustomField::label))
                    .forEach(c -> fields.add(customField(c)));
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("section", s.section());
            section.put("title", s.title() == null ? SECTION_TITLES.get(s.section()) : s.title());
            section.put("intro", s.intro());
            section.put("verify", s.verify());
            section.put("fields", fields);
            out.add(section);
        }
        return out;
    }

    public static Map<String, Object> customField(CustomField c) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("key", "custom." + c.key());
        e.put("id", c.id() == null ? null : c.id().toString());
        e.put("label", c.label());
        e.put("help", c.help());
        e.put("type", c.dataType());
        e.put("visibility", c.visibility());
        e.put("editableBy", c.editableBy());
        e.put("fixed", false);
        e.put("custom", true);
        e.put("sensitive", c.sensitive());
        if (c.options() != null) {
            e.put("options", c.options());
        }
        if (c.min() != null) {
            e.put("min", c.min());
        }
        if (c.max() != null) {
            e.put("max", c.max());
        }
        return e;
    }

    private static Field hr(String key, String section, String label, String type, String visibility, boolean fixed,
                            List<String> options) {
        return new Field(key, section, label, type, visibility, fixed, "hr", options, null);
    }

    private static Field joiner(String key, String section, String label, String type, String visibility, boolean fixed,
                                List<String> options, String help) {
        return new Field(key, section, label, type, visibility, fixed, "joiner", options, help);
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw new ApiException(400, "field.invalid", "Keep it to " + max + " characters");
        }
        return v;
    }
}
