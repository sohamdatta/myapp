package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Employee codes from series such as GLX-{YY}-0001. Prefix and suffix may use
 * the tokens {YYYY} {YY} {MM} (from the joining date), {LOC} and {DEPT} (the
 * first letters of the work location and department) and {TYPE} (the kind of
 * employment). Each series keeps one counter per expanded prefix, and per
 * year when it resets yearly; the next number is taken under a row lock, so
 * two activations at the same moment never share a code. A code is drawn at
 * activation from the first series whose rules match, unless HR typed one.
 */
public final class NumberSeries {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z]+)\\}");
    public static final Set<String> TOKENS = Set.of("YYYY", "YY", "MM", "LOC", "DEPT", "TYPE");
    private static final Map<String, String> TYPE_CODES = Map.of("permanent", "P", "probation", "PB", "fixed_term", "FT",
            "intern", "IN", "consultant", "C");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record SeriesInput(String name, String prefix, String suffix, Integer digits, Integer startAt, String reset,
                              Map<String, Object> appliesTo, Integer position, Boolean active) {}

    private final Db db;

    public NumberSeries(Db db) {
        this.db = db;
    }

    // ── pure parts ──────────────────────────────────────────────────────

    /** The token values for a joiner. */
    public static Map<String, String> tokens(LocalDate joining, String location, String department, String employmentType) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("YYYY", String.valueOf(joining.getYear()));
        t.put("YY", String.format("%02d", joining.getYear() % 100));
        t.put("MM", String.format("%02d", joining.getMonthValue()));
        t.put("LOC", abbreviate(location));
        t.put("DEPT", abbreviate(department));
        t.put("TYPE", TYPE_CODES.getOrDefault(employmentType, "X"));
        return t;
    }

    /** The first three letters or digits of a name, in capitals: "Bengaluru office" is BEN. */
    static String abbreviate(String name) {
        if (name == null) {
            return "XXX";
        }
        String s = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s.isEmpty() ? "XXX" : s.substring(0, Math.min(3, s.length()));
    }

    /** Replaces the tokens. An unknown token is refused. */
    public static String expand(String pattern, Map<String, String> tokens) {
        Matcher m = TOKEN.matcher(pattern == null ? "" : pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = tokens.get(m.group(1));
            if (v == null) {
                throw new ApiException(400, "request.invalid", "Unknown token {" + m.group(1) + "}; use {YYYY} {YY} {MM} {LOC} {DEPT} or {TYPE}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A code: the expanded prefix, the number padded to the digits, the expanded suffix. */
    public static String format(String prefix, long number, int digits, String suffix) {
        String n = String.valueOf(number);
        while (n.length() < digits) {
            n = "0" + n;
        }
        return prefix + n + suffix;
    }

    // ── drawing a code ──────────────────────────────────────────────────

    /** Whether a series will give this joiner a code, so HR need not type one. */
    static boolean applies(Connection c, AppliesTo.Subject subject) throws SQLException {
        return pick(c, subject) != null;
    }

    private static Map<String, Object> pick(Connection c, AppliesTo.Subject subject) throws SQLException {
        for (Map<String, Object> s : Sql.query(c, "select * from app.employee_number_series where active order by position, lower(name)")) {
            if (AppliesTo.parse((String) s.get("appliesTo")).matches(subject)) {
                return s;
            }
        }
        return null;
    }

    /** Takes the next code for a joiner in its own transaction, without giving it to them. For tests of concurrency. */
    public static String drawFor(Db db, UUID tenantId, UUID onboardingId) {
        return db.inTenant(tenantId, c -> draw(c, JoinerService.load(c, onboardingId)));
    }

    /** Takes the next code for the joiner from the series that matches them, or returns null when none does. */
    static String draw(Connection c, JoinerService.Loaded l) throws SQLException {
        AppliesTo.Subject subject = JoinerService.subject(c, l);
        Map<String, Object> s = pick(c, subject);
        if (s == null) {
            return null;
        }
        Map<String, String> tokens = tokensFor(c, l);
        String prefix = expand((String) s.get("prefix"), tokens);
        String suffix = expand((String) s.get("suffix"), tokens);
        String bucket = prefix + ("yearly".equals(s.get("reset")) ? "|" + l.joiningDate().getYear() : "");
        Sql.update(c, "insert into app.employee_number_counter (tenant_id, series_id, bucket, next_number) values (?, ?::uuid, ?, ?) "
                + "on conflict (series_id, bucket) do nothing", l.tenantId(), s.get("id"), bucket, ((Number) s.get("startAt")).intValue());
        int digits = ((Number) s.get("digits")).intValue();
        // The row lock makes concurrent activations wait their turn; a code already used by hand is skipped.
        for (int tries = 0; tries < 1000; tries++) {
            Map<String, Object> counter = Sql.one(c, "select next_number from app.employee_number_counter where series_id = ?::uuid "
                    + "and bucket = ? for update", s.get("id"), bucket);
            long next = ((Number) counter.get("nextNumber")).longValue();
            Sql.update(c, "update app.employee_number_counter set next_number = next_number + 1 where series_id = ?::uuid and bucket = ?",
                    s.get("id"), bucket);
            String code = format(prefix, next, digits, suffix);
            if (Sql.one(c, "select 1 from app.employment where lower(employee_code) = lower(?) and status in ('onboarding','active') "
                    + "union all select 1 from app.membership where lower(employee_code) = lower(?)", code, code) == null) {
                return code;
            }
        }
        throw new ApiException(409, "series.exhausted", "Could not find an unused code in the series " + s.get("name"));
    }

    private static Map<String, String> tokensFor(Connection c, JoinerService.Loaded l) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select e.employment_type, wl.name as location, d.name as department from app.onboarding o "
                + "join app.employment e on e.id = o.employment_id left join app.work_location wl on wl.id = o.work_location_id "
                + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "left join app.department d on d.id = j.department_id where o.id = ?", l.id());
        return tokens(l.joiningDate(), (String) r.get("location"), (String) r.get("department"), (String) r.get("employmentType"));
    }

    // ── setup ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> list(SessionCtx ctx) {
        if (!ctx.reachesEveryone("employee.read")) {
            throw ApiException.denied("employee.read");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> s : Sql.query(c, "select * from app.employee_number_series order by position, lower(name)")) {
                s.put("appliesTo", AppliesTo.parse((String) s.get("appliesTo")).rules());
                s.put("example", example(c, s, LocalDate.now(IST)));
                out.add(s);
            }
            return out;
        });
    }

    public Map<String, Object> save(SessionCtx ctx, UUID id, SeriesInput in) {
        ctx.require("onboarding.configure");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> current = null;
            if (id != null) {
                current = Sql.one(c, "select * from app.employee_number_series where id = ? for update", id);
                if (current == null) {
                    throw ApiException.notFound("The series");
                }
            }
            String name = in.name() != null ? in.name().trim() : current == null ? "" : (String) current.get("name");
            if (name.isEmpty() || name.length() > 60) {
                throw ApiException.badRequest("Give the series a name of at most 60 characters");
            }
            String prefix = in.prefix() != null ? in.prefix() : current == null ? "" : (String) current.get("prefix");
            String suffix = in.suffix() != null ? in.suffix() : current == null ? "" : (String) current.get("suffix");
            if (prefix.length() > 30 || suffix.length() > 30) {
                throw ApiException.badRequest("Keep the prefix and suffix to 30 characters each");
            }
            Map<String, String> sample = tokens(LocalDate.now(IST), "Office", "Engineering", "permanent");
            expand(prefix, sample);
            expand(suffix, sample);
            if ((prefix + suffix).matches(".*[^A-Za-z0-9{}/_-].*")) {
                throw ApiException.badRequest("Use letters, digits, - / _ and tokens only");
            }
            int digits = in.digits() != null ? in.digits() : current == null ? 4 : ((Number) current.get("digits")).intValue();
            if (digits < 1 || digits > 10) {
                throw ApiException.badRequest("Digits must be from 1 to 10");
            }
            int start = in.startAt() != null ? in.startAt() : current == null ? 1 : ((Number) current.get("startAt")).intValue();
            if (start < 0) {
                throw ApiException.badRequest("The first number cannot be negative");
            }
            String reset = in.reset() != null ? in.reset() : current == null ? "never" : (String) current.get("reset");
            if (!Set.of("never", "yearly").contains(reset)) {
                throw ApiException.badRequest("Reset must be never or yearly");
            }
            AppliesTo rules = in.appliesTo() != null ? AppliesTo.from(in.appliesTo())
                    : current == null ? AppliesTo.EVERYONE : AppliesTo.parse((String) current.get("appliesTo"));
            int position = in.position() != null ? in.position() : current == null ? 0 : ((Number) current.get("position")).intValue();
            boolean active = in.active() != null ? in.active() : current == null || (Boolean) current.get("active");
            UUID saved;
            if (id == null) {
                saved = AuthService.uuid(Sql.one(c, "insert into app.employee_number_series (tenant_id, name, prefix, suffix, digits, start_at, "
                        + "reset, applies_to, position, active) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) returning id", ctx.tenantId(), name,
                        prefix, suffix, (short) digits, start, reset, rules.toJson(), (short) position, active).get("id"));
            } else {
                saved = id;
                Sql.update(c, "update app.employee_number_series set name = ?, prefix = ?, suffix = ?, digits = ?, start_at = ?, reset = ?, "
                        + "applies_to = ?::jsonb, position = ?, active = ? where id = ?", name, prefix, suffix, (short) digits, start, reset,
                        rules.toJson(), (short) position, active, id);
            }
            Map<String, Object> after = Sql.one(c, "select * from app.employee_number_series where id = ?", saved);
            UserService.audit(c, ctx, id == null ? "onboarding.series_created" : "onboarding.series_changed", "employee_number_series", saved,
                    Map.of("name", name, "pattern", prefix + "{" + digits + " digits}" + suffix));
            after.put("appliesTo", rules.rules());
            after.put("example", example(c, after, LocalDate.now(IST)));
            return after;
        });
    }

    /** The code the series would give a joiner joining on a date, in a sample department and location, without taking it. */
    public Map<String, Object> example(SessionCtx ctx, UUID id, String date, String location, String department, String type) {
        if (!ctx.reachesEveryone("employee.read")) {
            throw ApiException.denied("employee.read");
        }
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now(IST) : LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("The date must be like 2026-10-12");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> s = Sql.one(c, "select * from app.employee_number_series where id = ?", id);
            if (s == null) {
                throw ApiException.notFound("The series");
            }
            Map<String, String> tokens = tokens(day, location == null ? "Office" : location, department == null ? "Engineering" : department,
                    type == null ? "permanent" : type);
            return Map.of("code", next(c, s, tokens, day));
        });
    }

    private static String example(Connection c, Map<String, Object> s, LocalDate day) throws SQLException {
        return next(c, s, tokens(day, "Office", "Engineering", "permanent"), day);
    }

    private static String next(Connection c, Map<String, Object> s, Map<String, String> tokens, LocalDate day) throws SQLException {
        String prefix = expand((String) s.get("prefix"), tokens);
        String bucket = prefix + ("yearly".equals(s.get("reset")) ? "|" + day.getYear() : "");
        Map<String, Object> counter = Sql.one(c, "select next_number from app.employee_number_counter where series_id = ?::uuid and bucket = ?",
                s.get("id").toString(), bucket);
        long next = counter == null ? ((Number) s.get("startAt")).longValue() : ((Number) counter.get("nextNumber")).longValue();
        return format(prefix, next, ((Number) s.get("digits")).intValue(), expand((String) s.get("suffix"), tokens));
    }
}
