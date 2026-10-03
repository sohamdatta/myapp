package com.example.loginapp.core;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What attendance is measured against: work locations, shifts, holidays, and
 * which of them apply to each user. Anything that changes a day's result
 * forgets the stored results it affects, so they are worked out again on the
 * next read.
 */
public final class AttendanceSettingsService {

    private static final List<String> PUNCH_CHECKS = List.of("none", "ip", "location", "either");

    private final Db db;

    public AttendanceSettingsService(Db db) {
        this.db = db;
    }

    public record LocationInput(String name, String timezone, String stateCode, Double latitude, Double longitude,
                                Integer radiusM, List<String> allowedIps, String punchCheck, Boolean active) {}

    public record ShiftInput(String name, String startTime, String endTime, Integer graceMinutes,
                             Integer fullDayMinutes, Integer halfDayMinutes, Boolean active) {}

    // ── work locations ──────────────────────────────────────────────────

    public List<Map<String, Object>> locations(SessionCtx ctx) {
        requireView(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select id, name, timezone, state_code, latitude, longitude, radius_m, "
                        + "array(select host(ip) || case when masklen(ip) < 32 and family(ip) = 4 or masklen(ip) < 128 and family(ip) = 6 "
                        + "      then '/' || masklen(ip) else '' end from unnest(allowed_ips) ip) as allowed_ips, "
                        + "punch_check, active from app.work_location order by lower(name)"));
    }

    /** Creates a work location (id null) or changes one. */
    public Map<String, Object> saveLocation(SessionCtx ctx, UUID id, LocationInput in) {
        ctx.require("attendance.manage");
        String name = name(in.name());
        String timezone = in.timezone() == null || in.timezone().isBlank() ? "Asia/Kolkata" : in.timezone().trim();
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Unknown time zone: " + timezone);
        }
        String check = in.punchCheck() == null ? "either" : in.punchCheck();
        if (!PUNCH_CHECKS.contains(check)) {
            throw ApiException.badRequest("The punch check must be one of " + String.join(", ", PUNCH_CHECKS));
        }
        boolean hasPosition = in.latitude() != null || in.longitude() != null || in.radiusM() != null;
        if (hasPosition && (in.latitude() == null || in.longitude() == null || in.radiusM() == null)) {
            throw ApiException.badRequest("Give latitude, longitude and radius together, or leave all three empty");
        }
        if (in.radiusM() != null && (in.radiusM() < 25 || in.radiusM() > 5000)) {
            throw ApiException.badRequest("The radius must be between 25 and 5000 metres");
        }
        List<String> ips = new ArrayList<>();
        if (in.allowedIps() != null) {
            for (String ip : in.allowedIps()) {
                if (ip != null && !ip.isBlank()) {
                    ips.add(ip.trim());
                }
            }
        }
        if (check.equals("ip") && ips.isEmpty()) {
            throw ApiException.badRequest("Add at least one office network address, or choose another punch check");
        }
        if (check.equals("location") && !hasPosition) {
            throw ApiException.badRequest("Set the position and radius, or choose another punch check");
        }
        if (check.equals("either") && ips.isEmpty() && !hasPosition) {
            throw ApiException.badRequest("Add an office network address or a position, or set the punch check to none");
        }
        String state = in.stateCode() == null || in.stateCode().isBlank() ? null : in.stateCode().trim().toUpperCase();
        boolean active = in.active() == null || in.active();
        String[] ipArray = ips.toArray(new String[0]);
        return db.inTenant(ctx.tenantId(), c -> {
            // network() accepts an address with or without a prefix length and drops any host bits
            String networks = "coalesce((select array_agg(network(x::inet)) from unnest(?::text[]) x), '{}')";
            Map<String, Object> row;
            if (id == null) {
                row = Sql.one(c, "insert into app.work_location (tenant_id, name, timezone, state_code, latitude, longitude, "
                                + "radius_m, allowed_ips, punch_check, active) values (?, ?, ?, ?, ?, ?, ?, " + networks + ", ?, ?) returning id",
                        ctx.tenantId(), name, timezone, state, in.latitude(), in.longitude(), in.radiusM(), ipArray, check, active);
            } else {
                row = Sql.one(c, "update app.work_location set name = ?, timezone = ?, state_code = ?, latitude = ?, longitude = ?, "
                                + "radius_m = ?, allowed_ips = " + networks + ", punch_check = ?, active = ? where id = ? returning id",
                        name, timezone, state, in.latitude(), in.longitude(), in.radiusM(), ipArray, check, active, id);
                if (row == null) {
                    throw ApiException.notFound("The work location");
                }
                // the time zone decides which day a punch belongs to
                Sql.update(c, "delete from app.attendance_day d where exists (select 1 from app.attendance_assignment a "
                        + "where a.membership_id = d.membership_id and a.work_location_id = ?)", id);
            }
            UUID saved = AuthService.uuid(row.get("id"));
            UserService.audit(c, ctx, id == null ? "attendance.location_created" : "attendance.location_changed",
                    "work_location", saved, Map.of("name", name, "punchCheck", check));
            return Map.of("id", saved.toString());
        });
    }

    // ── shifts ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> shifts(SessionCtx ctx) {
        requireView(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select id, name, to_char(start_time, 'HH24:MI') as start_time, to_char(end_time, 'HH24:MI') as end_time, "
                        + "grace_minutes, full_day_minutes, half_day_minutes, active from app.shift order by lower(name)"));
    }

    /** Creates a shift (id null) or changes one. An end time at or before the start means it ends the next day. */
    public Map<String, Object> saveShift(SessionCtx ctx, UUID id, ShiftInput in) {
        ctx.require("attendance.manage");
        String name = name(in.name());
        LocalTime start = AttendanceService.time(in.startTime());
        LocalTime end = AttendanceService.time(in.endTime());
        int grace = in.graceMinutes() == null ? 10 : in.graceMinutes();
        int full = in.fullDayMinutes() == null ? 480 : in.fullDayMinutes();
        int half = in.halfDayMinutes() == null ? 240 : in.halfDayMinutes();
        if (grace < 0 || grace > 120) {
            throw ApiException.badRequest("Grace must be between 0 and 120 minutes");
        }
        if (full < 60 || full > 1440 || half < 30 || half >= full) {
            throw ApiException.badRequest("A full day must be 1 to 24 hours, and a half day at least 30 minutes and shorter than a full day");
        }
        boolean active = in.active() == null || in.active();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> row;
            if (id == null) {
                row = Sql.one(c, "insert into app.shift (tenant_id, name, start_time, end_time, grace_minutes, full_day_minutes, "
                                + "half_day_minutes, active) values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                        ctx.tenantId(), name, start, end, grace, full, half, active);
            } else {
                row = Sql.one(c, "update app.shift set name = ?, start_time = ?, end_time = ?, grace_minutes = ?, "
                                + "full_day_minutes = ?, half_day_minutes = ?, active = ? where id = ? returning id",
                        name, start, end, grace, full, half, active, id);
                if (row == null) {
                    throw ApiException.notFound("The shift");
                }
                Sql.update(c, "delete from app.attendance_day d where exists (select 1 from app.attendance_assignment a "
                        + "where a.membership_id = d.membership_id and a.shift_id = ?)", id);
            }
            UUID saved = AuthService.uuid(row.get("id"));
            UserService.audit(c, ctx, id == null ? "attendance.shift_created" : "attendance.shift_changed",
                    "shift", saved, Map.of("name", name, "hours", start + " to " + end));
            return Map.of("id", saved.toString());
        });
    }

    // ── holidays ────────────────────────────────────────────────────────

    public List<Map<String, Object>> holidays(SessionCtx ctx, Integer year) {
        if (ctx.scopeOf("attendance.read") == null) {
            throw ApiException.denied("attendance.read");
        }
        int y = year == null ? LocalDate.now(AttendanceService.DEFAULT_ZONE).getYear() : year;
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select h.id, h.on_date, h.name, h.work_location_id, l.name as location_name from app.holiday h "
                        + "left join app.work_location l on l.id = h.work_location_id "
                        + "where h.on_date between ? and ? order by h.on_date, l.name",
                LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)));
    }

    /** Adds a holiday for one work location, or for every location when locationId is null. */
    public Map<String, Object> addHoliday(SessionCtx ctx, String date, String holidayName, UUID locationId) {
        ctx.require("attendance.manage");
        String name = name(holidayName);
        if (date == null || date.isBlank()) {
            throw ApiException.badRequest("Choose the date of the holiday");
        }
        LocalDate day = AttendanceService.date(date);
        return db.inTenant(ctx.tenantId(), c -> {
            if (locationId != null && Sql.one(c, "select 1 from app.work_location where id = ?", locationId) == null) {
                throw ApiException.notFound("The work location");
            }
            Map<String, Object> row = Sql.one(c,
                    "insert into app.holiday (tenant_id, work_location_id, on_date, name) values (?, ?, ?, ?) returning id",
                    ctx.tenantId(), locationId, day, name);
            AttendanceService.invalidateDate(c, day);
            UserService.audit(c, ctx, "attendance.holiday_added", "holiday", AuthService.uuid(row.get("id")),
                    Map.of("date", day.toString(), "name", name));
            return Map.of("id", row.get("id"));
        });
    }

    public void removeHoliday(SessionCtx ctx, UUID id) {
        ctx.require("attendance.manage");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> row = Sql.one(c, "delete from app.holiday where id = ? returning on_date, name", id);
            if (row == null) {
                throw ApiException.notFound("The holiday");
            }
            AttendanceService.invalidateDate(c, LocalDate.parse((String) row.get("onDate")));
            UserService.audit(c, ctx, "attendance.holiday_removed", "holiday", id,
                    Map.of("date", row.get("onDate"), "name", row.get("name")));
            return null;
        });
    }

    // ── who works where and when ────────────────────────────────────────

    /** Every current user with their employee code and the assignment in force today. */
    public List<Map<String, Object>> people(SessionCtx ctx) {
        requireView(ctx);
        LocalDate today = LocalDate.now(AttendanceService.DEFAULT_ZONE);   // the database's own date is UTC
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select m.id as membership_id, dir.email, m.status, m.employee_code, a.work_location_id, l.name as location_name, "
                        + "a.shift_id, s.name as shift_name, a.weekly_offs, a.effective_from, "
                        + "(select min(n.effective_from) from app.attendance_assignment n "
                        + "  where n.membership_id = m.id and n.effective_from > ?) as next_change "
                        + "from app.membership m join app.member_directory() dir on dir.membership_id = m.id "
                        + "left join lateral (select * from app.attendance_assignment x where x.membership_id = m.id "
                        + "  and x.effective_from <= ? order by x.effective_from desc, x.created_at desc limit 1) a on true "
                        + "left join app.work_location l on l.id = a.work_location_id left join app.shift s on s.id = a.shift_id "
                        + "where m.status in ('active','suspended') order by dir.email", today, today));
    }

    /**
     * Gives users a work location, shift and weekly offs from a date. Rows are
     * added, never edited: a correction is a new row for the same date.
     */
    public void assign(SessionCtx ctx, List<String> membershipIds, UUID locationId, UUID shiftId,
                       List<Integer> weeklyOffs, String effectiveFrom) {
        ctx.require("attendance.manage");
        if (membershipIds == null || membershipIds.isEmpty()) {
            throw ApiException.badRequest("Choose at least one user");
        }
        if (locationId == null || shiftId == null) {
            throw ApiException.badRequest("Choose a work location and a shift");
        }
        TreeSet<Integer> offs = new TreeSet<>(weeklyOffs == null ? List.of() : weeklyOffs);
        if (offs.stream().anyMatch(d -> d == null || d < 0 || d > 6) || offs.size() > 6) {
            throw ApiException.badRequest("Weekly offs are days 0 (Sunday) to 6 (Saturday), at most six of them");
        }
        if (effectiveFrom == null || effectiveFrom.isBlank()) {
            throw ApiException.badRequest("Choose the date the assignment starts");
        }
        LocalDate from = AttendanceService.date(effectiveFrom);
        List<UUID> ids = new ArrayList<>();
        for (String id : membershipIds) {
            try {
                ids.add(UUID.fromString(id));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw ApiException.badRequest("Not a valid id: " + id);
            }
        }
        String[] offArray = offs.stream().map(String::valueOf).toArray(String[]::new);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> location = Sql.one(c, "select name from app.work_location where id = ? and active", locationId);
            Map<String, Object> shift = Sql.one(c, "select name from app.shift where id = ? and active", shiftId);
            if (location == null) {
                throw ApiException.notFound("The active work location");
            }
            if (shift == null) {
                throw ApiException.notFound("The active shift");
            }
            for (UUID membershipId : ids) {
                if (Sql.one(c, "select 1 from app.membership where id = ? and status in ('active','suspended')", membershipId) == null) {
                    throw ApiException.notFound("The user");
                }
                Sql.update(c, "insert into app.attendance_assignment (tenant_id, membership_id, work_location_id, shift_id, "
                                + "weekly_offs, effective_from, created_by) values (?, ?, ?, ?, ?::smallint[], ?, ?)",
                        ctx.tenantId(), membershipId, locationId, shiftId, offArray, from, ctx.membershipId());
                AttendanceService.invalidateFrom(c, membershipId, from.minusDays(1));   // a night shift's day can start the evening before
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("location", location.get("name"));
                detail.put("shift", shift.get("name"));
                detail.put("from", from.toString());
                UserService.audit(c, ctx, "attendance.assigned", "membership", membershipId, detail);
            }
            return null;
        });
    }

    /** Sets or clears the code that device files use for a user. */
    public void setEmployeeCode(SessionCtx ctx, UUID membershipId, String code) {
        ctx.require("attendance.manage");
        String value = code == null || code.isBlank() ? null : code.trim();
        if (value != null && value.length() > 40) {
            throw ApiException.badRequest("An employee code can be at most 40 characters");
        }
        db.inTenant(ctx.tenantId(), c -> {
            if (Sql.update(c, "update app.membership set employee_code = ? where id = ?", value, membershipId) == 0) {
                throw ApiException.notFound("The user");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", value);
            UserService.audit(c, ctx, "attendance.employee_code_set", "membership", membershipId, detail);
            return null;
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Settings can be seen by anyone who can see everyone's attendance; changing them needs attendance.manage. */
    private static void requireView(SessionCtx ctx) {
        if (!ctx.reachesEveryone("attendance.read")) {
            throw ApiException.denied("attendance.read");
        }
    }

    private static String name(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Give it a name");
        }
        String name = value.trim();
        if (name.length() > 80) {
            throw ApiException.badRequest("The name can be at most 80 characters");
        }
        return name;
    }
}
