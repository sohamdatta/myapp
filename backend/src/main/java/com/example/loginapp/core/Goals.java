package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Goals. Each employee has a set of goals for a goal period (the financial year by default),
 * whose weights add up to 100. The employee drafts and submits the set; their manager (or HR)
 * agrees it, or returns it with a note. Once agreed, a goal the employee changes is a new version
 * that goes back to the manager; one the manager or HR changes is agreed as they save it. Either
 * side records progress at any time. HR can also publish department goals, which people's goals
 * can support, as can the manager's own goals.
 */
public final class Goals {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> MEASURES = List.of("rating", "number", "done");

    public record GoalInput(String employmentId, String departmentId, String periodStart, String title, String description, Integer weight,
                            String measure, BigDecimal target, String unit, String dueDate, String parentGoalId, String reason) {}

    public record SetInput(String employmentId, String periodStart, String note) {}

    public record ReasonInput(String reason) {}

    public record CheckinInput(Integer progress, BigDecimal value, String note) {}

    private final Db db;
    private final Clock clock;

    public Goals(Db db) {
        this(db, Clock.systemUTC());
    }

    public Goals(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── periods ─────────────────────────────────────────────────────────

    /** The goal period a day falls in: from the first of April (or January) to the day before a year later. */
    static LocalDate periodStart(LocalDate day, int startMonth) {
        LocalDate start = LocalDate.of(day.getYear(), startMonth, 1);
        return day.isBefore(start) ? start.minusYears(1) : start;
    }

    static String periodName(LocalDate start) {
        return start.getMonthValue() == 1 ? String.valueOf(start.getYear())
                : "FY " + start.getYear() + "-" + String.format("%02d", (start.getYear() + 1) % 100);
    }

    static int startMonth(Connection c) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select goal_year_start from app.perf_settings");
        return s == null ? 4 : ((Number) s.get("goalYearStart")).intValue();
    }

    private static LocalDate period(Connection c, String given, LocalDate today) throws SQLException {
        int month = startMonth(c);
        LocalDate day = given == null || given.isBlank() ? today : Perf.date(given, "goal period");
        return periodStart(day, month);
    }

    // ── one person's goals ──────────────────────────────────────────────

