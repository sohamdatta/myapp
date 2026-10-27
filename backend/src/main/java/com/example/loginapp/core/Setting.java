package com.example.loginapp.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One setting: its key, what it means, the values it takes, who may change it and how much assurance a
 * change needs. Declared once in {@link Settings}; the database stores values, never definitions.
 */
public final class Setting {

    public enum Type { BOOLEAN, INTEGER, CHOICE }

    /** Who owns the value: the platform for every Organization, each Organization, or each person. */
    public enum Layer { PLATFORM, ORGANIZATION, PERSONAL }

    public enum Scope {
        ORGANIZATION("organization"), LOCATION("location"), PAY_GROUP("pay_group"), PERSON("person");

        public final String db;

        Scope(String db) {
            this.db = db;
        }

        static Scope of(String db) {
            for (Scope s : values()) {
                if (s.db.equals(db)) {
                    return s;
                }
            }
            throw ApiException.badRequest("The scope must be one of organization, location, pay_group, person");
        }
    }

    /** Which direction of change lowers safety or moves money, and so needs more assurance. */
    public enum Weakens { NEVER, UP, DOWN, ANY }

    /**
     * What a weakening change needs. PASSWORD asks the person to confirm their password; it becomes a
     * fresh MFA code once MFA exists. SECOND_PERSON holds the change until another owner approves it.
     */
    public enum Approval { NONE, PASSWORD, SECOND_PERSON }

    final String key;
    final String group;
    final String label;
    final String help;
    final String unit;
    final Type type;
    final Layer layer;
    final Set<Scope> scopes;
    final boolean dated;
    final Object defaultValue;
    final Integer min;
    final Integer max;
    final List<String> choices;
    final String owner;
    final Weakens weakens;
    final Approval approval;
    final String module;
    final String since;

    private Setting(Builder b) {
        key = b.key;
        group = b.group;
        label = b.label;
        help = b.help;
        unit = b.unit;
        type = b.type;
        layer = b.layer;
        scopes = b.scopes;
        dated = b.dated;
        defaultValue = b.defaultValue;
        min = b.min;
        max = b.max;
        choices = b.choices;
        owner = b.owner;
        weakens = b.weakens;
        approval = b.approval;
        module = b.module;
        since = b.since;
    }

    public String key() {
        return key;
    }

    public boolean dated() {
        return dated;
    }

    /** The value as the API shows it and the database stores it: Integer, Boolean or String. */
    Object check(Object raw) {
        if (raw == null) {
            return null;
        }
        switch (type) {
            case BOOLEAN -> {
                if (!(raw instanceof Boolean)) {
                    throw new ApiException(400, "setting.out_of_range", label + " is on or off");
                }
                return raw;
            }
            case INTEGER -> {
                BigDecimal n;
                try {
                    n = new BigDecimal(String.valueOf(raw));
                } catch (NumberFormatException e) {
                    throw new ApiException(400, "setting.out_of_range", label + " must be a whole number" + range());
                }
                if (n.stripTrailingZeros().scale() > 0) {
                    throw new ApiException(400, "setting.out_of_range", label + " must be a whole number" + range());
                }
                int v = n.intValueExact();
                if ((min != null && v < min) || (max != null && v > max)) {
                    throw new ApiException(400, "setting.out_of_range", label + " must be" + range());
                }
                return v;
            }
            default -> {
                String s = String.valueOf(raw);
                if (!choices.contains(s)) {
                    throw new ApiException(400, "setting.out_of_range", label + " must be one of " + String.join(", ", choices));
                }
                return s;
            }
        }
    }

    String range() {
        if (min == null && max == null) {
            return "";
        }
        return " between " + min + " and " + max + (unit == null ? "" : " " + unit);
    }

