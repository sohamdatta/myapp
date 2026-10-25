package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Review outcomes. Once a cycle is released, HR prepares one outcome for each final rating: the
 * increment the merit table suggests (changed with a note), a promotion where the manager
 * recommended one, and a bonus. HR sends them for approval, and someone else with
 * performance.approve approves them, which records the pay revision and the job change through the
 * same rules as entering them by hand, and issues the increment and promotion letters.
 */
public final class Outcomes {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record OutcomeInput(BigDecimal incrementPct, Boolean promotion, String newDesignation, BigDecimal bonusAmount, String effectiveFrom,
                               String note) {}

    public record BatchInput(List<String> ids, String note) {}

    private final Db db;
    private final DocumentService documents;
    private final Clock clock;

    public Outcomes(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public Outcomes(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.documents = new DocumentService(db, crypto);
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    static boolean canApprove(SessionCtx ctx) {
        return ctx.canAcrossOrganization("performance.approve") && ctx.membershipId() != null && !ctx.isSupport();
    }

    private static void requireSeeing(SessionCtx ctx) {
        if (!Perf.canManage(ctx) && !canApprove(ctx)) {
            throw ApiException.denied("performance.manage");
        }
    }

    // ── reading ─────────────────────────────────────────────────────────

    public Map<String, Object> list(SessionCtx ctx, UUID cycleId) {
        requireSeeing(ctx);
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx, cycleId));
    }

    // ── preparing ───────────────────────────────────────────────────────