    /** One person's goals for a period (the current one when none is given), with what the caller may do. */
    public Map<String, Object> view(SessionCtx ctx, UUID employmentId, String periodStart) {
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            requireSee(c, ctx, employmentId, today);
            return view(c, ctx, employmentId, period(c, periodStart, today), today);
        });
    }

    /** The caller's own goals for the current period, or null when they have no employment. */
    public Map<String, Object> mine(SessionCtx ctx) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> e = Perf.ownEmployment(c, ctx);
            if (e == null) {
                return null;
            }
            return view(c, ctx, AuthService.uuid(e.get("id")), period(c, null, today), today);
        });
    }

    public Map<String, Object> create(SessionCtx ctx, GoalInput in) {
        Perf.requireMember(ctx);
        if (in == null) {
            throw ApiException.badRequest("Describe the goal");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            if (in.departmentId() != null && !in.departmentId().isBlank()) {
                return createDepartmentGoal(c, ctx, in, today);
            }
            UUID employment = Perf.id(in.employmentId(), "person");
            requireEdit(c, ctx, employment, today);
            LocalDate start = period(c, in.periodStart(), today);
            Checked k = check(c, in, employment, start, true);
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.goal (tenant_id, employment_id, period_start, period_end, title, description, "
                    + "weight, measure, target, unit, due_date, parent_goal_id, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "returning id", ctx.tenantId(), employment, start, start.plusYears(1).minusDays(1), k.title, k.description,
                    k.weight.shortValue(), k.measure, k.target, k.unit, k.due, k.parent, ctx.membershipId()).get("id"));
            UserService.audit(c, ctx, "performance.goal_created", "goal", id, Map.of("title", k.title, "weight", k.weight));
            return view(c, ctx, employment, start, today);
        });
    }

    /**
     * Changes a goal. Before agreement it changes in place. An agreed goal keeps its earlier version:
     * changed by the employee it goes back to the manager; changed by the manager or HR it stays agreed.
     */
    public Map<String, Object> update(SessionCtx ctx, UUID id, GoalInput in) {
        Perf.requireMember(ctx);
        if (in == null) {
            throw ApiException.badRequest("Describe the goal");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> g = lock(c, id);
            if (g.get("departmentId") != null) {
                return updateDepartmentGoal(c, ctx, g, in, today);
            }
            UUID employment = AuthService.uuid(g.get("employmentId"));
            requireEdit(c, ctx, employment, today);
            String status = (String) g.get("status");
            if (status.equals("dropped")) {
                throw new ApiException(409, "goal.dropped", "This goal was dropped and cannot change");
            }
            LocalDate start = LocalDate.parse((String) g.get("periodStart"));
            Checked k = check(c, in, employment, start, false);
            boolean own = Perf.own(c, ctx, employment);
            if (status.equals("agreed")) {
                String reason = Perf.text(in.reason(), "why the goal changes", 5, 500);
                Sql.update(c, "insert into app.goal_version (tenant_id, goal_id, version, title, description, weight, measure, target, unit, "
                        + "due_date, agreed_by, agreed_at, replaced_by) select tenant_id, id, version, title, description, weight, measure, "
                        + "target, unit, due_date, agreed_by, agreed_at, ? from app.goal where id = ?", ctx.membershipId(), id);
                Sql.update(c, "update app.goal set title = ?, description = ?, weight = ?, measure = ?, target = ?, unit = ?, due_date = ?, "
                        + "parent_goal_id = ?, version = version + 1, change_reason = ?, status = ?, agreed_by = ?, agreed_at = "
                        + (own ? "null" : "now()") + ", updated_at = now() where id = ?",
                        k.title, k.description, k.weight.shortValue(), k.measure, k.target, k.unit, k.due, k.parent, reason,
                        own ? "submitted" : "agreed", own ? null : ctx.membershipId(), id);
                UserService.audit(c, ctx, "performance.goal_changed", "goal", id, Map.of("title", k.title, "reason", reason));
                if (own) {
                    Map<String, String> v = values(c, employment, start);
                    v.put("goal.title", k.title);
                    Notifications.send(c, ctx.tenantId(), ExitService.managerMembership(c, employment, today), "goal_changed", v, "performance");
                }
            } else {
                Sql.update(c, "update app.goal set title = ?, description = ?, weight = ?, measure = ?, target = ?, unit = ?, due_date = ?, "
                        + "parent_goal_id = ?, updated_at = now() where id = ?",
                        k.title, k.description, k.weight.shortValue(), k.measure, k.target, k.unit, k.due, k.parent, id);
            }
            return view(c, ctx, employment, start, today);
        });
    }

    /** Removes a goal that was never agreed. */
    public Map<String, Object> delete(SessionCtx ctx, UUID id) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> g = lock(c, id);
            if (g.get("departmentId") != null) {
                Perf.requireManage(ctx);
                if (Sql.one(c, "select 1 from app.goal where parent_goal_id = ?", id) != null) {
                    throw new ApiException(409, "goal.in_use", "People's goals support this goal; it cannot be removed");
                }
                Sql.update(c, "delete from app.goal where id = ?", id);
                return Map.of("deleted", true);
            }
            UUID employment = AuthService.uuid(g.get("employmentId"));
            requireEdit(c, ctx, employment, today);
            if (!Set.of("draft", "returned", "submitted").contains((String) g.get("status")) || ((Number) g.get("version")).intValue() > 1
                    || Sql.one(c, "select 1 from app.goal_checkin where goal_id = ?", id) != null) {
                throw new ApiException(409, "goal.agreed", "An agreed goal is not removed; your manager can drop it");
            }
            if (Sql.one(c, "select 1 from app.goal where parent_goal_id = ?", id) != null) {
                throw new ApiException(409, "goal.in_use", "Your team's goals support this goal; it cannot be removed");
            }
            Sql.update(c, "delete from app.goal where id = ?", id);
            return view(c, ctx, employment, LocalDate.parse((String) g.get("periodStart")), today);
        });
    }

    /** The manager or HR drops a goal that no longer applies. It stays, marked dropped, and counts for nothing. */
    public Map<String, Object> drop(SessionCtx ctx, UUID id, ReasonInput in) {
        Perf.requireMember(ctx);
        String reason = Perf.text(in == null ? null : in.reason(), "why the goal is dropped", 5, 500);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> g = lock(c, id);
            if (g.get("employmentId") == null) {
                throw ApiException.notFound("The goal");
            }
            UUID employment = AuthService.uuid(g.get("employmentId"));
            requireDecide(c, ctx, employment, today);
            if (!Set.of("agreed", "submitted").contains((String) g.get("status"))) {
                throw new ApiException(409, "request.refused", "Only an agreed or submitted goal is dropped; a draft is removed");
            }
            Sql.update(c, "update app.goal set status = 'dropped', drop_reason = ?, updated_at = now() where id = ?", reason, id);
            UserService.audit(c, ctx, "performance.goal_dropped", "goal", id, Map.of("title", g.get("title"), "reason", reason));
            return view(c, ctx, employment, LocalDate.parse((String) g.get("periodStart")), today);
        });
    }

    /** The employee sends their goals for the period to their manager. The weights must add up to 100. */
    public Map<String, Object> submit(SessionCtx ctx, SetInput in) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = Perf.ownEmployment(c, ctx);
            if (e == null) {
                throw new ApiException(409, "request.refused", "You have no current employment to set goals for");
            }
            UUID employment = AuthService.uuid(e.get("id"));
            LocalDate start = period(c, in == null ? null : in.periodStart(), today);
            lockSet(c, employment, start);
            if (Sql.one(c, "select 1 from app.goal where employment_id = ? and period_start = ? and status in ('draft','returned')",
                    employment, start) == null) {
                throw new ApiException(409, "request.refused", "There are no new or changed goals to send");
            }
            requireHundred(c, employment, start);
            Sql.update(c, "update app.goal set status = 'submitted', return_note = null, updated_at = now() where employment_id = ? "
                    + "and period_start = ? and status in ('draft','returned')", employment, start);
            UserService.audit(c, ctx, "performance.goals_submitted", "employment", employment, Map.of("period", periodName(start)));
            Notifications.send(c, ctx.tenantId(), ExitService.managerMembership(c, employment, today), "goals_submitted",
                    values(c, employment, start), "performance");
            return view(c, ctx, employment, start, today);
        });
    }

    /** The manager (or HR) agrees the submitted goals. Nothing may still be a draft, and the weights must add up to 100. */
    public Map<String, Object> agree(SessionCtx ctx, SetInput in) {
        Perf.requireMember(ctx);
        UUID employment = Perf.id(in == null ? null : in.employmentId(), "person");
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            requireDecide(c, ctx, employment, today);
            LocalDate start = period(c, in.periodStart(), today);
            lockSet(c, employment, start);
            if (Sql.one(c, "select 1 from app.goal where employment_id = ? and period_start = ? and status = 'submitted'", employment, start) == null) {
                throw new ApiException(409, "request.refused", "There are no goals waiting to be agreed");
            }
            if (Sql.one(c, "select 1 from app.goal where employment_id = ? and period_start = ? and status in ('draft','returned')",
                    employment, start) != null) {
                throw new ApiException(409, "goal.drafts", "Some goals are still being written; agree them once they are all sent");
            }
            requireHundred(c, employment, start);
            Sql.update(c, "update app.goal set status = 'agreed', agreed_by = ?, agreed_at = now(), updated_at = now() "
                    + "where employment_id = ? and period_start = ? and status = 'submitted'", ctx.membershipId(), employment, start);
            UserService.audit(c, ctx, "performance.goals_agreed", "employment", employment, Map.of("period", periodName(start)));
            Notifications.send(c, ctx.tenantId(), membership(c, employment), "goals_agreed", values(c, employment, start), "my-performance");
            return view(c, ctx, employment, start, today);
        });
    }

    /** The manager (or HR) sends the submitted goals back with a note. */
    public Map<String, Object> returnSet(SessionCtx ctx, SetInput in) {
        Perf.requireMember(ctx);
        UUID employment = Perf.id(in == null ? null : in.employmentId(), "person");
        String note = Perf.text(in.note(), "what should change", 10, 1000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            requireDecide(c, ctx, employment, today);
            LocalDate start = period(c, in.periodStart(), today);
            lockSet(c, employment, start);
            if (Sql.update(c, "update app.goal set status = 'returned', return_note = ?, updated_at = now() where employment_id = ? "
                    + "and period_start = ? and status = 'submitted'", note, employment, start) == 0) {
                throw new ApiException(409, "request.refused", "There are no goals waiting to be agreed");
            }
            UserService.audit(c, ctx, "performance.goals_returned", "employment", employment, Map.of("period", periodName(start)));
            Map<String, String> v = values(c, employment, start);
            v.put("note", note);
            Notifications.send(c, ctx.tenantId(), membership(c, employment), "goals_returned", v, "my-performance");
            return view(c, ctx, employment, start, today);
        });
    }

    /** Progress on an agreed goal, recorded by the employee, their manager or HR. */
    public Map<String, Object> checkin(SessionCtx ctx, UUID goalId, CheckinInput in) {
        Perf.requireMember(ctx);
        if (in == null || (in.progress() == null && in.value() == null && (in.note() == null || in.note().isBlank()))) {
            throw ApiException.badRequest("Give the progress, a value or a note");
        }
        if (in.progress() != null && (in.progress() < 0 || in.progress() > 100)) {
            throw ApiException.badRequest("Progress is a percentage from 0 to 100");
        }
        String note = Perf.text(in.note(), "a note", 0, 1000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> g = lock(c, goalId);
            if (g.get("employmentId") == null) {
                Perf.requireManage(ctx);
            } else {
                requireEdit(c, ctx, AuthService.uuid(g.get("employmentId")), today);
            }
            if (!"agreed".equals(g.get("status")) && g.get("employmentId") != null) {
                throw new ApiException(409, "goal.not_agreed", "Progress is recorded once the goal is agreed");
            }
            if (in.value() != null && !"number".equals(g.get("measure"))) {
                throw ApiException.badRequest("Only a goal measured by a number takes a value");
            }
            Integer progress = in.progress();
            if (progress == null && in.value() != null && g.get("target") != null) {
                BigDecimal target = new BigDecimal(g.get("target").toString());
                if (target.signum() > 0) {
                    progress = Math.max(0, Math.min(100, in.value().multiply(BigDecimal.valueOf(100)).divide(target, 0,
                            java.math.RoundingMode.HALF_UP).intValue()));
                }
            }
            Sql.update(c, "insert into app.goal_checkin (tenant_id, goal_id, progress, value, note, written_by) values (?, ?, ?, ?, ?, ?)",
                    ctx.tenantId(), goalId, progress == null ? null : progress.shortValue(), in.value(), note, ctx.membershipId());
            Sql.update(c, "update app.goal set progress = coalesce(?, progress), progress_value = coalesce(?, progress_value), "
                    + "updated_at = now() where id = ?", progress == null ? null : progress.shortValue(), in.value(), goalId);
            LocalDate start = LocalDate.parse((String) g.get("periodStart"));
            return g.get("employmentId") == null ? departmentGoal(c, goalId)
                    : view(c, ctx, AuthService.uuid(g.get("employmentId")), start, today);
        });
    }

    // ── department goals ────────────────────────────────────────────────

    /** Department goals for a period, which anyone may read so their own goals can support them. */
    public List<Map<String, Object>> departmentGoals(SessionCtx ctx, String periodStart) {
        if (!ctx.inOrganization() || ctx.scopeOf("performance.read") == null) {
            throw ApiException.denied("performance.read");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            LocalDate start = period(c, periodStart, today);
            return Sql.query(c, "select g.id, g.department_id, d.name as department, g.title, g.description, g.measure, g.target, g.unit, "
                    + "g.due_date, g.progress, g.progress_value, g.period_start, g.period_end, "
                    + "(select count(*) from app.goal k where k.parent_goal_id = g.id and k.status <> 'dropped') as supporting "
                    + "from app.goal g join app.department d on d.id = g.department_id where g.department_id is not null "
                    + "and g.period_start = ? order by lower(d.name), g.created_at", start);
        });
    }

    private Map<String, Object> createDepartmentGoal(Connection c, SessionCtx ctx, GoalInput in, LocalDate today) throws SQLException {
        Perf.requireManage(ctx);
        UUID department = Perf.id(in.departmentId(), "department");
        if (Sql.one(c, "select 1 from app.department where id = ? and active", department) == null) {
            throw ApiException.notFound("The department");
        }
        LocalDate start = period(c, in.periodStart(), today);
        Checked k = check(c, in, null, start, true);
        UUID id = AuthService.uuid(Sql.one(c, "insert into app.goal (tenant_id, department_id, period_start, period_end, title, description, "
                + "measure, target, unit, due_date, status, agreed_by, agreed_at, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'agreed', ?, now(), ?) "
                + "returning id", ctx.tenantId(), department, start, start.plusYears(1).minusDays(1), k.title, k.description, k.measure, k.target,
                k.unit, k.due, ctx.membershipId(), ctx.membershipId()).get("id"));
        UserService.audit(c, ctx, "performance.department_goal_created", "goal", id, Map.of("title", k.title));
        return departmentGoal(c, id);
    }

    private Map<String, Object> updateDepartmentGoal(Connection c, SessionCtx ctx, Map<String, Object> g, GoalInput in, LocalDate today)
            throws SQLException {
        Perf.requireManage(ctx);
        UUID id = AuthService.uuid(g.get("id"));
        Checked k = check(c, in, null, LocalDate.parse((String) g.get("periodStart")), true);
        Sql.update(c, "update app.goal set title = ?, description = ?, measure = ?, target = ?, unit = ?, due_date = ?, updated_at = now() "
                + "where id = ?", k.title, k.description, k.measure, k.target, k.unit, k.due, id);
        UserService.audit(c, ctx, "performance.department_goal_changed", "goal", id, Map.of("title", k.title));
        return departmentGoal(c, id);
    }

    private static Map<String, Object> departmentGoal(Connection c, UUID id) throws SQLException {
        return Sql.one(c, "select g.id, g.department_id, d.name as department, g.title, g.description, g.measure, g.target, g.unit, g.due_date, "
                + "g.progress, g.progress_value, g.period_start, g.period_end from app.goal g join app.department d on d.id = g.department_id "
                + "where g.id = ?", id);
    }

    // ── the view ────────────────────────────────────────────────────────

    static Map<String, Object> view(Connection c, SessionCtx ctx, UUID employment, LocalDate start, LocalDate today) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> e = Sql.one(c, "select e.id, " + Perf.NAME + " as name, e.membership_id, j.designation, d.name as department, "
                + "j.department_id, j.manager_id, coalesce(mp.preferred_name, mp.legal_name) as manager from app.employment e "
                + "join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB + "where e.id = ?", employment);
        out.put("employee", e);
        out.put("periodStart", start.toString());
        out.put("periodEnd", start.plusYears(1).minusDays(1).toString());
        out.put("period", periodName(start));
        List<Map<String, Object>> goals = new ArrayList<>();
        int total = 0;
        Set<String> statuses = new java.util.HashSet<>();
        for (Map<String, Object> g : Sql.query(c, "select g.id, g.title, g.description, g.weight, g.measure, g.target, g.unit, g.due_date, "
                + "g.parent_goal_id, pg.title as parent_title, g.status, g.version, g.change_reason, g.return_note, g.drop_reason, g.agreed_at, "
                + Perf.who("g.agreed_by") + " as agreed_by_name, g.progress, g.progress_value, g.updated_at "
                + "from app.goal g left join app.goal pg on pg.id = g.parent_goal_id where g.employment_id = ? and g.period_start = ? "
                + "order by (g.status = 'dropped'), g.created_at", employment, start)) {
            g.put("checkins", Sql.query(c, "select k.id, k.progress, k.value, k.note, k.written_at, " + Perf.who("k.written_by") + " as written_by_name "
                    + "from app.goal_checkin k where k.goal_id = ?::uuid order by k.written_at desc limit 10", g.get("id")));
            g.put("versions", ((Number) g.get("version")).intValue() > 1 ? Sql.query(c, "select version, title, weight, target, unit, due_date, "
                    + "replaced_at from app.goal_version where goal_id = ?::uuid order by version desc", g.get("id")) : List.of());
            if (!"dropped".equals(g.get("status"))) {
                total += ((Number) g.get("weight")).intValue();
                statuses.add((String) g.get("status"));
            }
            goals.add(g);
        }
        out.put("goals", goals);
        out.put("totalWeight", total);
        String set = statuses.isEmpty() ? "none" : statuses.contains("returned") ? "returned" : statuses.contains("draft") ? "draft"
                : statuses.contains("submitted") ? "submitted" : "agreed";
        out.put("setStatus", set);
        boolean own = Perf.own(c, ctx, employment);
        boolean support = ctx.isSupport();
        boolean decide = !own && !support && ctx.membershipId() != null && Perf.reaches(c, ctx, "performance.write", employment, today);
        boolean edit = !support && ctx.membershipId() != null && (own ? ctx.scopeOf("performance.write") != null : decide);
        out.put("own", own);
        out.put("canEdit", edit);
        out.put("canSubmit", own && edit && Set.of("draft", "returned").contains(set) && total == 100);
        out.put("canAgree", decide && statuses.contains("submitted") && !statuses.contains("draft") && !statuses.contains("returned")
                && total == 100);
        out.put("canReturn", decide && statuses.contains("submitted"));
        out.put("canDrop", decide);
        out.put("isCurrentPeriod", start.equals(periodStart(today, startMonth(c))));
        // Goals a person's goal can support: their manager's own, and their department's.
        List<Map<String, Object>> parents = new ArrayList<>();
        if (e.get("managerId") != null) {
            parents.addAll(Sql.query(c, "select g.id, g.title, 'manager' as source from app.goal g where g.employment_id = ?::uuid "
                    + "and g.period_start = ? and g.status = 'agreed' order by g.created_at", e.get("managerId"), start));
        }
        if (e.get("departmentId") != null) {
            parents.addAll(Sql.query(c, "select g.id, g.title, 'department' as source from app.goal g where g.department_id = ?::uuid "
                    + "and g.period_start = ? order by g.created_at", e.get("departmentId"), start));
        }
        out.put("parentOptions", parents);
        return out;
    }

    // ── checks ──────────────────────────────────────────────────────────

    private record Checked(String title, String description, Integer weight, String measure, BigDecimal target, String unit, LocalDate due,
                           UUID parent) {}

    private static Checked check(Connection c, GoalInput in, UUID employment, LocalDate start, boolean creating) throws SQLException {
        String title = Perf.text(in.title(), "the goal, in a short title", 3, 200);
        String description = Perf.text(in.description(), "how success is measured", 0, 2000);
        Integer weight = null;
        if (employment != null) {
            weight = in.weight();
            if (weight == null || weight < 5 || weight > 100 || weight % 5 != 0) {
                throw ApiException.badRequest("Give the goal a weight from 5 to 100, in steps of 5");
            }
        }
        String measure = in.measure() == null || in.measure().isBlank() ? "rating"
                : Perf.oneOf(in.measure(), MEASURES, "Measure the goal by a rating, a number or done / not done");
        BigDecimal target = measure.equals("number") ? in.target() : null;
        if (measure.equals("number") && target == null) {
            throw ApiException.badRequest("Give the number to reach");
        }
        String unit = measure.equals("number") ? Perf.text(in.unit(), "the unit", 0, 20) : null;
        LocalDate end = start.plusYears(1).minusDays(1);
        LocalDate due = in.dueDate() == null || in.dueDate().isBlank() ? null : Perf.date(in.dueDate(), "due date");
        if (due != null && (due.isBefore(start) || due.isAfter(end))) {
            throw ApiException.badRequest("The due date must fall in the goal period, " + TemplateValues.DATE.format(start) + " to "
                    + TemplateValues.DATE.format(end));
        }
        UUID parent = null;
        if (employment != null && in.parentGoalId() != null && !in.parentGoalId().isBlank()) {
            parent = Perf.id(in.parentGoalId(), "goal it supports");
            Map<String, Object> p = Sql.one(c, "select g.employment_id, g.department_id, g.period_start, g.status from app.goal g where g.id = ?",
                    parent);
            Map<String, Object> job = Sql.one(c, "select j.manager_id, j.department_id from app.job_assignment j where j.employment_id = ? "
                    + "and j.effective_from <= current_date order by j.effective_from desc, j.created_at desc limit 1", employment);
            boolean fits = p != null && start.toString().equals(p.get("periodStart")) && job != null
                    && ((p.get("employmentId") != null && p.get("employmentId").equals(job.get("managerId")) && "agreed".equals(p.get("status")))
                    || (p.get("departmentId") != null && p.get("departmentId").equals(job.get("departmentId"))));
            if (!fits) {
                throw ApiException.badRequest("A goal can support one of the manager's agreed goals, or a goal of the department, in the same period");
            }
        }
        return new Checked(title, description, weight, measure, target, unit, due, parent);
    }

    private static void requireHundred(Connection c, UUID employment, LocalDate start) throws SQLException {
        int total = ((Number) Sql.one(c, "select coalesce(sum(weight), 0) as total from app.goal where employment_id = ? and period_start = ? "
                + "and status <> 'dropped'", employment, start).get("total")).intValue();
        if (total != 100) {
            throw new ApiException(409, "goal.weights", "The weights add up to " + total + "; make them add up to 100");
        }
    }

    private static void requireSee(Connection c, SessionCtx ctx, UUID employment, LocalDate today) throws SQLException {
        if (!ctx.inOrganization() || ctx.scopeOf("performance.read") == null) {
            throw ApiException.denied("performance.read");
        }
        if (Perf.own(c, ctx, employment)) {
            return;
        }
        if (!Perf.reaches(c, ctx, "performance.read", employment, today)) {
            throw ApiException.notFound("The person");
        }
    }

    /** The employee themselves, their manager, or HR. */
    private static void requireEdit(Connection c, SessionCtx ctx, UUID employment, LocalDate today) throws SQLException {
        requireSee(c, ctx, employment, today);
        if (Sql.one(c, "select 1 from app.employment where id = ? and status = 'active'", employment) == null) {
            throw new ApiException(409, "employee.not_active", "Goals are set for current employees");
        }
        if (Perf.own(c, ctx, employment)) {
            if (ctx.scopeOf("performance.write") == null) {
                throw ApiException.denied("performance.write");
            }
            return;
        }
        if (!Perf.reaches(c, ctx, "performance.write", employment, today)) {
            throw ApiException.denied("performance.write");
        }
    }

    /** The manager or HR, never the employee about their own goals. */
    private static void requireDecide(Connection c, SessionCtx ctx, UUID employment, LocalDate today) throws SQLException {
        requireSee(c, ctx, employment, today);
        if (Perf.own(c, ctx, employment)) {
            throw new ApiException(409, "request.refused", "Nobody agrees, returns or drops their own goals");
        }
        if (!Perf.reaches(c, ctx, "performance.write", employment, today)) {
            throw ApiException.denied("performance.write");
        }
    }

    private static Map<String, Object> lock(Connection c, UUID id) throws SQLException {
        Map<String, Object> g = Sql.one(c, "select * from app.goal where id = ? for update", id);
        if (g == null) {
            throw ApiException.notFound("The goal");
        }
        return g;
    }

    /** Locks a person's goals for a period, so a submission and an agreement do not cross. */
    private static void lockSet(Connection c, UUID employment, LocalDate start) throws SQLException {
        Sql.query(c, "select id from app.goal where employment_id = ? and period_start = ? for update", employment, start);
    }

    private static UUID membership(Connection c, UUID employment) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?", employment);
        return e == null ? null : AuthService.uuid(e.get("membershipId"));
    }

    private static Map<String, String> values(Connection c, UUID employment, LocalDate start) throws SQLException {
        Map<String, String> v = ExitValues.forEmployment(c, employment, null);
        v.put("goals.period", periodName(start));
        return v;
    }
}
