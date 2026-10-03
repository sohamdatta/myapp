package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Punching, day results and correction requests inside one Organization.
 *
 * Punches are facts and are only ever added. A day's result is worked out from
 * them by {@link DayCalculator} and stored in attendance_day, which can be
 * deleted and rebuilt at any time: deleting a row is how it is invalidated.
 */
public final class AttendanceService {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int REQUEST_WINDOW_DAYS = 31;

    private final Db db;
    private final Clock clock;

    public AttendanceService(Db db) {
        this(db, Clock.systemUTC());
    }

    /** The clock is a parameter so tests can punch at a chosen moment. */
    public AttendanceService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** The location, shift and weekly offs in force for a user on a date. */
    record Assigned(UUID locationId, String locationName, String punchCheck, Double latitude, Double longitude,
                    Integer radiusM, UUID shiftId, DayCalculator.Schedule schedule) {
        ZoneId zone() {
            return schedule.zone();
        }
    }

    // ── the signed-in user's own attendance ─────────────────────────────

    public Map<String, Object> today(SessionCtx ctx) {
        requirePunch(ctx);
        return db.inTenant(ctx.tenantId(), c -> today(c, ctx, clock.instant()));
    }

    /**
     * Records a punch at the server's time. The direction alternates, and the
     * work location's check (office network, position, either or none) must pass.
     */
    public Map<String, Object> punch(SessionCtx ctx, Double latitude, Double longitude, Integer accuracyM, String ip) {
        requirePunch(ctx);
        Instant now = clock.instant();
        return db.inTenant(ctx.tenantId(), c -> {
            Sql.one(c, "select id from app.membership where id = ? for update", ctx.membershipId());   // one punch at a time
            Assigned a = assignedAt(c, ctx.membershipId(), now);
            if (a == null) {
                throw notAssigned();
            }
            LocalDate workDate = PunchChecks.workDate(now, a.zone(), a.schedule().start(), a.schedule().end());
            Map<String, Object> last = Sql.one(c,
                    "select at, direction from app.punch where membership_id = ? order by at desc limit 1", ctx.membershipId());
            String direction = "in";
            if (last != null) {
                Instant lastAt = Instant.parse((String) last.get("at"));
                if (Duration.between(lastAt, now).getSeconds() < 60) {
                    throw new ApiException(409, "attendance.too_soon", "You punched a moment ago. Wait a minute before punching again");
                }
                boolean sameDay = PunchChecks.workDate(lastAt, a.zone(), a.schedule().start(), a.schedule().end()).equals(workDate);
                if (sameDay && "in".equals(last.get("direction"))) {
                    direction = "out";
                }
            }
            String check = verify(c, a, latitude, longitude, accuracyM, ip);
            Sql.update(c, "insert into app.punch (tenant_id, membership_id, at, direction, source, work_location_id, "
                            + "latitude, longitude, accuracy_m, ip, check_result) values (?, ?, ?, ?, 'web', ?, ?, ?, ?, ?::inet, ?)",
                    ctx.tenantId(), ctx.membershipId(), Timestamp.from(now), direction, a.locationId(),
                    latitude, longitude, accuracyM, ip, check);
            recompute(c, ctx.tenantId(), ctx.membershipId(), workDate);
            return today(c, ctx, now);
        });
    }

    /** The signed-in user's days for a month (yyyy-MM), newest first, each with its trail. */
    public List<Map<String, Object>> myDays(SessionCtx ctx, String month) {
        if (ctx.scopeOf("attendance.read") == null || ctx.membershipId() == null) {
            throw ApiException.denied("attendance.read");
        }
        YearMonth ym = monthOrCurrent(month);
        return db.inTenant(ctx.tenantId(), c -> monthOf(c, ctx.tenantId(), ctx.membershipId(), ym, today()));
    }

    // ── other people's attendance ───────────────────────────────────────

