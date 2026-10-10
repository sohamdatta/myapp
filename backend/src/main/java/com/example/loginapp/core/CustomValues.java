package com.example.loginapp.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a value for one of an Organization's own fields against its
 * definition: type, choices, pattern and limits. Pure: storing the value
 * (encrypted when the field is sensitive) is the caller's job.
 */
public final class CustomValues {

    public static final Set<String> TYPES = Set.of("text", "long_text", "number", "date", "select", "multi_select", "yes_no", "email", "phone");

    private CustomValues() {}

    /** Checks a field definition before it is saved. */
    public static void checkDefinition(String dataType, List<String> options, String pattern, BigDecimal min, BigDecimal max) {
        if (!TYPES.contains(dataType == null ? "" : dataType)) {
            throw bad("The type must be one of text, long_text, number, date, select, multi_select, yes_no, email, phone");
        }
        boolean choice = dataType.equals("select") || dataType.equals("multi_select");
        if (choice) {
            if (options == null || options.isEmpty()) {
                throw bad("A choice field needs at least one choice");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String o : options) {
                if (o == null || o.isBlank() || o.trim().length() > 80) {
                    throw bad("Each choice needs 1 to 80 characters");
                }
                if (!seen.add(o.trim().toLowerCase())) {
                    throw bad("The choice " + o.trim() + " is listed twice");
                }
            }
            if (options.size() > 100) {
                throw bad("A choice field can have at most 100 choices");
            }
        } else if (options != null && !options.isEmpty()) {
            throw bad("Only choice fields have choices");
        }
        if (pattern != null && !pattern.isBlank()) {
            if (!dataType.equals("text")) {
                throw bad("Only text fields have a pattern");
            }
            if (pattern.length() > 200) {
                throw bad("Keep the pattern to 200 characters");
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw bad("The pattern is not a valid regular expression");
            }
        }
        if ((min != null || max != null) && !dataType.equals("number")) {
            throw bad("Only number fields have a lowest and highest value");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw bad("The lowest value is above the highest");
        }
    }

    /**
     * The value in its stored form, or null when it is empty. Throws a 400 naming the field
     * when the value does not fit. Text and long text come back trimmed; numbers as BigDecimal;
     * dates as yyyy-MM-dd; yes or no as Boolean; multiple choice as a list in the field's order.
     */
    public static Object normalize(FieldCatalog.CustomField field, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank()) || (raw instanceof List<?> l && l.isEmpty())) {
            return null;
        }
        String label = field.label();
        switch (field.dataType()) {
            case "text", "long_text" -> {
                String v = text(raw, label).trim();
                int max = field.dataType().equals("text") ? 200 : 4000;
                if (v.length() > max) {
                    throw bad(label + ": keep it to " + max + " characters");
                }
                if (field.pattern() != null && !field.pattern().isBlank() && !Pattern.compile(field.pattern()).matcher(v).matches()) {
                    throw bad(label + ": this is not in the expected format");
                }
                return v;
            }
            case "number" -> {
                BigDecimal n;
                try {
                    n = raw instanceof Number num ? new BigDecimal(num.toString()) : new BigDecimal(text(raw, label).trim());
                } catch (NumberFormatException e) {
                    throw bad(label + ": enter a number");
                }
                if (field.min() != null && n.compareTo(field.min()) < 0) {
                    throw bad(label + ": the lowest allowed is " + field.min().toPlainString());
                }
                if (field.max() != null && n.compareTo(field.max()) > 0) {
                    throw bad(label + ": the highest allowed is " + field.max().toPlainString());
                }
                return n;
            }
            case "date" -> {
                try {
                    return LocalDate.parse(text(raw, label).trim()).toString();
                } catch (DateTimeParseException e) {
                    throw bad(label + ": enter a date like 2026-10-12");
                }
            }
            case "select" -> {
                String v = text(raw, label).trim();
                return field.options().stream().filter(o -> o.equals(v)).findFirst()
                        .orElseThrow(() -> bad(label + ": choose one of the listed choices"));
            }
            case "multi_select" -> {
                if (!(raw instanceof List<?> list)) {
                    throw bad(label + ": choose from the listed choices");
                }
                Set<String> chosen = new LinkedHashSet<>();
                for (Object o : list) {
                    String v = text(o, label).trim();
                    if (!field.options().contains(v)) {
                        throw bad(label + ": " + v + " is not one of the choices");
                    }
                    chosen.add(v);
                }
                List<String> ordered = new ArrayList<>();
                for (String o : field.options()) {
                    if (chosen.contains(o)) {
                        ordered.add(o);
                    }
                }
                return ordered;
            }
            case "yes_no" -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                String v = text(raw, label).trim().toLowerCase();
                if (v.equals("true") || v.equals("yes")) {
                    return Boolean.TRUE;
                }
                if (v.equals("false") || v.equals("no")) {
                    return Boolean.FALSE;
                }
                throw bad(label + ": answer yes or no");
            }
            case "email" -> {
                try {
                    return IdChecks.email(text(raw, label));
                } catch (ApiException e) {
                    throw bad(label + ": " + e.getMessage());
                }
            }
            case "phone" -> {
                String v = text(raw, label).replaceAll("[\\s()-]", "");
                if (!v.matches("^\\+?[0-9]{8,15}$")) {
                    throw bad(label + ": enter a phone number of 8 to 15 digits");
                }
                return v;
            }
            default -> throw bad(label + ": unknown field type");
        }
    }

    private static String text(Object raw, String label) {
        if (raw instanceof String s) {
            return s;
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return raw.toString();
        }
        throw bad(label + ": expected a single value");
    }

    private static ApiException bad(String message) {
        return new ApiException(400, "field.invalid", message);
    }
}
