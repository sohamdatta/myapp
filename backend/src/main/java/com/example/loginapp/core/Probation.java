package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Probation reviews. Every active employment on probation gets one, with check-ins the manager
 * writes at set days from joining (30, 60 and 90 by default), the manager's recommendation before the
 * probation ends, and HR's decision:
 *
 * <ul>
 *   <li>confirm: the employment type becomes permanent and the confirmation letter is issued;</li>
 *   <li>extend: the probation end moves by 1 to 6 months, the extension letter is issued, and a new review starts;</li>
 *   <li>not confirm: an exit of kind termination, reason performance, starts with the last working day HR gives.</li>
 * </ul>
 *
 * Nothing is decided by itself when the probation end passes: whether an employee is confirmed by
 * default is a legal question for each Organization, so HR is reminded instead. As elsewhere there
 * is no background job: reviews are created, and reminders sent, whenever someone looks.
 */
public final class Probation {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final Map<String, String> RATINGS = Map.of("on_track", "On track", "some_concerns", "Some concerns", "not_on_track", "Not on track");
    static final Map<String, String> CHOICES = Map.of("confirm", "confirm", "extend", "extend the probation", "not_confirm", "do not confirm");
    private static final Map<String, String> DECIDED = Map.of("confirm", "confirmed", "extend", "extended", "not_confirm", "not confirmed");

    public record CheckinInput(String rating, String note) {}

    public record ReplyInput(String reply) {}

    public record RecommendInput(String recommendation, Integer months, String note) {}

    public record DecideInput(String decision, Integer months, String note, String lastWorkingDay) {}

    public record SettingsInput(List<Integer> checkinDays, Integer recommendDays) {}

    private final Db db;
    private final Crypto crypto;
    private final ExitService exits;
    private final DocumentService documents;
    private final Clock clock;

    public Probation(Db db, Crypto crypto, ExitService exits) {
        this(db, crypto, exits, Clock.systemUTC());
    }

