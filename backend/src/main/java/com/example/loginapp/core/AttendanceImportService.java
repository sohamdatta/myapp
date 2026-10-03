package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Brings punches in from the files that biometric devices export. HR says once
 * which column holds what; the mapping is remembered per device. Rows are
 * matched to users by employee code, and a punch that is already there is
 * skipped, so the same file can be uploaded twice without harm.
 */
public final class AttendanceImportService {

    static final int MAX_CHARS = 5_000_000;
    static final int MAX_ROWS = 50_000;
    private static final int SAMPLE_ROWS = 5;
    private static final int REJECTED_SAMPLE = 20;

    /** Tried in this order when the mapping does not name a format. Day comes before month. */
    static final List<String> FORMATS = List.of(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss",
            "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
            "dd-MMM-yyyy HH:mm:ss", "dd-MMM-yyyy HH:mm", "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm");

    private static final Set<String> IN_WORDS = Set.of("in", "i", "0", "checkin", "check-in", "check in", "c/in", "entry");
    private static final Set<String> OUT_WORDS = Set.of("out", "o", "1", "checkout", "check-out", "check out", "c/out", "exit");

    private final Db db;
    private final Clock clock;

    public AttendanceImportService(Db db) {
        this(db, Clock.systemUTC());
    }

    public AttendanceImportService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * Which column holds what. Columns are numbered from 0.
     *
     * @param timeColumn      set when the date and the time are in separate columns
     * @param directionColumn null when the device does not say in or out; punches then alternate through the day
     * @param format          a date-time pattern such as dd/MM/yyyy HH:mm:ss, or null to try the common ones
     */
    public record Mapping(Boolean hasHeader, Integer codeColumn, Integer dateTimeColumn, Integer timeColumn,
                          Integer directionColumn, String format, String timezone) {}

    private record Row(int line, UUID membershipId, Instant at, String direction) {}

    // ── preview ─────────────────────────────────────────────────────────

