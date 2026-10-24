package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.loginapp.core.Setting.Approval;
import com.example.loginapp.core.Setting.Layer;
import com.example.loginapp.core.Setting.Scope;
import com.example.loginapp.core.Setting.Weakens;

/**
 * The registry of every setting, and how a value is found.
 *
 * A value is found by walking from the narrowest scope that applies to the widest (person, then pay group
 * or location, then the Organization), taking the newest value in force on the date asked about, then the
 * platform's default for every Organization, then the registry default; the platform's bounds then hold it
 * within limits, and a locked key takes the platform's value whatever is stored. See
 * docs/configuration/design.md.
 */
public final class Settings {

    private Settings() {}

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Map<String, Setting> BY_KEY = new ConcurrentHashMap<>();
    private static final List<Setting> ORDER = new ArrayList<>();

    /** Groups in the order the screens show them: key and title. */
    public static final Map<String, String> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("security", "Security");
        GROUPS.put("attendance", "Attendance and leave");
        GROUPS.put("signin", "Sign-in (platform)");
    }

    // ── the registry ────────────────────────────────────────────────────

    public static final Setting LOCKOUT_AFTER = define(Setting.integer("auth.lockout.after_failures")
            .group("signin").layer(Layer.PLATFORM)
            .label("Failed sign-ins before an account locks")
            .help("Counted per person across Organizations, since sign-in comes before choosing one. A successful sign-in resets the count.")
            .defaultValue(5).range(3, 10).weakens(Weakens.UP, Approval.NONE).since("V18"));

    public static final Setting LOCKOUT_MINUTES = define(Setting.integer("auth.lockout.minutes")
            .group("signin").layer(Layer.PLATFORM)
            .label("How long a locked account stays locked").unit("minutes")
            .defaultValue(15).range(5, 60).weakens(Weakens.DOWN, Approval.NONE).since("V18"));

    public static final Setting INVITATION_DAYS = define(Setting.integer("auth.invitation.valid_days")
            .group("security").owner("tenant.manage")
            .label("Invitation links work for").unit("days")
            .help("A link not used in time stops working; resending makes a new one.")
            .defaultValue(7).range(1, 30).weakens(Weakens.UP, Approval.PASSWORD).since("V18"));

    public static final Setting LEAVE_BACK_DAYS = define(Setting.integer("leave.apply.back_days")
            .group("attendance").owner("attendance.manage").module(Modules.ATTENDANCE)
            .label("Employees may apply for leave this far back").unit("days")
            .help("Older dates go through HR.")
            .defaultValue(31).range(0, 90).since("V18"));

    public static final Setting LEAVE_AHEAD_DAYS = define(Setting.integer("leave.apply.ahead_days")
            .group("attendance").owner("attendance.manage").module(Modules.ATTENDANCE)
            .label("Employees may apply for leave this far ahead").unit("days")
            .defaultValue(365).range(30, 730).since("V18"));

    public static final Setting REQUEST_BACK_DAYS = define(Setting.integer("attendance.request.back_days")
            .group("attendance").owner("attendance.manage").module(Modules.ATTENDANCE)
            .label("Corrections and requests may be for days this far back").unit("days")
            .defaultValue(31).range(0, 90).scopes(Scope.ORGANIZATION, Scope.LOCATION).since("V18"));

    public static final Setting REQUEST_AHEAD_DAYS = define(Setting.integer("attendance.request.ahead_days")
            .group("attendance").owner("attendance.manage").module(Modules.ATTENDANCE)
            .label("Work-from-home and on-duty requests may be for days this far ahead").unit("days")
            .defaultValue(60).range(0, 365).scopes(Scope.ORGANIZATION, Scope.LOCATION).since("V18"));

    /** Declares a setting; called once per key when this class loads, and by tests for their own keys. */
    public static Setting define(Setting.Builder b) {
        Setting s = b.build();
        if (BY_KEY.putIfAbsent(s.key, s) != null) {
            throw new IllegalStateException("Setting declared twice: " + s.key);
        }
        synchronized (ORDER) {
            ORDER.add(s);
        }
        return s;
    }

    public static Setting byKey(String key) {
        Setting s = BY_KEY.get(key);
        if (s == null) {
            throw new ApiException(404, "setting.unknown", "There is no setting " + key);
        }
        return s;
    }

    public static List<Setting> all() {
        synchronized (ORDER) {
            return List.copyOf(ORDER);
        }
    }

    // ── resolving, as a pure function ───────────────────────────────────

    /** A stored value of one key at one scope and start date. */
    public record Row(Scope scope, UUID scopeId, LocalDate validFrom, Object value, UUID changeId) {}

    /** What the platform says about a key: its value or default for all, and its bounds. */
    public record Platform(Object value, Integer min, Integer max, List<String> allowed, boolean locked, Object lockedValue) {
        public static final Platform NONE = new Platform(null, null, null, null, false, null);
    }

    /** Where in an Organization a value is wanted. Any of location, pay group and person may be unknown. */
    public record Context(UUID location, UUID payGroup, UUID person) {
        public static final Context ORGANIZATION = new Context(null, null, null);

        public static Context location(UUID id) {
            return new Context(id, null, null);
        }

        public static Context payGroup(UUID id) {
            return new Context(null, id, null);
        }

        public static Context person(UUID membership) {
            return new Context(null, null, membership);
        }
    }

    /** A value with where it came from, and each step of the walk that found it. */
    public record Resolved(Object value, String source, Row row, boolean clamped, List<String> trail) {

        public int asInt() {
            return ((Number) value).intValue();
        }

        public boolean asBoolean() {
            return (Boolean) value;
        }

        public String asString() {
            return (String) value;
        }
    }

    /** Finds the value of a setting in a context on a date, from the stored rows of that key. */
    public static Resolved resolve(Setting s, Collection<Row> rows, Platform p, Context ctx, LocalDate asOf) {
        List<String> trail = new ArrayList<>();
        Object value = null;
        String source = null;
        Row found = null;
        if (s.layer != Layer.PLATFORM) {
            for (Scope scope : List.of(Scope.PERSON, Scope.PAY_GROUP, Scope.LOCATION, Scope.ORGANIZATION)) {
                if (!s.scopes.contains(scope)) {
                    continue;
                }
                UUID id = switch (scope) {
                    case PERSON -> ctx.person();
                    case PAY_GROUP -> ctx.payGroup();
                    case LOCATION -> ctx.location();
                    case ORGANIZATION -> null;
                };
                if (scope != Scope.ORGANIZATION && id == null) {
                    continue;
                }
                Row best = null;
                for (Row r : rows) {
                    if (r.scope() != scope || !java.util.Objects.equals(r.scopeId(), id)) {
                        continue;
                    }
                    if (s.dated && r.validFrom() != null && asOf != null && r.validFrom().isAfter(asOf)) {
                        continue;
                    }
                    if (best == null || (r.validFrom() != null && (best.validFrom() == null || r.validFrom().isAfter(best.validFrom())))) {
                        best = r;
                    }
                }
                if (best != null) {
                    value = best.value();
                    found = best;
                    source = scope.db + (best.validFrom() == null ? "" : " from " + best.validFrom());
                    trail.add(scope.db + ": " + value + (best.validFrom() == null ? "" : " from " + best.validFrom()));
                    break;
                }
                trail.add(scope.db + ": not set");
            }
        }
        if (value == null && p.value() != null) {
            value = p.value();
            source = "platform default";
            trail.add("platform default: " + value);
        }
        if (value == null) {
            value = s.defaultValue;
            source = "default";
            trail.add("default: " + value);
        }
        boolean clamped = false;
        if (p.locked()) {
            value = p.lockedValue();
            source = "locked by the platform";
            trail.add("locked by the platform: " + value);
        } else if (s.type == Setting.Type.INTEGER) {
            int v = ((Number) value).intValue();
            int lo = p.min() == null ? Integer.MIN_VALUE : p.min();
            int hi = p.max() == null ? Integer.MAX_VALUE : p.max();
            if (v < lo || v > hi) {
                value = Math.max(lo, Math.min(hi, v));
                clamped = true;
                trail.add("held within the platform's limits: " + value);
            }
        } else if (s.type == Setting.Type.CHOICE && p.allowed() != null && !p.allowed().contains(value)) {
            value = p.allowed().isEmpty() ? s.defaultValue : p.allowed().get(0);
            clamped = true;
            trail.add("not allowed by the platform; using " + value);
        }
        return new Resolved(value, source, found, clamped, trail);
    }

    // ── reading inside an Organization's transaction ────────────────────

    private record Cached(long epoch, Map<String, List<Row>> rows) {}

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    /** The value for the Organization of the transaction, today. */
    public static Resolved get(Connection c, Setting s) throws SQLException {
        return get(c, s, Context.ORGANIZATION, LocalDate.now(IST));
    }

    public static Resolved get(Connection c, Setting s, Context ctx, LocalDate asOf) throws SQLException {
        if (s.layer == Layer.PLATFORM) {
            throw new IllegalArgumentException(s.key + " is a platform setting: read it with platform()");
        }
        if (s.dated && asOf == null) {
            throw new IllegalArgumentException(s.key + " is dated: give the date it is wanted for");
        }
        Map<String, Object> t = Sql.one(c, "select app.current_tenant() as t, coalesce((select epoch from app.setting_epoch), 0) as e");
        UUID tenant = AuthService.uuid(t.get("t"));
        long epoch = ((Number) t.get("e")).longValue();
        Cached cached = CACHE.get(tenant);
        if (cached == null || cached.epoch() != epoch) {
            cached = new Cached(epoch, load(c));
            CACHE.put(tenant, cached);
        }
        return resolve(s, cached.rows().getOrDefault(s.key, List.of()), platformOf(c, s.key), ctx, asOf);
    }

    static Map<String, List<Row>> load(Connection c) throws SQLException {
        Map<String, List<Row>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : Sql.query(c, "select key, scope_kind, scope_id, valid_from, value::text as value, change_id "
                + "from app.setting_value")) {
            Setting s = BY_KEY.get((String) r.get("key"));
            if (s == null) {
                continue;   // a key no longer declared: its values are ignored until a migration clears them
            }
            out.computeIfAbsent(s.key, k -> new ArrayList<>()).add(new Row(Scope.of((String) r.get("scopeKind")),
                    r.get("scopeId") == null ? null : AuthService.uuid(r.get("scopeId")),
                    r.get("validFrom") == null ? null : LocalDate.parse((String) r.get("validFrom")),
                    fromJson(s, (String) r.get("value")), AuthService.uuid(r.get("changeId"))));
        }
        return out;
    }

    /** The platform's value and bounds for a key, read through the definer function inside a tenant transaction. */
    static Platform platformOf(Connection c, String key) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select value::text as value, minimum, maximum, allowed::text as allowed, locked, "
                + "locked_value::text as locked_value from app.platform_settings() where key = ?", key);
        return platform(BY_KEY.get(key), r);
    }

    static Platform platform(Setting s, Map<String, Object> r) {
        if (r == null) {
            return Platform.NONE;
        }
        @SuppressWarnings("unchecked")
        List<String> allowed = r.get("allowed") == null ? null : (List<String>) Json.parse((String) r.get("allowed"));
        return new Platform(r.get("value") == null ? null : fromJson(s, (String) r.get("value")),
                r.get("minimum") == null ? null : ((BigDecimal) r.get("minimum")).intValue(),
                r.get("maximum") == null ? null : ((BigDecimal) r.get("maximum")).intValue(),
                allowed, Boolean.TRUE.equals(r.get("locked")),
                r.get("lockedValue") == null ? null : fromJson(s, (String) r.get("lockedValue")));
    }

    /** A platform-layer value, read in the sign-in or platform plane, where no Organization is chosen yet. */
    public static Resolved platform(Connection c, Setting s) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select value::text as value from platform.setting where key = ?", s.key);
        Object v = r == null ? null : fromJson(s, (String) r.get("value"));
        return new Resolved(v == null ? s.defaultValue : v, v == null ? "default" : "platform", null, false,
                List.of(v == null ? "default: " + s.defaultValue : "platform: " + v));
    }

    /** Forgets cached values, for tests that change the clock or the database underneath. */
    public static void forget() {
        CACHE.clear();
    }

    static Object fromJson(Setting s, String json) {
        Object v = Json.parse(json);
        if (s != null && s.type == Setting.Type.INTEGER && v instanceof Number n) {
            return n.intValue();
        }
        return v;
    }
}