    /** Everyone the caller can see, on one date. */
    public List<Map<String, Object>> board(SessionCtx ctx, String date) {
        if (!canSeeOthers(ctx, "attendance.read")) {
            throw ApiException.denied("attendance.read");
        }
        LocalDate day = date == null || date.isBlank() ? today() : date(date);
        if (!ctx.reachesEveryone("attendance.read")) {
            return List.of();   // team scope: nobody until employee records carry reporting lines
        }
        return db.inTenant(ctx.tenantId(), c -> {
            if (!day.isAfter(today())) {
                for (Map<String, Object> m : Sql.query(c,
                        "select m.id from app.membership m where m.status = 'active' and m.joined_at::date <= ? "
                                + "and not exists (select 1 from app.attendance_day d where d.membership_id = m.id and d.work_date = ?)",
                        day, day)) {
                    recompute(c, ctx.tenantId(), AuthService.uuid(m.get("id")), day);
                }
            }
            return Sql.query(c,
                    "select m.id as membership_id, dir.email, m.employee_code, d.status, d.first_in, d.last_out, "
                            + "d.worked_minutes, d.late_minutes, d.early_minutes, d.overtime_minutes, d.incomplete, d.trail "
                            + "from app.membership m join app.member_directory() dir on dir.membership_id = m.id "
                            + "left join app.attendance_day d on d.membership_id = m.id and d.work_date = ? "
                            + "where m.status = 'active' order by dir.email", day);
        });
    }

