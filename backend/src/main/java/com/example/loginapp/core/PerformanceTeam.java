package com.example.loginapp.core;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Team view: for a manager, the people who report to them; for HR, everyone working here.
 * One row each, with where their goals stand this period, their progress, an open probation and,
 * for the caller's own reports, when they last had a one-to-one.
 */
public final class PerformanceTeam {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Db db;
    private final Clock clock;

    public PerformanceTeam(Db db) {
        this(db, Clock.systemUTC());
    }

    public PerformanceTeam(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Map<String, Object> team(SessionCtx ctx) {
        Perf.requireSomeone(ctx, "performance.read");
        LocalDate today = LocalDate.now(clock.withZone(IST));
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            Probation.ensure(c, ctx.tenantId(), today);
            UUID[] reach = Perf.reach(c, ctx, "performance.read", today);
            LocalDate start = Goals.periodStart(today, Goals.startMonth(c));
            Map<String, Object> me = Perf.ownEmployment(c, ctx);
            UUID mine = me == null ? null : AuthService.uuid(me.get("id"));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, "select e.id, " + Perf.NAME + " as name, e.employee_code, j.designation, d.name as department, "
                    + "j.manager_id, coalesce(mp.preferred_name, mp.legal_name) as manager, "
                    + "(select count(*) from app.goal g where g.employment_id = e.id and g.period_start = ? and g.status <> 'dropped') as goals, "
                    + "(select string_agg(distinct g.status, ',') from app.goal g where g.employment_id = e.id and g.period_start = ? "
                    + "  and g.status <> 'dropped') as goal_statuses, "
                    + "(select round(sum(coalesce(g.progress, 0) * g.weight) / nullif(sum(g.weight), 0)) from app.goal g "
                    + "  where g.employment_id = e.id and g.period_start = ? and g.status = 'agreed') as progress, "
                    + "(select r.id from app.probation_review r where r.employment_id = e.id and r.status in ('open','recommended') limit 1) "
                    + "  as probation_id, (select r.probation_end from app.probation_review r where r.employment_id = e.id "
                    + "  and r.status in ('open','recommended') limit 1) as probation_end, "
                    + "(select max(o.meeting_date) from app.one_to_one o where o.manager_id = ?::uuid and o.report_id = e.id) as last_one_to_one "
                    + "from app.employment e join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB
                    + "where e.status = 'active' and (?::uuid[] is null or e.id = any(?::uuid[])) and e.membership_id is distinct from ? "
                    + "order by " + Perf.NAME, start, start, start, mine, reach, reach, ctx.membershipId())) {
                String statuses = (String) r.remove("goalStatuses");
                r.put("goalStatus", statuses == null ? "none" : statuses.contains("returned") ? "returned" : statuses.contains("draft") ? "draft"
                        : statuses.contains("submitted") ? "submitted" : "agreed");
                r.put("isReport", mine != null && mine.toString().equals(r.get("managerId")));
                r.remove("managerId");
                rows.add(r);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("period", Goals.periodName(start));
            out.put("periodStart", start.toString());
            out.put("people", rows);
            return out;
        });
    }
}
