package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Performance setup: the rating scale (saved as a new version whenever it changes, so reviews keep
 * the scale they were rated on), competencies and the sets that say who is rated on which, and
 * the goal year and one-to-one rhythm. Probation settings are in {@link Probation}.
 */
public final class PerformanceSetup {

    public record Level(Integer value, String label, String description) {}

    public record ScaleInput(List<Level> levels) {}

    public record CompetencyInput(String name, String description, Boolean active) {}

    public record SetInput(String name, Map<String, Object> appliesTo, List<String> competencyIds, Boolean active) {}

    public record OrderInput(List<String> ids) {}

    public record GeneralInput(Integer goalYearStart, Integer oneToOneDays, Integer oneToOneNudgeDays) {}

    private final Db db;

    public PerformanceSetup(Db db) {
        this.db = db;
    }

    /** Everything on the setup screen. HR and managers may read it; only HR changes it. */
    public Map<String, Object> get(SessionCtx ctx) {
        Perf.requireSomeone(ctx, "performance.read");
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            return view(c);
        });
    }

    public Map<String, Object> saveScale(SessionCtx ctx, ScaleInput in) {
        Perf.requireManage(ctx);
        List<Level> levels = in == null || in.levels() == null ? List.of() : new ArrayList<>(in.levels());
        if (levels.size() < 3 || levels.size() > 7) {
            throw ApiException.badRequest("A rating scale has 3 to 7 levels");
        }
        levels.sort((a, b) -> Integer.compare(b.value() == null ? 0 : b.value(), a.value() == null ? 0 : a.value()));
        for (int i = 0; i < levels.size(); i++) {
            Level l = levels.get(i);
            if (l.value() == null || l.value() != levels.size() - i) {
                throw ApiException.badRequest("Number the levels from 1 up to " + levels.size() + ", one each");
            }
            Perf.text(l.label(), "a name for level " + l.value(), 1, 40);
            Perf.text(l.description(), "the description of level " + l.value(), 0, 300);
        }
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            Sql.one(c, "select 1 from app.perf_settings for update");
            int version = ((Number) Sql.one(c, "select coalesce(max(version), 0) + 1 as v from app.rating_scale").get("v")).intValue();
            UUID scale = AuthService.uuid(Sql.one(c, "insert into app.rating_scale (tenant_id, version, created_by) values (?, ?, ?) returning id",
                    ctx.tenantId(), version, ctx.membershipId()).get("id"));
            for (Level l : levels) {
                Sql.update(c, "insert into app.rating_level (tenant_id, scale_id, value, label, description) values (?, ?, ?, ?, ?)",
                        ctx.tenantId(), scale, l.value().shortValue(), l.label().trim(), Perf.text(l.description(), "a description", 0, 300));
            }
            Sql.update(c, "update app.perf_settings set rating_scale_id = ?, updated_by = ?, updated_at = now()", scale, ctx.membershipId());
            UserService.audit(c, ctx, "performance.scale_changed", "rating_scale", scale, Map.of("version", version, "levels", levels.size()));
            return view(c);
        });
    }

    public Map<String, Object> saveCompetency(SessionCtx ctx, UUID id, CompetencyInput in) {
        Perf.requireManage(ctx);
        String name = Perf.text(in == null ? null : in.name(), "the competency's name", 1, 60);
        String description = Perf.text(in.description(), "what it looks like", 0, 500);
        boolean active = in.active() == null || in.active();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            if (Sql.one(c, "select 1 from app.competency where lower(name) = lower(?) and id is distinct from ?", name, id) != null) {
                throw new ApiException(409, "request.refused", "A competency with that name already exists");
            }
            UUID saved = id;
            if (id == null) {
                saved = AuthService.uuid(Sql.one(c, "insert into app.competency (tenant_id, name, description, active) values (?, ?, ?, ?) returning id",
                        ctx.tenantId(), name, description, active).get("id"));
            } else if (Sql.update(c, "update app.competency set name = ?, description = ?, active = ? where id = ?", name, description, active, id) == 0) {
                throw ApiException.notFound("The competency");
            }
            UserService.audit(c, ctx, id == null ? "performance.competency_created" : "performance.competency_changed", "competency", saved,
                    Map.of("name", name, "active", active));
            return view(c);
        });
    }

    public Map<String, Object> saveSet(SessionCtx ctx, UUID id, SetInput in) {
        Perf.requireManage(ctx);
        String name = Perf.text(in == null ? null : in.name(), "the set's name", 1, 60);
        AppliesTo rules = AppliesTo.from(in.appliesTo());
        List<UUID> competencies = new ArrayList<>(new LinkedHashSet<>((in.competencyIds() == null ? List.<String>of() : in.competencyIds())
                .stream().map(s -> Perf.id(s, "competency")).toList()));
        if (competencies.isEmpty() || competencies.size() > 12) {
            throw ApiException.badRequest("Choose 1 to 12 competencies for the set");
        }
        boolean active = in.active() == null || in.active();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            for (UUID k : competencies) {
                if (Sql.one(c, "select 1 from app.competency where id = ?", k) == null) {
                    throw ApiException.badRequest("One of the competencies does not exist");
                }
            }
            for (UUID d : rules.ids("department")) {
                if (Sql.one(c, "select 1 from app.department where id = ?", d) == null) {
                    throw ApiException.badRequest("One of the departments in the rules does not exist");
                }
            }
            UUID saved = id;
            UUID[] ids = competencies.toArray(UUID[]::new);
            if (id == null) {
                int position = ((Number) Sql.one(c, "select coalesce(max(position), -1) + 1 as p from app.competency_set").get("p")).intValue();
                saved = AuthService.uuid(Sql.one(c, "insert into app.competency_set (tenant_id, name, applies_to, competency_ids, position, active, "
                        + "updated_by) values (?, ?, ?::jsonb, ?, ?, ?, ?) returning id", ctx.tenantId(), name, rules.toJson(), ids, (short) position,
                        active, ctx.membershipId()).get("id"));
            } else if (Sql.update(c, "update app.competency_set set name = ?, applies_to = ?::jsonb, competency_ids = ?, active = ?, updated_by = ?, "
                    + "updated_at = now() where id = ?", name, rules.toJson(), ids, active, ctx.membershipId(), id) == 0) {
                throw ApiException.notFound("The set");
            }
            UserService.audit(c, ctx, id == null ? "performance.competency_set_created" : "performance.competency_set_changed",
                    "competency_set", saved, Map.of("name", name, "competencies", competencies.size()));
            return view(c);
        });
    }

    public Map<String, Object> orderSets(SessionCtx ctx, OrderInput in) {
        Perf.requireManage(ctx);
        List<UUID> ids = (in == null || in.ids() == null ? List.<String>of() : in.ids()).stream().map(s -> Perf.id(s, "set")).toList();
        return db.inTenant(ctx.tenantId(), c -> {
            for (int i = 0; i < ids.size(); i++) {
                Sql.update(c, "update app.competency_set set position = ? where id = ?", (short) i, ids.get(i));
            }
            return view(c);
        });
    }

    public Map<String, Object> saveGeneral(SessionCtx ctx, GeneralInput in) {
        Perf.requireManage(ctx);
        if (in == null) {
            throw ApiException.badRequest("Nothing to save");
        }
        if (in.goalYearStart() != null && in.goalYearStart() != 1 && in.goalYearStart() != 4) {
            throw ApiException.badRequest("The goal year starts in April (the financial year) or January");
        }
        if (in.oneToOneDays() != null && (in.oneToOneDays() < 7 || in.oneToOneDays() > 90)) {
            throw ApiException.badRequest("Suggest a one-to-one every 7 to 90 days");
        }
        if (in.oneToOneNudgeDays() != null && (in.oneToOneNudgeDays() < 14 || in.oneToOneNudgeDays() > 180)) {
            throw ApiException.badRequest("Remind a manager after 14 to 180 days without a one-to-one");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            if (in.goalYearStart() != null && Sql.one(c, "select 1 from app.goal limit 1") != null
                    && ((Number) Sql.one(c, "select goal_year_start from app.perf_settings").get("goalYearStart")).intValue() != in.goalYearStart()) {
                throw new ApiException(409, "request.refused", "Goals have been set already; the goal year can no longer change");
            }
            Sql.update(c, "update app.perf_settings set goal_year_start = coalesce(?, goal_year_start), one_to_one_days = coalesce(?, one_to_one_days), "
                    + "one_to_one_nudge_days = coalesce(?, one_to_one_nudge_days), updated_by = ?, updated_at = now()",
                    in.goalYearStart() == null ? null : in.goalYearStart().shortValue(), in.oneToOneDays() == null ? null : in.oneToOneDays().shortValue(),
                    in.oneToOneNudgeDays() == null ? null : in.oneToOneNudgeDays().shortValue(), ctx.membershipId());
            UserService.audit(c, ctx, "performance.settings_changed", "perf_settings", ctx.tenantId(), Map.of("general", true));
            return view(c);
        });
    }

    // ── reading ─────────────────────────────────────────────────────────

    static Map<String, Object> view(Connection c) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> s = Sql.one(c, "select goal_year_start, one_to_one_days, one_to_one_nudge_days, rating_scale_id, updated_at "
                + "from app.perf_settings");
        out.put("goalYearStart", s.get("goalYearStart"));
        out.put("oneToOneDays", s.get("oneToOneDays"));
        out.put("oneToOneNudgeDays", s.get("oneToOneNudgeDays"));
        out.put("updatedAt", s.get("updatedAt"));
        out.put("scale", scale(c, AuthService.uuid(s.get("ratingScaleId"))));
        out.put("competencies", Sql.query(c, "select id, name, description, active from app.competency order by active desc, lower(name)"));
        List<Map<String, Object>> sets = new ArrayList<>();
        for (Map<String, Object> set : Sql.query(c, "select id, name, applies_to::text as applies_to, competency_ids, position, active "
                + "from app.competency_set order by position, lower(name)")) {
            set.put("appliesTo", AppliesTo.parse((String) set.get("appliesTo")).rules());
            sets.add(set);
        }
        out.put("competencySets", sets);
        return out;
    }

    /** A rating scale version with its levels, highest first. */
    static Map<String, Object> scale(Connection c, UUID id) throws SQLException {
        if (id == null) {
            return null;
        }
        Map<String, Object> out = Sql.one(c, "select id, version, created_at from app.rating_scale where id = ?", id);
        out.put("levels", Sql.query(c, "select value, label, description from app.rating_level where scale_id = ? order by value desc", id));
        return out;
    }

    /** The competencies an employee is rated on: the first active set whose rules match them, or none. */
    static List<Map<String, Object>> competenciesFor(Connection c, UUID employment) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select e.employment_type, j.department_id, j.designation, "
                + "(select a.work_location_id from app.attendance_assignment a where a.membership_id = e.membership_id order by a.effective_from desc limit 1) "
                + "as work_location_id from app.employment e left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "and j.effective_from <= current_date order by j.effective_from desc, j.created_at desc limit 1) j on true where e.id = ?", employment);
        if (e == null) {
            return List.of();
        }
        AppliesTo.Subject subject = new AppliesTo.Subject(AuthService.uuid(e.get("departmentId")), AuthService.uuid(e.get("workLocationId")),
                (String) e.get("employmentType"), (String) e.get("designation"));
        for (Map<String, Object> set : Sql.query(c, "select applies_to::text as applies_to, competency_ids from app.competency_set "
                + "where active order by position")) {
            if (AppliesTo.parse((String) set.get("appliesTo")).matches(subject)) {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) set.get("competencyIds");
                List<Map<String, Object>> out = new ArrayList<>();
                for (String id : ids) {
                    Map<String, Object> k = Sql.one(c, "select id, name, description from app.competency where id = ?::uuid and active", id);
                    if (k != null) {
                        out.add(k);
                    }
                }
                return out;
            }
        }
        return List.of();
    }
}