    /** One user's month, if the caller can see that user. */
    public List<Map<String, Object>> userDays(SessionCtx ctx, UUID membershipId, String month) {
        boolean own = membershipId.equals(ctx.membershipId()) && ctx.scopeOf("attendance.read") != null;
        if (!own && !ctx.reachesEveryone("attendance.read")) {
            throw ApiException.notFound("The user");
        }
        YearMonth ym = monthOrCurrent(month);
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.one(c, "select 1 from app.membership where id = ?", membershipId) == null) {
                throw ApiException.notFound("The user");
            }
            return monthOf(c, ctx.tenantId(), membershipId, ym, today());
        });
    }

    /** HR enters a punch for a user. The time is local to the user's work location, as yyyy-MM-ddTHH:mm. */
    public void manualPunch(SessionCtx ctx, UUID membershipId, String at, String direction, String reason) {
        ctx.require("attendance.manage");
        if (ctx.membershipId() == null) {
            throw new ApiException(403, "permission.denied", "Only a user of the Organization can enter a punch");
        }
        if (!"in".equals(direction) && !"out".equals(direction)) {
            throw ApiException.badRequest("The direction must be in or out");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        LocalDateTime local = localDateTime(at);
        db.inTenant(ctx.tenantId(), c -> {
            Assigned a = assigned(c, membershipId, local.toLocalDate());
            if (a == null) {
                throw notAssigned();
            }
            Instant when = local.atZone(a.zone()).toInstant();
            if (when.isAfter(clock.instant())) {
                throw ApiException.badRequest("A punch cannot be in the future");
            }
            Map<String, Object> row = Sql.one(c,
                    "insert into app.punch (tenant_id, membership_id, at, direction, source, work_location_id, entered_by, reason) "
                            + "values (?, ?, ?, ?, 'manual', ?, ?, ?) returning id",
                    ctx.tenantId(), membershipId, Timestamp.from(when), direction, a.locationId(), ctx.membershipId(), reason.trim());
            LocalDate workDate = PunchChecks.workDate(when, a.zone(), a.schedule().start(), a.schedule().end());
            recompute(c, ctx.tenantId(), membershipId, workDate);
            UserService.audit(c, ctx, "attendance.manual_punch", "punch", AuthService.uuid(row.get("id")),
                    Map.of("date", workDate.toString(), "direction", direction, "reason", reason.trim()));
            return null;
        });
    }

    /** The attendance register for a month as CSV text. */
    public Map<String, Object> register(SessionCtx ctx, String month) {
        if (!ctx.reachesEveryone("attendance.read")) {
            throw ApiException.denied("attendance.read");
        }
        YearMonth ym = monthOrCurrent(month);
        String csv = db.inTenant(ctx.tenantId(), c -> {
            StringBuilder out = new StringBuilder(
                    "Email,Employee code,Date,Status,First in,Last out,Worked minutes,Late minutes,Overtime minutes,Missing punch\n");
            for (Map<String, Object> m : Sql.query(c,
                    "select m.id, dir.email, m.employee_code from app.membership m "
                            + "join app.member_directory() dir on dir.membership_id = m.id "
                            + "where m.status in ('active','suspended') order by dir.email")) {
                UUID membershipId = AuthService.uuid(m.get("id"));
                Assigned a = assigned(c, membershipId, ym.atEndOfMonth());
                ZoneId zone = a == null ? DEFAULT_ZONE : a.zone();   // times are written as the work location's clock shows them
                List<Map<String, Object>> days = monthOf(c, ctx.tenantId(), membershipId, ym, today());
                for (int i = days.size() - 1; i >= 0; i--) {         // oldest first
                    Map<String, Object> d = days.get(i);
                    out.append(csv(m.get("email"))).append(',').append(csv(m.get("employeeCode"))).append(',')
                            .append(d.get("workDate")).append(',').append(d.get("status")).append(',')
                            .append(localTime(d.get("firstIn"), zone)).append(',').append(localTime(d.get("lastOut"), zone)).append(',')
                            .append(d.get("workedMinutes")).append(',').append(d.get("lateMinutes")).append(',')
                            .append(d.get("overtimeMinutes")).append(',')
                            .append(Boolean.TRUE.equals(d.get("incomplete")) ? "yes" : "no").append('\n');
                }
            }
            return out.toString();
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", "attendance-" + ym + ".csv");
        body.put("csv", csv);
        return body;
    }

    // ── requests ────────────────────────────────────────────────────────

    /**
     * Raises a correction (regularization), on-duty or work-from-home request for a day.
     * Times are local to the work location, as HH:mm; an out time at or before the in time means the next day.
     */
    public Map<String, Object> createRequest(SessionCtx ctx, String kind, String workDate, String inTime, String outTime, String reason) {
        requirePunch(ctx);
        if (kind == null || !List.of("regularization", "on_duty", "work_from_home").contains(kind)) {
            throw ApiException.badRequest("The kind must be regularization, on_duty or work_from_home");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        LocalDate day = workDate == null || workDate.isBlank() ? today() : date(workDate);
        return db.inTenant(ctx.tenantId(), c -> {
            Assigned a = assigned(c, ctx.membershipId(), day);
            if (a == null) {
                throw notAssigned();
            }
            LocalDate today = LocalDate.now(clock.withZone(a.zone()));
            if (day.isAfter(today) || day.isBefore(today.minusDays(REQUEST_WINDOW_DAYS))) {
                throw new ApiException(400, "attendance.request_window",
                        "A request can be for today or the last " + REQUEST_WINDOW_DAYS + " days");
            }
            Timestamp in = null;
            Timestamp out = null;
            if (kind.equals("regularization")) {
                LocalTime start = time(inTime);
                LocalTime end = time(outTime);
                LocalDate endDay = end.isAfter(start) ? day : day.plusDays(1);
                in = Timestamp.from(day.atTime(start).atZone(a.zone()).toInstant());
                out = Timestamp.from(endDay.atTime(end).atZone(a.zone()).toInstant());
            }
            return Sql.one(c,
                    "insert into app.attendance_request (tenant_id, membership_id, kind, work_date, requested_in, requested_out, reason) "
                            + "values (?, ?, ?, ?, ?, ?, ?) returning id, kind, work_date, requested_in, requested_out, reason, status, created_at",
                    ctx.tenantId(), ctx.membershipId(), kind, day, in, out, reason.trim());
        });
    }

    public List<Map<String, Object>> myRequests(SessionCtx ctx) {
        requirePunch(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select id, kind, work_date, requested_in, requested_out, reason, status, decision_note, decided_at, created_at "
                        + "from app.attendance_request where membership_id = ? order by created_at desc limit 100", ctx.membershipId()));
    }

    public void cancelRequest(SessionCtx ctx, UUID requestId) {
        requirePunch(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            if (Sql.update(c, "update app.attendance_request set status = 'cancelled' "
                    + "where id = ? and membership_id = ? and status = 'pending'", requestId, ctx.membershipId()) == 0) {
                throw ApiException.notFound("The pending request");
            }
            return null;
        });
    }

    /** Requests the caller may decide, with what the day currently shows beside each. */
    public List<Map<String, Object>> requests(SessionCtx ctx, String status) {
        if (!canSeeOthers(ctx, "attendance.approve")) {
            throw ApiException.denied("attendance.approve");
        }
        if (!ctx.reachesEveryone("attendance.approve")) {
            return List.of();
        }
        String wanted = status == null || status.isBlank() ? "pending" : status;
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select r.id, r.membership_id, dir.email, r.kind, r.work_date, r.requested_in, r.requested_out, r.reason, "
                        + "r.status, r.decision_note, r.decided_at, r.created_at, "
                        + "d.status as day_status, d.first_in as day_first_in, d.last_out as day_last_out, "
                        + "(r.membership_id = ?) as own "
                        + "from app.attendance_request r join app.member_directory() dir on dir.membership_id = r.membership_id "
                        + "left join app.attendance_day d on d.membership_id = r.membership_id and d.work_date = r.work_date "
                        + "where r.status = ? order by r.created_at desc limit 200", ctx.membershipId(), wanted));
    }

    /** Approves or rejects a request. An approved correction adds its two punches; the originals stay. */
    public void decideRequest(SessionCtx ctx, UUID requestId, boolean approve, String note) {
        if (!ctx.reachesEveryone("attendance.approve") || ctx.membershipId() == null) {
            throw ApiException.denied("attendance.approve");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = Sql.one(c,
                    "select id, membership_id, kind, work_date, requested_in, requested_out, status "
                            + "from app.attendance_request where id = ? for update", requestId);
            if (r == null || !"pending".equals(r.get("status"))) {
                throw ApiException.notFound("The pending request");
            }
            UUID owner = AuthService.uuid(r.get("membershipId"));
            if (owner.equals(ctx.membershipId())) {
                throw new ApiException(409, "attendance.own_request", "You cannot decide your own request");
            }
            Sql.update(c, "update app.attendance_request set status = ?, decided_by = ?, decided_at = now(), decision_note = ? where id = ?",
                    approve ? "approved" : "rejected", ctx.membershipId(), note == null || note.isBlank() ? null : note.trim(), requestId);
            LocalDate day = LocalDate.parse((String) r.get("workDate"));
            if (approve && "regularization".equals(r.get("kind"))) {
                for (String[] p : new String[][] {{"in", "requestedIn"}, {"out", "requestedOut"}}) {
                    Sql.update(c, "insert into app.punch (tenant_id, membership_id, at, direction, source, request_id) "
                                    + "values (?, ?, ?, ?, 'request', ?)",
                            ctx.tenantId(), owner, Timestamp.from(Instant.parse((String) r.get(p[1]))), p[0], requestId);
                }
            }
            if (approve) {
                recompute(c, ctx.tenantId(), owner, day);
            }
            UserService.audit(c, ctx, approve ? "attendance.request_approved" : "attendance.request_rejected",
                    "attendance_request", requestId, Map.of("kind", r.get("kind"), "date", day.toString()));
            return null;
        });
    }

    // ── calculation, shared with the settings and import services ───────

    /** Works out and stores one user's result for one day. */
    static void recompute(Connection c, UUID tenantId, UUID membershipId, LocalDate day) throws SQLException {
        Assigned a = assigned(c, membershipId, day);
        DayCalculator.Input input;
        if (a == null) {
            input = new DayCalculator.Input(day, null, null, null, List.of());
        } else {
            DayCalculator.Schedule s = a.schedule();
            Map<String, Object> holiday = Sql.one(c,
                    "select name from app.holiday where on_date = ? and (work_location_id is null or work_location_id = ?) "
                            + "order by work_location_id nulls last limit 1", day, a.locationId());
            Map<String, Object> away = Sql.one(c,
                    "select kind from app.attendance_request where membership_id = ? and work_date = ? and status = 'approved' "
                            + "and kind in ('on_duty','work_from_home') order by decided_at desc limit 1", membershipId, day);
            Instant from = PunchChecks.dayStart(day, s.zone(), s.start(), s.end());
            Instant to = PunchChecks.dayStart(day.plusDays(1), s.zone(), s.start(), s.end());
            List<DayCalculator.Punch> punches = new ArrayList<>();
            for (Map<String, Object> p : Sql.query(c,
                    "select at, direction, source from app.punch where membership_id = ? and ("
                            + "(source <> 'request' and at >= ? and at < ?) or "
                            + "request_id in (select id from app.attendance_request where membership_id = ? and work_date = ? and status = 'approved')) "
                            + "order by at, created_at",
                    membershipId, Timestamp.from(from), Timestamp.from(to), membershipId, day)) {
                punches.add(new DayCalculator.Punch(Instant.parse((String) p.get("at")),
                        (String) p.get("direction"), (String) p.get("source")));
            }
            input = new DayCalculator.Input(day, s, holiday == null ? null : (String) holiday.get("name"),
                    away == null ? null : (String) away.get("kind"), punches);
        }
        DayCalculator.Result r = DayCalculator.calculate(input);
        Sql.update(c, "insert into app.attendance_day (tenant_id, membership_id, work_date, status, shift_id, first_in, last_out, "
                        + "worked_minutes, late_minutes, early_minutes, overtime_minutes, incomplete, trail, computed_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                        + "on conflict (membership_id, work_date) do update set status = excluded.status, shift_id = excluded.shift_id, "
                        + "first_in = excluded.first_in, last_out = excluded.last_out, worked_minutes = excluded.worked_minutes, "
                        + "late_minutes = excluded.late_minutes, early_minutes = excluded.early_minutes, "
                        + "overtime_minutes = excluded.overtime_minutes, incomplete = excluded.incomplete, trail = excluded.trail, "
                        + "computed_at = now()",
                tenantId, membershipId, day, r.status(), a == null ? null : a.shiftId(),
                r.firstIn() == null ? null : Timestamp.from(r.firstIn()), r.lastOut() == null ? null : Timestamp.from(r.lastOut()),
                r.workedMinutes(), r.lateMinutes(), r.earlyMinutes(), r.overtimeMinutes(), r.incomplete(),
                r.trail().toArray(new String[0]));
    }

    /** Forgets stored results so they are worked out again when next read. */
    static void invalidateFrom(Connection c, UUID membershipId, LocalDate from) throws SQLException {
        Sql.update(c, "delete from app.attendance_day where membership_id = ? and work_date >= ?", membershipId, from);
    }

    static void invalidateDate(Connection c, LocalDate day) throws SQLException {
        Sql.update(c, "delete from app.attendance_day where work_date = ?", day);
    }

    /** The assignment in force for a user on a date, or null. The newest row for the latest start date wins. */
    static Assigned assigned(Connection c, UUID membershipId, LocalDate asOf) throws SQLException {
        Map<String, Object> r = Sql.one(c,
                "select l.id as location_id, l.name as location_name, l.timezone, l.punch_check, l.latitude, l.longitude, l.radius_m, "
                        + "s.id as shift_id, s.name as shift_name, s.start_time, s.end_time, s.grace_minutes, s.full_day_minutes, "
                        + "s.half_day_minutes, a.weekly_offs "
                        + "from app.attendance_assignment a join app.work_location l on l.id = a.work_location_id "
                        + "join app.shift s on s.id = a.shift_id "
                        + "where a.membership_id = ? and a.effective_from <= ? "
                        + "order by a.effective_from desc, a.created_at desc limit 1", membershipId, asOf);
        if (r == null) {
            return null;
        }
        Set<Integer> offs = new HashSet<>();
        for (Object o : (List<?>) r.get("weeklyOffs")) {
            offs.add(Integer.parseInt(o.toString()));
        }
        DayCalculator.Schedule schedule = new DayCalculator.Schedule((String) r.get("shiftName"),
                LocalTime.parse((String) r.get("startTime")), LocalTime.parse((String) r.get("endTime")),
                ((Number) r.get("graceMinutes")).intValue(), ((Number) r.get("fullDayMinutes")).intValue(),
                ((Number) r.get("halfDayMinutes")).intValue(), offs, zone((String) r.get("timezone")));
        return new Assigned(AuthService.uuid(r.get("locationId")), (String) r.get("locationName"), (String) r.get("punchCheck"),
                r.get("latitude") == null ? null : ((Number) r.get("latitude")).doubleValue(),
                r.get("longitude") == null ? null : ((Number) r.get("longitude")).doubleValue(),
                r.get("radiusM") == null ? null : ((Number) r.get("radiusM")).intValue(),
                AuthService.uuid(r.get("shiftId")), schedule);
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The assignment for the work date that the moment belongs to. */
    private static Assigned assignedAt(Connection c, UUID membershipId, Instant at) throws SQLException {
        // Start with the latest date this moment could be anywhere, then settle on the location's own date.
        Assigned a = assigned(c, membershipId, at.atZone(ZoneId.of("UTC")).toLocalDate().plusDays(1));
        if (a == null) {
            return null;
        }
        LocalDate workDate = PunchChecks.workDate(at, a.zone(), a.schedule().start(), a.schedule().end());
        return assigned(c, membershipId, workDate);
    }

    private static Map<String, Object> today(Connection c, SessionCtx ctx, Instant now) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        Assigned a = assignedAt(c, ctx.membershipId(), now);
        out.put("assigned", a != null);
        if (a == null) {
            return out;
        }
        DayCalculator.Schedule s = a.schedule();
        LocalDate workDate = PunchChecks.workDate(now, s.zone(), s.start(), s.end());
        recompute(c, ctx.tenantId(), ctx.membershipId(), workDate);
        List<Map<String, Object>> punches = Sql.query(c,
                "select at, direction, source, check_result from app.punch where membership_id = ? and at >= ? and at < ? order by at",
                ctx.membershipId(), Timestamp.from(PunchChecks.dayStart(workDate, s.zone(), s.start(), s.end())),
                Timestamp.from(PunchChecks.dayStart(workDate.plusDays(1), s.zone(), s.start(), s.end())));
        boolean in = !punches.isEmpty() && "in".equals(punches.get(punches.size() - 1).get("direction"));
        out.put("workDate", workDate.toString());
        out.put("timezone", s.zone().getId());
        out.put("location", a.locationName());
        out.put("shift", s.shiftName() + " " + s.start() + " to " + s.end());
        out.put("punchCheck", a.punchCheck());
        out.put("wantsPosition", a.latitude() != null && List.of("location", "either").contains(a.punchCheck()));
        out.put("nextDirection", in ? "out" : "in");
        out.put("punches", punches);
        out.put("day", Sql.one(c, "select status, first_in, last_out, worked_minutes, late_minutes, early_minutes, "
                + "overtime_minutes, incomplete, trail from app.attendance_day where membership_id = ? and work_date = ?",
                ctx.membershipId(), workDate));
        return out;
    }

    /** Checks the punch against the location's rule and returns which check passed. */
    private static String verify(Connection c, Assigned a, Double latitude, Double longitude, Integer accuracyM, String ip)
            throws SQLException {
        String mode = a.punchCheck();
        if (mode.equals("none")) {
            return "not_checked";
        }
        boolean ipOk = false;
        if (!mode.equals("location")) {
            Map<String, Object> row = Sql.one(c,
                    "select coalesce(?::inet <<= any(allowed_ips), false) as ok from app.work_location where id = ?", ip, a.locationId());
            ipOk = row != null && Boolean.TRUE.equals(row.get("ok"));
            if (ipOk) {
                return "ip_ok";
            }
            if (mode.equals("ip")) {
                throw new ApiException(403, "attendance.outside_network",
                        "You are not on the office network for " + a.locationName());
            }
        }
        if (a.latitude() == null) {
            throw new ApiException(403, "attendance.outside_network",
                    "You are not on the office network for " + a.locationName() + ", and it has no position set to check against");
        }
        if (latitude == null || longitude == null) {
            throw new ApiException(400, "attendance.location_required",
                    mode.equals("either") ? "Allow location access in your browser, or connect to the office network"
                            : "Allow location access in your browser to punch");
        }
        if (accuracyM != null && accuracyM > PunchChecks.MAX_ACCURACY_M) {
            throw new ApiException(403, "attendance.outside_location",
                    "Your device's position is only accurate to " + accuracyM + " m; it must be within "
                            + PunchChecks.MAX_ACCURACY_M + " m to punch");
        }
        long distance = Math.round(PunchChecks.distanceMeters(latitude, longitude, a.latitude(), a.longitude()));
        if (distance > a.radiusM()) {
            throw new ApiException(403, "attendance.outside_location",
                    "You are " + distance + " m from " + a.locationName() + "; the limit is " + a.radiusM() + " m");
        }
        return "location_ok";
    }

    /** A user's stored days for a month, filling in any that have not been worked out yet. */
    static List<Map<String, Object>> monthOf(Connection c, UUID tenantId, UUID membershipId, YearMonth ym, LocalDate today)
            throws SQLException {
        Map<String, Object> m = Sql.one(c, "select joined_at::date as joined from app.membership where id = ?", membershipId);
        LocalDate first = ym.atDay(1);
        LocalDate joined = LocalDate.parse((String) m.get("joined"));
        if (joined.isAfter(first)) {
            first = joined;
        }
        LocalDate last = ym.atEndOfMonth();
        if (last.isAfter(today)) {
            last = today;
        }
        Set<String> have = new HashSet<>();
        for (Map<String, Object> d : Sql.query(c,
                "select work_date from app.attendance_day where membership_id = ? and work_date between ? and ?",
                membershipId, ym.atDay(1), ym.atEndOfMonth())) {
            have.add((String) d.get("workDate"));
        }
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (!have.contains(d.toString())) {
                recompute(c, tenantId, membershipId, d);
            }
        }
        return Sql.query(c,
                "select work_date, status, first_in, last_out, worked_minutes, late_minutes, early_minutes, overtime_minutes, "
                        + "incomplete, trail from app.attendance_day where membership_id = ? and work_date between ? and ? "
                        + "order by work_date desc", membershipId, ym.atDay(1), ym.atEndOfMonth());
    }

    private static void requirePunch(SessionCtx ctx) {
        if (!ctx.can("attendance.punch") || ctx.membershipId() == null) {
            throw ApiException.denied("attendance.punch");
        }
    }

    /** True when the permission is held beyond the user's own records. */
    private static boolean canSeeOthers(SessionCtx ctx, String permission) {
        String scope = ctx.scopeOf(permission);
        return scope != null && !scope.equals("self");
    }

    private static ApiException notAssigned() {
        return new ApiException(409, "attendance.not_assigned",
                "No work location and shift are assigned yet. Ask HR to set them up");
    }

    /** Today's date where most users are, used when a request names no date. */
    private LocalDate today() {
        return LocalDate.now(clock.withZone(DEFAULT_ZONE));
    }

    private YearMonth monthOrCurrent(String value) {
        try {
            return value == null || value.isBlank() ? YearMonth.from(today()) : YearMonth.parse(value);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("The month must look like 2026-10");
        }
    }

    static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException | NullPointerException e) {
            throw ApiException.badRequest("The date must look like 2026-10-03");
        }
    }

    static LocalTime time(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException | NullPointerException e) {
            throw ApiException.badRequest("Times must look like 09:30");
        }
    }

    private static LocalDateTime localDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeException | NullPointerException e) {
            throw ApiException.badRequest("The time must look like 2026-10-03T09:30");
        }
    }

    private static String localTime(Object instant, ZoneId zone) {
        return instant == null ? "" : Instant.parse((String) instant).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString();
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;   // so a spreadsheet shows the text instead of running it as a formula
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