    /** Whether going from one value to another moves in the weakening direction. */
    boolean weakening(Object from, Object to) {
        if (java.util.Objects.equals(from, to)) {
            return false;
        }
        return switch (weakens) {
            case NEVER -> false;
            case ANY -> true;
            case UP, DOWN -> {
                if (from instanceof String f && to instanceof String t) {   // a choice is ordered as listed: later is higher
                    int x = choices.indexOf(f);
                    int y = choices.indexOf(t);
                    yield weakens == Weakens.UP ? y > x : y < x;
                }
                int a = from instanceof Boolean f ? (f ? 1 : 0) : ((Number) from).intValue();
                int b = to instanceof Boolean t ? (t ? 1 : 0) : ((Number) to).intValue();
                yield weakens == Weakens.UP ? b > a : b < a;
            }
        };
    }

    Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("key", key);
        d.put("group", group);
        d.put("label", label);
        d.put("help", help);
        d.put("unit", unit);
        d.put("type", type.name().toLowerCase());
        d.put("layer", layer.name().toLowerCase());
        List<String> s = new ArrayList<>();
        for (Scope x : scopes) {
            s.add(x.db);
        }
        d.put("scopes", s);
        d.put("dated", dated);
        d.put("default", defaultValue);
        d.put("min", min);
        d.put("max", max);
        d.put("choices", choices);
        d.put("weakens", weakens.name().toLowerCase());
        d.put("approval", approval.name().toLowerCase());
        return d;
    }

    // ── declaring ───────────────────────────────────────────────────────

    public static Builder integer(String key) {
        return new Builder(key, Type.INTEGER);
    }

    public static Builder bool(String key) {
        return new Builder(key, Type.BOOLEAN);
    }

    public static Builder choice(String key, String... choices) {
        Builder b = new Builder(key, Type.CHOICE);
        b.choices = List.of(choices);
        return b;
    }

    public static final class Builder {
        private final String key;
        private final Type type;
        private String group = "general";
        private String label;
        private String help;
        private String unit;
        private Layer layer = Layer.ORGANIZATION;
        private Set<Scope> scopes = EnumSet.of(Scope.ORGANIZATION);
        private boolean dated;
        private Object defaultValue;
        private Integer min;
        private Integer max;
        private List<String> choices = List.of();
        private String owner = "tenant.manage";
        private Weakens weakens = Weakens.NEVER;
        private Approval approval = Approval.NONE;
        private String module;
        private String since;

        private Builder(String key, Type type) {
            if (!key.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) {
                throw new IllegalArgumentException("Setting keys are dotted lower case: " + key);
            }
            this.key = key;
            this.type = type;
        }

        public Builder group(String g) { group = g; return this; }
        public Builder label(String l) { label = l; return this; }
        public Builder help(String h) { help = h; return this; }
        public Builder unit(String u) { unit = u; return this; }
        public Builder layer(Layer l) { layer = l; return this; }
        public Builder scopes(Scope first, Scope... rest) { scopes = EnumSet.of(first, rest); return this; }
        public Builder dated() { dated = true; return this; }
        public Builder defaultValue(Object v) { defaultValue = v; return this; }
        public Builder range(int lo, int hi) { min = lo; max = hi; return this; }
        public Builder owner(String permission) { owner = permission; return this; }
        public Builder weakens(Weakens w, Approval a) { weakens = w; approval = a; return this; }
        public Builder module(String m) { module = m; return this; }
        public Builder since(String s) { since = s; return this; }

        public Setting build() {
            if (label == null || defaultValue == null) {
                throw new IllegalStateException(key + " needs a label and a default");
            }
            if (scopes.contains(Scope.LOCATION) && scopes.contains(Scope.PAY_GROUP)) {
                throw new IllegalStateException(key + ": a setting varies by location or by pay group, never both");
            }
            if (layer == Layer.PLATFORM && !scopes.equals(EnumSet.of(Scope.ORGANIZATION))) {
                throw new IllegalStateException(key + ": a platform setting has no scopes");
            }
            Setting s = new Setting(this);
            s.check(defaultValue);   // the default itself must be valid
            return s;
        }
    }
}
