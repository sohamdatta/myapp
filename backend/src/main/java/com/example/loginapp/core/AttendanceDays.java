package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Each user's day results and the arrangements behind them, shared by attendance, leave, payroll,
 * devices, settings and import: the assignment in force (location, shift, weekly offs, mode),
 * holidays and remote days, working out and storing a day, and forgetting stored days so they are
 * worked out again.
 *
 * A day's result is worked out from the punches by {@link DayCalculator} and stored in attendance_day,
 * which can be deleted and rebuilt at any time: deleting a row is how it is invalidated.
 */
@SuppressWarnings("unchecked")
final class AttendanceDays {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    private AttendanceDays() {
    }

    /** The location, shift and weekly offs in force for a user on a date. */
    /** @param mode how the location records attendance on the date: app, device or present */
    record Assigned(UUID locationId, String locationName, String punchCheck, Double latitude, Double longitude,
                    Integer radiusM, UUID shiftId, DayCalculator.Schedule schedule, Set<Integer> remoteDays, String mode) {
        ZoneId zone() {
            return schedule.zone();
        }

        boolean presentByDefault() {
            return "present".equals(mode);
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
            boolean present = a.presentByDefault();
            input = new DayCalculator.Input(day, s, holiday == null ? null : (String) holiday.get("name"), awayKind, punches,
                    LeaveService.leaveOn(c, membershipId, day, dayOff),
                    "work_from_home".equals(awayKind) ? (String) away.get("half") : null,
                    remote, !present && settings(c).wfhRequiresPunch(), present, present ? markOn(c, membershipId, day) : null);
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

    /** The exception in force for a day: the newest mark, unless that one says present. */
    static DayCalculator.Mark markOn(Connection c, UUID membershipId, LocalDate day) throws SQLException {
        Map<String, Object> m = Sql.one(c, "select k.status, k.reason, coalesce(" + Names.of("k.marked_by") + ", dir.email) as marked_by "
                + "from app.attendance_mark k left join app.member_directory() dir on dir.membership_id = k.marked_by "
                + "where k.membership_id = ? and k.work_date = ? order by k.created_at desc limit 1", membershipId, day);
        if (m == null || "present".equals(m.get("status"))) {
            return null;
        }
        return new DayCalculator.Mark((String) m.get("status"), (String) m.get("reason"), (String) m.get("markedBy"));
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

    static void invalidateDate(Connection c, LocalDate day) throws SQLException {
        Sql.update(c, "delete from app.attendance_day where work_date = ?", day);
    }

    /** The assignment in force for a user on a date, or null. The newest row for the latest start date wins. */
    static Assigned assigned(Connection c, UUID membershipId, LocalDate asOf) throws SQLException {
        Map<String, Object> r = Sql.one(c,
                "select l.id as location_id, l.name as location_name, l.timezone, l.punch_check, l.latitude, l.longitude, l.radius_m, "
                        + "s.id as shift_id, s.name as shift_name, s.start_time, s.end_time, s.grace_minutes, s.full_day_minutes, "
                        + "s.half_day_minutes, a.weekly_offs, a.remote_days, "
                        + "coalesce((select lm.mode from app.location_mode lm where lm.work_location_id = l.id and lm.effective_from <= ? "
                        + "          order by lm.effective_from desc, lm.created_at desc limit 1), 'app') as mode "
                        + "from app.attendance_assignment a join app.work_location l on l.id = a.work_location_id "
                        + "join app.shift s on s.id = a.shift_id "
                        + "where a.membership_id = ? and a.effective_from <= ? "
                        + "order by a.effective_from desc, a.created_at desc limit 1", asOf, membershipId, asOf);
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
                AuthService.uuid(r.get("shiftId")), schedule, remoteDays, (String) r.get("mode"));
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
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
}