    /** Shows the file's columns and first rows, with the mapping last used for this device or a guess. */
    public Map<String, Object> preview(SessionCtx ctx, String deviceLabel, String content) {
        ctx.require("attendance.manage");
        List<List<String>> rows = parse(content);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("The file has no rows");
        }
        boolean header = looksLikeHeader(rows.get(0));
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String title = header && i < rows.get(0).size() ? rows.get(0).get(i).trim() : "";
            columns.add(title.isEmpty() ? "Column " + (i + 1) : title);
        }
        List<List<String>> body = header ? rows.subList(1, rows.size()) : rows;
        Map<String, Object> saved = deviceLabel == null || deviceLabel.isBlank() ? null
                : db.inTenant(ctx.tenantId(), c -> Sql.one(c,
                "select (mapping->>'hasHeader')::boolean as has_header, (mapping->>'codeColumn')::int as code_column, "
                        + "(mapping->>'dateTimeColumn')::int as date_time_column, (mapping->>'timeColumn')::int as time_column, "
                        + "(mapping->>'directionColumn')::int as direction_column, mapping->>'format' as format, "
                        + "mapping->>'timezone' as timezone from app.import_batch where lower(device_label) = lower(?) "
                        + "order by uploaded_at desc limit 1", deviceLabel.trim()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("sample", body.subList(0, Math.min(SAMPLE_ROWS, body.size())));
        out.put("rows", body.size());
        out.put("mappingSaved", saved != null);
        out.put("mapping", saved != null ? saved : guess(header, columns, body));
        out.put("formats", FORMATS);
        return out;
    }

    // ── import ──────────────────────────────────────────────────────────

    public Map<String, Object> importFile(SessionCtx ctx, String deviceLabel, String fileName, String content, Mapping mapping) {
        ctx.require("attendance.manage");
        if (deviceLabel == null || deviceLabel.isBlank()) {
            throw ApiException.badRequest("Name the device this file came from");
        }
        if (mapping == null || mapping.codeColumn() == null || mapping.dateTimeColumn() == null) {
            throw ApiException.badRequest("Choose the employee code column and the date and time column");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(mapping.timezone() == null || mapping.timezone().isBlank() ? "Asia/Kolkata" : mapping.timezone().trim());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Unknown time zone: " + mapping.timezone());
        }
        List<DateTimeFormatter> formats = new ArrayList<>();
        try {
            for (String f : mapping.format() == null || mapping.format().isBlank() ? FORMATS : List.of(mapping.format().trim())) {
                formats.add(DateTimeFormatter.ofPattern(f, Locale.ENGLISH));
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The date and time format is not valid: " + mapping.format());
        }
        List<List<String>> rows = parse(content);
        boolean header = mapping.hasHeader() != null && mapping.hasHeader();
        int firstRow = header ? 1 : 0;
        if (rows.size() <= firstRow) {
            throw ApiException.badRequest("The file has no rows");
        }
        String label = deviceLabel.trim();
        String file = fileName == null || fileName.isBlank() ? "upload.csv" : fileName.trim();
        Instant now = clock.instant();

        return db.inTenant(ctx.tenantId(), c -> {
            Sql.query(c, "select pg_advisory_xact_lock(hashtext(?))", "attendance-import:" + ctx.tenantId());   // one import at a time
            Map<String, UUID> byCode = new HashMap<>();
            for (Map<String, Object> m : Sql.query(c, "select id, lower(employee_code) as code from app.membership where employee_code is not null")) {
                byCode.put((String) m.get("code"), AuthService.uuid(m.get("id")));
            }

            List<String> rejected = new ArrayList<>();
            int rejectedCount = 0;
            int duplicates = 0;
            List<Row> read = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = firstRow; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                String reason = null;
                UUID member = null;
                Instant at = null;
                String direction = null;
                String code = cell(r, mapping.codeColumn());
                if (code.isEmpty()) {
                    reason = "No employee code";
                } else if ((member = byCode.get(code.toLowerCase(Locale.ROOT))) == null) {
                    reason = "No user has employee code " + code;
                } else {
                    String when = cell(r, mapping.dateTimeColumn());
                    if (mapping.timeColumn() != null) {
                        when = (when + " " + cell(r, mapping.timeColumn())).trim();
                    }
                    at = parseWhen(when, formats, zone);
                    if (at == null) {
                        reason = "Unreadable date and time: " + when;
                    } else if (at.isAfter(now)) {
                        reason = "In the future: " + when;
                    } else if (mapping.directionColumn() != null) {
                        String word = cell(r, mapping.directionColumn()).toLowerCase(Locale.ROOT);
                        direction = IN_WORDS.contains(word) ? "in" : OUT_WORDS.contains(word) ? "out" : null;
                        if (direction == null) {
                            reason = "Neither in nor out: " + cell(r, mapping.directionColumn());
                        }
                    }
                }
                if (reason != null) {
                    rejectedCount++;
                    if (rejected.size() < REJECTED_SAMPLE) {
                        rejected.add("{\"row\":" + (i + 1) + ",\"reason\":" + Json.quote(reason) + "}");
                    }
                } else if (!seen.add(member + "|" + at + "|" + direction)) {
                    duplicates++;
                } else {
                    read.add(new Row(i + 1, member, at, direction));
                }
            }

            // what is already there, so a file uploaded twice adds nothing
            List<Row> fresh = new ArrayList<>();
            Map<UUID, List<Row>> perMember = new LinkedHashMap<>();
            for (Row r : read) {
                perMember.computeIfAbsent(r.membershipId(), k -> new ArrayList<>()).add(r);
            }
            Map<UUID, LocalDate[]> touched = new HashMap<>();
            for (Map.Entry<UUID, List<Row>> e : perMember.entrySet()) {
                List<Row> mine = e.getValue();
                mine.sort(Comparator.comparing(Row::at));
                Instant from = mine.get(0).at();
                Instant to = mine.get(mine.size() - 1).at();
                Set<String> existing = new HashSet<>();
                TreeSet<Instant> existingTimes = new TreeSet<>();
                for (Map<String, Object> p : Sql.query(c,
                        "select at, direction from app.punch where membership_id = ? and source = 'import' and at between ? and ?",
                        e.getKey(), Timestamp.from(from.minusSeconds(86_400)), Timestamp.from(to.plusSeconds(86_400)))) {
                    Instant at = Instant.parse((String) p.get("at"));
                    existing.add(at + "|" + p.get("direction"));
                    existingTimes.add(at);
                }
                for (Row r : mine) {
                    if (r.direction() != null ? existing.contains(r.at() + "|" + r.direction()) : existingTimes.contains(r.at())) {
                        duplicates++;
                    } else {
                        fresh.add(r);
                    }
                }
                touched.put(e.getKey(), new LocalDate[] {from.atZone(zone).toLocalDate().minusDays(1), to.atZone(zone).toLocalDate().plusDays(1)});
                if (mapping.directionColumn() == null) {
                    alternate(c, e.getKey(), fresh, existingTimes, zone);
                }
            }

            Instant firstAt = fresh.stream().map(Row::at).min(Comparator.naturalOrder()).orElse(null);
            Instant lastAt = fresh.stream().map(Row::at).max(Comparator.naturalOrder()).orElse(null);
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("hasHeader", header);
            saved.put("codeColumn", mapping.codeColumn());
            saved.put("dateTimeColumn", mapping.dateTimeColumn());
            saved.put("timeColumn", mapping.timeColumn());
            saved.put("directionColumn", mapping.directionColumn());
            saved.put("format", mapping.format() == null || mapping.format().isBlank() ? null : mapping.format().trim());
            saved.put("timezone", zone.getId());
            int total = rows.size() - firstRow;
            Map<String, Object> batch = Sql.one(c,
                    "insert into app.import_batch (tenant_id, device_label, file_name, mapping, rows_total, rows_imported, rows_duplicate, "
                            + "rows_rejected, rejected_sample, first_punch_at, last_punch_at, uploaded_by) "
                            + "values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) returning id",
                    ctx.tenantId(), label, file, Json.object(saved), total, fresh.size(), duplicates, rejectedCount,
                    "[" + String.join(",", rejected) + "]", firstAt == null ? null : Timestamp.from(firstAt),
                    lastAt == null ? null : Timestamp.from(lastAt), ctx.membershipId());
            UUID batchId = AuthService.uuid(batch.get("id"));
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into app.punch (tenant_id, membership_id, at, direction, source, import_batch_id) values (?, ?, ?, ?, 'import', ?)")) {
                for (Row r : fresh) {
                    ps.setObject(1, ctx.tenantId());
                    ps.setObject(2, r.membershipId());
                    ps.setTimestamp(3, Timestamp.from(r.at()));
                    ps.setString(4, r.direction());
                    ps.setObject(5, batchId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            for (Map.Entry<UUID, LocalDate[]> t : touched.entrySet()) {
                Sql.update(c, "delete from app.attendance_day where membership_id = ? and work_date between ? and ?",
                        t.getKey(), t.getValue()[0], t.getValue()[1]);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("device", label);
            detail.put("file", file);
            detail.put("imported", fresh.size());
            detail.put("duplicate", duplicates);
            detail.put("rejected", rejectedCount);
            UserService.audit(c, ctx, "attendance.imported", "import_batch", batchId, detail);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", batchId.toString());
            out.put("rowsTotal", total);
            out.put("rowsImported", fresh.size());
            out.put("rowsDuplicate", duplicates);
            out.put("rowsRejected", rejectedCount);
            return out;
        });
    }

    /** Each device's last upload and last punch, and the recent files with what was rejected. */
    public Map<String, Object> health(SessionCtx ctx) {
        if (!ctx.reachesEveryone("attendance.read")) {
            throw ApiException.denied("attendance.read");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("devices", Sql.query(c,
                    "select min(device_label) as device_label, count(*) as files, max(uploaded_at) as last_upload, "
                            + "max(last_punch_at) as last_punch, sum(rows_imported) as rows_imported, sum(rows_rejected) as rows_rejected, "
                            + "((now() at time zone 'Asia/Kolkata')::date - (max(last_punch_at) at time zone 'Asia/Kolkata')::date) as days_since_last_punch "
                            + "from app.import_batch group by lower(device_label) order by 1"));
            out.put("batches", Sql.query(c,
                    "select b.id, b.device_label, b.file_name, b.rows_total, b.rows_imported, b.rows_duplicate, b.rows_rejected, "
                            + "array(select 'Row ' || (e->>'row') || ': ' || (e->>'reason') from jsonb_array_elements(b.rejected_sample) e) as rejected, "
                            + "b.first_punch_at, b.last_punch_at, b.uploaded_at, dir.email as uploaded_by "
                            + "from app.import_batch b left join app.member_directory() dir on dir.membership_id = b.uploaded_by "
                            + "order by b.uploaded_at desc limit 50"));
            return out;
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Gives a direction to this user's new punches when the device does not:
     * through each work date, punches alternate in, out, in, out, counting the
     * ones imported earlier.
     */
    private static void alternate(Connection c, UUID membershipId, List<Row> fresh, TreeSet<Instant> existingTimes,
                                  ZoneId fileZone) throws SQLException {
        Map<LocalDate, TreeSet<Instant>> byDay = new HashMap<>();
        List<Integer> mine = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            if (fresh.get(i).membershipId().equals(membershipId) && fresh.get(i).direction() == null) {
                mine.add(i);
            }
        }
        Map<LocalDate, AttendanceService.Assigned> assignments = new HashMap<>();
        Function<Instant, LocalDate> workDate = at -> {
            LocalDate local = at.atZone(fileZone).toLocalDate();
            AttendanceService.Assigned a = assignments.computeIfAbsent(local, d -> {
                try {
                    return AttendanceService.assigned(c, membershipId, d);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            return a == null ? local : PunchChecks.workDate(at, a.zone(), a.schedule().start(), a.schedule().end());
        };
        for (Instant at : existingTimes) {
            byDay.computeIfAbsent(workDate.apply(at), k -> new TreeSet<>()).add(at);
        }
        for (int i : mine) {
            byDay.computeIfAbsent(workDate.apply(fresh.get(i).at()), k -> new TreeSet<>()).add(fresh.get(i).at());
        }
        for (int i : mine) {
            Row r = fresh.get(i);
            int position = byDay.get(workDate.apply(r.at())).headSet(r.at()).size();
            fresh.set(i, new Row(r.line(), r.membershipId(), r.at(), position % 2 == 0 ? "in" : "out"));
        }
    }

    private static Instant parseWhen(String text, List<DateTimeFormatter> formats, ZoneId zone) {
        for (DateTimeFormatter f : formats) {
            try {
                return LocalDateTime.parse(text, f).atZone(zone).toInstant();
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return null;
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index).trim() : "";
    }

    /** A first row with no digits in it is taken to be column titles. */
    private static boolean looksLikeHeader(List<String> row) {
        return row.stream().noneMatch(v -> v.chars().anyMatch(Character::isDigit))
                && row.stream().anyMatch(v -> !v.isBlank());
    }

    private static Map<String, Object> guess(boolean header, List<String> columns, List<List<String>> body) {
        Integer code = null;
        Integer dateTime = null;
        Integer time = null;
        Integer direction = null;
        for (int i = 0; i < columns.size(); i++) {
            String title = columns.get(i).toLowerCase(Locale.ROOT);
            String value = body.isEmpty() ? "" : cell(body.get(0), i);
            boolean dateLike = value.matches(".*\\d{1,4}[-/]\\w{1,3}[-/]\\d{1,4}.*");
            boolean timeOnly = value.matches("\\d{1,2}:\\d{2}(:\\d{2})?");
            if (dateTime == null && (dateLike || (header && title.contains("date")))) {
                dateTime = i;
            } else if (time == null && dateTime != null && timeOnly) {
                time = i;
            } else if (direction == null && header && (title.contains("direction") || title.contains("status")
                    || title.contains("type") || title.contains("in/out") || title.contains("mode"))) {
                direction = i;
            } else if (code == null && (header ? title.contains("code") || title.contains("emp") || title.contains("user")
                    || title.equals("id") : !value.isEmpty() && !dateLike && !timeOnly)) {
                code = i;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasHeader", header);
        m.put("codeColumn", code);
        m.put("dateTimeColumn", dateTime);
        m.put("timeColumn", time);
        m.put("directionColumn", direction);
        m.put("format", null);
        m.put("timezone", "Asia/Kolkata");
        return m;
    }

    /** Splits delimited text into rows of cells. The delimiter is the tab, semicolon or comma found most in the first line. */
    static List<List<String>> parse(String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("The file is empty");
        }
        if (content.length() > MAX_CHARS) {
            throw ApiException.badRequest("The file is too large; split it into files under 5 MB");
        }
        String text = content.charAt(0) == '﻿' ? content.substring(1) : content;
        int lineEnd = text.indexOf('\n');
        String firstLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
        char delimiter = ',';
        long best = 0;
        for (char candidate : new char[] {'\t', ';', ','}) {
            long count = firstLine.chars().filter(ch -> ch == candidate).count();
            if (count > best) {
                best = count;
                delimiter = candidate;
            }
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.length() == 0) {
                quoted = true;
            } else if (ch == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (row.stream().anyMatch(v -> !v.isBlank())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString());
        if (row.stream().anyMatch(v -> !v.isBlank())) {
            rows.add(row);
        }
        if (rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("The file has more than " + MAX_ROWS + " rows; split it into smaller files");
        }
        return rows;
    }
}
