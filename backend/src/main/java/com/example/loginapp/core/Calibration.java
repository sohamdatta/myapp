package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calibration. Once a cycle is calibrating, HR groups its written reviews into sessions (one
 * department, or everyone left), compares the spread of ratings with the guide distribution, and
 * changes a rating where the group agrees, always with a reason the manager is told about. Closing
 * a session makes its reviews ready; the cycle cannot be released while a session is open.
 */
public final class Calibration {

    public record SessionInput(String name, String departmentId) {}

    public record ChangeInput(Integer level, String reason) {}

    public record NotesInput(String notes) {}

    public record SettingsInput(Map<String, Object> distributionGuide, Map<String, Object> meritTable) {}

    private final Db db;
    private final Crypto crypto;

    public Calibration(Db db, Crypto crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    // ── a cycle's calibration ───────────────────────────────────────────

    /** The cycle's sessions, the spread of all its written ratings against the guide, and what is not in a session yet. */
    public Map<String, Object> overview(SessionCtx ctx, UUID cycleId) {
        Perf.requireManage(ctx);
        return db.inTenant(ctx.tenantId(), c -> overview(c, cycleId));
    }

    private Map<String, Object> overview(Connection c, UUID cycleId) throws SQLException {
        Map<String, Object> y = cycle(c, cycleId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cycle", Map.of("id", y.get("id"), "name", y.get("name"), "status", y.get("status")));
        out.put("sessions", Sql.query(c, "select s.id, s.name, s.status, s.department_id, d.name as department, s.created_at, s.closed_at, "
                + "(select count(*) from app.review r where r.calibration_session_id = s.id and r.status <> 'withdrawn') "
                + "as people, (select count(distinct x.review_id) from app.calibration_change x where x.session_id = s.id) as changed "
                + "from app.calibration_session s left join app.department d on d.id = s.department_id where s.cycle_id = ? "
                + "order by s.created_at", cycleId));
        out.put("unassigned", ((Number) Sql.one(c, "select count(*) as n from app.review where cycle_id = ? and status = 'calibration' "
                + "and calibration_session_id is null", cycleId).get("n")).intValue());
        out.put("stillWriting", ((Number) Sql.one(c, "select count(*) as n from app.review where cycle_id = ? and status in "
                + "('self_review','manager_review','skip_level')", cycleId).get("n")).intValue());
        out.put("distribution", distribution(c, y, Sql.query(c, "select manager_level, calibrated_level from app.review where cycle_id = ? "
                + "and status in ('calibration','ready','released','acknowledged') and manager_level is not null", cycleId)));
        out.put("departments", Sql.query(c, "select id, name from app.department order by lower(name)"));
        out.put("canChange", "calibrating".equals(y.get("status")));
        return out;
    }

    /** HR opens a session for one department's written reviews, or for every written review not yet in a session. */
    public Map<String, Object> createSession(SessionCtx ctx, UUID cycleId, SessionInput in) {
        Perf.requireManage(ctx);
        String name = Perf.text(in == null ? null : in.name(), "a name for the session", 3, 80);
        UUID department = in.departmentId() == null || in.departmentId().isBlank() ? null : Perf.id(in.departmentId(), "department");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ? for update", cycleId);
            if (y == null) {
                throw ApiException.notFound("The cycle");
            }
            if (!"calibrating".equals(y.get("status"))) {
                throw new ApiException(409, "cycle.state", "Sessions are opened while the cycle is calibrating");
            }
            if (department != null && Sql.one(c, "select 1 from app.department where id = ?", department) == null) {
                throw ApiException.badRequest("Choose the department");
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.calibration_session (tenant_id, cycle_id, name, department_id, created_by) "
                    + "values (?, ?, ?, ?, ?) returning id", ctx.tenantId(), cycleId, name, department, ctx.membershipId()).get("id"));
            if (takeIn(c, id) == 0) {
                throw new ApiException(409, "calibration.empty", department == null
                        ? "Every written review is already in a session"
                        : "No written review in that department is waiting for calibration");
            }
            UserService.audit(c, ctx, "performance.calibration_opened", "calibration_session", id, Map.of("name", name));
            return session(c, ctx, id);
        });
    }

    /** Takes in the session's written reviews not yet in a session; returns how many it took. */
    private static int takeIn(Connection c, UUID sessionId) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select cycle_id, department_id from app.calibration_session where id = ?", sessionId);
        return Sql.update(c, "update app.review r set calibration_session_id = ? where r.cycle_id = ?::uuid and r.status = 'calibration' "
                + "and r.calibration_session_id is null and (?::uuid is null or (select j.department_id from app.job_assignment j "
                + "where j.employment_id = r.employment_id and j.effective_from <= current_date order by j.effective_from desc, j.created_at desc "
                + "limit 1) = ?::uuid)", sessionId, s.get("cycleId"), s.get("departmentId"), s.get("departmentId"));
    }

    public Map<String, Object> session(SessionCtx ctx, UUID id) {
        Perf.requireManage(ctx);
        return db.inTenant(ctx.tenantId(), c -> session(c, ctx, id));
    }

    /** Takes in reviews written since the session opened. */
    public Map<String, Object> refresh(SessionCtx ctx, UUID id) {
        Perf.requireManage(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            lockOpen(c, id);
            int taken = takeIn(c, id);
            Map<String, Object> out = session(c, ctx, id);
            out.put("taken", taken);
            return out;
        });
    }

    /** Changes one rating, with why. The manager is told. */
    public Map<String, Object> change(SessionCtx ctx, UUID sessionId, UUID reviewId, ChangeInput in) {
        Perf.requireManage(ctx);
        String reason = Perf.text(in == null ? null : in.reason(), "why the rating changes", 20, 1000);
        if (in.level() == null) {
            throw ApiException.badRequest("Choose the rating");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> s = lockOpen(c, sessionId);
            Map<String, Object> r = Sql.one(c, "select * from app.review where id = ? and calibration_session_id = ? for update", reviewId, sessionId);
            if (r == null) {
                throw ApiException.notFound("The review");
            }
            if (Perf.own(c, ctx, AuthService.uuid(r.get("employmentId")))) {
                throw new ApiException(409, "calibration.own", "Your own rating is calibrated by someone else");
            }
            if (!"calibration".equals(r.get("status"))) {
                throw new ApiException(409, "review.state", "This review is not waiting for calibration");
            }
            Map<String, Object> y = cycle(c, AuthService.uuid(s.get("cycleId")));
            if (Sql.one(c, "select 1 from app.rating_level where scale_id = ?::uuid and value = ?", y.get("scaleId"), in.level().shortValue()) == null) {
                throw ApiException.badRequest("Choose a rating on the cycle's scale");
            }
            int from = level(r);
            if (from == in.level()) {
                throw ApiException.badRequest("That is already the rating");
            }
            Sql.update(c, "insert into app.calibration_change (tenant_id, session_id, review_id, from_level, to_level, reason_enc, changed_by) "
                    + "values (?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), sessionId, reviewId, (short) from, in.level().shortValue(),
                    Perf.seal(crypto, ctx, reason), ctx.membershipId());
            // Back to the manager's own rating is no calibration at all.
            boolean back = r.get("managerLevel") != null && ((Number) r.get("managerLevel")).intValue() == in.level();
            Sql.update(c, "update app.review set calibrated_level = ? where id = ?", back ? null : in.level().shortValue(), reviewId);
            UserService.audit(c, ctx, "performance.rating_calibrated", "review", reviewId, Map.of("from", from, "to", in.level()));
            if (r.get("managerId") != null) {
                Map<String, String> v = Reviews.values(c, r, y);
                v.put("review.rating", label(c, y, in.level()));
                Notifications.send(c, ctx.tenantId(), membership(c, AuthService.uuid(r.get("managerId"))), "review_calibrated", v, "performance");
            }
            return session(c, ctx, sessionId);
        });
    }

    public Map<String, Object> saveNotes(SessionCtx ctx, UUID id, NotesInput in) {
        Perf.requireManage(ctx);
        String notes = Perf.text(in == null ? null : in.notes(), "the notes", 0, 4000);
        return db.inTenant(ctx.tenantId(), c -> {
            lockOpen(c, id);
            Sql.update(c, "update app.calibration_session set notes_enc = ? where id = ?", Perf.seal(crypto, ctx, notes), id);
            return session(c, ctx, id);
        });
    }

    /** Closes the session: its reviews are ready to release at the ratings agreed. */
    public Map<String, Object> close(SessionCtx ctx, UUID id) {
        Perf.requireManage(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            lockOpen(c, id);
            Sql.update(c, "update app.review set status = 'ready' where calibration_session_id = ? and status = 'calibration'", id);
            Sql.update(c, "update app.calibration_session set status = 'closed', closed_by = ?, closed_at = now() where id = ?", ctx.membershipId(), id);
            UserService.audit(c, ctx, "performance.calibration_closed", "calibration_session", id, Map.of());
            return session(c, ctx, id);
        });
    }

    /** What stops a cycle's release: an open session. */
    static String releaseBlocker(Connection c, UUID cycleId) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select name from app.calibration_session where cycle_id = ? and status = 'open' order by created_at limit 1",
                cycleId);
        return s == null ? null : "The calibration session \"" + s.get("name") + "\" is still open; close it first";
    }

    // ── the guide and the merit table ──────────────────────────────────

    public Map<String, Object> settings(SessionCtx ctx) {
        Perf.requireSomeone(ctx, "performance.read");
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            return settings(c);
        });
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> settings(Connection c) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select distribution_guide::text as guide, merit_table::text as merit, rating_scale_id from app.perf_settings");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distributionGuide", Json.parse((String) s.get("guide")));
        out.put("meritTable", Json.parse((String) s.get("merit")));
        out.put("scale", Sql.query(c, "select value, label from app.rating_level where scale_id = ?::uuid order by value desc", s.get("ratingScaleId")));
        return out;
    }

    /** The guide's shares add up to 100; the merit table's increments are between 0 and 50 per cent. Both cover every rating. */
    public Map<String, Object> saveSettings(SessionCtx ctx, SettingsInput in) {
        Perf.requireManage(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            List<String> values = new ArrayList<>();
            for (Map<String, Object> l : Sql.query(c, "select value from app.rating_level where scale_id = (select rating_scale_id from app.perf_settings)")) {
                values.add(String.valueOf(l.get("value")));
            }
            Map<String, BigDecimal> guide = shares(in == null ? null : in.distributionGuide(), values, "the guide", 100);
            BigDecimal total = guide.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(new BigDecimal(100)) != 0) {
                throw ApiException.badRequest("The guide's shares add up to " + total.stripTrailingZeros().toPlainString() + "%; make them 100%");
            }
            Map<String, BigDecimal> merit = shares(in.meritTable(), values, "the merit table", 50);
            Sql.update(c, "update app.perf_settings set distribution_guide = ?::jsonb, merit_table = ?::jsonb, updated_by = ?, updated_at = now()",
                    Json.write(guide), Json.write(merit), ctx.membershipId());
            UserService.audit(c, ctx, "performance.merit_saved", "perf_settings", ctx.tenantId(), Map.of());
            return settings(c);
        });
    }

    private static Map<String, BigDecimal> shares(Map<String, Object> in, List<String> values, String what, int max) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String v : values) {
            Object x = in == null ? null : in.get(v);
            BigDecimal n;
            try {
                n = x == null || x.toString().isBlank() ? null : new BigDecimal(x.toString().trim());
            } catch (NumberFormatException e) {
                n = null;
            }
            if (n == null || n.signum() < 0 || n.compareTo(new BigDecimal(max)) > 0 || n.scale() > 2) {
                throw ApiException.badRequest("Give " + what + " a figure from 0 to " + max + " for every rating");
            }
            out.put(v, n);
        }
        return out;
    }

    // ── internals ───────────────────────────────────────────────────────

    private Map<String, Object> session(Connection c, SessionCtx ctx, UUID id) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select s.id, s.cycle_id, s.name, s.status, s.department_id, d.name as department, s.created_at, "
                + "s.closed_at, " + Perf.who("s.created_by") + " as opened_by from app.calibration_session s "
                + "left join app.department d on d.id = s.department_id where s.id = ?", id);
        if (s == null) {
            throw ApiException.notFound("The session");
        }
        Map<String, Object> y = cycle(c, AuthService.uuid(s.get("cycleId")));
        s.put("cycle", Map.of("id", y.get("id"), "name", y.get("name"), "status", y.get("status")));
        s.put("notes", Perf.open(c, crypto, ctx.tenantId(), "calibration_session", "notes_enc", id));
        List<Map<String, Object>> reviews = Sql.query(c, "select r.id, r.status, r.employment_id, r.manager_level, r.calibrated_level, "
                + "r.proposed_rating, " + Perf.NAME + " as name, e.employee_code, j.designation, d.name as department, "
                + "(select " + Perf.NAME + " from app.employment me join app.person p on p.id = me.person_id where me.id = r.manager_id) as manager, "
                + "(select x.promotion_designation from app.review_entry x where x.review_id = r.id and x.role = 'manager' "
                + "and x.promotion_recommended and x.submitted_at is not null limit 1) as promotion "
                + "from app.review r join app.employment e on e.id = r.employment_id join app.person p on p.id = e.person_id "
                + Perf.CURRENT_JOB + "where r.calibration_session_id = ? and r.status <> 'withdrawn' order by r.manager_level desc nulls last, "
                + Perf.NAME, id);
        for (Map<String, Object> r : reviews) {
            List<Map<String, Object>> changes = Sql.query(c, "select id, from_level, to_level, changed_at, " + Perf.who("changed_by")
                    + " as changed_by from app.calibration_change where review_id = ?::uuid order by changed_at", r.get("id"));
            for (Map<String, Object> x : changes) {
                x.put("reason", Perf.open(c, crypto, ctx.tenantId(), "calibration_change", "reason_enc", x.get("id")));
            }
            r.put("changes", changes);
            r.put("level", r.get("calibratedLevel") != null ? r.get("calibratedLevel") : r.get("managerLevel"));
            r.put("own", Perf.own(c, ctx, AuthService.uuid(r.get("employmentId"))));
        }
        s.put("reviews", reviews);
        s.put("scale", Sql.query(c, "select value, label from app.rating_level where scale_id = ?::uuid order by value desc", y.get("scaleId")));
        s.put("distribution", distribution(c, y, reviews));
        s.put("canChange", "open".equals(s.get("status")) && "calibrating".equals(y.get("status")));
        return s;
    }

    /** For each rating: how many have it now, what the managers gave, and what the guide expects. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> distribution(Connection c, Map<String, Object> y, List<Map<String, Object>> reviews) throws SQLException {
        Map<String, Object> guide = (Map<String, Object>) Json.parse((String) Sql.one(c, "select distribution_guide::text as g from app.perf_settings")
                .get("g"));
        int total = 0;
        Map<Integer, Integer> now = new LinkedHashMap<>();
        Map<Integer, Integer> managers = new LinkedHashMap<>();
        for (Map<String, Object> r : reviews) {
            if (r.get("managerLevel") == null) {
                continue;
            }
            total++;
            managers.merge(((Number) r.get("managerLevel")).intValue(), 1, Integer::sum);
            now.merge(level(r), 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : Sql.query(c, "select value, label from app.rating_level where scale_id = ?::uuid order by value desc", y.get("scaleId"))) {
            int v = ((Number) l.get("value")).intValue();
            BigDecimal guidePct = guide.get(String.valueOf(v)) == null ? BigDecimal.ZERO : new BigDecimal(guide.get(String.valueOf(v)).toString());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("value", v);
            d.put("label", l.get("label"));
            d.put("count", now.getOrDefault(v, 0));
            d.put("managerCount", managers.getOrDefault(v, 0));
            d.put("pct", total == 0 ? 0 : Math.round(now.getOrDefault(v, 0) * 100.0 / total));
            d.put("guidePct", guidePct);
            d.put("guideCount", guidePct.multiply(new BigDecimal(total)).divide(new BigDecimal(100), 0, RoundingMode.HALF_UP));
            out.add(d);
        }
        return out;
    }

    private static int level(Map<String, Object> r) {
        Object l = r.get("calibratedLevel") != null ? r.get("calibratedLevel") : r.get("managerLevel");
        return ((Number) l).intValue();
    }

    static String label(Connection c, Map<String, Object> y, int value) throws SQLException {
        Map<String, Object> l = Sql.one(c, "select label from app.rating_level where scale_id = ?::uuid and value = ?", y.get("scaleId"), (short) value);
        return l == null ? String.valueOf(value) : (String) l.get("label");
    }

    private static Map<String, Object> cycle(Connection c, UUID id) throws SQLException {
        Map<String, Object> y = Sql.one(c, "select * from app.review_cycle where id = ?", id);
        if (y == null) {
            throw ApiException.notFound("The cycle");
        }
        return y;
    }

    private static Map<String, Object> lockOpen(Connection c, UUID id) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select * from app.calibration_session where id = ? for update", id);
        if (s == null) {
            throw ApiException.notFound("The session");
        }
        if (!"open".equals(s.get("status"))) {
            throw new ApiException(409, "calibration.closed", "This session is closed");
        }
        Map<String, Object> y = cycle(c, AuthService.uuid(s.get("cycleId")));
        if (!"calibrating".equals(y.get("status"))) {
            throw new ApiException(409, "cycle.state", "The cycle is no longer calibrating");
        }
        return s;
    }

    private static UUID membership(Connection c, UUID employment) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?", employment);
        return e == null ? null : AuthService.uuid(e.get("membershipId"));
    }
}
