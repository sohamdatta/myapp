package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Biometric machines that send punches by themselves: eSSL and ZKTeco devices with "ADMS"
 * (cloud push). The device calls in with its serial number, picks up a few settings, and then
 * posts its attendance log as lines of text. Each line becomes a punch for the employee whose
 * employee code matches the device's user id.
 *
 * <p>A device proves nothing but its serial number, so a serial must be registered by HR first,
 * and when the location has office network addresses, calls from anywhere else are refused.
 */
public final class DeviceService {

    /** A device's clock may run a little fast; later than this is "in the future". */
    static final Duration CLOCK_SLACK = Duration.ofMinutes(10);
    static final int MAX_LINES = 20_000;
    static final Pattern SERIAL = Pattern.compile("^[A-Za-z0-9-]{4,40}$");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final Pattern LOOSE = Pattern.compile("^\\s*(\\S+)\\s+(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    private final Db db;
    private final Clock clock;

    public DeviceService(Db db) {
        this(db, Clock.systemUTC());
    }

    public DeviceService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ── what the device calls ───────────────────────────────────────────

    /** One line of a device's attendance log: its user id and local time, or why it could not be read. */
    public record Line(String code, LocalDateTime at, String raw) {}

    /** Reads an ATTLOG upload: "user id TAB yyyy-MM-dd HH:mm:ss TAB status TAB verify ...", one punch a line. */
    public static List<Line> parse(String body) {
        List<Line> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (String raw : body.split("\\r?\\n")) {
            if (raw.isBlank()) {
                continue;
            }
            String code = null;
            String when = null;
            String[] f = raw.split("\t");
            if (f.length >= 2) {
                code = f[0].trim();
                when = f[1].trim();
            } else {
                Matcher m = LOOSE.matcher(raw);
                if (m.find()) {
                    code = m.group(1);
                    when = m.group(2);
                }
            }
            LocalDateTime at = null;
            if (when != null) {
                try {
                    at = LocalDateTime.parse(when, WHEN);
                } catch (DateTimeParseException e) {
                    at = null;
                }
            }
            out.add(new Line(code == null || code.isEmpty() ? null : code, at, raw.length() > 200 ? raw.substring(0, 200) : raw));
        }
        return out;
    }

    /** The settings a device asks for when it starts: send attendance only, at once, and from where it got to. */
    static String options(String serial, String stamp) {
        return String.join("\r\n",
                "GET OPTION FROM: " + serial,
                "ATTLOGStamp=" + (stamp == null || stamp.isBlank() ? "None" : stamp),
                "OPERLOGStamp=9999",
                "ATTPHOTOStamp=None",
                "ErrorDelay=60",
                "Delay=30",
                "TransTimes=00:00;14:05",
                "TransInterval=1",
                "TransFlag=TransData AttLog",
                "Realtime=1",
                "Encrypt=None") + "\r\n";
    }

    /** The device starts up and asks for its settings. */
    public String handshake(String serial, String ip) {
        Found f = find(serial);
        return db.inTenant(f.tenantId(), c -> {
            Map<String, Object> d = allowed(c, f, ip);
            return options((String) d.get("serialNumber"), (String) d.get("attlogStamp"));
        });
    }

    /** The device asks whether there is anything for it to do. There never is. */
    public String commands(String serial, String ip) {
        Found f = find(serial);
        return db.inTenant(f.tenantId(), c -> {
            allowed(c, f, ip);
            return "OK";
        });
    }

    /**
     * The device posts part of its log. Attendance lines become punches; other tables (operator
     * logs, photos, user lists) are acknowledged and dropped. Answers "OK: n" so the device moves on.
     */
    public String receive(String serial, String table, String stamp, String body, String ip) {
        Found f = find(serial);
        List<Line> lines = "ATTLOG".equalsIgnoreCase(table) ? parse(body) : List.of();
        if (lines.size() > MAX_LINES) {
            throw new ApiException(413, "device.too_many_lines", "Send at most " + MAX_LINES + " lines at a time");
        }
        Instant now = clock.instant();
        return db.inTenant(f.tenantId(), c -> {
            Map<String, Object> d = allowed(c, f, ip);
            if (lines.isEmpty()) {
                return "OK: 0";
            }
            Sql.query(c, "select pg_advisory_xact_lock(hashtext(?))", "attendance-device:" + f.deviceId());   // one upload at a time
            ZoneId zone = AttendanceService.zone((String) d.get("timezone"));
            UUID location = AuthService.uuid(d.get("workLocationId"));
            Map<String, UUID> byCode = new HashMap<>();
            for (Map<String, Object> m : Sql.query(c, "select id, lower(employee_code) as code from app.membership where employee_code is not null")) {
                byCode.put((String) m.get("code"), AuthService.uuid(m.get("id")));
            }
            Map<UUID, Set<LocalDate>> touched = new HashMap<>();
            for (Line line : lines) {
                String outcome;
                UUID member = null;
                Instant at = line.at() == null ? null : line.at().atZone(zone).toInstant();
                if (line.code() == null || at == null) {
                    outcome = "unreadable";
                } else if ((member = byCode.get(line.code().toLowerCase(Locale.ROOT))) == null) {
                    outcome = "unknown_code";
                } else if (at.isAfter(now.plus(CLOCK_SLACK))) {
                    outcome = "in_the_future";
                } else {
                    outcome = punch(c, f, member, at, location, ip, touched) ? "imported" : "duplicate";
                }
                Sql.update(c, "insert into app.device_punch_log (tenant_id, device_id, ip, employee_code, punched_at, outcome, line) "
                                + "values (?, ?, ?::inet, ?, ?, ?, ?)",
                        f.tenantId(), f.deviceId(), ip, line.code() == null ? null : line.code().length() > 40 ? line.code().substring(0, 40) : line.code(),
                        at == null ? null : Timestamp.from(at), outcome, line.raw());
            }
            for (Map.Entry<UUID, Set<LocalDate>> t : touched.entrySet()) {
                for (LocalDate day : t.getValue()) {
                    AttendanceService.forget(c, t.getKey(), day, day);
                }
            }
            if (stamp != null && !stamp.isBlank() && stamp.length() <= 40) {
                Sql.update(c, "update app.attendance_device set attlog_stamp = ? where id = ?", stamp.trim(), f.deviceId());
            }
            return "OK: " + lines.size();
        });
    }

    /** Stores one punch unless it is already there. In and out go by order within the work day. */
    private static boolean punch(Connection c, Found f, UUID member, Instant at, UUID location, String ip,
                                 Map<UUID, Set<LocalDate>> touched) throws SQLException {
        LocalDate local = at.atZone(AttendanceService.DEFAULT_ZONE).toLocalDate();
        AttendanceService.Assigned a = AttendanceService.assigned(c, member, local);
        LocalDate workDate = a == null ? local : PunchChecks.workDate(at, a.zone(), a.schedule().start(), a.schedule().end());
        String direction = "in";
        if (a != null) {
            Map<String, Object> before = Sql.one(c, "select count(*) as n from app.punch where membership_id = ? and at >= ? and at < ? "
                            + "and source <> 'request'", member,
                    Timestamp.from(PunchChecks.dayStart(workDate, a.zone(), a.schedule().start(), a.schedule().end())), Timestamp.from(at));
            direction = ((Number) before.get("n")).longValue() % 2 == 0 ? "in" : "out";
        }
        Map<String, Object> row = Sql.one(c, "insert into app.punch (tenant_id, membership_id, at, direction, source, work_location_id, ip, "
                        + "check_result, device_id) values (?, ?, ?, ?, 'device', ?, ?::inet, 'not_checked', ?) "
                        + "on conflict (membership_id, at) where source = 'device' do nothing returning id",
                f.tenantId(), member, Timestamp.from(at), direction, location, ip, f.deviceId());
        if (row == null) {
            return false;
        }
        touched.computeIfAbsent(member, k -> new LinkedHashSet<>()).add(workDate);
        return true;
    }

    private record Found(UUID tenantId, UUID deviceId) {}

    private Found find(String serial) {
        if (serial == null || !SERIAL.matcher(serial.trim()).matches()) {
            throw new ApiException(400, "device.serial", "The device did not give a serial number");
        }
        Map<String, Object> r = db.inAuth(c -> Sql.one(c, "select tenant_id, device_id from auth.device_for_serial(?)", serial.trim()));
        if (r == null) {
            throw new ApiException(404, "device.unknown", "This device is not registered");
        }
        return new Found(AuthService.uuid(r.get("tenantId")), AuthService.uuid(r.get("deviceId")));
    }

    /**
     * The device's row, once the call is known to come from the office network (when the location
     * names one). Notes when it was last heard from, or why it was turned away.
     */
    private static Map<String, Object> allowed(Connection c, Found f, String ip) throws SQLException {
        Map<String, Object> d = Sql.one(c, "select d.serial_number, d.attlog_stamp, d.work_location_id, l.name as location, l.timezone, "
                + "cardinality(l.allowed_ips) as networks, coalesce(?::inet <<= any(l.allowed_ips), false) as on_network "
                + "from app.attendance_device d join app.work_location l on l.id = d.work_location_id where d.id = ?", ip, f.deviceId());
        if (((Number) d.get("networks")).intValue() > 0 && !Boolean.TRUE.equals(d.get("onNetwork"))) {
            // Recorded in a transaction of its own, so it stays although this call is refused.
            Sql.update(c, "update app.attendance_device set last_refused_at = now(), last_refused = ? where id = ?",
                    "Called from " + ip + ", which is not an office network address of " + d.get("location"), f.deviceId());
            c.commit();
            throw new ApiException(403, "device.outside_network", "This device is not on the office network");
        }
        Sql.update(c, "update app.attendance_device set last_seen_at = now(), last_ip = ?::inet where id = ?", ip, f.deviceId());
        return d;
    }

    // ── what HR does ────────────────────────────────────────────────────

    public record DeviceInput(String name, String serialNumber, String workLocationId, Boolean active) {}

    /** Every device with where it is, when it last called, and what it sent lately. */
    public List<Map<String, Object>> devices(SessionCtx ctx) {
        ctx.require("attendance.manage");
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> rows = Sql.query(c, "select d.id, d.name, d.serial_number, d.active, d.work_location_id, l.name as location, "
                    + "cardinality(l.allowed_ips) > 0 as network_locked, d.last_seen_at, d.last_ip, d.last_refused_at, d.last_refused, "
                    + "(select count(*) from app.device_punch_log g where g.device_id = d.id and g.outcome = 'imported' "
                    + "  and g.received_at >= now() - interval '1 day') as imported_last_day "
                    + "from app.attendance_device d join app.work_location l on l.id = d.work_location_id order by lower(d.name)");
            for (Map<String, Object> r : rows) {
                r.put("unknownCodes", Sql.query(c, "select employee_code as code, count(*) as lines from app.device_punch_log "
                        + "where device_id = ? and outcome = 'unknown_code' and received_at >= now() - interval '7 days' "
                        + "group by employee_code order by count(*) desc limit 10", AuthService.uuid(r.get("id"))));
            }
            return rows;
        });
    }

    /** The latest lines a device sent and what became of each. */
    public List<Map<String, Object>> log(SessionCtx ctx, UUID deviceId) {
        ctx.require("attendance.manage");
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.one(c, "select 1 from app.attendance_device where id = ?", deviceId) == null) {
                throw ApiException.notFound("The device");
            }
            return Sql.query(c, "select received_at, employee_code, punched_at, outcome, line from app.device_punch_log "
                    + "where device_id = ? order by received_at desc, punched_at desc limit 50", deviceId);
        });
    }

    /** Registers a device (id null) or changes one. A serial number belongs to one device on the whole platform. */
    public Map<String, Object> save(SessionCtx ctx, UUID id, DeviceInput in) {
        ctx.require("attendance.manage");
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiException.badRequest("Give the device a name of up to 80 characters, such as Main gate");
        }
        String serial = in.serialNumber() == null ? "" : in.serialNumber().trim().toUpperCase(Locale.ROOT);
        if (id == null && !SERIAL.matcher(serial).matches()) {
            throw ApiException.badRequest("The serial number is 4 to 40 letters, digits or dashes, as printed on the device "
                    + "or shown under its System info");
        }
        if (in.workLocationId() == null || in.workLocationId().isBlank()) {
            throw ApiException.badRequest("Choose the work location the device is at");
        }
        UUID location;
        try {
            location = UUID.fromString(in.workLocationId().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Choose the work location the device is at");
        }
        if (id == null && Boolean.TRUE.equals(db.inAuth(c -> Sql.one(c, "select auth.device_serial_taken(?) as taken", serial).get("taken")))) {
            throw new ApiException(409, "device.serial_taken", "A device with serial number " + serial + " is already registered");
        }
        boolean active = in.active() == null || in.active();
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.one(c, "select 1 from app.work_location where id = ? and active", location) == null) {
                throw ApiException.notFound("The work location");
            }
            Map<String, Object> row;
            if (id == null) {
                row = Sql.one(c, "insert into app.attendance_device (tenant_id, work_location_id, name, serial_number, active, created_by) "
                        + "values (?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), location, name, serial, active, ctx.membershipId());
            } else {
                row = Sql.one(c, "update app.attendance_device set name = ?, work_location_id = ?, active = ? where id = ? returning id",
                        name, location, active, id);
                if (row == null) {
                    throw ApiException.notFound("The device");
                }
            }
            UUID saved = AuthService.uuid(row.get("id"));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", name);
            detail.put("active", active);
            if (id == null) {
                detail.put("serialNumber", serial);
            }
            UserService.audit(c, ctx, id == null ? "attendance.device_registered" : "attendance.device_changed",
                    "attendance_device", saved, detail);
            return Map.of("id", saved.toString());
        });
    }
}