    /** One draft outcome for every released review that has none, from the merit table and the manager's recommendation. */
    public Map<String, Object> prepare(SessionCtx ctx, UUID cycleId) {
        Perf.requireManage(ctx);
        LocalDate effective = today().withDayOfMonth(1).plusMonths(1);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ? for update", cycleId);
            if (y == null) {
                throw ApiException.notFound("The cycle");
            }
            if (!Set.of("released", "closed").contains((String) y.get("status"))) {
                throw new ApiException(409, "cycle.state", "Outcomes are prepared once the cycle is released");
            }
            Map<String, Object> merit = merit(c);
            int made = 0;
            for (Map<String, Object> r : Sql.query(c, "select r.* from app.review r join app.employment e on e.id = r.employment_id "
                    + "where r.cycle_id = ? and r.status in ('released','acknowledged') and e.status in ('active','onboarding') "
                    + "and not exists (select 1 from app.review_outcome o where o.review_id = r.id)", cycleId)) {
                UUID employment = AuthService.uuid(r.get("employmentId"));
                int level = ((Number) r.get("finalLevel")).intValue();
                Map<String, Object> pay = pay(c, employment, effective);
                BigDecimal pct = pay == null ? BigDecimal.ZERO : suggested(merit, level);
                Map<String, Object> promo = Sql.one(c, "select promotion_designation from app.review_entry where review_id = ?::uuid "
                        + "and role = 'manager' and promotion_recommended and submitted_at is not null limit 1", r.get("id"));
                String designation = promo == null ? null : (String) promo.get("promotionDesignation");
                BigDecimal current = pay == null ? null : (BigDecimal) pay.get("annualAmount");
                Sql.update(c, "insert into app.review_outcome (tenant_id, review_id, cycle_id, employment_id, level, current_annual, structure_id, "
                        + "increment_pct, new_annual, promotion, new_designation, effective_from, prepared_by) "
                        + "values (?, ?::uuid, ?, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), r.get("id"), cycleId, employment, (short) level,
                        current, pay == null ? null : pay.get("structureId"), pct, raised(current, pct), designation != null, designation, effective,
                        ctx.membershipId());
                made++;
            }
            UserService.audit(c, ctx, "performance.outcomes_prepared", "review_cycle", cycleId, Map.of("count", made));
            return view(c, ctx, cycleId);
        });
    }

    /** HR changes a draft: the increment, a promotion, the bonus and the date. An increment off the merit table needs a note. */
    public Map<String, Object> update(SessionCtx ctx, UUID id, OutcomeInput in) {
        Perf.requireManage(ctx);
        if (in == null) {
            throw ApiException.badRequest("Nothing to change");
        }
        BigDecimal pct = in.incrementPct() == null ? BigDecimal.ZERO : in.incrementPct();
        if (pct.signum() < 0 || pct.compareTo(new BigDecimal(50)) > 0 || pct.scale() > 2) {
            throw ApiException.badRequest("Give an increment from 0 to 50%");
        }
        boolean promotion = Boolean.TRUE.equals(in.promotion());
        String designation = promotion ? Perf.text(in.newDesignation(), "the new designation", 1, 80) : null;
        BigDecimal bonus = in.bonusAmount();
        if (bonus != null && (bonus.signum() < 0 || bonus.compareTo(new BigDecimal("100000000")) > 0)) {
            throw ApiException.badRequest("Give a bonus of zero or more");
        }
        LocalDate effective = Perf.date(in.effectiveFrom(), "date it takes effect");
        if (effective.getDayOfMonth() != 1) {
            throw ApiException.badRequest("An outcome takes effect from the first of a month");
        }
        String note = Perf.text(in.note(), "a note", 0, 500);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> o = lock(c, id);
            if (!"draft".equals(o.get("status"))) {
                throw new ApiException(409, "outcome.state", "Only a draft outcome changes; this one is " + o.get("status"));
            }
            UUID employment = AuthService.uuid(o.get("employmentId"));
            if (Perf.own(c, ctx, employment)) {
                throw new ApiException(409, "outcome.own", "Your own outcome is prepared by someone else");
            }
            Map<String, Object> pay = pay(c, employment, effective);
            if (pay == null && pct.signum() > 0) {
                throw new ApiException(409, "outcome.no_pay", "They have no pay recorded on that date, so there is nothing to increase");
            }
            BigDecimal suggested = suggested(merit(c), ((Number) o.get("level")).intValue());
            if (pct.compareTo(suggested) != 0 && note == null) {
                throw ApiException.badRequest("The merit table suggests " + pct(suggested) + "; write a note on why this differs");
            }
            BigDecimal current = pay == null ? null : (BigDecimal) pay.get("annualAmount");
            Sql.update(c, "update app.review_outcome set current_annual = ?, structure_id = ?::uuid, increment_pct = ?, new_annual = ?, promotion = ?, "
                    + "new_designation = ?, bonus_amount = ?, effective_from = ?, note = ?, prepared_by = ?, prepared_at = now() where id = ?",
                    current, pay == null ? null : pay.get("structureId"), pct, raised(current, pct), promotion, designation, bonus, effective, note,
                    ctx.membershipId(), id);
            return view(c, ctx, AuthService.uuid(o.get("cycleId")));
        });
    }

    /** HR sends drafts for approval (the ones named, or all of them). The approvers are told. */
    public Map<String, Object> submit(SessionCtx ctx, UUID cycleId, BatchInput in) {
        Perf.requireManage(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> picked = pick(c, cycleId, in, "draft");
            if (named(in)) {
                for (Map<String, Object> o : picked) {
                    if (Perf.own(c, ctx, AuthService.uuid(o.get("employmentId")))) {
                        throw new ApiException(409, "outcome.own", "Your own outcome is sent by someone else");
                    }
                }
            } else {
                // Sending them all leaves your own for someone else.
                picked = keep(picked, o -> !Perf.own(c, ctx, AuthService.uuid(o.get("employmentId"))), "outcome.own",
                        "The only draft left is your own; someone else sends it");
            }
            for (Map<String, Object> o : picked) {
                Sql.update(c, "update app.review_outcome set status = 'submitted', submitted_by = ?, submitted_at = now(), return_note = null "
                        + "where id = ?::uuid", ctx.membershipId(), o.get("id"));
            }
            UserService.audit(c, ctx, "performance.outcomes_submitted", "review_cycle", cycleId, Map.of("count", picked.size()));
            Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?", cycleId);
            Map<String, String> v = new java.util.HashMap<>();
            v.put("review.cycle", (String) y.get("name"));
            v.put("note", picked.size() + (picked.size() == 1 ? " outcome" : " outcomes"));
            for (UUID m : approvers(c, ctx.membershipId())) {
                Notifications.send(c, ctx.tenantId(), m, "outcomes_submitted", v, "performance");
            }
            return view(c, ctx, cycleId);
        });
    }

    /** An approver sends outcomes back to HR, with a note. */
    public Map<String, Object> sendBack(SessionCtx ctx, UUID cycleId, BatchInput in) {
        if (!canApprove(ctx)) {
            throw ApiException.denied("performance.approve");
        }
        String note = Perf.text(in == null ? null : in.note(), "what should change", 5, 500);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> picked = pick(c, cycleId, in, "submitted");
            Set<UUID> told = new java.util.HashSet<>();
            for (Map<String, Object> o : picked) {
                Sql.update(c, "update app.review_outcome set status = 'draft', submitted_at = null, return_note = ? where id = ?::uuid", note, o.get("id"));
                told.add(AuthService.uuid(o.get("submittedBy")));
            }
            UserService.audit(c, ctx, "performance.outcomes_returned", "review_cycle", cycleId, Map.of("count", picked.size(), "note", note));
            Map<String, Object> y = Sql.one(c, "select name from app.review_cycle where id = ?", cycleId);
            for (UUID m : told) {
                if (m != null) {
                    Map<String, String> v = new java.util.HashMap<>();
                    v.put("review.cycle", (String) y.get("name"));
                    v.put("note", note);
                    Notifications.send(c, ctx.tenantId(), m, "outcomes_returned", v, "performance");
                }
            }
            return view(c, ctx, cycleId);
        });
    }

    /**
     * An approver approves outcomes (the ones named, or all sent): each pay revision and job change
     * is recorded and the letters issued. One that cannot be recorded stops the lot, and says whose.
     */
    public Map<String, Object> approve(SessionCtx ctx, UUID cycleId, BatchInput in) {
        if (!canApprove(ctx)) {
            throw ApiException.denied("performance.approve");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?", cycleId);
            List<Map<String, Object>> picked = pick(c, cycleId, in, "submitted");
            if (!named(in)) {
                // Approving them all leaves what you sent, and your own, for someone else.
                picked = keep(picked, o -> !ctx.membershipId().toString().equals(o.get("submittedBy"))
                        && !Perf.own(c, ctx, AuthService.uuid(o.get("employmentId"))), "outcome.same_person",
                        "You sent every outcome waiting, so someone else approves them");
            }
            for (Map<String, Object> o : picked) {
                String name = name(c, AuthService.uuid(o.get("employmentId")));
                if (ctx.membershipId().toString().equals(o.get("submittedBy"))) {
                    throw new ApiException(409, "outcome.same_person", "You sent " + name + "'s outcome for approval, so someone else approves it");
                }
                if (Perf.own(c, ctx, AuthService.uuid(o.get("employmentId")))) {
                    throw new ApiException(409, "outcome.own", "Your own outcome is approved by someone else");
                }
                try {
                    apply(c, ctx, y, o, today);
                } catch (ApiException e) {
                    throw new ApiException(e.status(), e.code(), name + ": " + e.getMessage());
                }
            }
            UserService.audit(c, ctx, "performance.outcomes_approved", "review_cycle", cycleId, Map.of("count", picked.size()));
            return view(c, ctx, cycleId);
        });
    }

    private void apply(Connection c, SessionCtx ctx, Map<String, Object> y, Map<String, Object> o, LocalDate today) throws SQLException {
        UUID id = AuthService.uuid(o.get("id"));
        UUID employment = AuthService.uuid(o.get("employmentId"));
        LocalDate effective = LocalDate.parse((String) o.get("effectiveFrom"));
        BigDecimal pct = (BigDecimal) o.get("incrementPct");
        UUID compensation = null;
        UUID job = null;
        String reason = "Annual review: " + y.get("name");
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);
        }
        if (o.get("newAnnual") != null && pct.signum() > 0) {
            Map<String, Object> row = PayrollPay.reviseIn(c, ctx, employment, new PayrollPay.ReviseInput(effective.toString(),
                    (String) o.get("structureId"), (BigDecimal) o.get("newAnnual"), reason), today);
            compensation = AuthService.uuid(row.get("id"));
        }
        if (Boolean.TRUE.equals(o.get("promotion"))) {
            Map<String, Object> current = Sql.one(c, "select department_id, manager_id from app.job_assignment where employment_id = ? "
                    + "and effective_from <= ? order by effective_from desc, created_at desc limit 1", employment, effective);
            job = EmployeeService.changeJobIn(c, ctx, employment, effective, (String) o.get("newDesignation"),
                    current == null ? null : AuthService.uuid(current.get("departmentId")),
                    current == null ? null : AuthService.uuid(current.get("managerId")), "Promotion: " + y.get("name"));
        }
        Sql.update(c, "update app.review_outcome set status = 'applied', approved_by = ?, approved_at = now(), compensation_id = ?, "
                + "job_assignment_id = ? where id = ?", ctx.membershipId(), compensation, job, id);
        Map<String, String> v = values(c, y, o);
        if (compensation != null) {
            PerformanceLetters.issue(c, ctx, documents, employment, "increment", v, "review_outcome_id", id);
        }
        if (job != null) {
            PerformanceLetters.issue(c, ctx, documents, employment, "promotion", v, "review_outcome_id", id);
        }
    }

    /** The letters of the caller's own applied outcomes. */
    public List<Map<String, Object>> myLetters(SessionCtx ctx) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> me = Perf.ownEmployment(c, ctx);
            if (me == null) {
                return List.<Map<String, Object>>of();
            }
            return Sql.query(c, "select l.id, l.title, l.kind, l.document_id, l.issued_at, y.name as cycle from app.issued_letter l "
                    + "join app.review_outcome o on o.id = l.review_outcome_id join app.review_cycle y on y.id = o.cycle_id "
                    + "where o.employment_id = ?::uuid order by l.issued_at desc", me.get("id"));
        });
    }

    // ── internals ───────────────────────────────────────────────────────

    private Map<String, Object> view(Connection c, SessionCtx ctx, UUID cycleId) throws SQLException {
        Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?", cycleId);
        if (y == null) {
            throw ApiException.notFound("The cycle");
        }
        Map<String, Object> merit = merit(c);
        List<Map<String, Object>> rows = Sql.query(c, "select o.id, o.review_id, o.employment_id, o.level, l.label as rating, o.current_annual, "
                + "o.increment_pct, o.new_annual, o.promotion, o.new_designation, o.bonus_amount, o.effective_from, o.note, o.status, o.return_note, "
                + "o.submitted_at, o.approved_at, " + Perf.who("o.submitted_by") + " as submitted_by_name, o.submitted_by, "
                + Perf.who("o.approved_by") + " as approved_by_name, " + Perf.NAME + " as name, e.employee_code, j.designation, d.name as department, "
                + "(select count(*) from app.issued_letter il where il.review_outcome_id = o.id) as letters "
                + "from app.review_outcome o join app.employment e on e.id = o.employment_id join app.person p on p.id = e.person_id "
                + Perf.CURRENT_JOB + "left join app.rating_level l on l.scale_id = ?::uuid and l.value = o.level "
                + "where o.cycle_id = ? order by o.level desc, " + Perf.NAME, y.get("scaleId"), cycleId);
        BigDecimal before = BigDecimal.ZERO;
        BigDecimal after = BigDecimal.ZERO;
        BigDecimal bonus = BigDecimal.ZERO;
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            r.put("suggestedPct", suggested(merit, ((Number) r.get("level")).intValue()));
            r.put("own", Perf.own(c, ctx, AuthService.uuid(r.get("employmentId"))));
            r.put("sentByYou", ctx.membershipId() != null && ctx.membershipId().toString().equals(r.remove("submittedBy")));
            counts.merge((String) r.get("status"), 1L, Long::sum);
            if (r.get("currentAnnual") != null) {
                before = before.add((BigDecimal) r.get("currentAnnual"));
                after = after.add(r.get("newAnnual") == null ? (BigDecimal) r.get("currentAnnual") : (BigDecimal) r.get("newAnnual"));
            }
            if (r.get("bonusAmount") != null) {
                bonus = bonus.add((BigDecimal) r.get("bonusAmount"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cycle", Map.of("id", y.get("id"), "name", y.get("name"), "status", y.get("status")));
        out.put("outcomes", rows);
        out.put("counts", counts);
        out.put("totals", Map.of("before", before, "after", after, "increase", after.subtract(before), "bonus", bonus,
                "increasePct", before.signum() == 0 ? BigDecimal.ZERO
                        : after.subtract(before).multiply(new BigDecimal(100)).divide(before, 2, RoundingMode.HALF_UP)));
        out.put("waiting", ((Number) Sql.one(c, "select count(*) as n from app.review r join app.employment e on e.id = r.employment_id "
                + "where r.cycle_id = ? and r.status in ('released','acknowledged') and e.status in ('active','onboarding') "
                + "and not exists (select 1 from app.review_outcome o where o.review_id = r.id)", cycleId).get("n")).intValue());
        out.put("canPrepare", Perf.canManage(ctx) && Set.of("released", "closed").contains((String) y.get("status")));
        out.put("canApprove", canApprove(ctx));
        return out;
    }

    private static List<Map<String, Object>> pick(Connection c, UUID cycleId, BatchInput in, String status) throws SQLException {
        List<Map<String, Object>> all = Sql.query(c, "select * from app.review_outcome where cycle_id = ? and status = ? order by prepared_at for update",
                cycleId, status);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> wanted = in == null || in.ids() == null || in.ids().isEmpty() ? null : Set.copyOf(in.ids());
        for (Map<String, Object> o : all) {
            if (wanted == null || wanted.contains((String) o.get("id"))) {
                out.add(o);
            }
        }
        if (wanted != null && out.size() != wanted.size()) {
            throw new ApiException(409, "outcome.state", "Some of those outcomes are not " + ("draft".equals(status) ? "drafts" : "waiting for approval"));
        }
        if (out.isEmpty()) {
            throw new ApiException(409, "outcome.none", "draft".equals(status) ? "There are no draft outcomes to send" : "There are no outcomes waiting");
        }
        return out;
    }

    private static boolean named(BatchInput in) {
        return in != null && in.ids() != null && !in.ids().isEmpty();
    }

    private interface Test {
        boolean ok(Map<String, Object> o) throws SQLException;
    }

    private static List<Map<String, Object>> keep(List<Map<String, Object>> all, Test test, String code, String none) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : all) {
            if (test.ok(o)) {
                out.add(o);
            }
        }
        if (out.isEmpty()) {
            throw new ApiException(409, code, none);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merit(Connection c) throws SQLException {
        return (Map<String, Object>) Json.parse((String) Sql.one(c, "select merit_table::text as m from app.perf_settings").get("m"));
    }

    private static BigDecimal suggested(Map<String, Object> merit, int level) {
        Object v = merit.get(String.valueOf(level));
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    /** Yearly pay with an increment, to the whole rupee; null without pay to raise. */
    static BigDecimal raised(BigDecimal current, BigDecimal pct) {
        if (current == null) {
            return null;
        }
        return current.multiply(BigDecimal.ONE.add(pct.divide(new BigDecimal(100), 6, RoundingMode.HALF_UP))).setScale(0, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The pay in force on a day: the latest revision from on or before it. */
    private static Map<String, Object> pay(Connection c, UUID employment, LocalDate on) throws SQLException {
        return Sql.one(c, "select annual_amount, structure_id from app.compensation where employment_id = ? and superseded_at is null "
                + "and valid_from <= ? order by valid_from desc limit 1", employment, on);
    }

    private static String pct(BigDecimal p) {
        return p.stripTrailingZeros().toPlainString() + "%";
    }

    private static List<UUID> approvers(Connection c, UUID except) throws SQLException {
        List<UUID> out = new ArrayList<>();
        for (Map<String, Object> m : Sql.query(c, "select distinct m.id from app.membership m join app.membership_role mr on mr.membership_id = m.id "
                + "join app.role_permission rp on rp.role_id = mr.role_id where m.status = 'active' and rp.permission_code = 'performance.approve' "
                + "and rp.scope = 'tenant' and m.id <> ? order by m.id", except)) {
            out.add(AuthService.uuid(m.get("id")));
        }
        return out;
    }

    private static String name(Connection c, UUID employment) throws SQLException {
        return (String) Sql.one(c, "select " + Perf.NAME + " as name from app.employment e join app.person p on p.id = e.person_id where e.id = ?",
                employment).get("name");
    }

    private static Map<String, Object> lock(Connection c, UUID id) throws SQLException {
        Map<String, Object> o = Sql.one(c, "select * from app.review_outcome where id = ? for update", id);
        if (o == null) {
            throw ApiException.notFound("The outcome");
        }
        return o;
    }

    private static Map<String, String> values(Connection c, Map<String, Object> y, Map<String, Object> o) throws SQLException {
        Map<String, String> v = ExitValues.forEmployment(c, AuthService.uuid(o.get("employmentId")), null);
        v.put("review.cycle", (String) y.get("name"));
        v.put("review.rating", Calibration.label(c, y, ((Number) o.get("level")).intValue()));
        v.put("outcome.increment_pct", pct((BigDecimal) o.get("incrementPct")));
        v.put("outcome.new_annual", o.get("newAnnual") == null ? "" : PayrollEngine.rupees((BigDecimal) o.get("newAnnual")));
        v.put("outcome.new_designation", o.get("newDesignation") == null ? "" : (String) o.get("newDesignation"));
        v.put("outcome.bonus", o.get("bonusAmount") == null ? "" : PayrollEngine.rupees((BigDecimal) o.get("bonusAmount")));
        v.put("outcome.effective_from", Perf.date(o.get("effectiveFrom")));
        return v;
    }
}
