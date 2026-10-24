package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.example.loginapp.core.Setting.Approval;
import com.example.loginapp.core.Setting.Layer;
import com.example.loginapp.core.Setting.Scope;
import com.example.loginapp.core.Settings.Context;
import com.example.loginapp.core.Settings.Resolved;
import com.example.loginapp.core.Settings.Row;

/**
 * Reading and changing settings for the screens: every change goes through {@link #change}, which checks
 * it, decides the assurance it needs (none, the person's password, or a second person), applies it or
 * holds it for approval, and records it. Nothing else writes the setting tables.
 */
public final class SettingsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int UNDO_DAYS = 30;

    public record ChangeInput(String scope, String scopeId, String validFrom, Object value, Boolean reset, String reason, String password) {}

    public record DecisionInput(String note, String password) {}

    public record PlatformInput(Object value, Boolean reset, String reason) {}

    public record BoundInput(Integer min, Integer max, List<String> allowed, Boolean locked, Object lockedValue, String reason) {}

    private final Db db;
    private final Clock clock;

    public SettingsService(Db db) {
        this(db, Clock.systemUTC());
    }

    public SettingsService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── reading ─────────────────────────────────────────────────────────

    /** The groups the caller may see, and the settings of one group with their values, sources and pending changes. */
    public Map<String, Object> list(SessionCtx ctx, String group) {
        requireOrganization(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> groups = new ArrayList<>();
            for (Map.Entry<String, String> g : Settings.GROUPS.entrySet()) {
                if (Settings.all().stream().anyMatch(s -> s.group.equals(g.getKey()) && visible(ctx, s))) {
                    groups.add(Map.of("key", g.getKey(), "title", g.getValue()));
                }
            }
            String chosen = group != null && !group.isBlank() ? group : groups.isEmpty() ? null : (String) groups.get(0).get("key");
            Places names = places(c);
            List<Map<String, Object>> settings = new ArrayList<>();
            for (Setting s : Settings.all()) {
                if (s.group.equals(chosen) && visible(ctx, s)) {
                    settings.add(view(c, ctx, s, names, today));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("groups", groups);
            out.put("group", chosen);
            out.put("settings", settings);
            out.put("pendingCount", ((Number) Sql.one(c, "select count(*) as n from app.setting_change where status = 'pending'").get("n")).intValue());
            return out;
        });
    }

    /** One setting resolved for a scope and date, with every step of the walk. */
    public Map<String, Object> explain(SessionCtx ctx, String key, String scope, String scopeId, String asOf) {
        requireOrganization(ctx);
        Setting s = Settings.byKey(key);
        if (!visible(ctx, s)) {
            throw new ApiException(404, "setting.unknown", "There is no setting " + key);
        }
        LocalDate date = asOf == null || asOf.isBlank() ? today() : date(asOf, "The date");
        return db.inTenant(ctx.tenantId(), c -> {
            Context context = context(scope == null ? Scope.ORGANIZATION : Scope.of(scope), id(scopeId));
            Resolved r = Settings.get(c, s, context, date);
            Map<String, Object> out = new LinkedHashMap<>(s.describe());
            out.put("asOf", date.toString());
            out.put("value", r.value());
            out.put("source", r.source());
            out.put("clamped", r.clamped());
            out.put("trail", r.trail());
            return out;
        });
    }

    /** The change log, newest first, for one key or all the caller can see. */
    public List<Map<String, Object>> changes(SessionCtx ctx, String key) {
        requireOrganization(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, "select ch.id, ch.key, ch.scope_kind, ch.scope_id, ch.valid_from, ch.old_value::text as old_value, "
                    + "ch.new_value::text as new_value, ch.reason, ch.status, ch.assurance, ch.requested_at, ch.decided_at, ch.decision_note, ch.undoes, "
                    + "coalesce(" + Names.of("ch.requested_by") + ", rq.email::text, 'system') as requested_by_name, ch.requested_by, "
                    + "coalesce(" + Names.of("ch.decided_by") + ", dc.email::text) as decided_by_name "
                    + "from app.setting_change ch left join app.member_directory() rq on rq.membership_id = ch.requested_by "
                    + "left join app.member_directory() dc on dc.membership_id = ch.decided_by "
                    + "where (?::text is null or ch.key = ?::text) order by ch.requested_at desc limit 200", key, key)) {
                Setting s = known((String) r.get("key"));
                if (s == null || !visible(ctx, s)) {
                    continue;
                }
                r.put("label", s.label);
                r.put("oldValue", r.get("oldValue") == null ? null : Settings.fromJson(s, (String) r.get("oldValue")));
                r.put("newValue", r.get("newValue") == null ? null : Settings.fromJson(s, (String) r.get("newValue")));
                r.put("canUndo", "applied".equals(r.get("status")) && mayChange(ctx, s)
                        && java.time.Instant.parse((String) r.get("decidedAt")).isAfter(java.time.Instant.now(clock).minusSeconds(UNDO_DAYS * 86400L)));
                out.add(r);
            }
            return out;
        });
    }

    // ── changing ────────────────────────────────────────────────────────

    /** Sets a value at a scope (and start date, for a dated setting), or puts it back to the default. */
    public Map<String, Object> change(SessionCtx ctx, String key, ChangeInput in) {
        requireOrganization(ctx);
        Setting s = Settings.byKey(key);
        requireMayChange(ctx, s);
        boolean confirmed = confirmPassword(ctx, in.password());
        Scope scope = in.scope() == null || in.scope().isBlank() ? Scope.ORGANIZATION : Scope.of(in.scope());
        UUID scopeId = id(in.scopeId());
        LocalDate validFrom = in.validFrom() == null || in.validFrom().isBlank() ? null : date(in.validFrom(), "The start date");
        Object value = Boolean.TRUE.equals(in.reset()) ? null : s.check(in.value());
        if (value == null && !Boolean.TRUE.equals(in.reset())) {
            throw new ApiException(400, "setting.out_of_range", "Give a value for " + s.label + ", or reset it to the default");
        }
        String reason = reason(in.reason());
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> result = doChange(c, ctx, s, scope, scopeId, validFrom, value, reason, confirmed, null, today);
            Map<String, Object> out = view(c, ctx, s, places(c), today);
            out.put("change", result);
            return out;
        });
    }

    public Map<String, Object> approve(SessionCtx ctx, UUID changeId, DecisionInput in) {
        return decide(ctx, changeId, true, in == null ? null : in.note());
    }

    public Map<String, Object> reject(SessionCtx ctx, UUID changeId, DecisionInput in) {
        String note = in == null || in.note() == null ? "" : in.note().trim();
        if (note.length() < 3) {
            throw ApiException.badRequest("Say why the change is rejected");
        }
        return decide(ctx, changeId, false, note);
    }

    public Map<String, Object> withdraw(SessionCtx ctx, UUID changeId) {
        requireOrganization(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> ch = pendingChange(c, changeId);
            if (!Objects.equals(String.valueOf(ch.get("requestedBy")), String.valueOf(ctx.membershipId()))) {
                throw new ApiException(403, "permission.denied", "Only the person who asked for a change can withdraw it");
            }
            Sql.update(c, "update app.setting_change set status = 'withdrawn' where id = ?", changeId);
            UserService.audit(c, ctx, "setting.withdrawn", "setting_change", changeId, Map.of("key", ch.get("key")));
            return Map.of("status", "withdrawn");
        });
    }

    /** Puts back the value an applied change replaced, within 30 days, through the same checks as any change. */
    public Map<String, Object> undo(SessionCtx ctx, UUID changeId, DecisionInput in) {
        requireOrganization(ctx);
        boolean confirmed = confirmPassword(ctx, in == null ? null : in.password());
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> ch = Sql.one(c, "select key, scope_kind, scope_id, valid_from, old_value::text as old_value, new_value::text as new_value, "
                    + "status, decided_at, reason from app.setting_change where id = ? for update", changeId);
            if (ch == null) {
                throw ApiException.notFound("The change");
            }
            Setting s = Settings.byKey((String) ch.get("key"));
            requireMayChange(ctx, s);
            if (!"applied".equals(ch.get("status"))) {
                throw new ApiException(409, "setting.not_applied", "Only an applied change can be undone");
            }
            if (java.time.Instant.parse((String) ch.get("decidedAt")).isBefore(java.time.Instant.now(clock).minusSeconds(UNDO_DAYS * 86400L))) {
                throw new ApiException(409, "setting.too_old", "Changes can be undone for " + UNDO_DAYS + " days; set the value again instead");
            }
            Scope scope = Scope.of((String) ch.get("scopeKind"));
            UUID scopeId = ch.get("scopeId") == null ? null : AuthService.uuid(ch.get("scopeId"));
            LocalDate validFrom = ch.get("validFrom") == null ? null : LocalDate.parse((String) ch.get("validFrom"));
            Object now = slotValue(c, s, scope, scopeId, validFrom);
            Object wrote = ch.get("newValue") == null ? null : Settings.fromJson(s, (String) ch.get("newValue"));
            if (!Objects.equals(now, wrote)) {
                throw new ApiException(409, "setting.stale", "This setting has changed again since; set the value you want directly");
            }
            Object back = ch.get("oldValue") == null ? null : Settings.fromJson(s, (String) ch.get("oldValue"));
            Map<String, Object> result = doChange(c, ctx, s, scope, scopeId, validFrom, back,
                    "Undo of a change" + (ch.get("reason") == null ? "" : ": " + ch.get("reason")), confirmed, changeId, today);
            if ("applied".equals(result.get("status"))) {
                Sql.update(c, "update app.setting_change set status = 'undone' where id = ?", changeId);
            }
            Map<String, Object> out = view(c, ctx, s, places(c), today);
            out.put("change", result);
            return out;
        });
    }

    private Map<String, Object> decide(SessionCtx ctx, UUID changeId, boolean approve, String note) {
        requireOrganization(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> ch = pendingChange(c, changeId);
            Setting s = Settings.byKey((String) ch.get("key"));
            requireMayChange(ctx, s);
            if (Objects.equals(String.valueOf(ch.get("requestedBy")), String.valueOf(ctx.membershipId()))) {
                throw new ApiException(409, "setting.own_change", "A second person decides this change, not the one who asked for it");
            }
            Scope scope = Scope.of((String) ch.get("scopeKind"));
            UUID scopeId = ch.get("scopeId") == null ? null : AuthService.uuid(ch.get("scopeId"));
            LocalDate validFrom = ch.get("validFrom") == null ? null : LocalDate.parse((String) ch.get("validFrom"));
            Object value = ch.get("newValue") == null ? null : Settings.fromJson(s, (String) ch.get("newValue"));
            Object was = ch.get("oldValue") == null ? null : Settings.fromJson(s, (String) ch.get("oldValue"));
            if (approve) {
                if (!Objects.equals(slotValue(c, s, scope, scopeId, validFrom), was)) {
                    throw new ApiException(409, "setting.stale", "The setting has changed since this was asked for; reject it and ask again");
                }
                s.check(value);
                checkDated(c, s, validFrom);
                apply(c, ctx.tenantId(), s, scope, scopeId, validFrom, value, changeId);
            }
            Sql.update(c, "update app.setting_change set status = ?, decided_by = ?, decided_at = now(), decision_note = ? where id = ?",
                    approve ? "applied" : "rejected", ctx.membershipId(), note == null || note.isBlank() ? null : note.trim(), changeId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("key", s.key);
            detail.put("scope", scope.db);
            detail.put("value", value == null ? "default" : value);
            UserService.audit(c, ctx, approve ? "setting.approved" : "setting.rejected", "setting_change", changeId, detail);
            if (ch.get("requestedBy") != null) {
                ensureTemplates(c, ctx.tenantId());
                Map<String, String> v = new LinkedHashMap<>();
                v.put("setting", s.label);
                v.put("outcome", approve ? "approved and applied" : "rejected" + (note == null ? "" : ": " + note));
                Notifications.send(c, ctx.tenantId(), AuthService.uuid(ch.get("requestedBy")), "setting_decided", v, "settings");
            }
            if (approve) {
                notifyWeakened(c, ctx, s, value, AuthService.uuid(ch.get("requestedBy")));
            }
            Map<String, Object> out = view(c, ctx, s, places(c), today);
            out.put("change", Map.of("id", changeId.toString(), "status", approve ? "applied" : "rejected"));
            return out;
        });
    }

    /** The heart of every change: validate, decide the assurance, then apply or hold for approval, and record. */
    private Map<String, Object> doChange(Connection c, SessionCtx ctx, Setting s, Scope scope, UUID scopeId, LocalDate validFrom, Object value,
                                         String reason, boolean confirmed, UUID undoes, LocalDate today) throws SQLException {
        if (s.layer == Layer.PLATFORM) {
            throw new ApiException(403, "permission.denied", s.label + " is set by the platform team");
        }
        if (!s.scopes.contains(scope)) {
            throw ApiException.badRequest(s.label + " cannot be set for a " + scope.db.replace('_', ' '));
        }
        if ((scope == Scope.ORGANIZATION) != (scopeId == null)) {
            throw ApiException.badRequest(scope == Scope.ORGANIZATION ? "The Organization needs no id" : "Say which " + scope.db.replace('_', ' '));
        }
        checkScopeExists(c, scope, scopeId);
        if (s.dated && validFrom == null) {
            throw ApiException.badRequest(s.label + " changes from a date: give the start date");
        }
        if (!s.dated && validFrom != null) {
            throw ApiException.badRequest(s.label + " takes effect at once; it has no start date");
        }
        checkDated(c, s, validFrom);
        Settings.Platform p = Settings.platformOf(c, s.key);
        if (p.locked()) {
            throw new ApiException(409, "setting.locked", s.label + " is fixed by the platform team");
        }
        if (value instanceof Integer v && ((p.min() != null && v < p.min()) || (p.max() != null && v > p.max()))) {
            throw new ApiException(400, "setting.out_of_range", s.label + " must be between " + (p.min() == null ? s.min : p.min()) + " and "
                    + (p.max() == null ? s.max : p.max()) + " in this service" + (s.unit == null ? "" : " (" + s.unit + ")"));
        }
        if (value instanceof String v && p.allowed() != null && !p.allowed().contains(v)) {
            throw new ApiException(400, "setting.out_of_range", s.label + " must be one of " + String.join(", ", p.allowed()) + " in this service");
        }
        Object stored = slotValue(c, s, scope, scopeId, validFrom);
        if (Objects.equals(stored, value)) {
            throw new ApiException(409, "setting.unchanged", s.label + " already has that value");
        }
        if (Sql.one(c, "select 1 from app.setting_change where key = ? and scope_kind = ? and scope_id is not distinct from ?::uuid "
                + "and valid_from is not distinct from ?::date and status = 'pending'", s.key, scope.db, scopeId, validFrom) != null) {
            throw new ApiException(409, "setting.pending", "A change to " + s.label + " is waiting for approval; decide or withdraw it first");
        }
        // Weakening is judged on the value in effect before and after, so resetting to a weaker default counts too.
        Context context = context(scope, scopeId);
        LocalDate asOf = validFrom == null ? today : validFrom;
        List<Row> rows = new ArrayList<>(Settings.load(c).getOrDefault(s.key, List.of()));
        Object before = Settings.resolve(s, rows, p, context, asOf).value();
        rows.removeIf(r -> r.scope() == scope && Objects.equals(r.scopeId(), scopeId) && Objects.equals(r.validFrom(), validFrom));
        if (value != null) {
            rows.add(new Row(scope, scopeId, validFrom, value, null));
        }
        Object after = Settings.resolve(s, rows, p, context, asOf).value();
        boolean weakening = s.weakening(before, after);
        Approval needed = weakening ? s.approval : Approval.NONE;
        if (needed == Approval.PASSWORD && !confirmed) {
            throw new ApiException(403, "setting.password_required", "This change makes the Organization less safe. Confirm your password to make it");
        }
        boolean hold = needed == Approval.SECOND_PERSON;
        UUID changeId = AuthService.uuid(Sql.one(c, "insert into app.setting_change (tenant_id, key, scope_kind, scope_id, valid_from, old_value, "
                + "new_value, reason, status, assurance, requested_by, decided_by, decided_at, undoes) values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, "
                + (hold ? "null" : "now()") + ", ?) returning id",
                ctx.tenantId(), s.key, scope.db, scopeId, validFrom, stored == null ? null : Json.write(stored),
                value == null ? null : Json.write(value), reason, hold ? "pending" : "applied",
                needed == Approval.NONE ? "none" : needed == Approval.PASSWORD ? "password" : "second_person",
                ctx.membershipId(), hold ? null : ctx.membershipId(), undoes).get("id"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("key", s.key);
        detail.put("scope", scope.db + (scopeId == null ? "" : ":" + scopeId));
        if (validFrom != null) {
            detail.put("from", validFrom.toString());
        }
        detail.put("old", stored == null ? "default" : stored);
        detail.put("new", value == null ? "default" : value);
        if (reason != null) {
            detail.put("reason", reason);
        }
        if (hold) {
            UserService.audit(c, ctx, "setting.requested", "setting_change", changeId, detail);
            ensureTemplates(c, ctx.tenantId());
            for (UUID m : holders(c, s.owner)) {
                if (!m.equals(ctx.membershipId())) {
                    Notifications.send(c, ctx.tenantId(), m, "setting_pending", Map.of("setting", s.label), "settings");
                }
            }
        } else {
            apply(c, ctx.tenantId(), s, scope, scopeId, validFrom, value, changeId);
            UserService.audit(c, ctx, "setting.changed", "setting_change", changeId, detail);
            if (weakening) {
                notifyWeakened(c, ctx, s, after, ctx.membershipId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", changeId.toString());
        out.put("status", hold ? "pending" : "applied");
        out.put("weakening", weakening);
        return out;
    }

    /** Writes the value into its slot (or clears it), and tells every server to reload this Organization's values. */
    private static void apply(Connection c, UUID tenant, Setting s, Scope scope, UUID scopeId, LocalDate validFrom, Object value, UUID changeId)
            throws SQLException {
        Sql.update(c, "delete from app.setting_value where key = ? and scope_kind = ? and scope_id is not distinct from ?::uuid "
                + "and valid_from is not distinct from ?::date", s.key, scope.db, scopeId, validFrom);
        if (value != null) {
            Sql.update(c, "insert into app.setting_value (tenant_id, key, scope_kind, scope_id, valid_from, value, change_id) "
                    + "values (?, ?, ?, ?, ?, ?::jsonb, ?)", tenant, s.key, scope.db, scopeId, validFrom, Json.write(value), changeId);
        }
        Sql.update(c, "insert into app.setting_epoch (tenant_id, epoch) values (?, 1) on conflict (tenant_id) do update set epoch = app.setting_epoch.epoch + 1",
                tenant);
        if (s.dated && Modules.ATTENDANCE.equals(s.module) && validFrom != null) {
            // Day results from the start date were worked out with the old value: forget them so they are worked out again.
            Sql.update(c, "delete from app.attendance_day where work_date >= ?", validFrom);
        }
    }

    /** A dated payroll setting cannot start in a month already approved: that month's pay is final. */
    private static void checkDated(Connection c, Setting s, LocalDate validFrom) throws SQLException {
        if (validFrom == null || !Modules.PAYROLL.equals(s.module)) {
            return;
        }
        Map<String, Object> r = Sql.one(c, "select max(period) as period from app.payroll_run where state in ('approved','paid','released') "
                + "and period >= date_trunc('month', ?::date)::date", validFrom);
        if (r != null && r.get("period") != null) {
            throw new ApiException(409, "setting.dated_locked", "Payroll for " + PayrollRuns.monthWords(LocalDate.parse((String) r.get("period")))
                    + " is approved; a change can start after it");
        }
    }

    // ── the platform ────────────────────────────────────────────────────

    /** Every setting with its platform value or default for all Organizations, and its bounds. */
    public List<Map<String, Object>> platformList(SessionCtx ctx) {
        ctx.requirePlatform("super_admin");
        return db.inPlatform(c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Setting s : Settings.all()) {
                Map<String, Object> d = new LinkedHashMap<>(s.describe());
                Map<String, Object> v = Sql.one(c, "select value::text as value from platform.setting where key = ?", s.key);
                d.put("platformValue", v == null ? null : Settings.fromJson(s, (String) v.get("value")));
                Map<String, Object> b = Sql.one(c, "select minimum, maximum, allowed::text as allowed, locked, locked_value::text as locked_value "
                        + "from platform.setting_bound where key = ?", s.key);
                if (b != null) {
                    Settings.Platform p = Settings.platform(s, b);
                    Map<String, Object> bound = new LinkedHashMap<>();
                    bound.put("min", p.min());
                    bound.put("max", p.max());
                    bound.put("allowed", p.allowed());
                    bound.put("locked", p.locked());
                    bound.put("lockedValue", p.lockedValue());
                    d.put("bound", bound);
                }
                out.add(d);
            }
            return out;
        });
    }

    /** A platform setting's value, or for an Organization setting the default every Organization gets unless it sets its own. */
    public Map<String, Object> setPlatformValue(SessionCtx ctx, String key, PlatformInput in) {
        ctx.requirePlatform("super_admin");
        Setting s = Settings.byKey(key);
        Object value = Boolean.TRUE.equals(in.reset()) ? null : s.check(in.value());
        if (value == null && !Boolean.TRUE.equals(in.reset())) {
            throw new ApiException(400, "setting.out_of_range", "Give a value for " + s.label + ", or reset it");
        }
        String reason = reason(in.reason());
        return db.inPlatform(c -> {
            Map<String, Object> was = Sql.one(c, "select value::text as value from platform.setting where key = ?", s.key);
            UUID changeId = AuthService.uuid(Sql.one(c, "insert into platform.setting_change (key, what, old_value, new_value, reason, changed_by) "
                    + "values (?, 'value', ?::jsonb, ?::jsonb, ?, ?) returning id", s.key, was == null ? null : was.get("value"),
                    value == null ? null : Json.write(value), reason, ctx.platformUserId()).get("id"));
            Sql.update(c, "delete from platform.setting where key = ?", s.key);
            if (value != null) {
                Sql.update(c, "insert into platform.setting (key, value, change_id) values (?, ?::jsonb, ?)", s.key, Json.write(value), changeId);
            }
            platformAudit(c, ctx, "setting.platform_value", changeId, Map.of("key", s.key, "value", value == null ? "default" : value));
            return Map.of("key", s.key, "value", value == null ? s.defaultValue : value, "changeId", changeId.toString());
        });
    }

    /** Limits on what Organizations may choose, never wider than the registry's own range. */
    public Map<String, Object> setBound(SessionCtx ctx, String key, BoundInput in) {
        ctx.requirePlatform("super_admin");
        Setting s = Settings.byKey(key);
        if (s.layer != Layer.ORGANIZATION) {
            throw ApiException.badRequest("Bounds apply to Organization settings");
        }
        if (in.min() != null || in.max() != null) {
            if (s.type != Setting.Type.INTEGER) {
                throw ApiException.badRequest("Only number settings have a minimum and maximum");
            }
            if ((in.min() != null && in.min() < s.min) || (in.max() != null && in.max() > s.max)
                    || (in.min() != null && in.max() != null && in.min() > in.max())) {
                throw ApiException.badRequest("Bounds must sit within " + s.min + " to " + s.max);
            }
        }
        if (in.allowed() != null) {
            for (String a : in.allowed()) {
                s.check(a);
            }
        }
        Object lockedValue = Boolean.TRUE.equals(in.locked()) ? s.check(in.lockedValue()) : null;
        if (Boolean.TRUE.equals(in.locked()) && lockedValue == null) {
            throw ApiException.badRequest("A locked setting needs its value");
        }
        String reason = reason(in.reason());
        return db.inPlatform(c -> {
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put("min", in.min());
            bound.put("max", in.max());
            bound.put("allowed", in.allowed());
            bound.put("locked", Boolean.TRUE.equals(in.locked()));
            bound.put("lockedValue", lockedValue);
            UUID changeId = AuthService.uuid(Sql.one(c, "insert into platform.setting_change (key, what, new_value, reason, changed_by) "
                    + "values (?, 'bound', ?::jsonb, ?, ?) returning id", s.key, Json.write(bound), reason, ctx.platformUserId()).get("id"));
            Sql.update(c, "delete from platform.setting_bound where key = ?", s.key);
            boolean any = in.min() != null || in.max() != null || in.allowed() != null || Boolean.TRUE.equals(in.locked());
            if (any) {
                Sql.update(c, "insert into platform.setting_bound (key, minimum, maximum, allowed, locked, locked_value, change_id) "
                        + "values (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?)", s.key, in.min(), in.max(), in.allowed() == null ? null : Json.write(in.allowed()),
                        Boolean.TRUE.equals(in.locked()), lockedValue == null ? null : Json.write(lockedValue), changeId);
            }
            platformAudit(c, ctx, "setting.platform_bound", changeId, Map.of("key", s.key, "bound", any ? Json.write(bound) : "removed"));
            return bound;
        });
    }

    // ── one setting, as the screens show it ─────────────────────────────

    private Map<String, Object> view(Connection c, SessionCtx ctx, Setting s, Places names, LocalDate today) throws SQLException {
        Map<String, Object> d = new LinkedHashMap<>(s.describe());
        Settings.Platform p = Settings.platformOf(c, s.key);
        Resolved r = Settings.get(c, s, Context.ORGANIZATION, today);
        d.put("value", r.value());
        d.put("source", r.source());
        d.put("clamped", r.clamped());
        d.put("platformDefault", p.value());
        d.put("bound", Map.of("min", p.min() == null ? s.min == null ? "" : s.min : p.min(), "max", p.max() == null ? s.max == null ? "" : s.max : p.max(),
                "locked", p.locked()));
        List<Map<String, Object>> values = new ArrayList<>();
        for (Map<String, Object> v : Sql.query(c, "select v.scope_kind, v.scope_id, v.valid_from, v.value::text as value, ch.requested_at, "
                + "coalesce(" + Names.of("ch.requested_by") + ", rq.email::text, 'system') as set_by from app.setting_value v "
                + "join app.setting_change ch on ch.id = v.change_id left join app.member_directory() rq on rq.membership_id = ch.requested_by "
                + "where v.key = ? order by (v.scope_kind <> 'organization'), v.valid_from nulls first", s.key)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scope", v.get("scopeKind"));
            row.put("scopeId", v.get("scopeId"));
            row.put("scopeName", names.of((String) v.get("scopeKind"), (String) v.get("scopeId")));
            row.put("validFrom", v.get("validFrom"));
            row.put("value", Settings.fromJson(s, (String) v.get("value")));
            row.put("setBy", v.get("setBy"));
            row.put("setAt", v.get("requestedAt"));
            values.add(row);
        }
        d.put("values", values);
        List<Map<String, Object>> pending = Sql.query(c, "select ch.id, ch.scope_kind, ch.scope_id, ch.valid_from, ch.old_value::text as old_value, "
                + "ch.new_value::text as new_value, ch.reason, ch.requested_at, ch.requested_by, coalesce(" + Names.of("ch.requested_by")
                + ", rq.email::text) as requested_by_name from app.setting_change ch left join app.member_directory() rq on rq.membership_id = ch.requested_by "
                + "where ch.key = ? and ch.status = 'pending' order by ch.requested_at", s.key);
        for (Map<String, Object> pc : pending) {
            pc.put("scopeName", names.of((String) pc.get("scopeKind"), (String) pc.get("scopeId")));
            pc.put("oldValue", pc.get("oldValue") == null ? null : Settings.fromJson(s, (String) pc.get("oldValue")));
            pc.put("newValue", pc.get("newValue") == null ? null : Settings.fromJson(s, (String) pc.get("newValue")));
            pc.put("mine", Objects.equals(String.valueOf(pc.get("requestedBy")), String.valueOf(ctx.membershipId())));
        }
        d.put("pending", pending);
        d.put("mayChange", mayChange(ctx, s));
        if (s.scopes.contains(Scope.LOCATION)) {
            d.put("scopeChoices", names.locations);
        } else if (s.scopes.contains(Scope.PAY_GROUP)) {
            d.put("scopeChoices", names.payGroups);
        }
        return d;
    }

    /** Names of the places a value can be set, for the screens. */
    private record Places(List<Map<String, Object>> locations, List<Map<String, Object>> payGroups, Map<String, String> byId) {
        String of(String kind, String id) {
            if (id == null) {
                return "Organization";
            }
            return byId.getOrDefault(id, kind.replace('_', ' '));
        }
    }

    private static Places places(Connection c) throws SQLException {
        List<Map<String, Object>> locations = Sql.query(c, "select id, name from app.work_location where active order by lower(name)");
        List<Map<String, Object>> groups = Modules.on(c, Modules.PAYROLL)
                ? Sql.query(c, "select id, name from app.pay_group order by lower(name)") : List.of();
        Map<String, String> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : locations) {
            byId.put((String) r.get("id"), (String) r.get("name"));
        }
        for (Map<String, Object> r : groups) {
            byId.put((String) r.get("id"), (String) r.get("name"));
        }
        return new Places(locations, groups, byId);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Object slotValue(Connection c, Setting s, Scope scope, UUID scopeId, LocalDate validFrom) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select value::text as value from app.setting_value where key = ? and scope_kind = ? "
                + "and scope_id is not distinct from ?::uuid and valid_from is not distinct from ?::date", s.key, scope.db, scopeId, validFrom);
        return r == null ? null : Settings.fromJson(s, (String) r.get("value"));
    }

    private static Map<String, Object> pendingChange(Connection c, UUID changeId) throws SQLException {
        Map<String, Object> ch = Sql.one(c, "select key, scope_kind, scope_id, valid_from, old_value::text as old_value, new_value::text as new_value, "
                + "requested_by, status from app.setting_change where id = ? for update", changeId);
        if (ch == null) {
            throw ApiException.notFound("The change");
        }
        if (!"pending".equals(ch.get("status"))) {
            throw new ApiException(409, "setting.decided", "This change is " + ch.get("status") + " already");
        }
        return ch;
    }

    private static void checkScopeExists(Connection c, Scope scope, UUID id) throws SQLException {
        String sql = switch (scope) {
            case ORGANIZATION -> null;
            case LOCATION -> "select 1 from app.work_location where id = ?";
            case PAY_GROUP -> "select 1 from app.pay_group where id = ?";
            case PERSON -> "select 1 from app.membership where id = ?";
        };
        if (sql != null && Sql.one(c, sql, id) == null) {
            throw ApiException.notFound("The " + scope.db.replace('_', ' '));
        }
    }

    private static Context context(Scope scope, UUID id) {
        return switch (scope) {
            case ORGANIZATION -> Context.ORGANIZATION;
            case LOCATION -> Context.location(id);
            case PAY_GROUP -> Context.payGroup(id);
            case PERSON -> Context.person(id);
        };
    }

    /** Members who hold a permission across the Organization: who is told of a change waiting for a second person. */
    private static List<UUID> holders(Connection c, String permission) throws SQLException {
        List<UUID> out = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select distinct mr.membership_id from app.membership_role mr join app.role_permission rp "
                + "on rp.role_id = mr.role_id join app.membership m on m.id = mr.membership_id where rp.permission_code = ? "
                + "and rp.scope in ('tenant','assigned') and m.status = 'active'", permission)) {
            out.add(AuthService.uuid(r.get("membershipId")));
        }
        return out;
    }

    /** Every Org Admin hears of a change that makes the Organization less safe. */
    private static void notifyWeakened(Connection c, SessionCtx ctx, Setting s, Object value, UUID by) throws SQLException {
        ensureTemplates(c, ctx.tenantId());
        for (UUID m : holders(c, "tenant.manage")) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("setting", s.label);
            v.put("value", String.valueOf(value) + (s.unit == null ? "" : " " + s.unit));
            Notifications.send(c, ctx.tenantId(), m, "setting_weakened", v, "settings");
        }
    }

    private static void ensureTemplates(Connection c, UUID tenant) throws SQLException {
        String[][] templates = {
                {"setting_weakened", "A setting was made less strict", "{{setting}} is now {{value}}. See Settings → History for who changed it and why."},
                {"setting_pending", "A setting change waits for you", "A change to {{setting}} needs a second person to approve it, under Settings."},
                {"setting_decided", "Your setting change was decided", "Your change to {{setting}} was {{outcome}}."},
        };
        for (String[] t : templates) {
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) on conflict (tenant_id, event, channel) do nothing",
                    tenant, t[0], t[1], t[2]);
        }
    }

    private static void platformAudit(Connection c, SessionCtx ctx, String action, UUID target, Map<String, ?> detail) throws SQLException {
        Sql.update(c, "insert into platform.platform_audit (platform_user_id, action, target_type, target_id, detail) values (?, ?, 'setting', ?, ?::jsonb)",
                ctx.platformUserId(), action, target, Json.object(detail));
    }

    /** True when the password given is the signed-in person's own. Checked in the sign-in plane, where hashes live. */
    private boolean confirmPassword(SessionCtx ctx, String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        boolean ok = db.inAuth(c -> {
            Map<String, Object> r = Sql.one(c, "select password_hash from auth.identity where id = ?", ctx.identityId());
            return r != null && r.get("passwordHash") != null && Secrets.verifyPassword(password, (String) r.get("passwordHash"));
        });
        if (!ok) {
            throw new ApiException(403, "setting.password_wrong", "That password is not right");
        }
        return true;
    }

    private static boolean visible(SessionCtx ctx, Setting s) {
        if (s.layer == Layer.PLATFORM || (s.module != null && !ctx.modules().contains(s.module))) {
            return false;
        }
        return ctx.reachesEveryone(s.owner) || ctx.reachesEveryone("audit.read");
    }

    private static boolean mayChange(SessionCtx ctx, Setting s) {
        return s.layer == Layer.ORGANIZATION && ctx.reachesEveryone(s.owner) && ctx.membershipId() != null && !ctx.isSupport()
                && (s.module == null || ctx.modules().contains(s.module));
    }

    private static void requireMayChange(SessionCtx ctx, Setting s) {
        if (s.module != null && !ctx.modules().contains(s.module)) {
            throw Modules.off(s.module, ctx.tenantName());
        }
        if (!mayChange(ctx, s)) {
            throw ApiException.denied(s.owner);
        }
    }

    private static void requireOrganization(SessionCtx ctx) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
    }

    private static Setting known(String key) {
        try {
            return Settings.byKey(key);
        } catch (ApiException e) {
            return null;
        }
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String r = reason.trim();
        if (r.length() < 3 || r.length() > 400) {
            throw ApiException.badRequest("A reason is a few words, at most 400 characters");
        }
        return r;
    }

    private static UUID id(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("That id is not valid");
        }
    }

    private static LocalDate date(String value, String what) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw ApiException.badRequest(what + " must look like 2026-11-01");
        }
    }
}