    public Probation(Db db, Crypto crypto, ExitService exits, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.exits = exits;
        this.documents = new DocumentService(db, crypto);
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── keeping reviews in step with employments ────────────────────────

    /**
     * Starts a review for every active employment on probation that has none for its current end,
     * with its check-ins, and cancels open reviews whose employment has ended.
     */
    static void ensure(Connection c, UUID tenantId, LocalDate today) throws SQLException {
        PerformanceDefaults.ensure(c, tenantId);
        Sql.update(c, "update app.probation_review r set status = 'cancelled' from app.employment e where e.id = r.employment_id "
                + "and r.status in ('open','recommended') and e.status in ('exited','withdrawn')");
        Map<String, Object> s = settings(c);
        List<Integer> days = days(s);
        for (Map<String, Object> e : Sql.query(c, "select e.id, e.joining_date, e.probation_end from app.employment e "
                + "where e.status = 'active' and e.probation_end is not null "
                + "and (e.employment_type = 'probation' or e.probation_end >= ?) "
                + "and not exists (select 1 from app.probation_review r where r.employment_id = e.id "
                + "  and (r.status in ('open','recommended') or (r.probation_end = e.probation_end and r.status <> 'cancelled')))", today)) {
            LocalDate joining = LocalDate.parse((String) e.get("joiningDate"));
            LocalDate end = LocalDate.parse((String) e.get("probationEnd"));
            Map<String, Object> r = Sql.one(c, "insert into app.probation_review (tenant_id, employment_id, probation_end) "
                    + "values (?, ?::uuid, ?) on conflict do nothing returning id", tenantId, e.get("id"), end);
            if (r == null) {
                continue;   // another request started it a moment ago
            }
            for (int d : days) {
                LocalDate due = joining.plusDays(d);
                if (due.isBefore(end)) {
                    Sql.update(c, "insert into app.probation_checkin (tenant_id, probation_review_id, day, due_date) values (?, ?::uuid, ?, ?)",
                            tenantId, r.get("id"), (short) d, due);
                }
            }
        }
    }

    // ── HR's and managers' view ─────────────────────────────────────────

    /** Probation reviews the caller may see: open (the default), decided, or all. */
    public List<Map<String, Object>> list(SessionCtx ctx, String status) {
        Perf.requireSomeone(ctx, "performance.read");
        LocalDate today = today();
        String which = status == null || status.isBlank() ? "open" : status.trim();
        if (!Set.of("open", "decided", "all").contains(which)) {
            throw ApiException.badRequest("Show open, decided or all");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            ensure(c, ctx.tenantId(), today);
            UUID[] reach = Perf.reach(c, ctx, "performance.read", today);
            int recommendDays = recommendDays(c);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, SUMMARY + "where (?::uuid[] is null or r.employment_id = any(?::uuid[])) "
                    + (which.equals("open") ? "and r.status in ('open','recommended') "
                    : which.equals("decided") ? "and r.status = 'decided' " : "and r.status <> 'cancelled' ")
                    + "order by (r.status = 'decided'), r.probation_end, " + Perf.NAME, reach, reach)) {
                out.add(summary(r, today, recommendDays));
            }
            return out;
        });
    }

    /** One review, with its check-ins, recommendation and decision, and what the caller may do next. */
    public Map<String, Object> get(SessionCtx ctx, UUID id) {
        Perf.requireSomeone(ctx, "performance.read");
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            ensure(c, ctx.tenantId(), today);
            Map<String, Object> r = load(c, id);
            requireSee(c, ctx, r, today);
            return view(c, ctx, r, today);
        });
    }

    /** The manager (or HR) writes a check-in: how the person is doing, and a note the employee reads. */
    public Map<String, Object> writeCheckin(SessionCtx ctx, UUID reviewId, UUID checkinId, CheckinInput in) {
        Perf.requireMember(ctx);
        String rating = Perf.oneOf(in == null ? null : in.rating(), List.copyOf(RATINGS.keySet()), "Choose how they are doing: on track, "
                + "some concerns, or not on track");
        String note = Perf.text(in.note(), "a note the employee will read", 10, 2000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lock(c, reviewId);
            requireWrite(c, ctx, r, today);
            Map<String, Object> k = Sql.one(c, "select * from app.probation_checkin where id = ? and probation_review_id = ? for update",
                    checkinId, reviewId);
            if (k == null) {
                throw ApiException.notFound("The check-in");
            }
            if (k.get("repliedAt") != null) {
                throw new ApiException(409, "probation.replied", "The employee has replied to this check-in, so it can no longer change");
            }
            if (k.get("writtenBy") != null && !k.get("writtenBy").equals(ctx.membershipId().toString()) && !Perf.canManage(ctx)) {
                throw new ApiException(409, "request.refused", "Someone else wrote this check-in; only they or HR can change it");
            }
            boolean first = k.get("writtenAt") == null;
            Sql.update(c, "update app.probation_checkin set rating = ?, note_enc = ?, written_by = ?, written_at = now() where id = ?",
                    rating, Perf.seal(crypto, ctx, note), ctx.membershipId(), checkinId);
            UserService.audit(c, ctx, first ? "performance.probation_checkin_written" : "performance.probation_checkin_changed",
                    "probation_review", reviewId, Map.of("day", k.get("day"), "rating", rating));
            if (first) {
                UUID employee = employeeMembership(c, r);
                Map<String, String> v = values(c, r);
                v.put("probation.checkin_day", "day " + k.get("day"));
                Notifications.send(c, ctx.tenantId(), employee, "probation_checkin_written", v, "my-performance");
            }
            return view(c, ctx, load(c, reviewId), today);
        });
    }

    /** The manager (or HR) recommends: confirm, extend by some months, or not confirm. It can be changed until HR decides. */
    public Map<String, Object> recommend(SessionCtx ctx, UUID id, RecommendInput in) {
        Perf.requireMember(ctx);
        String choice = Perf.oneOf(in == null ? null : in.recommendation(), List.of("confirm", "extend", "not_confirm"),
                "Choose a recommendation: confirm, extend or do not confirm");
        Integer months = choice.equals("extend") ? months(in.months()) : null;
        String note = choice.equals("confirm") ? Perf.text(in.note(), "a note", 0, 2000)
                : Perf.text(in.note(), "why, for HR", 20, 2000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lock(c, id);
            requireWrite(c, ctx, r, today);
            Sql.update(c, "update app.probation_review set status = 'recommended', recommendation = ?, recommend_months = ?, "
                    + "recommendation_note_enc = ?, recommended_by = ?, recommended_at = now() where id = ?",
                    choice, months == null ? null : months.shortValue(), Perf.seal(crypto, ctx, note), ctx.membershipId(), id);
            UserService.audit(c, ctx, "performance.probation_recommended", "probation_review", id,
                    months == null ? Map.of("recommendation", choice) : Map.of("recommendation", choice, "months", months));
            Map<String, String> v = values(c, r);
            v.put("probation.recommendation", CHOICES.get(choice) + (months == null ? "" : " by " + months(months)));
            for (UUID hr : ExitService.hrMembers(c)) {
                if (!hr.equals(ctx.membershipId())) {
                    Notifications.send(c, ctx.tenantId(), hr, "probation_recommended", v, "performance");
                }
            }
            return view(c, ctx, load(c, id), today);
        });
    }

    /** HR decides. Confirming and extending issue their letters; not confirming starts the exit. */
    public Map<String, Object> decide(SessionCtx ctx, UUID id, DecideInput in) {
        Perf.requireManage(ctx);
        String choice = Perf.oneOf(in == null ? null : in.decision(), List.of("confirm", "extend", "not_confirm"),
                "Choose a decision: confirm, extend or do not confirm");
        String note = choice.equals("confirm") ? Perf.text(in.note(), "a note", 0, 2000) : Perf.text(in.note(), "the reason", 20, 2000);
        LocalDate today = today();
        if (choice.equals("not_confirm") && !ctx.canAcrossOrganization("exit.manage")) {
            throw ApiException.denied("exit.manage");
        }
        LocalDate lastDay = choice.equals("not_confirm") ? Perf.date(in.lastWorkingDay(), "last working day") : null;
        if (lastDay != null && lastDay.isBefore(today)) {
            throw ApiException.badRequest("The last working day cannot be in the past");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lock(c, id);
            if (!Set.of("open", "recommended").contains((String) r.get("status"))) {
                throw new ApiException(409, "probation.decided", "This probation has already been decided");
            }
            UUID employment = AuthService.uuid(r.get("employmentId"));
            if (Perf.own(c, ctx, employment)) {
                throw new ApiException(409, "request.refused", "Nobody can decide their own probation");
            }
            LocalDate end = LocalDate.parse((String) r.get("probationEnd"));
            Map<String, String> v = values(c, r);
            switch (choice) {
                case "confirm" -> {
                    LocalDate from = end.plusDays(1);
                    Sql.update(c, "update app.employment set employment_type = 'permanent' where id = ? and employment_type = 'probation'",
                            employment);
                    Sql.update(c, "update app.probation_review set status = 'decided', decision = 'confirm', decision_note_enc = ?, "
                            + "decided_by = ?, decided_at = now(), confirmed_from = ? where id = ?",
                            Perf.seal(crypto, ctx, note), ctx.membershipId(), from, id);
                    v.put("probation.confirmed_from", TemplateValues.DATE.format(from));
                    UUID letter = PerformanceLetters.issue(c, ctx, documents, employment, "confirmation", v, "probation_review_id", id);
                    Sql.update(c, "update app.probation_review set letter_id = ? where id = ?", letter, id);
                }
                case "extend" -> {
                    int m = in.months() == null && r.get("recommendMonths") != null ? ((Number) r.get("recommendMonths")).intValue()
                            : months(in.months());
                    LocalDate next = end.plusMonths(m);
                    if (next.isBefore(today)) {
                        throw ApiException.badRequest("Extended by " + months(m) + ", the probation would already have ended on "
                                + TemplateValues.DATE.format(next) + "; extend it by more");
                    }
                    Sql.update(c, "update app.employment set probation_end = ? where id = ?", next, employment);
                    Sql.update(c, "update app.probation_review set status = 'decided', decision = 'extend', decision_months = ?, "
                            + "decision_note_enc = ?, decided_by = ?, decided_at = now(), new_probation_end = ? where id = ?",
                            (short) m, Perf.seal(crypto, ctx, note), ctx.membershipId(), next, id);
                    startExtension(c, ctx.tenantId(), employment, end, next);
                    v.put("probation.new_end_date", TemplateValues.DATE.format(next));
                    v.put("probation.extension_months", months(m));
                    UUID letter = PerformanceLetters.issue(c, ctx, documents, employment, "probation_extension", v, "probation_review_id", id);
                    Sql.update(c, "update app.probation_review set letter_id = ? where id = ?", letter, id);
                }
                default -> {
                    UUID exit = exits.startIn(c, ctx, new ExitService.StartInput(employment.toString(), "termination", "performance",
                            note, lastDay.toString(), null, null), today);
                    Sql.update(c, "update app.probation_review set status = 'decided', decision = 'not_confirm', decision_note_enc = ?, "
                            + "decided_by = ?, decided_at = now(), exit_case_id = ? where id = ?",
                            Perf.seal(crypto, ctx, note), ctx.membershipId(), exit, id);
                }
            }
            UserService.audit(c, ctx, "performance.probation_decided", "probation_review", id, Map.of("decision", choice));
            UUID manager = ExitService.managerMembership(c, employment, today);
            if (manager != null && !manager.equals(ctx.membershipId())) {
                v.put("probation.decision", DECIDED.get(choice));
                Notifications.send(c, ctx.tenantId(), manager, "probation_decided", v, "performance");
            }
            return view(c, ctx, load(c, id), today);
        });
    }

    // ── the employee's own ──────────────────────────────────────────────

    /** The caller's probation: the review in progress, or the latest decided one, with check-ins and letters. */
    public Map<String, Object> mine(SessionCtx ctx) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            ensure(c, ctx.tenantId(), today);
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> e = Perf.ownEmployment(c, ctx);
            if (e == null) {
                out.put("probation", null);
                return out;
            }
            Map<String, Object> r = Sql.one(c, "select * from app.probation_review where employment_id = ?::uuid and status <> 'cancelled' "
                    + "order by (status in ('open','recommended')) desc, probation_end desc limit 1", e.get("id"));
            if (r == null) {
                out.put("probation", null);
                return out;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", r.get("id"));
            p.put("probationEnd", r.get("probationEnd"));
            p.put("status", r.get("status"));
            p.put("onProbation", !"decided".equals(r.get("status")));
            p.put("decision", r.get("decision"));
            p.put("decidedAt", r.get("decidedAt"));
            p.put("confirmedFrom", r.get("confirmedFrom"));
            p.put("newProbationEnd", r.get("newProbationEnd"));
            List<Map<String, Object>> checkins = new ArrayList<>();
            for (Map<String, Object> k : checkins(c, ctx, AuthService.uuid(r.get("id")), true)) {
                if (k.get("writtenAt") != null) {
                    checkins.add(k);
                }
            }
            p.put("checkins", checkins);
            p.put("letters", letters(c, AuthService.uuid(e.get("id"))));
            out.put("probation", p);
            return out;
        });
    }

    /** The employee replies to a check-in, once. */
    public Map<String, Object> reply(SessionCtx ctx, UUID checkinId, ReplyInput in) {
        Perf.requireMember(ctx);
        String reply = Perf.text(in == null ? null : in.reply(), "your reply", 2, 2000);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = Sql.one(c, "select k.*, r.employment_id, r.status as review_status from app.probation_checkin k "
                    + "join app.probation_review r on r.id = k.probation_review_id where k.id = ? for update of k", checkinId);
            if (k == null || !Perf.own(c, ctx, AuthService.uuid(k.get("employmentId"))) || k.get("writtenAt") == null) {
                throw ApiException.notFound("The check-in");
            }
            if (k.get("repliedAt") != null) {
                throw new ApiException(409, "probation.replied", "You have already replied to this check-in");
            }
            if ("cancelled".equals(k.get("reviewStatus"))) {
                throw new ApiException(409, "request.refused", "This probation review has ended");
            }
            Sql.update(c, "update app.probation_checkin set reply_enc = ?, replied_at = now() where id = ?", Perf.seal(crypto, ctx, reply),
                    checkinId);
            UserService.audit(c, ctx, "performance.probation_checkin_replied", "probation_review", AuthService.uuid(k.get("probationReviewId")),
                    Map.of("day", k.get("day")));
            return Map.of("repliedAt", Sql.one(c, "select replied_at from app.probation_checkin where id = ?", checkinId).get("repliedAt"));
        });
    }

    // ── settings ────────────────────────────────────────────────────────

    public Map<String, Object> getSettings(SessionCtx ctx) {
        Perf.requireSomeone(ctx, "performance.read");
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            return settingsView(c);
        });
    }

    public Map<String, Object> saveSettings(SessionCtx ctx, SettingsInput in) {
        Perf.requireManage(ctx);
        List<Integer> days = in == null || in.checkinDays() == null ? null : new ArrayList<>(new LinkedHashSet<>(in.checkinDays()));
        if (days != null) {
            if (days.size() > 6 || days.stream().anyMatch(d -> d == null || d < 1 || d > 365)) {
                throw ApiException.badRequest("Give up to 6 check-in days, each between 1 and 365 days from joining");
            }
            days.sort(Integer::compare);
        }
        Integer recommend = in == null ? null : in.recommendDays();
        if (recommend != null && (recommend < 7 || recommend > 90)) {
            throw ApiException.badRequest("Ask for the recommendation between 7 and 90 days before the probation ends");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> before = settingsView(c);
            Sql.update(c, "update app.perf_settings set probation_checkin_days = coalesce(?::smallint[], probation_checkin_days), "
                    + "probation_recommend_days = coalesce(?, probation_recommend_days), updated_by = ?, updated_at = now()",
                    days == null ? null : days.stream().map(String::valueOf).toArray(String[]::new),
                    recommend == null ? null : recommend.shortValue(), ctx.membershipId());
            Map<String, Object> after = settingsView(c);
            UserService.audit(c, ctx, "performance.settings_changed", "perf_settings", ctx.tenantId(), Map.of("before", before, "after", after));
            return after;
        });
    }

    // ── reminders ───────────────────────────────────────────────────────

    /**
     * Reminders for one person, worked out when they open their notifications: as a manager, check-ins
     * due and recommendations due; as HR, decisions overdue. Each at most once a day.
     */
    static void remind(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        if (Sql.one(c, "select 1 from app.perf_settings") == null && Sql.one(c, "select 1 from app.employment where status = 'active' "
                + "and probation_end is not null and (employment_type = 'probation' or probation_end >= ?) limit 1", today) == null) {
            return;   // nobody on probation, and performance not used yet in this Organization
        }
        ensure(c, tenantId, today);
        int recommendDays = recommendDays(c);
        // As a manager: check-ins due within a week, or overdue, not yet written.
        for (Map<String, Object> k : Sql.query(c, "select k.id, k.day, k.due_date, r.id as review_id from app.probation_checkin k "
                + "join app.probation_review r on r.id = k.probation_review_id join app.employment e on e.id = r.employment_id "
                + "join lateral (select j.manager_id from app.job_assignment j where j.employment_id = e.id and j.effective_from <= ? "
                + "  order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                + "join app.employment boss on boss.id = cur.manager_id "
                + "where boss.membership_id = ? and r.status in ('open','recommended') and k.written_at is null and k.due_date <= ? "
                + "and (k.reminded_on is null or k.reminded_on < ?)", today, membershipId, today.plusDays(7), today.minusDays(3))) {
            if (Sql.update(c, "update app.probation_checkin set reminded_on = ? where id = ?::uuid and (reminded_on is null or reminded_on < ?)",
                    today, k.get("id"), today) > 0) {
                Map<String, String> v = values(c, load(c, AuthService.uuid(k.get("reviewId"))));
                v.put("probation.checkin_day", "day " + k.get("day"));
                v.put("probation.checkin_due", Perf.date(k.get("dueDate")));
                Notifications.send(c, tenantId, membershipId, "probation_checkin_due", v, "performance");
            }
        }
        // As a manager: a recommendation due (and every few days once the probation has ended).
        for (Map<String, Object> r : Sql.query(c, "select r.id from app.probation_review r join app.employment e on e.id = r.employment_id "
                + "join lateral (select j.manager_id from app.job_assignment j where j.employment_id = e.id and j.effective_from <= ? "
                + "  order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                + "join app.employment boss on boss.id = cur.manager_id "
                + "where boss.membership_id = ? and r.status = 'open' and r.probation_end - ?::int <= ? "
                + "and (r.manager_reminded_on is null or (r.probation_end < ? and r.manager_reminded_on < ?))",
                today, membershipId, recommendDays, today, today, today.minusDays(2))) {
            if (Sql.update(c, "update app.probation_review set manager_reminded_on = ? where id = ?::uuid "
                    + "and (manager_reminded_on is null or manager_reminded_on < ?)", today, r.get("id"), today) > 0) {
                Notifications.send(c, tenantId, membershipId, "probation_recommend_due", values(c, load(c, AuthService.uuid(r.get("id")))),
                        "performance");
            }
        }
        // As HR: a probation that has ended and is not decided, every day until it is.
        if (ExitService.hrMembers(c).contains(membershipId)) {
            for (Map<String, Object> r : Sql.query(c, "select r.id from app.probation_review r where r.status in ('open','recommended') "
                    + "and r.probation_end < ? and (r.hr_reminded_on is null or r.hr_reminded_on < ?)", today, today)) {
                if (Sql.update(c, "update app.probation_review set hr_reminded_on = ? where id = ?::uuid "
                        + "and (hr_reminded_on is null or hr_reminded_on < ?)", today, r.get("id"), today) > 0) {
                    Map<String, String> v = values(c, load(c, AuthService.uuid(r.get("id"))));
                    for (UUID hr : ExitService.hrMembers(c)) {
                        Notifications.send(c, tenantId, hr, "probation_overdue", v, "performance");
                    }
                }
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private static final String SUMMARY = "select r.id, r.employment_id, r.probation_end, r.status, r.recommendation, r.recommend_months, "
            + "r.recommended_at, r.decision, r.decided_at, r.confirmed_from, r.new_probation_end, r.exit_case_id, "
            + Perf.NAME + " as name, e.employee_code, e.joining_date, e.employment_type, e.membership_id, "
            + "j.designation, d.name as department, coalesce(mp.preferred_name, mp.legal_name) as manager, "
            + "(select count(*) from app.probation_checkin k where k.probation_review_id = r.id and k.written_at is not null) as checkins_written, "
            + "(select count(*) from app.probation_checkin k where k.probation_review_id = r.id) as checkins_total, "
            + "(select min(k.due_date) from app.probation_checkin k where k.probation_review_id = r.id and k.written_at is null) as next_checkin "
            + "from app.probation_review r join app.employment e on e.id = r.employment_id join app.person p on p.id = e.person_id "
            + Perf.CURRENT_JOB;

    private static Map<String, Object> summary(Map<String, Object> r, LocalDate today, int recommendDays) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("stage", stage(r, today, recommendDays));
        return out;
    }

    /**
     * Where a review stands, in one word for lists: checkins (check-ins running), recommend (the
     * recommendation is due), deciding (recommended, waiting for HR), overdue (the probation has ended
     * undecided), decided or cancelled.
     */
    static String stage(Map<String, Object> r, LocalDate today, int recommendDays) {
        String status = (String) r.get("status");
        if (Set.of("decided", "cancelled").contains(status)) {
            return status;
        }
        LocalDate end = LocalDate.parse((String) r.get("probationEnd"));
        if (end.isBefore(today)) {
            return "overdue";
        }
        if (status.equals("recommended")) {
            return "deciding";
        }
        return end.minusDays(recommendDays).isAfter(today) ? "checkins" : "recommend";
    }

    private Map<String, Object> view(Connection c, SessionCtx ctx, Map<String, Object> raw, LocalDate today) throws SQLException {
        UUID id = AuthService.uuid(raw.get("id"));
        UUID employment = AuthService.uuid(raw.get("employmentId"));
        Map<String, Object> r = Sql.one(c, SUMMARY + "where r.id = ?", id);
        Map<String, Object> out = summary(r, today, recommendDays(c));
        boolean support = ctx.isSupport();
        boolean open = Set.of("open", "recommended").contains((String) r.get("status"));
        boolean own = Perf.own(c, ctx, employment);
        boolean write = !own && !support && ctx.membershipId() != null && Perf.reaches(c, ctx, "performance.write", employment, today);
        out.put("checkins", checkins(c, ctx, id, false));
        out.put("recommendedBy", raw.get("recommendedBy") == null ? null
                : Sql.one(c, "select " + Perf.who("x.m") + " as who from (select ?::uuid as m) x", raw.get("recommendedBy")).get("who"));
        out.put("recommendationNote", support ? null : Perf.open(c, crypto, ctx.tenantId(), "probation_review", "recommendation_note_enc", id));
        out.put("decidedBy", raw.get("decidedBy") == null ? null
                : Sql.one(c, "select " + Perf.who("x.m") + " as who from (select ?::uuid as m) x", raw.get("decidedBy")).get("who"));
        out.put("decisionNote", support ? null : Perf.open(c, crypto, ctx.tenantId(), "probation_review", "decision_note_enc", id));
        out.put("decisionMonths", raw.get("decisionMonths"));
        out.put("letter", raw.get("letterId") == null ? null
                : Sql.one(c, "select id, title, document_id, issued_at from app.issued_letter where id = ?::uuid", raw.get("letterId")));
        out.put("canWrite", open && write);
        out.put("canDecide", open && !own && Perf.canManage(ctx));
        out.put("canEndEmployment", open && !own && Perf.canManage(ctx) && ctx.canAcrossOrganization("exit.manage"));
        Map<String, Object> e = Sql.one(c, "select * from app.employment where id = ?", employment);
        ExitDefaults.ensure(c, ctx.tenantId());
        int notice = ExitService.noticeDays(ExitService.settings(c), e, today);
        out.put("probationNoticeDays", notice);
        out.put("proposedLastDay", today.plusDays(notice).toString());
        out.put("history", Sql.query(c, "select id, probation_end, decision, decided_at, new_probation_end, confirmed_from "
                + "from app.probation_review where employment_id = ? and status = 'decided' and id <> ? order by probation_end", employment, id));
        return out;
    }

    private List<Map<String, Object>> checkins(Connection c, SessionCtx ctx, UUID reviewId, boolean mine) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> k : Sql.query(c, "select k.id, k.day, k.due_date, k.rating, k.written_at, k.replied_at, k.written_by, "
                + Perf.who("k.written_by") + " as written_by_name from app.probation_checkin k where k.probation_review_id = ? order by k.day",
                reviewId)) {
            boolean hidden = ctx.isSupport();
            k.put("note", hidden || k.get("writtenAt") == null ? null : Perf.open(c, crypto, ctx.tenantId(), "probation_checkin", "note_enc", k.get("id")));
            k.put("reply", hidden || k.get("repliedAt") == null ? null : Perf.open(c, crypto, ctx.tenantId(), "probation_checkin", "reply_enc", k.get("id")));
            if (mine) {
                k.remove("writtenBy");
                k.put("canReply", k.get("repliedAt") == null);
            }
            out.add(k);
        }
        return out;
    }

    private static List<Map<String, Object>> letters(Connection c, UUID employmentId) throws SQLException {
        return Sql.query(c, "select il.id, il.kind, il.title, il.document_id, il.issued_at from app.issued_letter il "
                + "join app.probation_review r on r.id = il.probation_review_id where r.employment_id = ? order by il.issued_at desc", employmentId);
    }

    /** An extension starts a new review for the new end, with a check-in every 30 days of the extension. */
    private static void startExtension(Connection c, UUID tenantId, UUID employment, LocalDate from, LocalDate to) throws SQLException {
        UUID review = AuthService.uuid(Sql.one(c, "insert into app.probation_review (tenant_id, employment_id, probation_end) values (?, ?, ?) "
                + "returning id", tenantId, employment, to).get("id"));
        for (int d = 30; from.plusDays(d).isBefore(to); d += 30) {
            Sql.update(c, "insert into app.probation_checkin (tenant_id, probation_review_id, day, due_date) values (?, ?, ?, ?)",
                    tenantId, review, (short) d, from.plusDays(d));
        }
    }

    private static Map<String, Object> load(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select * from app.probation_review where id = ?", id);
        if (r == null) {
            throw ApiException.notFound("The probation review");
        }
        return r;
    }

    private static Map<String, Object> lock(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select * from app.probation_review where id = ? for update", id);
        if (r == null) {
            throw ApiException.notFound("The probation review");
        }
        return r;
    }

    private static void requireSee(Connection c, SessionCtx ctx, Map<String, Object> r, LocalDate today) throws SQLException {
        if (!Perf.reaches(c, ctx, "performance.read", AuthService.uuid(r.get("employmentId")), today)) {
            throw ApiException.notFound("The probation review");   // as if it did not exist, to someone outside the team
        }
    }

    private static void requireWrite(Connection c, SessionCtx ctx, Map<String, Object> r, LocalDate today) throws SQLException {
        UUID employment = AuthService.uuid(r.get("employmentId"));
        requireSee(c, ctx, r, today);
        if (Perf.own(c, ctx, employment)) {
            throw new ApiException(409, "request.refused", "Nobody can write about their own probation");
        }
        if (!Perf.reaches(c, ctx, "performance.write", employment, today)) {
            throw ApiException.denied("performance.write");
        }
        if (!Set.of("open", "recommended").contains((String) r.get("status"))) {
            throw new ApiException(409, "probation.decided", "This probation has already been decided");
        }
    }

    private static UUID employeeMembership(Connection c, Map<String, Object> r) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?::uuid", r.get("employmentId"));
        return e == null ? null : AuthService.uuid(e.get("membershipId"));
    }

    private static Map<String, String> values(Connection c, Map<String, Object> r) throws SQLException {
        Map<String, String> v = ExitValues.forEmployment(c, AuthService.uuid(r.get("employmentId")), null);
        v.put("probation.end_date", Perf.date(r.get("probationEnd")));
        return v;
    }

    static Map<String, Object> settings(Connection c) throws SQLException {
        return Sql.one(c, "select probation_checkin_days, probation_recommend_days, updated_at from app.perf_settings");
    }

    private static Map<String, Object> settingsView(Connection c) throws SQLException {
        Map<String, Object> s = settings(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkinDays", days(s));
        out.put("recommendDays", s.get("probationRecommendDays"));
        out.put("updatedAt", s.get("updatedAt"));
        return out;
    }

    private static int recommendDays(Connection c) throws SQLException {
        Map<String, Object> s = settings(c);
        return s == null ? 21 : ((Number) s.get("probationRecommendDays")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> days(Map<String, Object> s) {
        List<Integer> out = new ArrayList<>();
        for (Object d : (List<Object>) s.get("probationCheckinDays")) {
            out.add(Integer.parseInt(d.toString()));
        }
        return out;
    }

    private static int months(Integer months) {
        if (months == null || months < 1 || months > 6) {
            throw ApiException.badRequest("Extend the probation by 1 to 6 months");
        }
        return months;
    }

    private static String months(int months) {
        return months + (months == 1 ? " month" : " months");
    }
}
