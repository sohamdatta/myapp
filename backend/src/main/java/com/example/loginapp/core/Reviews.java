package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Review cycles. HR sets a cycle up (who is in it, the form, the weights of goals and
 * competencies, and a date for each phase) and starts it, which creates one review for each
 * eligible employee with their manager on that day. The employee writes a self review; their
 * manager rates each goal and competency, answers the form and proposes the overall rating, which
 * is worked out from the ratings and can be overridden with a reason. The cycle then goes to
 * calibration, and HR releases it: each person reads their review and acknowledges it.
 */
public final class Reviews {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record CycleInput(String name, String periodStart, String periodEnd, Map<String, Object> appliesTo, String joinedBy, String formId,
                             Integer goalWeight, Boolean selfReview, String selfDue, String managerDue, String calibrationDue,
                             String releaseDue) {}

    public record Rating(String id, Integer rating, String comment) {}

    public record EntryInput(List<Rating> goalRatings, List<Rating> competencyRatings, Map<String, Object> answers, Integer overall,
                             String overrideReason, Boolean promotionRecommended, String promotionDesignation, Boolean submit) {}

    public record ReasonInput(String reason) {}

    public record PeopleInput(List<String> employmentIds, String reason) {}

    public record AckInput(String comment) {}

    public record FormInput(String name, List<Map<String, Object>> questions) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public Reviews(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public Reviews(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── cycles, for HR ──────────────────────────────────────────────────

    public List<Map<String, Object>> cycles(SessionCtx ctx) {
        Perf.requireSomeone(ctx, "performance.read");
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            return Sql.query(c, "select y.id, y.name, y.period_start, y.period_end, y.status, y.self_due, y.manager_due, y.release_due, "
                    + "(select count(*) from app.review r where r.cycle_id = y.id and r.status <> 'withdrawn') as people, "
                    + "(select count(*) from app.review r where r.cycle_id = y.id and r.status in ('calibration','ready','released','acknowledged')) "
                    + "as written from app.review_cycle y order by (y.status = 'closed'), y.period_start desc, y.created_at desc");
        });
    }

    public Map<String, Object> cycle(SessionCtx ctx, UUID id) {
        Perf.requireSomeone(ctx, "performance.read");
        if (!ctx.reachesEveryone("performance.read")) {
            throw ApiException.denied("performance.read");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> cycleView(c, ctx, id, today));
    }

