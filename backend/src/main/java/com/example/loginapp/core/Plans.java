package com.example.loginapp.core;

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
 * Improvement plans. A manager (or HR) drafts a plan for someone in their team: why, up to six
 * objectives with how each is measured, the support offered, its length and how often to check in.
 * HR starts it, which issues the plan letter and tells the employee; they acknowledge it and may
 * respond. The manager writes a check-in on each due date, rating every objective, and the
 * employee may reply. At the end HR decides: completed, extended once, or not met (which starts an
 * exit through the exit module). A plan can be cancelled with a reason at any time before then.
 */
public final class Plans {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final Map<String, String> PROGRESS = Map.of("on_track", "On track", "at_risk", "At risk", "met", "Met", "not_met", "Not met");
    static final Map<String, String> DECIDED = Map.of("completed", "completed", "extended", "extended", "not_met", "not met",
            "cancelled", "cancelled");

    public record Objective(String title, String measure) {}

    public record PlanInput(String employmentId, String reviewId, String reason, String support, List<Objective> objectives, Integer lengthDays,
                            Integer checkinEvery) {}

    public record StartInput(String startDate) {}

    public record CheckinInput(Map<String, String> ratings, String note) {}

    public record TextInput(String text) {}

    public record DecideInput(String decision, String note, Integer extendDays, String lastWorkingDay) {}

    private final Db db;
    private final Crypto crypto;
    private final ExitService exits;
    private final DocumentService documents;
    private final Clock clock;

    public Plans(Db db, Crypto crypto, ExitService exits) {
        this(db, crypto, exits, Clock.systemUTC());
    }

