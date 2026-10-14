package com.example.loginapp.core;

import java.math.BigDecimal;
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
@SuppressWarnings("unchecked")
public final class AttendanceService {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int REQUEST_WINDOW_DAYS = 31;
    private static final int FUTURE_WINDOW_DAYS = 60;

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
                    Integer radiusM, UUID shiftId, DayCalculator.Schedule schedule, Set<Integer> remoteDays) {
        ZoneId zone() {
            return schedule.zone();
        }

        boolean weeklyOff(LocalDate day) {
            return schedule.weeklyOffs().contains(day.getDayOfWeek().getValue() % 7);
        }

        /** A regular remote day: named in the assignment, and not a weekly off. */
        boolean regularRemote(LocalDate day) {
            return remoteDays.contains(day.getDayOfWeek().getValue() % 7) && !weeklyOff(day);
        }
    }

    /** An Organization's attendance and leave settings. The defaults apply until someone saves them. */
    record Settings(int leaveYearStartMonth, boolean wfhEnabled, boolean wfhRequiresPunch,
                    Integer wfhLimitPerWeek, Integer wfhLimitPerMonth) {}

    static Settings settings(Connection c) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select leave_year_start_month, wfh_enabled, wfh_requires_punch, "
                + "wfh_limit_per_week, wfh_limit_per_month from app.attendance_settings");
        if (r == null) {
            return new Settings(1, true, false, null, null);
        }
        return new Settings(((Number) r.get("leaveYearStartMonth")).intValue(), (Boolean) r.get("wfhEnabled"),
                (Boolean) r.get("wfhRequiresPunch"),
                r.get("wfhLimitPerWeek") == null ? null : ((Number) r.get("wfhLimitPerWeek")).intValue(),
                r.get("wfhLimitPerMonth") == null ? null : ((Number) r.get("wfhLimitPerMonth")).intValue());
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
            // On a remote day the office checks do not apply. The server decides that from the day, not from the browser.
            boolean remote = remoteOn(c, ctx.membershipId(), workDate, a) != null;
            String check = remote ? "remote" : verify(c, a, latitude, longitude, accuracyM, ip);
            Sql.update(c, "insert into app.punch (tenant_id, membership_id, at, direction, source, work_location_id, "
                            + "latitude, longitude, accuracy_m, ip, check_result) values (?, ?, ?, ?, 'web', ?, ?, ?, ?, ?::inet, ?)",
                    ctx.tenantId(), ctx.membershipId(), Timestamp.from(now), direction, a.locationId(),
                    remote ? null : latitude, remote ? null : longitude, remote ? null : accuracyM, ip, check);
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
        return db.inTenant(ctx.tenantId(), c -> {
            UUID[] team = Team.reach(c, ctx, "attendance.read", today());   // null: everyone
            String only = team == null ? "" : " and m.id = any(?)";
            if (!day.isAfter(today())) {
                for (Map<String, Object> m : Sql.query(c,
                        "select m.id from app.membership m where m.status = 'active' and (m.joined_at at time zone 'Asia/Kolkata')::date <= ? "
                                + "and not exists (select 1 from app.attendance_day d where d.membership_id = m.id and d.work_date = ?)" + only,
                        team == null ? new Object[] {day, day} : new Object[] {day, day, team})) {
                    recompute(c, ctx.tenantId(), AuthService.uuid(m.get("id")), day);
                }
            }
            return Sql.query(c,
                    "select m.id as membership_id, dir.email, " + Names.of("m.id") + " as name, m.employee_code, d.status, d.first_in, d.last_out, "
                            + "d.worked_minutes, d.late_minutes, d.early_minutes, d.overtime_minutes, d.incomplete, d.trail, "
                            + "d.leave_days, d.leave_unpaid, d.remote "
                            + "from app.membership m join app.member_directory() dir on dir.membership_id = m.id "
                            + "left join app.attendance_day d on d.membership_id = m.id and d.work_date = ? "
                            + "where m.status = 'active'" + only + " order by lower(coalesce(" + Names.of("m.id") + ", dir.email))",
                    team == null ? new Object[] {day} : new Object[] {day, team});
        });
    }

    /** One user's month, if the caller can see that user. */
    public List<Map<String, Object>> userDays(SessionCtx ctx, UUID membershipId, String month) {
        boolean own = membershipId.equals(ctx.membershipId()) && ctx.scopeOf("attendance.read") != null;
        if (!own && !canSeeOthers(ctx, "attendance.read")) {
            throw ApiException.notFound("The user");
        }
        YearMonth ym = monthOrCurrent(month);
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.one(c, "select 1 from app.membership where id = ?", membershipId) == null
                    || (!own && !Team.reaches(c, ctx, "attendance.read", membershipId, today()))) {
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
                    "Email,Employee code,Date,Status,First in,Last out,Worked minutes,Late minutes,Overtime minutes,Missing punch,Leave days,Unpaid leave,Remote\n");
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
                            .append(Boolean.TRUE.equals(d.get("incomplete")) ? "yes" : "no").append(',')
                            .append(LeaveService.plain((BigDecimal) d.get("leaveDays"))).append(',')
                            .append(Boolean.TRUE.equals(d.get("leaveUnpaid")) ? "yes" : "no").append(',')
                            .append(Boolean.TRUE.equals(d.get("remote")) ? "yes" : "no").append('\n');
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

    /** Raises a request for one day. Kept for callers that have a single date. */
    public Map<String, Object> createRequest(SessionCtx ctx, String kind, String workDate, String inTime, String outTime, String reason) {
        Map<String, Object> made = createRequests(ctx, kind, workDate, workDate, null, inTime, outTime, reason);
        return (Map<String, Object>) ((List<?>) made.get("requests")).get(0);
    }

    /**
     * Raises a correction (regularization) for one day, or on-duty or work-from-home requests for a range of dates:
     * one request per working day in the range, so each can be decided and cancelled on its own.
     * Times are local to the work location, as HH:mm; an out time at or before the in time means the next day.
     *
     * @param half first or second, for half a day at home on a single date; otherwise null
     */
    public Map<String, Object> createRequests(SessionCtx ctx, String kind, String fromDate, String toDate, String half,
                                              String inTime, String outTime, String reason) {
        requirePunch(ctx);
        if (kind == null || !List.of("regularization", "on_duty", "work_from_home").contains(kind)) {
            throw ApiException.badRequest("The kind must be regularization, on_duty or work_from_home");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        boolean correction = kind.equals("regularization");
        boolean home = kind.equals("work_from_home");
        LocalDate from = fromDate == null || fromDate.isBlank() ? today() : date(fromDate);
        LocalDate to = toDate == null || toDate.isBlank() ? from : date(toDate);
        String halfDay = half == null || half.isBlank() ? null : half;
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The last date is before the first");
        }
        if (correction && !to.equals(from)) {
            throw ApiException.badRequest("A punch correction is for one day");
        }
        if (halfDay != null && (!home || !to.equals(from) || !List.of("first", "second").contains(halfDay))) {
            throw ApiException.badRequest("A half day is for work from home on a single date, first or second half");
        }
        if (from.plusDays(30).isBefore(to)) {
            throw ApiException.badRequest("Ask for at most 31 days at a time");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            UUID me = ctx.membershipId();
            Assigned first = assigned(c, me, from);
            if (first == null) {
                throw notAssigned();
            }
            LocalDate today = LocalDate.now(clock.withZone(first.zone()));
            LocalDate latest = correction ? today : today.plusDays(FUTURE_WINDOW_DAYS);
            if (from.isBefore(today.minusDays(REQUEST_WINDOW_DAYS)) || to.isAfter(latest)) {
                throw new ApiException(400, "attendance.request_window", correction
                        ? "A correction can be for today or the last " + REQUEST_WINDOW_DAYS + " days"
                        : "A request can be for the last " + REQUEST_WINDOW_DAYS + " days or the next " + FUTURE_WINDOW_DAYS);
            }
            Settings settings = settings(c);
            if (home) {
                Map<String, Object> m = Sql.one(c, "select wfh_allowed from app.membership where id = ?", me);
                if (!settings.wfhEnabled() || !Boolean.TRUE.equals(m.get("wfhAllowed"))) {
                    throw new ApiException(403, "attendance.wfh_not_allowed", settings.wfhEnabled()
                            ? "Work from home is not switched on for you. Ask HR"
                            : "Work from home is not in use in this Organization");
                }
            }

            // Which dates need a request: working days, and for work from home not the regular remote days.
            List<LocalDate> days = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                Assigned a = assigned(c, me, d);
                if (a == null) {
                    continue;
                }
                if (!correction && (dayOff(c, a, d) != null || (home && a.regularRemote(d)))) {
                    continue;
                }
                days.add(d);
            }
            if (days.isEmpty()) {
                throw ApiException.badRequest(home
                        ? "None of those dates needs a request: they are days off or already your regular remote days"
                        : "None of those dates is a working day");
            }
            for (LocalDate d : days) {
                if (!correction && LeaveService.hasLeave(c, me, d)) {
                    throw new ApiException(409, "attendance.leave_on_date", "You have leave on " + d + ". Cancel the leave first");
                }
                if (Sql.one(c, "select 1 from app.attendance_request where membership_id = ? and work_date = ? and kind = ? "
                        + "and status = 'approved'", me, d, kind) != null) {
                    throw new ApiException(409, "attendance.request_exists", "A request for " + d + " is already approved");
                }
            }
            if (home) {
                checkHomeLimits(c, me, days, halfDay == null ? 1.0 : 0.5, settings);
            }

            Timestamp in = null;
            Timestamp out = null;
            if (correction) {
                LocalTime start = time(inTime);
                LocalTime end = time(outTime);
                LocalDate endDay = end.isAfter(start) ? from : from.plusDays(1);
                in = Timestamp.from(from.atTime(start).atZone(first.zone()).toInstant());
                out = Timestamp.from(endDay.atTime(end).atZone(first.zone()).toInstant());
            }
            List<Map<String, Object>> made = new ArrayList<>();
            for (LocalDate d : days) {
                made.add(Sql.one(c,
                        "insert into app.attendance_request (tenant_id, membership_id, kind, work_date, half, requested_in, requested_out, reason) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?) "
                                + "returning id, kind, work_date, half, requested_in, requested_out, reason, status, created_at",
                        ctx.tenantId(), me, kind, d, halfDay, in, out, reason.trim()));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requests", made);
            return result;
        });
    }

    /** Whether the caller may work from home, the limits, and how much of each is used in the current week and month. */
    public Map<String, Object> homeWorking(SessionCtx ctx) {
        requirePunch(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Settings settings = settings(c);
            LocalDate today = today();
            Map<String, Object> m = Sql.one(c, "select wfh_allowed from app.membership where id = ?", ctx.membershipId());
            Assigned a = assigned(c, ctx.membershipId(), today);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("allowed", settings.wfhEnabled() && Boolean.TRUE.equals(m.get("wfhAllowed")));
            out.put("requiresPunch", settings.wfhRequiresPunch());
            out.put("limitPerWeek", settings.wfhLimitPerWeek());
            out.put("limitPerMonth", settings.wfhLimitPerMonth());
            out.put("usedThisWeek", homeDays(c, ctx.membershipId(), weekStart(today), weekStart(today).plusDays(6)));
            out.put("usedThisMonth", homeDays(c, ctx.membershipId(), today.withDayOfMonth(1), YearMonth.from(today).atEndOfMonth()));
            out.put("remoteDays", a == null ? List.of() : a.remoteDays().stream().sorted().toList());
            return out;
        });
    }

    public List<Map<String, Object>> myRequests(SessionCtx ctx) {
        requirePunch(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select id, kind, work_date, half, requested_in, requested_out, reason, status, decision_note, decided_at, created_at, "
                        + "(status = 'pending' or (status = 'approved' and kind <> 'regularization' and work_date >= ?)) as can_cancel "
                        + "from app.attendance_request where membership_id = ? order by work_date desc, created_at desc limit 100",
                today(), ctx.membershipId()));
    }

    /** Withdraws the caller's own request: a pending one, or an approved day away that has not passed. */
    public void cancelRequest(SessionCtx ctx, UUID requestId) {
        requirePunch(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = Sql.one(c, "select kind, work_date, status from app.attendance_request "
                    + "where id = ? and membership_id = ? for update", requestId, ctx.membershipId());
            if (r == null || !List.of("pending", "approved").contains((String) r.get("status"))) {
                throw ApiException.notFound("The request");
            }
            if (r.get("status").equals("pending")) {
                Sql.update(c, "update app.attendance_request set status = 'cancelled', cancelled_at = now() where id = ?", requestId);
                return null;
            }
            LocalDate day = LocalDate.parse((String) r.get("workDate"));
            if (r.get("kind").equals("regularization") || day.isBefore(today())) {
                throw new ApiException(409, "attendance.cannot_cancel", r.get("kind").equals("regularization")
                        ? "An approved correction cannot be withdrawn. Ask HR to add the right punches"
                        : "That day has passed. Ask HR to correct it");
            }
            Sql.update(c, "update app.attendance_request set status = 'cancelled', cancelled_at = now() where id = ?", requestId);
            forget(c, ctx.membershipId(), day, day);
            UserService.audit(c, ctx, "attendance.request_cancelled", "attendance_request", requestId,
                    Map.of("kind", r.get("kind"), "date", day.toString()));
            return null;
        });
    }

    /** Requests the caller may decide, with what the day currently shows beside each. */
    public List<Map<String, Object>> requests(SessionCtx ctx, String status) {
        if (!canSeeOthers(ctx, "attendance.approve")) {
            throw ApiException.denied("attendance.approve");
        }
        String wanted = status == null || status.isBlank() ? "pending" : status;
        return db.inTenant(ctx.tenantId(), c -> {
            UUID[] team = Team.reach(c, ctx, "attendance.approve", today());
            return Sql.query(c,
                "select r.id, r.membership_id, dir.email, " + Names.of("r.membership_id") + " as name, r.kind, r.work_date, r.requested_in, r.requested_out, r.reason, "
                        + "r.half, r.status, r.decision_note, r.decided_at, r.created_at, "
                        + "d.status as day_status, d.first_in as day_first_in, d.last_out as day_last_out, "
                        + "(r.membership_id = ?) as own "
                        + "from app.attendance_request r join app.member_directory() dir on dir.membership_id = r.membership_id "
                        + "left join app.attendance_day d on d.membership_id = r.membership_id and d.work_date = r.work_date "
                        + "where r.status = ?" + (team == null ? "" : " and (r.membership_id = any(?) or r.membership_id = ?)")
                        + " order by r.created_at desc limit 200",
                    team == null ? new Object[] {ctx.membershipId(), wanted}
                            : new Object[] {ctx.membershipId(), wanted, team, ctx.membershipId()});
        });
    }

    /** Approves or rejects a request. An approved correction adds its two punches; the originals stay. */
    public void decideRequest(SessionCtx ctx, UUID requestId, boolean approve, String note) {
        if (!canSeeOthers(ctx, "attendance.approve") || ctx.membershipId() == null) {
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
            if (!owner.equals(ctx.membershipId()) && !Team.reaches(c, ctx, "attendance.approve", owner, today())) {
                throw ApiException.notFound("The pending request");
            }
            if (owner.equals(ctx.membershipId())) {
                throw new ApiException(409, "attendance.own_request", "You cannot decide your own request");
            }
            Sql.update(c, "update app.attendance_request set status = ?, decided_by = ?, decided_at = now(), decision_note = ? where id = ?",
                    approve ? "approved" : "rejected", ctx.membershipId(), note == null || note.isBlank() ? null : note.trim(), requestId);
            LocalDate day = LocalDate.parse((String) r.get("workDate"));
            if (approve && !"regularization".equals(r.get("kind")) && LeaveService.hasLeave(c, owner, day)) {
                throw new ApiException(409, "attendance.leave_on_date", "The user has leave on that date");
            }
            if (approve && "regularization".equals(r.get("kind"))) {
                for (String[] p : new String[][] {{"in", "requestedIn"}, {"out", "requestedOut"}}) {
                    Sql.update(c, "insert into app.punch (tenant_id, membership_id, at, direction, source, request_id) "
                                    + "values (?, ?, ?, ?, 'request', ?)",
                            ctx.tenantId(), owner, Timestamp.from(Instant.parse((String) r.get(p[1]))), p[0], requestId);
                }
            }
            if (approve) {
                forget(c, owner, day, day);
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
                    "select kind, half from app.attendance_request where membership_id = ? and work_date = ? and status = 'approved' "
                            + "and kind in ('on_duty','work_from_home') order by (kind = 'on_duty') desc, decided_at desc limit 1",
                    membershipId, day);
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
            String awayKind = away == null ? null : (String) away.get("kind");
            boolean dayOff = holiday != null || a.weeklyOff(day);
            boolean remote = "work_from_home".equals(awayKind) || (awayKind == null && a.regularRemote(day));
            input = new DayCalculator.Input(day, s, holiday == null ? null : (String) holiday.get("name"), awayKind, punches,
                    LeaveService.leaveOn(c, membershipId, day, dayOff),
                    "work_from_home".equals(awayKind) ? (String) away.get("half") : null,
                    remote, settings(c).wfhRequiresPunch());
        }
        DayCalculator.Result r = DayCalculator.calculate(input);
        Sql.update(c, "insert into app.attendance_day (tenant_id, membership_id, work_date, status, shift_id, first_in, last_out, "
                        + "worked_minutes, late_minutes, early_minutes, overtime_minutes, incomplete, trail, leave_days, leave_unpaid, remote, "
                        + "computed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                        + "on conflict (membership_id, work_date) do update set status = excluded.status, shift_id = excluded.shift_id, "
                        + "first_in = excluded.first_in, last_out = excluded.last_out, worked_minutes = excluded.worked_minutes, "
                        + "late_minutes = excluded.late_minutes, early_minutes = excluded.early_minutes, "
                        + "overtime_minutes = excluded.overtime_minutes, incomplete = excluded.incomplete, trail = excluded.trail, "
                        + "leave_days = excluded.leave_days, leave_unpaid = excluded.leave_unpaid, remote = excluded.remote, "
                        + "computed_at = now()",
                tenantId, membershipId, day, r.status(), a == null ? null : a.shiftId(),
                r.firstIn() == null ? null : Timestamp.from(r.firstIn()), r.lastOut() == null ? null : Timestamp.from(r.lastOut()),
                r.workedMinutes(), r.lateMinutes(), r.earlyMinutes(), r.overtimeMinutes(), r.incomplete(),
                r.trail().toArray(new String[0]), java.math.BigDecimal.valueOf(r.leaveDays()), r.leaveUnpaid(), r.remote());
    }

    /** Forgets stored results so they are worked out again when next read. */
    static void invalidateFrom(Connection c, UUID membershipId, LocalDate from) throws SQLException {
        Sql.update(c, "delete from app.attendance_day where membership_id = ? and work_date >= ?", membershipId, from);
    }

    /** Forgets one user's stored results for a range of dates. */
    static void forget(Connection c, UUID membershipId, LocalDate from, LocalDate to) throws SQLException {
        Sql.update(c, "delete from app.attendance_day where membership_id = ? and work_date between ? and ?", membershipId, from, to);
    }

    /** The holiday's name, "Weekly off", or null when the date is a working day for the user. */
    static String dayOff(Connection c, Assigned a, LocalDate day) throws SQLException {
        Map<String, Object> holiday = Sql.one(c,
                "select name from app.holiday where on_date = ? and (work_location_id is null or work_location_id = ?) limit 1",
                day, a.locationId());
        if (holiday != null) {
            return (String) holiday.get("name");
        }
        return a.weeklyOff(day) ? "Weekly off" : null;
    }

    /**
     * Whether the user works away from the office on the date by arrangement:
     * null when not, otherwise full, first or second.
     */
    static String remoteOn(Connection c, UUID membershipId, LocalDate day, Assigned a) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select half from app.attendance_request where membership_id = ? and work_date = ? "
                + "and kind = 'work_from_home' and status = 'approved' limit 1", membershipId, day);
        if (r != null) {
            return r.get("half") == null ? "full" : (String) r.get("half");
        }
        return a.regularRemote(day) ? "full" : null;
    }

    static LocalDate weekStart(LocalDate day) {
        return day.minusDays(day.getDayOfWeek().getValue() - 1L);
    }

    /** Work-from-home days asked for or approved in a period; a half day counts as half. */
    static double homeDays(Connection c, UUID membershipId, LocalDate from, LocalDate to) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select coalesce(sum(case when half is null then 1 else 0.5 end), 0) as used "
                + "from app.attendance_request where membership_id = ? and kind = 'work_from_home' "
                + "and status in ('pending','approved') and work_date between ? and ?", membershipId, from, to);
        return ((Number) r.get("used")).doubleValue();
    }

    private static void checkHomeLimits(Connection c, UUID membershipId, List<LocalDate> days, double each, Settings settings)
            throws SQLException {
        Map<LocalDate, Double> perWeek = new LinkedHashMap<>();
        Map<LocalDate, Double> perMonth = new LinkedHashMap<>();
        for (LocalDate d : days) {
            perWeek.merge(weekStart(d), each, Double::sum);
            perMonth.merge(d.withDayOfMonth(1), each, Double::sum);
        }
        if (settings.wfhLimitPerWeek() != null) {
            for (Map.Entry<LocalDate, Double> w : perWeek.entrySet()) {
                double used = homeDays(c, membershipId, w.getKey(), w.getKey().plusDays(6));
                if (used + w.getValue() > settings.wfhLimitPerWeek()) {
                    throw new ApiException(409, "attendance.wfh_limit", "The limit is " + settings.wfhLimitPerWeek()
                            + " work-from-home days a week; " + trim(used) + " already used in the week of " + w.getKey());
                }
            }
        }
        if (settings.wfhLimitPerMonth() != null) {
            for (Map.Entry<LocalDate, Double> m : perMonth.entrySet()) {
                double used = homeDays(c, membershipId, m.getKey(), YearMonth.from(m.getKey()).atEndOfMonth());
                if (used + m.getValue() > settings.wfhLimitPerMonth()) {
                    throw new ApiException(409, "attendance.wfh_limit", "The limit is " + settings.wfhLimitPerMonth()
                            + " work-from-home days a month; " + trim(used) + " already used in " + YearMonth.from(m.getKey()));
                }
            }
        }
    }

    private static String trim(double days) {
        return days == Math.rint(days) ? String.valueOf((long) days) : String.valueOf(days);
    }

    static void invalidateDate(Connection c, LocalDate day) throws SQLException {
        Sql.update(c, "delete from app.attendance_day where work_date = ?", day);
    }

    /** The assignment in force for a user on a date, or null. The newest row for the latest start date wins. */
    static Assigned assigned(Connection c, UUID membershipId, LocalDate asOf) throws SQLException {
        Map<String, Object> r = Sql.one(c,
                "select l.id as location_id, l.name as location_name, l.timezone, l.punch_check, l.latitude, l.longitude, l.radius_m, "
                        + "s.id as shift_id, s.name as shift_name, s.start_time, s.end_time, s.grace_minutes, s.full_day_minutes, "
                        + "s.half_day_minutes, a.weekly_offs, a.remote_days "
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
        Set<Integer> remoteDays = new HashSet<>();
        for (Object o : (List<?>) r.get("remoteDays")) {
            remoteDays.add(Integer.parseInt(o.toString()));
        }
        return new Assigned(AuthService.uuid(r.get("locationId")), (String) r.get("locationName"), (String) r.get("punchCheck"),
                r.get("latitude") == null ? null : ((Number) r.get("latitude")).doubleValue(),
                r.get("longitude") == null ? null : ((Number) r.get("longitude")).doubleValue(),
                r.get("radiusM") == null ? null : ((Number) r.get("radiusM")).intValue(),
                AuthService.uuid(r.get("shiftId")), schedule, remoteDays);
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
        boolean remote = remoteOn(c, ctx.membershipId(), workDate, a) != null;
        out.put("remote", remote);
        out.put("wantsPosition", !remote && a.latitude() != null && List.of("location", "either").contains(a.punchCheck()));
        out.put("nextDirection", in ? "out" : "in");
        out.put("punches", punches);
        out.put("day", Sql.one(c, "select status, first_in, last_out, worked_minutes, late_minutes, early_minutes, "
                + "overtime_minutes, incomplete, trail, leave_days, leave_unpaid, remote from app.attendance_day where membership_id = ? and work_date = ?",
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
        Map<String, Object> m = Sql.one(c, "select (joined_at at time zone 'Asia/Kolkata')::date as joined from app.membership where id = ?", membershipId);
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
                        + "incomplete, trail, leave_days, leave_unpaid, remote from app.attendance_day where membership_id = ? and work_date between ? and ? "
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