    public Map<String, Object> createCycle(SessionCtx ctx, CycleInput in) {
        Perf.requireManage(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            Checked k = check(c, in, today);
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.review_cycle (tenant_id, name, period_start, period_end, applies_to, joined_by, "
                    + "form_version_id, goal_weight, self_review, self_due, manager_due, calibration_due, release_due, created_by) "
                    + "values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), k.name, k.start, k.end, k.rules.toJson(),
                    k.joinedBy, k.formVersion, k.goalWeight.shortValue(), k.self, k.selfDue, k.managerDue, k.calibrationDue, k.releaseDue,
                    ctx.membershipId()).get("id"));
            UserService.audit(c, ctx, "performance.cycle_created", "review_cycle", id, Map.of("name", k.name));
            return cycleView(c, ctx, id, today);
        });
    }

    public Map<String, Object> updateCycle(SessionCtx ctx, UUID id, CycleInput in) {
        Perf.requireManage(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = lockCycle(c, id);
            Checked k = check(c, in, today);
            if (!"draft".equals(y.get("status"))) {
                // Once started only the name and the phase dates change.
                Sql.update(c, "update app.review_cycle set name = ?, self_due = ?, manager_due = ?, calibration_due = ?, release_due = ? where id = ?",
                        k.name, k.selfDue, k.managerDue, k.calibrationDue, k.releaseDue, id);
            } else {
                Sql.update(c, "update app.review_cycle set name = ?, period_start = ?, period_end = ?, applies_to = ?::jsonb, joined_by = ?, "
                        + "form_version_id = ?, goal_weight = ?, self_review = ?, self_due = ?, manager_due = ?, calibration_due = ?, "
                        + "release_due = ? where id = ?", k.name, k.start, k.end, k.rules.toJson(), k.joinedBy, k.formVersion,
                        k.goalWeight.shortValue(), k.self, k.selfDue, k.managerDue, k.calibrationDue, k.releaseDue, id);
            }
            UserService.audit(c, ctx, "performance.cycle_changed", "review_cycle", id, Map.of("name", k.name));
            return cycleView(c, ctx, id, today);
        });
    }

    /** Starts the cycle: a review for each eligible employee, with their manager today, and everyone told. */
    public Map<String, Object> startCycle(SessionCtx ctx, UUID id) {
        Perf.requireManage(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = lockCycle(c, id);
            if (!"draft".equals(y.get("status"))) {
                throw new ApiException(409, "cycle.started", "This cycle has already started");
            }
            UUID scale = AuthService.uuid(Sql.one(c, "select rating_scale_id from app.perf_settings").get("ratingScaleId"));
            Sql.update(c, "update app.review_cycle set status = 'active', scale_id = ?, started_by = ?, started_at = now() where id = ?",
                    scale, ctx.membershipId(), id);
            AppliesTo rules = AppliesTo.parse((String) Sql.one(c, "select applies_to::text as a from app.review_cycle where id = ?", id).get("a"));
            int n = 0;
            for (Map<String, Object> e : eligible(c, rules, LocalDate.parse((String) y.get("joinedBy")), today)) {
                addReview(c, ctx, y, AuthService.uuid(e.get("id")), null, today);
                n++;
            }
            if (n == 0) {
                throw new ApiException(409, "cycle.empty", "Nobody matches this cycle's rules; change them before starting");
            }
            UserService.audit(c, ctx, "performance.cycle_started", "review_cycle", id, Map.of("reviews", n));
            return cycleView(c, ctx, id, today);
        });
    }

    /** HR adds people to a started cycle (a late joiner, someone missed by the rules), with a reason. */
    public Map<String, Object> addPeople(SessionCtx ctx, UUID id, PeopleInput in) {
        Perf.requireManage(ctx);
        String reason = Perf.text(in == null ? null : in.reason(), "why they are added", 5, 500);
        List<UUID> ids = in.employmentIds() == null ? List.of() : in.employmentIds().stream().map(s -> Perf.id(s, "person")).toList();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("Choose who to add");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = lockCycle(c, id);
            if (!"active".equals(y.get("status"))) {
                throw new ApiException(409, "cycle.state", "People are added while the cycle is active, before calibration");
            }
            for (UUID e : ids) {
                if (Sql.one(c, "select 1 from app.employment where id = ? and status = 'active'", e) == null) {
                    throw ApiException.notFound("One of the people");
                }
                if (Sql.one(c, "select 1 from app.review where cycle_id = ? and employment_id = ?", id, e) != null) {
                    throw new ApiException(409, "request.refused", "One of them is already in this cycle");
                }
                addReview(c, ctx, y, e, reason, today);
            }
            UserService.audit(c, ctx, "performance.cycle_people_added", "review_cycle", id, Map.of("added", ids.size(), "reason", reason));
            return cycleView(c, ctx, id, today);
        });
    }

    /** HR takes a review out of the cycle, with a reason. It is kept, marked withdrawn. */
    public Map<String, Object> withdraw(SessionCtx ctx, UUID reviewId, ReasonInput in) {
        Perf.requireManage(ctx);
        String reason = Perf.text(in == null ? null : in.reason(), "why", 5, 500);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockReview(c, reviewId);
            if (Set.of("released", "acknowledged", "withdrawn").contains((String) r.get("status"))) {
                throw new ApiException(409, "review.state", "A released or withdrawn review stays as it is");
            }
            Sql.update(c, "update app.review set status = 'withdrawn', withdrawn_reason = ? where id = ?", reason, reviewId);
            UserService.audit(c, ctx, "performance.review_withdrawn", "review", reviewId, Map.of("reason", reason));
            return cycleView(c, ctx, AuthService.uuid(r.get("cycleId")), today);
        });
    }

    /** HR moves a review past the self review, with a reason (the employee is away, say). */
    public Map<String, Object> moveOn(SessionCtx ctx, UUID reviewId, ReasonInput in) {
        Perf.requireManage(ctx);
        String reason = Perf.text(in == null ? null : in.reason(), "why", 5, 500);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockReview(c, reviewId);
            if (!"self_review".equals(r.get("status"))) {
                throw new ApiException(409, "review.state", "Only a review waiting for its self review is moved on");
            }
            Sql.update(c, "update app.review set status = 'manager_review', moved_on_reason = ? where id = ?", reason, reviewId);
            UserService.audit(c, ctx, "performance.review_moved_on", "review", reviewId, Map.of("reason", reason));
            notifyManager(c, ctx, r);
            return cycleView(c, ctx, AuthService.uuid(r.get("cycleId")), today);
        });
    }

    /** HR starts calibration: ratings can then be compared and changed. Reviews still being written can still be finished. */
    public Map<String, Object> startCalibration(SessionCtx ctx, UUID id) {
        Perf.requireManage(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = lockCycle(c, id);
            if (!"active".equals(y.get("status"))) {
                throw new ApiException(409, "cycle.state", "Calibration starts from an active cycle");
            }
            Sql.update(c, "update app.review_cycle set status = 'calibrating' where id = ?", id);
            UserService.audit(c, ctx, "performance.cycle_calibrating", "review_cycle", id, Map.of());
            return cycleView(c, ctx, id, today);
        });
    }

    /**
     * HR releases the cycle: every review is final at its calibrated rating (or the manager's), and
     * each person can read theirs. Nothing may still be being written.
     */
    public Map<String, Object> release(SessionCtx ctx, UUID id) {
        Perf.requireManage(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = lockCycle(c, id);
            if (!"calibrating".equals(y.get("status"))) {
                throw new ApiException(409, "cycle.state", "A cycle is released after calibration");
            }
            long pending = ((Number) Sql.one(c, "select count(*) as n from app.review where cycle_id = ? and status in "
                    + "('self_review','manager_review','skip_level')", id).get("n")).longValue();
            if (pending > 0) {
                throw new ApiException(409, "cycle.pending", pending + (pending == 1 ? " review is" : " reviews are")
                        + " still being written; finish, move on or withdraw them first");
            }
            String blocked = releaseBlocker(c, id);
            if (blocked != null) {
                throw new ApiException(409, "cycle.pending", blocked);
            }
            Sql.update(c, "update app.review set status = 'released', final_level = coalesce(calibrated_level, manager_level), released_at = now() "
                    + "where cycle_id = ? and status in ('calibration','ready')", id);
            Sql.update(c, "update app.review_cycle set status = 'released', released_by = ?, released_at = now() where id = ?", ctx.membershipId(), id);
            UserService.audit(c, ctx, "performance.cycle_released", "review_cycle", id, Map.of());
            for (Map<String, Object> r : Sql.query(c, "select * from app.review where cycle_id = ? and status = 'released'", id)) {
                Notifications.send(c, ctx.tenantId(), membership(c, AuthService.uuid(r.get("employmentId"))), "review_released",
                        values(c, r, y), "my-performance");
            }
            return cycleView(c, ctx, id, today);
        });
    }

    /** What still stops the release, or null: an open calibration session. */
    static String releaseBlocker(Connection c, UUID cycleId) throws SQLException {
        return Calibration.releaseBlocker(c, cycleId);
    }

    public Map<String, Object> closeCycle(SessionCtx ctx, UUID id) {
        Perf.requireManage(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = lockCycle(c, id);
            if (!"released".equals(y.get("status"))) {
                throw new ApiException(409, "cycle.state", "A cycle is closed once it is released");
            }
            Sql.update(c, "update app.review_cycle set status = 'closed', closed_at = now() where id = ?", id);
            UserService.audit(c, ctx, "performance.cycle_closed", "review_cycle", id, Map.of());
            return cycleView(c, ctx, id, today);
        });
    }

    // ── reviews ─────────────────────────────────────────────────────────

    /** Reviews the caller writes as a manager, in cycles that are not closed. */
    public List<Map<String, Object>> toWrite(SessionCtx ctx) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> me = Perf.ownEmployment(c, ctx);
            if (me == null) {
                return List.<Map<String, Object>>of();
            }
            return Sql.query(c, LIST + "where r.manager_id = ?::uuid and y.status <> 'closed' and r.status <> 'withdrawn' "
                    + "order by y.period_start desc, (r.status = 'manager_review') desc, " + Perf.NAME, me.get("id"));
        });
    }

    /** The caller's own reviews, newest first: the current one to write, and released ones to read. */
    public List<Map<String, Object>> mine(SessionCtx ctx) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> me = Perf.ownEmployment(c, ctx);
            if (me == null) {
                return List.<Map<String, Object>>of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, LIST + "where r.employment_id = ?::uuid and r.status <> 'withdrawn' "
                    + "order by y.period_start desc, y.created_at desc", me.get("id"))) {
                if (!Set.of("released", "acknowledged").contains((String) r.get("status"))) {
                    r.remove("finalLevel");
                }
                out.add(r);
            }
            return out;
        });
    }

    /** One review, as the caller may see it: the employee, their manager, or HR. */
    public Map<String, Object> review(SessionCtx ctx, UUID id) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx, lockless(c, id), today));
    }

    /** The employee's self review, or the manager's part: saved as a draft, or sent. */
    public Map<String, Object> saveEntry(SessionCtx ctx, UUID reviewId, String role, EntryInput in) {
        Perf.requireMember(ctx);
        if (!Set.of("self", "manager").contains(role)) {
            throw ApiException.notFound("The review");
        }
        if (in == null) {
            throw ApiException.badRequest("Nothing to save");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockReview(c, reviewId);
            Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?::uuid", r.get("cycleId"));
            Who w = who(c, ctx, r);
            boolean self = role.equals("self");
            if (self ? !w.own : !(w.manager || w.hr) || w.own) {
                throw ApiException.notFound("The review");
            }
            String expected = self ? "self_review" : "manager_review";
            if (!expected.equals(r.get("status")) || !Set.of("active", "calibrating").contains((String) y.get("status"))) {
                throw new ApiException(409, "review.state", self ? "Your self review is not open" : "This review is not waiting for the manager");
            }
            Context k = context(c, r, y);
            List<Map<String, Object>> goals = ratings(in.goalRatings(), k.goalIds, k.levels, "goal");
            List<Map<String, Object>> comps = self ? List.of() : ratings(in.competencyRatings(), k.competencyIds, k.levels, "competency");
            Map<String, Object> answers = answers(in.answers(), k.questions, role);
            boolean submit = Boolean.TRUE.equals(in.submit());
            Integer overall = null;
            String overrideReason = null;
            BigDecimal proposed = null;
            if (!self) {
                proposed = proposed(goals, comps, k, ((Number) y.get("goalWeight")).intValue());
                overall = in.overall();
                if (overall != null && !k.levels.contains(overall)) {
                    throw ApiException.badRequest("The overall rating must be on the scale");
                }
                if (submit && overall == null) {
                    overall = proposed == null ? null : proposed.setScale(0, RoundingMode.HALF_UP).intValue();
                }
                if (overall != null && proposed != null && overall != proposed.setScale(0, RoundingMode.HALF_UP).intValue()) {
                    overrideReason = Perf.text(in.overrideReason(), "why the overall rating differs from the one worked out", 20, 1000);
                }
            }
            if (submit) {
                requireComplete(goals, comps, answers, k, role, overall);
            }
            boolean promote = !self && Boolean.TRUE.equals(in.promotionRecommended());
            String designation = promote ? Perf.text(in.promotionDesignation(), "the designation recommended", 1, 80) : null;
            Map<String, Object> entry = Sql.one(c, "select id from app.review_entry where review_id = ? and role = ?", reviewId, role);
            String goalJson = Json.write(goals);
            String compJson = Json.write(comps);
            byte[] sealed = Perf.seal(crypto, ctx, Json.write(answers));
            if (entry == null) {
                Sql.update(c, "insert into app.review_entry (tenant_id, review_id, role, reviewer_id, goal_ratings, competency_ratings, answers_enc, "
                        + "overall, promotion_recommended, promotion_designation, submitted_at) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, "
                        + (submit ? "now()" : "null") + ")", ctx.tenantId(), reviewId, role, ctx.membershipId(), goalJson, compJson, sealed,
                        overall == null ? null : overall.shortValue(), promote, designation);
            } else {
                Sql.update(c, "update app.review_entry set reviewer_id = ?, goal_ratings = ?::jsonb, competency_ratings = ?::jsonb, answers_enc = ?, "
                        + "overall = ?, promotion_recommended = ?, promotion_designation = ?, updated_at = now(), submitted_at = "
                        + (submit ? "now()" : "null") + " where id = ?::uuid", ctx.membershipId(), goalJson, compJson, sealed,
                        overall == null ? null : overall.shortValue(), promote, designation, entry.get("id"));
            }
            if (!self) {
                Sql.update(c, "update app.review set proposed_rating = ?, override_reason_enc = ? where id = ?", proposed,
                        Perf.seal(crypto, ctx, overrideReason), reviewId);
            }
            if (submit) {
                if (self) {
                    Sql.update(c, "update app.review set status = 'manager_review' where id = ?", reviewId);
                    notifyManager(c, ctx, r);
                } else {
                    Sql.update(c, "update app.review set status = ?, manager_level = ? where id = ?", afterManager(c, r), overall.shortValue(),
                            reviewId);
                }
                UserService.audit(c, ctx, "performance.review_" + role + "_submitted", "review", reviewId,
                        self ? Map.of() : Map.of("overall", overall, "proposed", proposed == null ? "" : proposed.toString()));
            }
            return view(c, ctx, lockless(c, reviewId), today);
        });
    }

    /** Where a review goes once the manager has written it: the skip-level manager (stage 6), or calibration. */
    static String afterManager(Connection c, Map<String, Object> r) throws SQLException {
        return "calibration";
    }

    /** The manager sends a self review back to the employee, once, with a note. */
    public Map<String, Object> returnSelf(SessionCtx ctx, UUID reviewId, ReasonInput in) {
        Perf.requireMember(ctx);
        String note = Perf.text(in == null ? null : in.reason(), "what should change", 10, 1000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockReview(c, reviewId);
            Who w = who(c, ctx, r);
            if (!(w.manager || w.hr) || w.own) {
                throw ApiException.notFound("The review");
            }
            if (!"manager_review".equals(r.get("status")) || r.get("selfReturnedAt") != null
                    || Sql.one(c, "select 1 from app.review_entry where review_id = ? and role = 'self' and submitted_at is not null", reviewId) == null) {
                throw new ApiException(409, "review.state", "A self review can be returned once, after it is sent");
            }
            Sql.update(c, "update app.review_entry set submitted_at = null, updated_at = now() where review_id = ? and role = 'self'", reviewId);
            Sql.update(c, "update app.review set status = 'self_review', self_returned_at = now(), self_return_note = ? where id = ?", note, reviewId);
            UserService.audit(c, ctx, "performance.review_self_returned", "review", reviewId, Map.of());
            Map<String, String> v = values(c, r, Sql.one(c, "select * from app.review_cycle where id = ?::uuid", r.get("cycleId")));
            v.put("note", note);
            Notifications.send(c, ctx.tenantId(), membership(c, AuthService.uuid(r.get("employmentId"))), "review_self_returned", v, "my-performance");
            return view(c, ctx, lockless(c, reviewId), today);
        });
    }

    /** The employee acknowledges their released review, with a comment if they want. */
    public Map<String, Object> acknowledge(SessionCtx ctx, UUID reviewId, AckInput in) {
        Perf.requireMember(ctx);
        String comment = Perf.text(in == null ? null : in.comment(), "your comment", 0, 2000);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockReview(c, reviewId);
            if (!who(c, ctx, r).own) {
                throw ApiException.notFound("The review");
            }
            if (!"released".equals(r.get("status"))) {
                throw new ApiException(409, "review.state", "Only a released review is acknowledged, once");
            }
            Sql.update(c, "update app.review set status = 'acknowledged', acknowledged_at = now(), employee_comment_enc = ? where id = ?",
                    Perf.seal(crypto, ctx, comment), reviewId);
            UserService.audit(c, ctx, "performance.review_acknowledged", "review", reviewId, Map.of());
            return view(c, ctx, lockless(c, reviewId), today);
        });
    }

    // ── review forms, for HR ────────────────────────────────────────────

    public List<Map<String, Object>> forms(SessionCtx ctx) {
        Perf.requireSomeone(ctx, "performance.read");
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> f : Sql.query(c, "select f.id, f.name, f.active, v.id as version_id, v.version, v.questions::text as questions "
                    + "from app.review_form f join lateral (select * from app.review_form_version v where v.form_id = f.id order by v.version desc "
                    + "limit 1) v on true order by f.created_at")) {
                f.put("questions", Json.parse((String) f.get("questions")));
                out.add(f);
            }
            return out;
        });
    }

    /** Saves a form as a new version; cycles already started keep the version they started with. */
    public List<Map<String, Object>> saveForm(SessionCtx ctx, UUID id, FormInput in) {
        Perf.requireManage(ctx);
        String name = Perf.text(in == null ? null : in.name(), "the form's name", 1, 60);
        List<Map<String, Object>> questions = checkQuestions(in.questions());
        db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            UUID form = id;
            int version = 1;
            if (id == null) {
                form = AuthService.uuid(Sql.one(c, "insert into app.review_form (tenant_id, name) values (?, ?) returning id", ctx.tenantId(), name).get("id"));
            } else {
                if (Sql.update(c, "update app.review_form set name = ? where id = ?", name, id) == 0) {
                    throw ApiException.notFound("The form");
                }
                version = ((Number) Sql.one(c, "select max(version) + 1 as v from app.review_form_version where form_id = ?", id).get("v")).intValue();
            }
            Sql.update(c, "insert into app.review_form_version (tenant_id, form_id, version, questions, created_by) values (?, ?, ?, ?::jsonb, ?)",
                    ctx.tenantId(), form, version, Json.write(questions), ctx.membershipId());
            UserService.audit(c, ctx, "performance.form_saved", "review_form", form, Map.of("name", name, "version", version));
            return null;
        });
        return forms(ctx);
    }

    // ── reminders ───────────────────────────────────────────────────────

    /** Reviews due within 3 days that the person still has to write, at most once a day each. */
    static void remind(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        if (Sql.one(c, "select 1 from app.review_cycle where status in ('active','calibrating') limit 1") == null) {
            return;
        }
        for (Map<String, Object> r : Sql.query(c, "select r.*, y.self_due, y.manager_due from app.review r join app.review_cycle y on y.id = r.cycle_id "
                + "join app.employment e on e.id = r.employment_id left join app.employment boss on boss.id = r.manager_id "
                + "where y.status in ('active','calibrating') and (r.reminded_on is null or r.reminded_on < ?) and ("
                + "  (r.status = 'self_review' and e.membership_id = ? and y.self_due <= ?) or "
                + "  (r.status = 'manager_review' and boss.membership_id = ? and y.manager_due <= ?))",
                today, membershipId, today.plusDays(3), membershipId, today.plusDays(3))) {
            if (Sql.update(c, "update app.review set reminded_on = ? where id = ?::uuid and (reminded_on is null or reminded_on < ?)",
                    today, r.get("id"), today) > 0) {
                Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?::uuid", r.get("cycleId"));
                Map<String, String> v = values(c, r, y);
                v.put("review.due", Perf.date("self_review".equals(r.get("status")) ? y.get("selfDue") : y.get("managerDue")));
                Notifications.send(c, tenantId, membershipId, "review_due", v, "self_review".equals(r.get("status")) ? "my-performance" : "performance");
            }
        }
    }

    // ── the view of one review ──────────────────────────────────────────

    private record Who(boolean own, boolean manager, boolean hr, boolean reader) {}

    private static Who who(Connection c, SessionCtx ctx, Map<String, Object> r) throws SQLException {
        boolean own = Perf.own(c, ctx, AuthService.uuid(r.get("employmentId")));
        Map<String, Object> me = Perf.ownEmployment(c, ctx);
        boolean manager = me != null && me.get("id").equals(r.get("managerId"));
        boolean hr = Perf.canManage(ctx);
        boolean reader = ctx.reachesEveryone("performance.read");
        return new Who(own, manager, hr, reader);
    }

    private record Context(List<Map<String, Object>> goals, List<Map<String, Object>> competencies, List<Map<String, Object>> levels0,
                           List<Integer> levels, List<Map<String, Object>> questions, List<String> goalIds, List<String> competencyIds) {}

    @SuppressWarnings("unchecked")
    private static Context context(Connection c, Map<String, Object> r, Map<String, Object> y) throws SQLException {
        UUID employment = AuthService.uuid(r.get("employmentId"));
        List<Map<String, Object>> goals = Sql.query(c, "select g.id, g.title, g.description, g.weight, g.measure, g.target, g.unit, g.status, "
                + "g.progress, g.progress_value from app.goal g where g.employment_id = ? and g.status <> 'dropped' "
                + "and g.period_start <= ?::date and g.period_end >= ?::date order by g.created_at", employment, y.get("periodEnd"), y.get("periodStart"));
        List<Map<String, Object>> competencies = PerformanceSetup.competenciesFor(c, employment);
        UUID scaleId = AuthService.uuid(y.get("scaleId") != null ? y.get("scaleId")
                : Sql.one(c, "select rating_scale_id from app.perf_settings").get("ratingScaleId"));
        List<Map<String, Object>> levels = Sql.query(c, "select value, label, description from app.rating_level where scale_id = ? order by value desc",
                scaleId);
        List<Map<String, Object>> questions = (List<Map<String, Object>>) Json.parse((String) Sql.one(c,
                "select questions::text as q from app.review_form_version where id = ?::uuid", y.get("formVersionId")).get("q"));
        return new Context(goals, competencies, levels, levels.stream().map(l -> ((Number) l.get("value")).intValue()).toList(), questions,
                goals.stream().map(g -> (String) g.get("id")).toList(), competencies.stream().map(k -> (String) k.get("id")).toList());
    }

    private Map<String, Object> view(Connection c, SessionCtx ctx, Map<String, Object> r, LocalDate today) throws SQLException {
        Who w = who(c, ctx, r);
        if (!w.own && !w.manager && !w.reader) {
            throw ApiException.notFound("The review");
        }
        UUID id = AuthService.uuid(r.get("id"));
        Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?::uuid", r.get("cycleId"));
        Context k = context(c, r, y);
        String status = (String) r.get("status");
        boolean released = Set.of("released", "acknowledged").contains(status);
        // The employee sees the manager's part only once released; a reader who is not HR sees ratings, not words.
        boolean seesManager = !w.own || released;
        boolean seesWords = (w.own || w.manager || w.hr) && !ctx.isSupport();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("status", status);
        out.put("cycle", Map.of("id", y.get("id"), "name", y.get("name"), "periodStart", y.get("periodStart"), "periodEnd", y.get("periodEnd"),
                "status", y.get("status"), "goalWeight", y.get("goalWeight"), "selfReview", y.get("selfReview"),
                "selfDue", y.get("selfDue") == null ? "" : y.get("selfDue"), "managerDue", y.get("managerDue") == null ? "" : y.get("managerDue")));
        out.put("employee", Sql.one(c, "select e.id, " + Perf.NAME + " as name, e.employee_code, j.designation, d.name as department "
                + "from app.employment e join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB + "where e.id = ?::uuid", r.get("employmentId")));
        out.put("manager", r.get("managerId") == null ? null : Sql.one(c, "select " + Perf.NAME + " as name from app.employment e "
                + "join app.person p on p.id = e.person_id where e.id = ?::uuid", r.get("managerId")).get("name"));
        out.put("goals", k.goals);
        out.put("competencies", k.competencies);
        out.put("scale", k.levels0);
        out.put("questions", k.questions.stream().filter(q -> !"peer".equals(q.get("for"))).toList());
        out.put("youAre", w.own ? "employee" : w.manager ? "manager" : "hr");
        Map<String, Object> self = entry(c, ctx, id, "self", w.own || seesWords, w.own);
        out.put("self", self != null && (w.own || self.get("submittedAt") != null) ? self : null);
        Map<String, Object> manager = seesManager ? entry(c, ctx, id, "manager", seesWords, w.manager || w.hr) : null;
        out.put("managerEntry", manager);
        if (seesManager) {
            out.put("proposedRating", r.get("proposedRating"));
            out.put("managerLevel", r.get("managerLevel"));
            out.put("overrideReason", seesWords && !w.own ? Perf.open(c, crypto, ctx.tenantId(), "review", "override_reason_enc", id) : null);
            out.put("calibratedLevel", r.get("calibratedLevel"));
            out.put("finalLevel", r.get("finalLevel"));
            out.put("why", manager == null ? List.of() : why(manager, k, ((Number) y.get("goalWeight")).intValue()));
        }
        out.put("selfReturnNote", r.get("selfReturnNote"));
        out.put("releasedAt", r.get("releasedAt"));
        out.put("acknowledgedAt", r.get("acknowledgedAt"));
        out.put("employeeComment", released && seesWords ? Perf.open(c, crypto, ctx.tenantId(), "review", "employee_comment_enc", id) : null);
        boolean open = Set.of("active", "calibrating").contains((String) y.get("status"));
        out.put("canWriteSelf", w.own && open && "self_review".equals(status));
        out.put("canWriteManager", (w.manager || w.hr) && !w.own && open && "manager_review".equals(status));
        out.put("canReturnSelf", (w.manager || w.hr) && !w.own && "manager_review".equals(status) && r.get("selfReturnedAt") == null
                && self != null && self.get("submittedAt") != null);
        out.put("canAcknowledge", w.own && "released".equals(status));
        out.put("feedbackCount", w.manager || w.hr ? ((Number) Sql.one(c, "select count(*) as n from app.feedback where receiver_id = ?::uuid "
                + "and withdrawn_at is null and created_at::date between ?::date and ?::date", r.get("employmentId"), y.get("periodStart"),
                y.get("periodEnd")).get("n")).intValue() : 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entry(Connection c, SessionCtx ctx, UUID review, String role, boolean words, boolean ownEdit) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select id, role, goal_ratings::text as goal_ratings, competency_ratings::text as competency_ratings, "
                + "overall, promotion_recommended, promotion_designation, submitted_at, updated_at, " + Perf.who("reviewer_id") + " as reviewer "
                + "from app.review_entry where review_id = ? and role = ? order by updated_at desc limit 1", review, role);
        if (e == null) {
            return null;
        }
        List<Map<String, Object>> goals = (List<Map<String, Object>>) Json.parse((String) e.get("goalRatings"));
        List<Map<String, Object>> comps = (List<Map<String, Object>>) Json.parse((String) e.get("competencyRatings"));
        if (!words) {
            goals.forEach(g -> g.remove("comment"));
            comps.forEach(g -> g.remove("comment"));
        }
        e.put("goalRatings", goals);
        e.put("competencyRatings", comps);
        String answers = words ? Perf.open(c, crypto, ctx.tenantId(), "review_entry", "answers_enc", e.get("id")) : null;
        e.put("answers", answers == null ? Map.of() : Json.parse(answers));
        return e;
    }

    // ── rating ──────────────────────────────────────────────────────────

    /** The overall rating worked out from the ratings: the goals' weighted average and the competencies' average, by the cycle's weights. */
    static BigDecimal proposed(List<Map<String, Object>> goals, List<Map<String, Object>> comps, Context k, int goalWeight) {
        Map<String, Integer> weights = new HashMap<>();
        for (Map<String, Object> g : k.goals) {
            weights.put((String) g.get("id"), ((Number) g.get("weight")).intValue());
        }
        BigDecimal goalSum = BigDecimal.ZERO;
        int weightSum = 0;
        for (Map<String, Object> g : goals) {
            Integer w = weights.get((String) g.get("id"));
            if (g.get("rating") != null && w != null) {
                goalSum = goalSum.add(BigDecimal.valueOf(((Number) g.get("rating")).intValue() * (long) w));
                weightSum += w;
            }
        }
        BigDecimal goalPart = weightSum == 0 ? null : goalSum.divide(BigDecimal.valueOf(weightSum), 4, RoundingMode.HALF_UP);
        int n = 0;
        BigDecimal compSum = BigDecimal.ZERO;
        for (Map<String, Object> g : comps) {
            if (g.get("rating") != null) {
                compSum = compSum.add(BigDecimal.valueOf(((Number) g.get("rating")).intValue()));
                n++;
            }
        }
        BigDecimal compPart = n == 0 ? null : compSum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        if (goalPart == null && compPart == null) {
            return null;
        }
        if (compPart == null || k.competencies.isEmpty()) {
            return goalPart == null ? null : goalPart.setScale(2, RoundingMode.HALF_UP);
        }
        if (goalPart == null) {
            return compPart.setScale(2, RoundingMode.HALF_UP);
        }
        return goalPart.multiply(BigDecimal.valueOf(goalWeight)).add(compPart.multiply(BigDecimal.valueOf(100 - goalWeight)))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** How the overall rating was worked out, step by step, as attendance shows how a day was counted. */
    @SuppressWarnings("unchecked")
    static List<String> why(Map<String, Object> manager, Context k, int goalWeight) {
        List<String> out = new ArrayList<>();
        List<Map<String, Object>> goals = (List<Map<String, Object>>) manager.get("goalRatings");
        List<Map<String, Object>> comps = (List<Map<String, Object>>) manager.get("competencyRatings");
        Map<String, Map<String, Object>> byId = new HashMap<>();
        k.goals.forEach(g -> byId.put((String) g.get("id"), g));
        int total = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> g : goals) {
            Map<String, Object> goal = byId.get((String) g.get("id"));
            if (goal != null && g.get("rating") != null) {
                int w = ((Number) goal.get("weight")).intValue();
                int rating = ((Number) g.get("rating")).intValue();
                out.add(goal.get("title") + ": " + rating + " × " + w + "%");
                total += w;
                sum = sum.add(BigDecimal.valueOf((long) rating * w));
            }
        }
        boolean hasComps = !k.competencies.isEmpty() && comps.stream().anyMatch(x -> x.get("rating") != null);
        if (total > 0) {
            out.add("Goals: " + sum.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP) + (total == 100 ? "" : " (weights rescaled from " + total + ")"));
        }
        if (hasComps) {
            BigDecimal avg = BigDecimal.valueOf(comps.stream().filter(x -> x.get("rating") != null).mapToInt(x -> ((Number) x.get("rating")).intValue())
                    .average().orElse(0)).setScale(2, RoundingMode.HALF_UP);
            out.add("Competencies, the average: " + avg);
            if (total > 0) {
                out.add("Overall: goals × " + goalWeight + "% + competencies × " + (100 - goalWeight) + "%");
            }
        }
        BigDecimal p = proposed(goals, comps, k, goalWeight);
        if (p != null) {
            out.add("Worked out: " + p + ", rounded to " + p.setScale(0, RoundingMode.HALF_UP));
        }
        if (manager.get("overall") != null && p != null && ((Number) manager.get("overall")).intValue() != p.setScale(0, RoundingMode.HALF_UP).intValue()) {
            out.add("The manager gave " + manager.get("overall") + " instead, with a reason");
        }
        return out;
    }

    // ── checks ──────────────────────────────────────────────────────────

    private static List<Map<String, Object>> ratings(List<Rating> in, List<String> allowed, List<Integer> levels, String what) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Rating r : in) {
            if (r == null || r.id() == null || !allowed.contains(r.id()) || !seen.add(r.id())) {
                throw ApiException.badRequest("One of the " + what + "s rated is not part of this review");
            }
            if (r.rating() != null && !levels.contains(r.rating())) {
                throw ApiException.badRequest("Ratings must be on the scale, from " + levels.get(levels.size() - 1) + " to " + levels.get(0));
            }
            String comment = Perf.text(r.comment(), "a comment", 0, 2000);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("rating", r.rating());
            m.put("comment", comment);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> answers(Map<String, Object> in, List<Map<String, Object>> questions, String role) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (Map<String, Object> q : questions) {
            if (!role.equals(q.get("for")) || !in.containsKey((String) q.get("id"))) {
                continue;
            }
            Object v = in.get((String) q.get("id"));
            if ("rating".equals(q.get("type"))) {
                if (v != null && !(v instanceof Number n && n.intValue() >= 1 && n.intValue() <= 7)) {
                    throw ApiException.badRequest("Answer \"" + q.get("text") + "\" with a rating");
                }
                out.put((String) q.get("id"), v);
            } else {
                out.put((String) q.get("id"), Perf.text(v == null ? null : v.toString(), "an answer", 0, 5000));
            }
        }
        return out;
    }

    private static void requireComplete(List<Map<String, Object>> goals, List<Map<String, Object>> comps, Map<String, Object> answers, Context k,
                                        String role, Integer overall) {
        for (String g : k.goalIds) {
            if (goals.stream().noneMatch(x -> g.equals(x.get("id")) && x.get("rating") != null)) {
                throw new ApiException(400, "review.incomplete", "Rate every goal before sending");
            }
        }
        if (role.equals("manager")) {
            for (String id : k.competencyIds) {
                if (comps.stream().noneMatch(x -> id.equals(x.get("id")) && x.get("rating") != null)) {
                    throw new ApiException(400, "review.incomplete", "Rate every competency before sending");
                }
            }
            if (overall == null) {
                throw new ApiException(400, "review.incomplete", "Give the overall rating before sending");
            }
        }
        for (Map<String, Object> q : k.questions) {
            if (role.equals(q.get("for")) && Boolean.TRUE.equals(q.get("required"))) {
                Object v = answers.get((String) q.get("id"));
                if (v == null || v.toString().isBlank()) {
                    throw new ApiException(400, "review.incomplete", "Answer \"" + q.get("text") + "\" before sending");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checkQuestions(List<Map<String, Object>> in) {
        if (in == null || in.isEmpty() || in.size() > 30) {
            throw ApiException.badRequest("A form has 1 to 30 questions");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        int n = 0;
        for (Map<String, Object> q : in) {
            n++;
            String text = Perf.text(q == null ? null : (String) q.get("text"), "question " + n, 3, 300);
            String forWhom = Perf.oneOf((String) q.get("for"), List.of("self", "manager", "peer"), "Say who answers question " + n);
            String type = Perf.oneOf(q.get("type") == null ? "text" : (String) q.get("type"), List.of("text", "rating"),
                    "A question is answered in words or with a rating");
            String id = q.get("id") == null || ((String) q.get("id")).isBlank() ? "q" + n : ((String) q.get("id")).trim();
            if (!id.matches("[a-z0-9_]{1,40}") || !ids.add(id)) {
                id = "q" + n + "_" + Integer.toHexString(text.hashCode() & 0xffff);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("for", forWhom);
            m.put("type", type);
            m.put("text", text);
            m.put("required", Boolean.TRUE.equals(q.get("required")));
            out.add(m);
        }
        return out;
    }

    private record Checked(String name, LocalDate start, LocalDate end, AppliesTo rules, LocalDate joinedBy, UUID formVersion, Integer goalWeight,
                           boolean self, LocalDate selfDue, LocalDate managerDue, LocalDate calibrationDue, LocalDate releaseDue) {}

    private static Checked check(Connection c, CycleInput in, LocalDate today) throws SQLException {
        if (in == null) {
            throw ApiException.badRequest("Describe the cycle");
        }
        String name = Perf.text(in.name(), "a name for the cycle", 3, 80);
        LocalDate start = in.periodStart() == null || in.periodStart().isBlank() ? Goals.periodStart(today, Goals.startMonth(c))
                : Perf.date(in.periodStart(), "start of the period");
        LocalDate end = in.periodEnd() == null || in.periodEnd().isBlank() ? start.plusYears(1).minusDays(1) : Perf.date(in.periodEnd(), "end of the period");
        if (!end.isAfter(start) || end.isAfter(start.plusYears(2))) {
            throw ApiException.badRequest("The period ends after it starts, within two years");
        }
        AppliesTo rules = in.appliesTo() == null ? AppliesTo.from(Map.of("employment_type", List.of("permanent", "probation", "fixed_term")))
                : AppliesTo.from(in.appliesTo());
        LocalDate joinedBy = in.joinedBy() == null || in.joinedBy().isBlank() ? end.minusDays(90) : Perf.date(in.joinedBy(), "joining cutoff");
        UUID form;
        if (in.formId() == null || in.formId().isBlank()) {
            form = AuthService.uuid(Sql.one(c, "select v.id from app.review_form f join app.review_form_version v on v.form_id = f.id "
                    + "where f.active order by f.created_at, v.version desc limit 1").get("id"));
        } else {
            Map<String, Object> f = Sql.one(c, "select v.id from app.review_form_version v where v.form_id = ? order by v.version desc limit 1",
                    Perf.id(in.formId(), "form"));
            if (f == null) {
                throw ApiException.notFound("The form");
            }
            form = AuthService.uuid(f.get("id"));
        }
        int goalWeight = in.goalWeight() == null ? (Sql.one(c, "select 1 from app.competency_set where active limit 1") == null ? 100 : 70) : in.goalWeight();
        if (goalWeight < 0 || goalWeight > 100 || goalWeight % 5 != 0) {
            throw ApiException.badRequest("The weight of goals is 0 to 100%, in steps of 5; competencies take the rest");
        }
        LocalDate selfDue = optional(in.selfDue(), "self review due date");
        LocalDate managerDue = optional(in.managerDue(), "manager review due date");
        LocalDate calibrationDue = optional(in.calibrationDue(), "calibration due date");
        LocalDate releaseDue = optional(in.releaseDue(), "release date");
        List<LocalDate> order = new ArrayList<>();
        for (LocalDate d : new LocalDate[] {selfDue, managerDue, calibrationDue, releaseDue}) {
            if (d != null) {
                order.add(d);
            }
        }
        for (int i = 1; i < order.size(); i++) {
            if (order.get(i).isBefore(order.get(i - 1))) {
                throw ApiException.badRequest("The phases go in order: self review, manager review, calibration, release");
            }
        }
        return new Checked(name, start, end, rules, joinedBy, form, goalWeight, in.selfReview() == null || in.selfReview(), selfDue, managerDue,
                calibrationDue, releaseDue);
    }

    private static LocalDate optional(String value, String what) {
        return value == null || value.isBlank() ? null : Perf.date(value, what);
    }

    // ── internals ───────────────────────────────────────────────────────

    private static final String LIST = "select r.id, r.status, r.employment_id, r.final_level, r.manager_level, r.calibrated_level, "
            + "y.id as cycle_id, y.name as cycle, y.status as cycle_status, y.period_start, y.period_end, y.self_due, y.manager_due, "
            + Perf.NAME + " as name, e.employee_code, j.designation, d.name as department "
            + "from app.review r join app.review_cycle y on y.id = r.cycle_id join app.employment e on e.id = r.employment_id "
            + "join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB;

    private static List<Map<String, Object>> eligible(Connection c, AppliesTo rules, LocalDate joinedBy, LocalDate today) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : Sql.query(c, "select e.id, e.employment_type, j.department_id, j.designation, "
                + "(select a.work_location_id from app.attendance_assignment a where a.membership_id = e.membership_id order by a.effective_from desc limit 1) "
                + "as work_location_id from app.employment e left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "and j.effective_from <= ? order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "where e.status = 'active' and e.joining_date <= ?", today, joinedBy)) {
            AppliesTo.Subject s = new AppliesTo.Subject(AuthService.uuid(e.get("departmentId")), AuthService.uuid(e.get("workLocationId")),
                    (String) e.get("employmentType"), (String) e.get("designation"));
            if (rules.matches(s)) {
                out.add(e);
            }
        }
        return out;
    }

    private static void addReview(Connection c, SessionCtx ctx, Map<String, Object> y, UUID employment, String reason, LocalDate today)
            throws SQLException {
        UUID manager = managerOf(c, employment, today);
        UUID skip = manager == null ? null : managerOf(c, manager, today);
        boolean self = (Boolean) Sql.one(c, "select self_review from app.review_cycle where id = ?::uuid", y.get("id")).get("selfReview");
        Map<String, Object> r = Sql.one(c, "insert into app.review (tenant_id, cycle_id, employment_id, manager_id, skip_level_id, status, added_reason) "
                + "values (?, ?::uuid, ?, ?, ?, ?, ?) returning *", ctx.tenantId(), y.get("id"), employment, manager,
                skip == null || skip.equals(employment) ? null : skip, self ? "self_review" : "manager_review", reason);
        Map<String, Object> cycle = Sql.one(c, "select * from app.review_cycle where id = ?::uuid", y.get("id"));
        if (self) {
            Map<String, String> v = values(c, r, cycle);
            v.put("review.due", Perf.date(cycle.get("selfDue")));
            Notifications.send(c, ctx.tenantId(), membership(c, employment), "review_self_open", v, "my-performance");
        } else {
            notifyManager(c, ctx, r);
        }
    }

    private static void notifyManager(Connection c, SessionCtx ctx, Map<String, Object> r) throws SQLException {
        if (r.get("managerId") == null) {
            return;
        }
        Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?::uuid", r.get("cycleId"));
        Map<String, String> v = values(c, r, y);
        v.put("review.due", Perf.date(y.get("managerDue")));
        Notifications.send(c, ctx.tenantId(), membership(c, AuthService.uuid(r.get("managerId"))), "review_manager_open", v, "performance");
    }

    private static UUID managerOf(Connection c, UUID employment, LocalDate on) throws SQLException {
        Map<String, Object> j = Sql.one(c, "select j.manager_id from app.job_assignment j join app.employment m on m.id = j.manager_id "
                + "where j.employment_id = ? and j.effective_from <= ? and m.status = 'active' order by j.effective_from desc, j.created_at desc limit 1",
                employment, on);
        return j == null ? null : AuthService.uuid(j.get("managerId"));
    }

    private Map<String, Object> cycleView(Connection c, SessionCtx ctx, UUID id, LocalDate today) throws SQLException {
        Map<String, Object> y = Sql.one(c, "select y.*, y.applies_to::text as rules, f.name as form, v.version as form_version, f.id as form_id "
                + "from app.review_cycle y join app.review_form_version v on v.id = y.form_version_id join app.review_form f on f.id = v.form_id "
                + "where y.id = ?", id);
        if (y == null) {
            throw ApiException.notFound("The cycle");
        }
        y.put("appliesTo", AppliesTo.parse((String) y.remove("rules")).rules());
        List<Map<String, Object>> reviews = Sql.query(c, LIST + "where r.cycle_id = ? order by (r.status = 'withdrawn'), " + Perf.NAME, id);
        for (Map<String, Object> r : reviews) {
            Map<String, Object> raw = Sql.one(c, "select manager_id, withdrawn_reason, moved_on_reason, proposed_rating from app.review where id = ?::uuid",
                    r.get("id"));
            r.put("manager", raw.get("managerId") == null ? null : Sql.one(c, "select " + Perf.NAME + " as name from app.employment e "
                    + "join app.person p on p.id = e.person_id where e.id = ?::uuid", raw.get("managerId")).get("name"));
            r.put("proposedRating", raw.get("proposedRating"));
            r.put("withdrawnReason", raw.get("withdrawnReason"));
            r.put("movedOnReason", raw.get("movedOnReason"));
        }
        y.put("reviews", reviews);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : reviews) {
            counts.merge((String) r.get("status"), 1L, Long::sum);
        }
        y.put("counts", counts);
        UUID scale = AuthService.uuid(y.get("scaleId") != null ? y.get("scaleId") : Sql.one(c, "select rating_scale_id from app.perf_settings")
                .get("ratingScaleId"));
        y.put("scale", Sql.query(c, "select value, label from app.rating_level where scale_id = ? order by value desc", scale));
        if ("draft".equals(y.get("status"))) {
            @SuppressWarnings("unchecked")
            AppliesTo rules = AppliesTo.from((Map<String, ?>) y.get("appliesTo"));
            y.put("eligibleCount", eligible(c, rules, LocalDate.parse((String) y.get("joinedBy")), today).size());
        }
        y.put("canManage", Perf.canManage(ctx));
        return y;
    }

    private static Map<String, Object> lockCycle(Connection c, UUID id) throws SQLException {
        Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ? for update", id);
        if (y == null) {
            throw ApiException.notFound("The cycle");
        }
        return y;
    }

    private static Map<String, Object> lockReview(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select * from app.review where id = ? for update", id);
        if (r == null) {
            throw ApiException.notFound("The review");
        }
        return r;
    }

    private static Map<String, Object> lockless(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select * from app.review where id = ?", id);
        if (r == null) {
            throw ApiException.notFound("The review");
        }
        return r;
    }

    private static UUID membership(Connection c, UUID employment) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?", employment);
        return e == null ? null : AuthService.uuid(e.get("membershipId"));
    }

    static Map<String, String> values(Connection c, Map<String, Object> r, Map<String, Object> y) throws SQLException {
        Map<String, String> v = ExitValues.forEmployment(c, AuthService.uuid(r.get("employmentId")), null);
        v.put("review.cycle", (String) y.get("name"));
        v.put("review.due", "");
        return v;
    }
}