    public Plans(Db db, Crypto crypto, ExitService exits, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.exits = exits;
        this.documents = new DocumentService(db, crypto);
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── lists ───────────────────────────────────────────────────────────

    /** The plans the caller reaches: live ones (draft and active) by default, or all. */
    public List<Map<String, Object>> list(SessionCtx ctx, String which) {
        Perf.requireSomeone(ctx, "performance.read");
        boolean all = "all".equals(which);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            closeEnded(c);
            UUID[] reach = Perf.reach(c, ctx, "performance.read", today);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> p : Sql.query(c, "select x.id, x.status, x.employment_id, x.start_date, x.end_date, x.original_end, "
                    + "x.acknowledged_at, x.created_at, " + Perf.NAME + " as name, e.employee_code, j.designation, d.name as department, "
                    + "coalesce(mp.preferred_name, mp.legal_name) as manager, "
                    + "(select count(*) from app.plan_checkin k where k.plan_id = x.id and k.written_at is null and k.due_date <= ?) as checkins_due "
                    + "from app.improvement_plan x join app.employment e on e.id = x.employment_id join app.person p on p.id = e.person_id "
                    + Perf.CURRENT_JOB + "where (?::uuid[] is null or x.employment_id = any(?::uuid[])) "
                    + (all ? "" : "and x.status in ('draft','active') ") + "order by (x.status in ('draft','active')) desc, x.end_date nulls first, "
                    + Perf.NAME, today, reach, reach)) {
                if (Perf.own(c, ctx, AuthService.uuid(p.get("employmentId")))) {
                    continue;   // your own plan is on My performance
                }
                p.put("stage", stage(p, today));
                out.add(p);
            }
            return out;
        });
    }

    // ── drafting and starting ──────────────────────────────────────────

    /** A manager or HR drafts a plan; HR is told when a manager drafted it. */
    public Map<String, Object> create(SessionCtx ctx, PlanInput in) {
        Perf.requireMember(ctx);
        Checked k = check(in);
        UUID employment = Perf.id(in.employmentId(), "employee");
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            requireWriter(c, ctx, employment, today);
            Map<String, Object> e = Sql.one(c, "select status from app.employment where id = ?", employment);
            if (!"active".equals(e.get("status"))) {
                throw new ApiException(409, "employee.not_active", "Only a current employee can be put on a plan");
            }
            if (Sql.one(c, "select 1 from app.improvement_plan where employment_id = ? and status in ('draft','active')", employment) != null) {
                throw new ApiException(409, "plan.exists", "They already have a plan in draft or running");
            }
            UUID review = in.reviewId() == null || in.reviewId().isBlank() ? null : Perf.id(in.reviewId(), "review");
            if (review != null && Sql.one(c, "select 1 from app.review where id = ? and employment_id = ?", review, employment) == null) {
                throw ApiException.badRequest("Choose one of their reviews");
            }
            UUID manager = managerOf(c, employment, today);
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.improvement_plan (tenant_id, employment_id, manager_id, review_id, reason_enc, "
                    + "support_enc, length_days, checkin_every, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(),
                    employment, manager, review, Perf.seal(crypto, ctx, k.reason), Perf.seal(crypto, ctx, k.support), k.length.shortValue(),
                    k.every.shortValue(), ctx.membershipId()).get("id"));
            writeObjectives(c, ctx, id, k.objectives);
            UserService.audit(c, ctx, "performance.plan_drafted", "improvement_plan", id, Map.of("employmentId", employment.toString()));
            if (!Perf.canManage(ctx)) {
                Map<String, String> v = values(c, load(c, id));
                for (UUID hr : ExitService.hrMembers(c)) {
                    Notifications.send(c, ctx.tenantId(), hr, "plan_drafted", v, "performance");
                }
            }
            return view(c, ctx, load(c, id), today);
        });
    }

    /** A draft changes until HR starts it. */
    public Map<String, Object> update(SessionCtx ctx, UUID id, PlanInput in) {
        Perf.requireMember(ctx);
        Checked k = check(in);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> p = lock(c, id);
            requireWriter(c, ctx, AuthService.uuid(p.get("employmentId")), today);
            if (!"draft".equals(p.get("status"))) {
                throw new ApiException(409, "plan.state", "Only a draft plan changes");
            }
            Sql.update(c, "update app.improvement_plan set reason_enc = ?, support_enc = ?, length_days = ?, checkin_every = ? where id = ?",
                    Perf.seal(crypto, ctx, k.reason), Perf.seal(crypto, ctx, k.support), k.length.shortValue(), k.every.shortValue(), id);
            Sql.update(c, "delete from app.plan_objective where plan_id = ?", id);
            writeObjectives(c, ctx, id, k.objectives);
            return view(c, ctx, load(c, id), today);
        });
    }

    /**
     * HR starts the plan from a day (today or later): the check-ins are scheduled, the plan letter
     * is issued, and the employee is told.
     */
    public Map<String, Object> start(SessionCtx ctx, UUID id, StartInput in) {
        Perf.requireManage(ctx);
        LocalDate today = today();
        LocalDate from = in == null || in.startDate() == null || in.startDate().isBlank() ? today : Perf.date(in.startDate(), "start date");
        if (from.isBefore(today)) {
            throw ApiException.badRequest("A plan starts today or later");
        }
        if (from.isAfter(today.plusDays(60))) {
            throw ApiException.badRequest("A plan starts within 60 days");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> p = lock(c, id);
            UUID employment = AuthService.uuid(p.get("employmentId"));
            if (Perf.own(c, ctx, employment)) {
                throw new ApiException(409, "request.refused", "Nobody starts their own plan");
            }
            if (!"draft".equals(p.get("status"))) {
                throw new ApiException(409, "plan.state", "This plan has already started");
            }
            if (!"active".equals(Sql.one(c, "select status from app.employment where id = ?", employment).get("status"))) {
                throw new ApiException(409, "employee.not_active", "They are no longer a current employee");
            }
            LocalDate end = from.plusDays(((Number) p.get("lengthDays")).intValue());
            Sql.update(c, "update app.improvement_plan set status = 'active', start_date = ?, end_date = ?, started_by = ?, started_at = now() "
                    + "where id = ?", from, end, ctx.membershipId(), id);
            schedule(c, ctx.tenantId(), id, from, end, ((Number) p.get("checkinEvery")).intValue());
            Map<String, Object> started = load(c, id);
            Map<String, String> v = values(c, started);
            UUID letter = PerformanceLetters.issue(c, ctx, documents, employment, "improvement_plan", v, "improvement_plan_id", id);
            Sql.update(c, "update app.improvement_plan set letter_id = ? where id = ?", letter, id);
            UserService.audit(c, ctx, "performance.plan_started", "improvement_plan", id, Map.of("from", from.toString(), "to", end.toString()));
            Notifications.send(c, ctx.tenantId(), membership(c, employment), "plan_started", v, "my-performance");
            return view(c, ctx, load(c, id), today);
        });
    }

    /** One check-in on each due date from the start, every so many days, and one on the last day. */
    private static void schedule(Connection c, UUID tenantId, UUID plan, LocalDate from, LocalDate end, int every) throws SQLException {
        for (LocalDate d = from.plusDays(every); d.isBefore(end); d = d.plusDays(every)) {
            if (end.toEpochDay() - d.toEpochDay() >= 3) {   // not one just before the end
                Sql.update(c, "insert into app.plan_checkin (tenant_id, plan_id, due_date) values (?, ?, ?) on conflict do nothing", tenantId, plan, d);
            }
        }
        Sql.update(c, "insert into app.plan_checkin (tenant_id, plan_id, due_date) values (?, ?, ?) on conflict do nothing", tenantId, plan, end);
    }

    // ── running ─────────────────────────────────────────────────────────

    /** The manager or HR writes a check-in: every objective rated, and a note the employee reads. It changes until they reply. */
    public Map<String, Object> checkin(SessionCtx ctx, UUID planId, UUID checkinId, CheckinInput in) {
        Perf.requireMember(ctx);
        String note = Perf.text(in == null ? null : in.note(), "a note the employee will read", 10, 2000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> p = lock(c, planId);
            requireWriter(c, ctx, AuthService.uuid(p.get("employmentId")), today);
            if (!"active".equals(p.get("status"))) {
                throw new ApiException(409, "plan.state", "Check-ins are written while the plan runs");
            }
            Map<String, Object> k = Sql.one(c, "select * from app.plan_checkin where id = ? and plan_id = ? for update", checkinId, planId);
            if (k == null) {
                throw ApiException.notFound("The check-in");
            }
            if (k.get("repliedAt") != null) {
                throw new ApiException(409, "plan.replied", "The employee has replied to this check-in, so it can no longer change");
            }
            if (LocalDate.parse((String) k.get("dueDate")).isAfter(today.plusDays(7))) {
                throw new ApiException(409, "plan.too_early", "This check-in is due on " + Perf.date(k.get("dueDate"))
                        + "; it can be written from a week before");
            }
            Map<String, String> ratings = new LinkedHashMap<>();
            for (Map<String, Object> o : objectives(c, planId)) {
                String r = in.ratings() == null ? null : in.ratings().get((String) o.get("id"));
                if (r == null || !PROGRESS.containsKey(r)) {
                    throw ApiException.badRequest("Say how \"" + o.get("title") + "\" is going");
                }
                ratings.put((String) o.get("id"), r);
            }
            boolean first = k.get("writtenAt") == null;
            Sql.update(c, "update app.plan_checkin set ratings = ?::jsonb, note_enc = ?, written_by = ?, written_at = now() where id = ?",
                    Json.write(ratings), Perf.seal(crypto, ctx, note), ctx.membershipId(), checkinId);
            UserService.audit(c, ctx, first ? "performance.plan_checkin_written" : "performance.plan_checkin_changed", "improvement_plan", planId,
                    Map.of("due", k.get("dueDate")));
            if (first) {
                Notifications.send(c, ctx.tenantId(), membership(c, AuthService.uuid(p.get("employmentId"))), "plan_checkin_written",
                        values(c, p), "my-performance");
            }
            return view(c, ctx, load(c, planId), today);
        });
    }

    /** HR decides: completed, extended once, or not met (which starts the exit); or cancels the plan with a reason. */
    public Map<String, Object> decide(SessionCtx ctx, UUID id, DecideInput in) {
        Perf.requireManage(ctx);
        String decision = Perf.oneOf(in == null ? null : in.decision(), List.of("completed", "extended", "not_met", "cancelled"),
                "Choose completed, extended, not met or cancelled");
        String note = Perf.text(in.note(), "a note on the decision", decision.equals("cancelled") ? 5 : 10, decision.equals("cancelled") ? 500 : 2000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> p = lock(c, id);
            UUID employment = AuthService.uuid(p.get("employmentId"));
            if (Perf.own(c, ctx, employment)) {
                throw new ApiException(409, "request.refused", "Nobody decides their own plan");
            }
            String status = (String) p.get("status");
            if (decision.equals("cancelled")) {
                if (!Set.of("draft", "active").contains(status)) {
                    throw new ApiException(409, "plan.state", "This plan has already been decided");
                }
                Sql.update(c, "update app.improvement_plan set status = 'cancelled', cancelled_reason = ? where id = ?", note, id);
            } else {
                if (!"active".equals(status)) {
                    throw new ApiException(409, "plan.state", "Only a running plan is decided");
                }
                LocalDate end = LocalDate.parse((String) p.get("endDate"));
                int written = ((Number) Sql.one(c, "select count(*) as n from app.plan_checkin where plan_id = ? and written_at is not null", id)
                        .get("n")).intValue();
                switch (decision) {
                    case "completed" -> Sql.update(c, "update app.improvement_plan set status = 'completed', decision_note_enc = ?, decided_by = ?, "
                            + "decided_at = now() where id = ?", Perf.seal(crypto, ctx, note), ctx.membershipId(), id);
                    case "extended" -> {
                        if (p.get("originalEnd") != null) {
                            throw new ApiException(409, "plan.extended", "A plan is extended once; decide it now");
                        }
                        int days = in.extendDays() == null ? 0 : in.extendDays();
                        if (days < 14 || days > 60) {
                            throw ApiException.badRequest("Extend by 14 to 60 days");
                        }
                        LocalDate next = end.plusDays(days);
                        Sql.update(c, "update app.improvement_plan set original_end = end_date, end_date = ?, decision_note_enc = ?, hr_reminded_on = null "
                                + "where id = ?", next, Perf.seal(crypto, ctx, note), id);
                        // The last check-in moves to the new end; the ones between are added.
                        Sql.update(c, "update app.plan_checkin set due_date = ? where plan_id = ? and due_date = ? and written_at is null", next, id, end);
                        schedule(c, ctx.tenantId(), id, end, next, ((Number) p.get("checkinEvery")).intValue());
                    }
                    default -> {
                        if (end.isAfter(today)) {
                            throw new ApiException(409, "plan.running", "The plan runs to " + Perf.date(p.get("endDate"))
                                    + "; it is decided as not met once it has ended");
                        }
                        if (written == 0) {
                            throw new ApiException(409, "plan.no_checkins", "No check-in has been written on this plan; a plan is not decided "
                                    + "as not met without them");
                        }
                        String lastDay = in.lastWorkingDay();
                        UUID exit = exits.startIn(c, ctx, new ExitService.StartInput(employment.toString(), "termination", "performance", note,
                                lastDay, null, null), today);
                        Sql.update(c, "update app.improvement_plan set status = 'not_met', decision_note_enc = ?, decided_by = ?, decided_at = now(), "
                                + "exit_case_id = ? where id = ?", Perf.seal(crypto, ctx, note), ctx.membershipId(), exit, id);
                    }
                }
            }
            UserService.audit(c, ctx, "performance.plan_" + decision, "improvement_plan", id, Map.of());
            if (!"draft".equals(status)) {
                Map<String, String> v = values(c, load(c, id));
                v.put("plan.decision", DECIDED.get(decision));
                Notifications.send(c, ctx.tenantId(), membership(c, employment), "plan_decided", v, "my-performance");
                UUID manager = ExitService.managerMembership(c, employment, today);
                if (manager != null && !manager.equals(ctx.membershipId())) {
                    Notifications.send(c, ctx.tenantId(), manager, "plan_decided", v, "performance");
                }
            }
            return view(c, ctx, load(c, id), today);
        });
    }

    // ── the employee's own ──────────────────────────────────────────────

    /** The caller's plan: the running one, or the latest decided one. Drafts are not shown. */
    public Map<String, Object> mine(SessionCtx ctx) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = Perf.ownEmployment(c, ctx);
            if (e == null) {
                return null;
            }
            Map<String, Object> p = Sql.one(c, "select * from app.improvement_plan where employment_id = ?::uuid and started_at is not null "
                    + "order by (status = 'active') desc, started_at desc limit 1", e.get("id"));
            return p == null ? null : view(c, ctx, p, today);
        });
    }

    /** The employee acknowledges reading the plan, with a response when they want to write one. */
    public Map<String, Object> acknowledge(SessionCtx ctx, UUID id, TextInput in) {
        Perf.requireMember(ctx);
        String response = Perf.text(in == null ? null : in.text(), "your response", 0, 2000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> p = lock(c, id);
            if (!Perf.own(c, ctx, AuthService.uuid(p.get("employmentId"))) || p.get("startedAt") == null) {
                throw ApiException.notFound("The plan");
            }
            if (p.get("acknowledgedAt") != null) {
                throw new ApiException(409, "plan.acknowledged", "You have already acknowledged this plan");
            }
            Sql.update(c, "update app.improvement_plan set acknowledged_at = now(), response_enc = ? where id = ?", Perf.seal(crypto, ctx, response), id);
            UserService.audit(c, ctx, "performance.plan_acknowledged", "improvement_plan", id, Map.of("responded", response != null));
            return view(c, ctx, load(c, id), today);
        });
    }

    /** The employee replies to a check-in, once. */
    public Map<String, Object> reply(SessionCtx ctx, UUID checkinId, TextInput in) {
        Perf.requireMember(ctx);
        String reply = Perf.text(in == null ? null : in.text(), "your reply", 2, 2000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = Sql.one(c, "select k.*, p.employment_id from app.plan_checkin k join app.improvement_plan p on p.id = k.plan_id "
                    + "where k.id = ? for update of k", checkinId);
            if (k == null || !Perf.own(c, ctx, AuthService.uuid(k.get("employmentId"))) || k.get("writtenAt") == null) {
                throw ApiException.notFound("The check-in");
            }
            if (k.get("repliedAt") != null) {
                throw new ApiException(409, "plan.replied", "You have already replied to this check-in");
            }
            Sql.update(c, "update app.plan_checkin set reply_enc = ?, replied_at = now() where id = ?", Perf.seal(crypto, ctx, reply), checkinId);
            UserService.audit(c, ctx, "performance.plan_checkin_replied", "improvement_plan", AuthService.uuid(k.get("planId")), Map.of());
            return view(c, ctx, load(c, AuthService.uuid(k.get("planId"))), today);
        });
    }

    public Map<String, Object> get(SessionCtx ctx, UUID id) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx, load(c, id), today));
    }

    // ── reminders ───────────────────────────────────────────────────────

    /** As a manager: plan check-ins due within three days or overdue. As HR: plans that have ended undecided. Each at most daily. */
    static void remind(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        if (Sql.one(c, "select 1 from app.improvement_plan where status = 'active' limit 1") == null) {
            return;
        }
        closeEnded(c);
        for (Map<String, Object> k : Sql.query(c, "select k.id, k.due_date, k.plan_id from app.plan_checkin k "
                + "join app.improvement_plan p on p.id = k.plan_id "
                + "join lateral (select j.manager_id from app.job_assignment j where j.employment_id = p.employment_id and j.effective_from <= ? "
                + "  order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                + "join app.employment boss on boss.id = cur.manager_id "
                + "where boss.membership_id = ? and p.status = 'active' and k.written_at is null and k.due_date <= ? "
                + "and (k.reminded_on is null or k.reminded_on < ?)", today, membershipId, today.plusDays(3), today)) {
            if (Sql.update(c, "update app.plan_checkin set reminded_on = ? where id = ?::uuid and (reminded_on is null or reminded_on < ?)",
                    today, k.get("id"), today) > 0) {
                Map<String, String> v = values(c, load(c, AuthService.uuid(k.get("planId"))));
                v.put("plan.checkin_due", Perf.date(k.get("dueDate")));
                Notifications.send(c, tenantId, membershipId, "plan_checkin_due", v, "performance");
            }
        }
        if (ExitService.hrMembers(c).contains(membershipId)) {
            for (Map<String, Object> p : Sql.query(c, "select id from app.improvement_plan where status = 'active' and end_date < ? "
                    + "and (hr_reminded_on is null or hr_reminded_on < ?)", today, today)) {
                if (Sql.update(c, "update app.improvement_plan set hr_reminded_on = ? where id = ?::uuid and (hr_reminded_on is null "
                        + "or hr_reminded_on < ?)", today, p.get("id"), today) > 0) {
                    Map<String, String> v = values(c, load(c, AuthService.uuid(p.get("id"))));
                    for (UUID hr : ExitService.hrMembers(c)) {
                        Notifications.send(c, tenantId, hr, "plan_overdue", v, "performance");
                    }
                }
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    /** A plan whose employee has left (or whose joining was cancelled) is cancelled. */
    static void closeEnded(Connection c) throws SQLException {
        Sql.update(c, "update app.improvement_plan x set status = 'cancelled', cancelled_reason = 'Their employment ended' "
                + "where x.status in ('draft','active') and exists (select 1 from app.employment e where e.id = x.employment_id "
                + "and e.status not in ('active','onboarding'))");
    }

    private Map<String, Object> view(Connection c, SessionCtx ctx, Map<String, Object> p, LocalDate today) throws SQLException {
        UUID id = AuthService.uuid(p.get("id"));
        UUID employment = AuthService.uuid(p.get("employmentId"));
        boolean own = Perf.own(c, ctx, employment);
        boolean writer = !own && Perf.reaches(c, ctx, "performance.write", employment, today);
        boolean hr = !own && Perf.canManage(ctx);
        boolean reader = Perf.reaches(c, ctx, "performance.read", employment, today);
        if (own ? p.get("startedAt") == null : !reader) {
            throw ApiException.notFound("The plan");
        }
        boolean words = (own || writer || hr) && !ctx.isSupport();
        String status = (String) p.get("status");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "status", "employmentId", "lengthDays", "checkinEvery", "startDate", "endDate", "originalEnd", "createdAt",
                "startedAt", "acknowledgedAt", "decidedAt", "cancelledReason", "exitCaseId")) {
            out.put(k, p.get(k));
        }
        out.put("employee", Sql.one(c, "select e.id, " + Perf.NAME + " as name, e.employee_code, j.designation, d.name as department, "
                + "coalesce(mp.preferred_name, mp.legal_name) as manager from app.employment e join app.person p on p.id = e.person_id "
                + Perf.CURRENT_JOB + "where e.id = ?", employment));
        out.put("createdBy", p.get("createdBy") == null ? null
                : Sql.one(c, "select " + Perf.who("x.m") + " as n from (select ?::uuid as m) x", p.get("createdBy")).get("n"));
        out.put("stage", stage(p, today));
        out.put("reason", words ? Perf.open(c, crypto, ctx.tenantId(), "improvement_plan", "reason_enc", id) : null);
        out.put("support", words ? Perf.open(c, crypto, ctx.tenantId(), "improvement_plan", "support_enc", id) : null);
        out.put("response", words ? Perf.open(c, crypto, ctx.tenantId(), "improvement_plan", "response_enc", id) : null);
        out.put("decisionNote", words && !own ? Perf.open(c, crypto, ctx.tenantId(), "improvement_plan", "decision_note_enc", id) : null);
        out.put("objectives", objectives(c, id));
        List<Map<String, Object>> checkins = new ArrayList<>();
        for (Map<String, Object> k : Sql.query(c, "select id, due_date, ratings::text as ratings, written_at, replied_at, "
                + Perf.who("written_by") + " as written_by_name from app.plan_checkin where plan_id = ? order by due_date", id)) {
            if ((own || !Set.of("draft", "active").contains(status)) && k.get("writtenAt") == null) {
                continue;   // the employee sees check-ins once written; a decided plan shows only those written
            }
            k.put("ratings", Json.parse((String) k.get("ratings")));
            k.put("note", words ? Perf.open(c, crypto, ctx.tenantId(), "plan_checkin", "note_enc", k.get("id")) : null);
            k.put("reply", words ? Perf.open(c, crypto, ctx.tenantId(), "plan_checkin", "reply_enc", k.get("id")) : null);
            k.put("canReply", own && k.get("writtenAt") != null && k.get("repliedAt") == null && !"cancelled".equals(status));
            k.put("canWrite", writer && "active".equals(status) && k.get("repliedAt") == null
                    && !LocalDate.parse((String) k.get("dueDate")).isAfter(today.plusDays(7)));
            checkins.add(k);
        }
        out.put("checkins", checkins);
        out.put("letter", p.get("letterId") == null ? null : Sql.one(c, "select id, title, document_id, issued_at from app.issued_letter "
                + "where id = ?::uuid", p.get("letterId")));
        out.put("youAre", own ? "employee" : hr ? "hr" : writer ? "manager" : "reader");
        out.put("canEdit", writer && "draft".equals(status));
        out.put("canStart", hr && "draft".equals(status));
        out.put("canDecide", hr && "active".equals(status));
        out.put("canCancel", hr && Set.of("draft", "active").contains(status));
        out.put("canExtend", hr && "active".equals(status) && p.get("originalEnd") == null);
        out.put("canAcknowledge", own && p.get("acknowledgedAt") == null && "active".equals(status));
        return out;
    }

    /** Where a plan stands, in a word the list shows. */
    static String stage(Map<String, Object> p, LocalDate today) {
        String status = (String) p.get("status");
        if (!"active".equals(status)) {
            return status;
        }
        LocalDate start = LocalDate.parse((String) p.get("startDate"));
        LocalDate end = LocalDate.parse((String) p.get("endDate"));
        if (today.isBefore(start)) {
            return "starting";
        }
        if (today.isAfter(end)) {
            return "to_decide";
        }
        if (p.get("checkinsDue") != null && ((Number) p.get("checkinsDue")).intValue() > 0) {
            return "checkin_due";
        }
        return "running";
    }

    private record Checked(String reason, String support, List<Objective> objectives, Integer length, Integer every) {}

    private static Checked check(PlanInput in) {
        if (in == null) {
            throw ApiException.badRequest("Write the plan");
        }
        String reason = Perf.text(in.reason(), "why the plan is needed", 20, 2000);
        String support = Perf.text(in.support(), "the support offered", 0, 2000);
        List<Objective> objectives = new ArrayList<>();
        for (Objective o : in.objectives() == null ? List.<Objective>of() : in.objectives()) {
            if (o == null || ((o.title() == null || o.title().isBlank()) && (o.measure() == null || o.measure().isBlank()))) {
                continue;
            }
            objectives.add(new Objective(Perf.text(o.title(), "each objective", 3, 200), Perf.text(o.measure(), "how each objective is measured",
                    3, 500)));
        }
        if (objectives.isEmpty() || objectives.size() > 6) {
            throw ApiException.badRequest("Give one to six objectives, each with how it is measured");
        }
        int length = in.lengthDays() == null ? 60 : in.lengthDays();
        if (length < 14 || length > 120) {
            throw ApiException.badRequest("A plan runs for 14 to 120 days");
        }
        int every = in.checkinEvery() == null ? 14 : in.checkinEvery();
        if (every < 7 || every > 31) {
            throw ApiException.badRequest("Check in every 7 to 31 days");
        }
        if (every >= length) {
            throw ApiException.badRequest("Check in more often than the plan's length");
        }
        return new Checked(reason, support, objectives, length, every);
    }

    private static void writeObjectives(Connection c, SessionCtx ctx, UUID plan, List<Objective> objectives) throws SQLException {
        int n = 1;
        for (Objective o : objectives) {
            Sql.update(c, "insert into app.plan_objective (tenant_id, plan_id, position, title, measure) values (?, ?, ?, ?, ?)", ctx.tenantId(), plan,
                    (short) n++, o.title(), o.measure());
        }
    }

    private static List<Map<String, Object>> objectives(Connection c, UUID plan) throws SQLException {
        return Sql.query(c, "select id, position, title, measure from app.plan_objective where plan_id = ? order by position", plan);
    }

    /** A manager writes plans for their team; HR for everyone; nobody for themselves. */
    private static void requireWriter(Connection c, SessionCtx ctx, UUID employment, LocalDate today) throws SQLException {
        if (!Perf.reaches(c, ctx, "performance.read", employment, today)) {
            throw ApiException.notFound("The employee");
        }
        if (Perf.own(c, ctx, employment)) {
            throw new ApiException(409, "request.refused", "Nobody writes their own improvement plan");
        }
        if (!Perf.reaches(c, ctx, "performance.write", employment, today)) {
            throw ApiException.denied("performance.write");
        }
    }

    private static UUID managerOf(Connection c, UUID employment, LocalDate on) throws SQLException {
        Map<String, Object> j = Sql.one(c, "select manager_id from app.job_assignment where employment_id = ? and effective_from <= ? "
                + "order by effective_from desc, created_at desc limit 1", employment, on);
        return j == null ? null : AuthService.uuid(j.get("managerId"));
    }

    private static Map<String, Object> load(Connection c, UUID id) throws SQLException {
        Map<String, Object> p = Sql.one(c, "select * from app.improvement_plan where id = ?", id);
        if (p == null) {
            throw ApiException.notFound("The plan");
        }
        return p;
    }

    private static Map<String, Object> lock(Connection c, UUID id) throws SQLException {
        Map<String, Object> p = Sql.one(c, "select * from app.improvement_plan where id = ? for update", id);
        if (p == null) {
            throw ApiException.notFound("The plan");
        }
        return p;
    }

    private static UUID membership(Connection c, UUID employment) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?", employment);
        return e == null ? null : AuthService.uuid(e.get("membershipId"));
    }

    private static Map<String, String> values(Connection c, Map<String, Object> p) throws SQLException {
        Map<String, String> v = ExitValues.forEmployment(c, AuthService.uuid(p.get("employmentId")), null);
        v.put("plan.start_date", Perf.date(p.get("startDate")));
        v.put("plan.end_date", Perf.date(p.get("endDate")));
        StringBuilder objectives = new StringBuilder();
        int n = 1;
        for (Map<String, Object> o : objectives(c, AuthService.uuid(p.get("id")))) {
            objectives.append(n++).append(". ").append(o.get("title")).append(" — ").append(o.get("measure")).append('\n');
        }
        v.put("plan.objectives", objectives.toString().strip());
        int every = ((Number) p.get("checkinEvery")).intValue();
        v.put("plan.checkin_every", every % 7 == 0 ? (every == 7 ? "every week" : "every " + every / 7 + " weeks") : "every " + every + " days");
        v.put("plan.decision", "");
        v.put("plan.checkin_due", "");
        return v;
    }
}
