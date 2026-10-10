package com.example.loginapp.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one rule syntax for whom a piece of configuration applies to: a JSON
 * object of attribute to list of allowed values. Every listed attribute must
 * match; values within one attribute are alternatives. An empty object applies
 * to everyone. Flows, custom fields, document types, checklist tasks, employee
 * code series, letters and policies all use it.
 *
 * <p>Pure: validating the shape and matching happen here; checking that ids
 * belong to the Organization is left to the caller, which has the database.
 */
public final class AppliesTo {

    public static final Set<String> ATTRIBUTES = Set.of("department", "work_location", "employment_type", "designation");
    public static final Set<String> EMPLOYMENT_TYPES = Set.of("permanent", "probation", "fixed_term", "intern", "consultant");
    private static final int MAX_VALUES = 50;

    /** What is known about a joiner (or employee) when rules are applied. Any part may be null. */
    public record Subject(UUID departmentId, UUID workLocationId, String employmentType, String designation) {

        public static final Subject UNKNOWN = new Subject(null, null, null, null);
    }

    public static final AppliesTo EVERYONE = new AppliesTo(Map.of());

    private final Map<String, List<String>> rules;

    private AppliesTo(Map<String, List<String>> rules) {
        this.rules = rules;
    }

    /** Reads and validates rules stored in a jsonb column, or given as JSON text. Null or blank means everyone. */
    public static AppliesTo parse(String json) {
        if (json == null || json.isBlank()) {
            return EVERYONE;
        }
        Object value;
        try {
            value = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw invalid("The rules are not valid JSON");
        }
        return of(value);
    }

    /** Validates rules given as a map (from a request body). Null or empty means everyone. */
    public static AppliesTo from(Map<String, ?> map) {
        return of(map);
    }

    private static AppliesTo of(Object node) {
        if (node == null) {
            return EVERYONE;
        }
        if (!(node instanceof Map<?, ?> object)) {
            throw invalid("The rules must be an object of attribute to allowed values");
        }
        Map<String, List<String>> rules = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : object.entrySet()) {
            String attribute = String.valueOf(field.getKey());
            if (!ATTRIBUTES.contains(attribute)) {
                throw invalid("Unknown rule attribute: " + attribute + ". Use department, work_location, employment_type or designation");
            }
            if (!(field.getValue() instanceof List<?> values) || values.isEmpty()) {
                throw invalid("The rule for " + attribute + " needs a list of at least one value");
            }
            if (values.size() > MAX_VALUES) {
                throw invalid("The rule for " + attribute + " has more than " + MAX_VALUES + " values");
            }
            List<String> list = new ArrayList<>();
            for (Object v : values) {
                if (!(v instanceof String text) || text.isBlank()) {
                    throw invalid("The rule for " + attribute + " must list words or ids");
                }
                String value = text.trim();
                switch (attribute) {
                    case "department", "work_location" -> {
                        try {
                            value = UUID.fromString(value).toString();
                        } catch (IllegalArgumentException e) {
                            throw invalid("The rule for " + attribute + " must list ids");
                        }
                    }
                    case "employment_type" -> {
                        if (!EMPLOYMENT_TYPES.contains(value)) {
                            throw invalid("Unknown employment type in the rule: " + value);
                        }
                    }
                    default -> {
                        if (value.length() > 80) {
                            throw invalid("A designation in the rule is longer than 80 characters");
                        }
                    }
                }
                if (!list.contains(value)) {
                    list.add(value);
                }
            }
            rules.put(attribute, List.copyOf(list));
        }
        return rules.isEmpty() ? EVERYONE : new AppliesTo(Map.copyOf(rules));
    }

    public boolean appliesToEveryone() {
        return rules.isEmpty();
    }

    public Map<String, List<String>> rules() {
        return rules;
    }

    /** The ids the rules name for an attribute, so the caller can check they belong to the Organization. */
    public List<UUID> ids(String attribute) {
        return rules.getOrDefault(attribute, List.of()).stream().map(UUID::fromString).toList();
    }

    /**
     * True when every listed attribute matches. An attribute the subject does not have yet
     * (null) does not match a rule that lists values for it. Designations compare without case.
     */
    public boolean matches(Subject s) {
        for (Map.Entry<String, List<String>> rule : rules.entrySet()) {
            String actual = switch (rule.getKey()) {
                case "department" -> s.departmentId() == null ? null : s.departmentId().toString();
                case "work_location" -> s.workLocationId() == null ? null : s.workLocationId().toString();
                case "employment_type" -> s.employmentType();
                default -> s.designation() == null ? null : s.designation().trim().toLowerCase(Locale.ROOT);
            };
            if (actual == null) {
                return false;
            }
            boolean any = rule.getKey().equals("designation")
                    ? rule.getValue().stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).equals(actual))
                    : rule.getValue().contains(actual);
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** The first item, in the order given, whose rules match; null when none does. */
    public static <T> T firstMatch(List<T> items, Function<T, AppliesTo> rulesOf, Subject subject) {
        for (T item : items) {
            if (rulesOf.apply(item).matches(subject)) {
                return item;
            }
        }
        return null;
    }

    /** The rules in words, for screens and audit entries: "Employment type intern; Department Sales or Support". */
    public String describe(Function<UUID, String> nameOf) {
        if (rules.isEmpty()) {
            return "Everyone";
        }
        List<String> parts = new ArrayList<>();
        for (String attribute : List.of("employment_type", "department", "work_location", "designation")) {
            List<String> values = rules.get(attribute);
            if (values == null) {
                continue;
            }
            List<String> words = values.stream().map(v -> switch (attribute) {
                case "department", "work_location" -> {
                    String name = nameOf == null ? null : nameOf.apply(UUID.fromString(v));
                    yield name == null ? "(removed)" : name;
                }
                case "employment_type" -> v.replace('_', ' ');
                default -> v;
            }).toList();
            String label = switch (attribute) {
                case "employment_type" -> "Employment type";
                case "department" -> "Department";
                case "work_location" -> "Work location";
                default -> "Designation";
            };
            parts.add(label + " " + String.join(" or ", words));
        }
        return String.join("; ", parts);
    }

    /** The rules as stored in a jsonb column, attributes in a fixed order. */
    public String toJson() {
        return Json.write(new TreeMap<>(rules));
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, "rules.invalid", message);
    }
}
