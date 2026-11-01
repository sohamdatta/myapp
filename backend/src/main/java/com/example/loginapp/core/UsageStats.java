package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.loginapp.core.UsageMetrics.Metric;
import com.example.loginapp.core.UsageMetrics.Source;

/**
 * How each Organization uses each feature, as daily counts only (release U1). A nightly job counts inside every Organization
 * (see {@link UsageMetrics}) and stores one number per Organization, day and metric in {@code platform.usage_daily}; the platform
 * team reads them as a grid of Organizations by module, a 12-week trend for one Organization and a cross-Organization view of one
 * metric. Nothing a person did is recorded for this: the counts come from records the app already keeps, and the platform's database
 * role still cannot read any Organization's own tables.
 *
 * The first run for an Organization works out the last 90 days; later runs fill the days since the last one and redo yesterday.
 * Rows are kept for two years.
 */
public final class UsageStats {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int BACKFILL_DAYS = 90;
    /** The last days counted again every night, because some records arrive late (imports, a filing date keyed in afterwards). */
    static final int REDO_DAYS = 7;
    private static final int MAX_FAILURES = 20;
    private static final int WEEKS = 12;
    /** The modules a grid column stands for, in order; core is what every Organization has. */
    private static final List<String> MODULES = List.of("core", "attendance", "leave", "payroll");

    /** One module for one Organization: whether it is bought, how much it was used lately, and which of the four states that makes. */
    public record Cell(String module, String name, boolean bought, String state, BigDecimal uses7, BigDecimal uses30) {}

    public record OrganizationRow(String id, String name, String slug, String status, List<Cell> cells, BigDecimal members) {}

    /** Active: used in the last 7 days. Quiet: used in the last 30 but not the last 7. Bought, not used: on, and nothing in 30 days. */
    public record Grid(String asOf, String latestDay, List<OrganizationRow> organizations) {}

    /** One metric's weekly numbers, oldest first; a week with nothing to say is null. */
    public record Series(String code, String module, String area, String description, String kind, boolean isUse, List<BigDecimal> weeks) {}

    public record Trend(String id, String name, List<String> weekStarts, List<Series> series) {}

    public record OrganizationSeries(String id, String name, List<BigDecimal> weeks, BigDecimal total) {}

    public record MetricView(String code, String module, String area, String description, String kind, List<String> weekStarts,
                             List<OrganizationSeries> organizations) {}

    public record MetricInfo(String code, String module, String area, String description, String kind, boolean isUse) {}

    /** What a run did. {@code failures} names any metric whose query failed, so a broken one is seen, not silently zero. */
    public record RunResult(boolean ran, int organizations, int days, int values, List<String> failures) {}

    private final Db db;
    private final Clock clock;

    public UsageStats(Db db) {
        this(db, Clock.system(IST));
    }

    public UsageStats(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── the job ─────────────────────────────────────────────────────────

    /**
     * The nightly run: every Organization still open gets the days it is missing up to yesterday, and yesterday again (it may have
     * been counted before the day ended). With {@code includeToday} the day so far is counted as well, for a refresh by hand.
     */
    public RunResult run(boolean includeToday) {
        // One count at a time across every server: the lock is held by this connection for as long as the run takes.
        return db.inPlatform(c -> {
            if (!Boolean.TRUE.equals(Sql.one(c, "select pg_try_advisory_xact_lock(hashtext('usage_stats')) as l").get("l"))) {
                return new RunResult(false, 0, 0, 0, List.of());
            }
            return runLocked(includeToday);
        });
    }

    private RunResult runLocked(boolean includeToday) {
        LocalDate last = includeToday ? today() : today().minusDays(1);
        LocalDate yesterday = today().minusDays(1);
        publishMetrics();
        record Tenant(UUID id, LocalDate created) {}
        List<Tenant> tenants = new ArrayList<>();
        for (Map<String, Object> t : db.inPlatform(c -> Sql.query(c, "select id, created_at from platform.tenant where status <> 'closed' order by created_at"))) {
            tenants.add(new Tenant(AuthService.uuid(t.get("id")), java.time.Instant.parse(String.valueOf(t.get("createdAt"))).atZone(IST).toLocalDate()));
        }
        int days = 0;
        int values = 0;
        List<String> failures = new ArrayList<>();
        for (Tenant tenant : tenants) {
            Object latest = db.inPlatform(c -> Sql.one(c, "select max(day) as d from platform.usage_daily where tenant_id = ?", tenant.id())).get("d");
            // Never before the Organization existed or further back than the backfill. The first time, all of that; after, from the first
            // day with no numbers or the last few days again, whichever is earlier, since some records arrive late.
            LocalDate floor = last.minusDays(BACKFILL_DAYS - 1L);
            if (tenant.created().isAfter(floor)) {
                floor = tenant.created();
            }
            LocalDate from = latest == null ? floor : LocalDate.parse(String.valueOf(latest)).plusDays(1);
            LocalDate redoFrom = yesterday.minusDays(REDO_DAYS - 1L);
            if (latest != null && from.isAfter(redoFrom)) {
                from = redoFrom;
            }
            if (from.isBefore(floor)) {
                from = floor;
            }
            try {
                for (LocalDate day = from; !day.isAfter(last); day = day.plusDays(1)) {
                    List<String> dayFailures = new ArrayList<>();
                    Map<String, BigDecimal> counted = countDay(tenant.id(), day, dayFailures);
                    // A metric whose query failed keeps the number it had; it is not deleted as if there were nothing to report.
                    Set<String> failed = new HashSet<>();
                    dayFailures.forEach(f -> failed.add(f.substring(0, f.indexOf(':'))));
                    store(tenant.id(), day, counted, failed);
                    days++;
                    values += counted.size();
                    for (String f : dayFailures) {
                        if (failures.size() < MAX_FAILURES) {
                            failures.add(f);
                        }
                    }
                }
            } catch (RuntimeException e) {
                // One Organization failing (a lost connection, say) must not stop the others being counted.
                if (failures.size() < MAX_FAILURES) {
                    failures.add("organization " + tenant.id() + ": " + e.getClass().getSimpleName());
                }
            }
        }
        LocalDate keepFrom = today().minusYears(2);
        db.inPlatform(c -> Sql.update(c, "delete from platform.usage_daily where day < ?", keepFrom));
        return new RunResult(true, tenants.size(), days, values, failures);
    }

    /** One Organization's numbers for one day. A metric with nothing to report (a median of nothing) is left out. */
    Map<String, BigDecimal> countDay(UUID tenant, LocalDate day, List<String> failures) {
        Object[] args = {tenant, day.atStartOfDay(IST).toOffsetDateTime(), day.plusDays(1).atStartOfDay(IST).toOffsetDateTime(), day};
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        db.inTenant(tenant, c -> runAll(c, Source.TENANT, args, out, failures));
        db.inAuth(c -> runAll(c, Source.AUTH, args, out, failures));
        db.inPlatform(c -> runAll(c, Source.PLATFORM, args, out, failures));
        return out;
    }

    private static Object runAll(Connection c, Source source, Object[] args, Map<String, BigDecimal> out, List<String> failures) throws SQLException {
        for (Metric m : UsageMetrics.ALL) {
            if (m.source() != source) {
                continue;
            }
            Savepoint sp = c.setSavepoint();   // a failing query must not poison the rest of the transaction
            try {
                Map<String, Object> row = Sql.one(c, m.sql(), args);
                Object v = row == null ? null : row.get("v");
                if (v != null) {
                    out.put(m.code(), new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO));
                }
            } catch (SQLException e) {
                c.rollback(sp);
                if (failures.size() < MAX_FAILURES) {
                    failures.add(m.code() + ": SQLSTATE " + e.getSQLState());
                }
            }
        }
        return null;
    }

    private void store(UUID tenant, LocalDate day, Map<String, BigDecimal> counted, Set<String> failed) {
        db.inPlatform(c -> {
            // Upserted, so a repeat or an overlapping run only rewrites the same numbers; a metric that now has nothing leaves no stale one.
            List<Object[]> rows = new ArrayList<>();
            counted.forEach((metric, value) -> rows.add(new Object[] {tenant, day, metric, value}));
            Sql.batch(c, "insert into platform.usage_daily (tenant_id, day, metric, value) values (?, ?, ?, ?) "
                    + "on conflict (tenant_id, day, metric) do update set value = excluded.value, computed_at = now()", rows);
            Set<String> keep = new HashSet<>(counted.keySet());
            keep.addAll(failed);
            Sql.update(c, "delete from platform.usage_daily where tenant_id = ? and day = ? and metric <> all(?)", tenant, day, keep.toArray(new String[0]));
            return null;
        });
    }

    /** Writes the list of metrics, so a new one is code and not a migration. */
    private void publishMetrics() {
        db.inPlatform(c -> {
            for (Metric m : UsageMetrics.ALL) {
                Sql.update(c, "insert into platform.usage_metric (code, module, area, description, kind, is_use) values (?, ?, ?, ?, ?, ?) "
                        + "on conflict (code) do update set module = excluded.module, area = excluded.area, description = excluded.description, "
                        + "kind = excluded.kind, is_use = excluded.is_use", m.code(), m.module(), m.area(), m.description(), m.kind(), m.isUse());
            }
            return null;
        });
    }

    /** The run by hand: the days not counted yet and today so far (Super Admin only). */
    public RunResult refresh(SessionCtx ctx) {
        ctx.requirePlatform("super_admin");
        RunResult r = run(true);
        if (!r.ran()) {
            throw new ApiException(409, "usage.running", "A count is already running. Try again in a few minutes.");
        }
        return r;
    }

    // ── reading ─────────────────────────────────────────────────────────

    public List<MetricInfo> metrics(SessionCtx ctx) {
        ctx.requirePlatform("super_admin", "support", "billing");
        List<MetricInfo> out = new ArrayList<>();
        for (Metric m : UsageMetrics.ALL) {
            out.add(new MetricInfo(m.code(), m.module(), m.area(), m.description(), m.kind(), m.isUse()));
        }
        return out;
    }

    public Grid grid(SessionCtx ctx) {
        ctx.requirePlatform("super_admin", "support", "billing");
        LocalDate today = today();
        return db.inPlatform(c -> {
            Map<String, Map<String, BigDecimal[]>> uses = new LinkedHashMap<>();   // tenant, module -> {last 7 days, last 30 days}
            for (Map<String, Object> r : Sql.query(c, "select d.tenant_id, m.module, "
                    + "coalesce(sum(d.value) filter (where d.day >= ?), 0) as u7, sum(d.value) as u30 "
                    + "from platform.usage_daily d join platform.usage_metric m on m.code = d.metric "
                    + "where m.is_use and m.kind = 'count' and d.day >= ? and m.code = any(?) group by d.tenant_id, m.module", today.minusDays(7), today.minusDays(30), UsageMetrics.ALL.stream().map(UsageMetrics.Metric::code).toArray(String[]::new))) {
                uses.computeIfAbsent(String.valueOf(r.get("tenantId")), k -> new LinkedHashMap<>())
                        .put((String) r.get("module"), new BigDecimal[] {new BigDecimal(String.valueOf(r.get("u7"))), new BigDecimal(String.valueOf(r.get("u30")))});
            }
            Map<String, BigDecimal> members = new LinkedHashMap<>();
            for (Map<String, Object> r : Sql.query(c, "select distinct on (tenant_id) tenant_id, value from platform.usage_daily where metric = 'members' "
                    + "order by tenant_id, day desc")) {
                members.put(String.valueOf(r.get("tenantId")), new BigDecimal(String.valueOf(r.get("value"))));
            }
            List<OrganizationRow> rows = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, "select id, name, slug, status from platform.tenant where status <> 'closed' order by lower(name)")) {
                String id = String.valueOf(t.get("id"));
                Map<String, Boolean> bought = bought(c, AuthService.uuid(id), today);
                List<Cell> cells = new ArrayList<>();
                for (String module : MODULES) {
                    BigDecimal[] u = uses.getOrDefault(id, Map.of()).getOrDefault(module, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    boolean has = "core".equals(module) || bought.getOrDefault(module, false);
                    String state = !has ? "not_bought" : u[0].signum() > 0 ? "active" : u[1].signum() > 0 ? "quiet" : "bought_not_used";
                    cells.add(new Cell(module, "core".equals(module) ? "Core HR" : Modules.name(module), has, state, u[0], u[1]));
                }
                rows.add(new OrganizationRow(id, (String) t.get("name"), (String) t.get("slug"), (String) t.get("status"), cells, members.get(id)));
            }
            Object latest = Sql.one(c, "select max(day) as d from platform.usage_daily").get("d");
            return new Grid(today.toString(), latest == null ? null : String.valueOf(latest), rows);
        });
    }

    /** Twelve weeks of one Organization's numbers, a series for each metric that has any. */
    public Trend trend(SessionCtx ctx, UUID tenant) {
        ctx.requirePlatform("super_admin", "support", "billing");
        List<LocalDate> weeks = weekStarts();
        return db.inPlatform(c -> {
            Map<String, Object> org = Sql.one(c, "select id, name from platform.tenant where id = ?", tenant);
            if (org == null) {
                throw ApiException.notFound("The Organization");
            }
            Map<String, Map<LocalDate, BigDecimal>> byMetric = new LinkedHashMap<>();
            for (Map<String, Object> r : weekly(c, "d.tenant_id = ?", tenant, weeks.getFirst())) {
                byMetric.computeIfAbsent((String) r.get("metric"), k -> new LinkedHashMap<>())
                        .put(LocalDate.parse(String.valueOf(r.get("week"))), new BigDecimal(String.valueOf(r.get("v"))));
            }
            List<Series> series = new ArrayList<>();
            for (Metric m : UsageMetrics.ALL) {
                Map<LocalDate, BigDecimal> by = byMetric.get(m.code());
                if (by == null) {
                    continue;
                }
                List<BigDecimal> values = new ArrayList<>();
                for (LocalDate w : weeks) {
                    values.add(by.get(w));
                }
                series.add(new Series(m.code(), m.module(), m.area(), m.description(), m.kind(), m.isUse(), values));
            }
            return new Trend(String.valueOf(org.get("id")), (String) org.get("name"), weeks.stream().map(LocalDate::toString).toList(), series);
        });
    }

    /** One metric across Organizations for twelve weeks. */
    public MetricView metric(SessionCtx ctx, String code) {
        ctx.requirePlatform("super_admin", "support", "billing");
        Metric m = UsageMetrics.ALL.stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow(() -> ApiException.notFound("The metric"));
        List<LocalDate> weeks = weekStarts();
        return db.inPlatform(c -> {
            Map<String, Map<LocalDate, BigDecimal>> byOrg = new LinkedHashMap<>();
            for (Map<String, Object> r : weekly(c, "d.metric = ?", code, weeks.getFirst())) {
                byOrg.computeIfAbsent(String.valueOf(r.get("tenantId")), k -> new LinkedHashMap<>())
                        .put(LocalDate.parse(String.valueOf(r.get("week"))), new BigDecimal(String.valueOf(r.get("v"))));
            }
            List<OrganizationSeries> rows = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, "select id, name from platform.tenant where status <> 'closed' order by lower(name)")) {
                String id = String.valueOf(t.get("id"));
                Map<LocalDate, BigDecimal> by = byOrg.getOrDefault(id, Map.of());
                List<BigDecimal> values = new ArrayList<>();
                BigDecimal total = BigDecimal.ZERO;
                for (LocalDate w : weeks) {
                    values.add(by.get(w));
                    // A count adds up over the weeks; a headcount, median or share does not, so those show the latest week.
                    total = "count".equals(m.kind()) ? total.add(by.getOrDefault(w, BigDecimal.ZERO)) : by.getOrDefault(w, total);
                }
                rows.add(new OrganizationSeries(id, (String) t.get("name"), values, total));
            }
            return new MetricView(m.code(), m.module(), m.area(), m.description(), m.kind(), weeks.stream().map(LocalDate::toString).toList(), rows);
        });
    }

    // ── CSV, for every view ─────────────────────────────────────────────

    public String gridCsv(SessionCtx ctx) {
        Grid g = grid(ctx);
        List<List<String>> rows = new ArrayList<>();
        List<String> head = new ArrayList<>(List.of("Organization", "Status", "Members"));
        for (String m : MODULES) {
            head.add(("core".equals(m) ? "Core HR" : Modules.name(m)) + " state");
            head.add(("core".equals(m) ? "Core HR" : Modules.name(m)) + " uses, last 30 days");
        }
        rows.add(head);
        for (OrganizationRow o : g.organizations()) {
            List<String> line = new ArrayList<>(List.of(o.name(), o.status(), o.members() == null ? "" : o.members().toPlainString()));
            for (Cell cell : o.cells()) {
                line.add(cell.state());
                line.add(cell.uses30().toPlainString());
            }
            rows.add(line);
        }
        return csv(rows);
    }

    public String trendCsv(SessionCtx ctx, UUID tenant) {
        Trend t = trend(ctx, tenant);
        List<List<String>> rows = new ArrayList<>();
        List<String> head = new ArrayList<>(List.of("Metric", "Area", "How a week is worked out"));
        head.addAll(t.weekStarts());
        rows.add(head);
        for (Series s : t.series()) {
            List<String> line = new ArrayList<>(List.of(s.description(), s.area(), s.kind()));
            s.weeks().forEach(v -> line.add(v == null ? "" : v.toPlainString()));
            rows.add(line);
        }
        return csv(rows);
    }

    public String metricCsv(SessionCtx ctx, String code) {
        MetricView v = metric(ctx, code);
        List<List<String>> rows = new ArrayList<>();
        List<String> head = new ArrayList<>(List.of("Organization"));
        head.addAll(v.weekStarts());
        head.add("count".equals(v.kind()) ? "Total" : "Latest week");
        rows.add(head);
        for (OrganizationSeries o : v.organizations()) {
            List<String> line = new ArrayList<>(List.of(o.name()));
            o.weeks().forEach(x -> line.add(x == null ? "" : x.toPlainString()));
            line.add(o.total().toPlainString());
            rows.add(line);
        }
        return csv(rows);
    }

    // ── steps ───────────────────────────────────────────────────────────

    /** The first day of each of the last twelve weeks (Monday), oldest first. */
    private List<LocalDate> weekStarts() {
        LocalDate thisWeek = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<LocalDate> out = new ArrayList<>();
        for (int i = WEEKS - 1; i >= 0; i--) {
            out.add(thisWeek.minusWeeks(i));
        }
        return out;
    }

    /** Daily numbers worked into weeks the way each metric's kind says: counts add up, a level is the last day's, the rest average. */
    private static List<Map<String, Object>> weekly(Connection c, String where, Object param, LocalDate from) throws SQLException {
        return Sql.query(c, "select d.tenant_id, d.metric, date_trunc('week', d.day)::date as week, "
                + "round(case m.kind when 'count' then sum(d.value) when 'level' then (array_agg(d.value order by d.day desc))[1] else avg(d.value) end, 2) as v "
                + "from platform.usage_daily d join platform.usage_metric m on m.code = d.metric where " + where + " and d.day >= ? "
                + "group by d.tenant_id, d.metric, m.kind, date_trunc('week', d.day)", param, from);
    }

    private static Map<String, Boolean> bought(Connection c, UUID tenant, LocalDate today) throws SQLException {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map<String, Object> m : Sql.query(c, "select module, (status = 'on' and (trial_ends_on is null or trial_ends_on >= ?)) as active "
                + "from platform.tenant_module where tenant_id = ?", today, tenant)) {
            out.put((String) m.get("module"), Boolean.TRUE.equals(m.get("active")));
        }
        return out;
    }

    /** CSV that spreadsheets will not run as a formula: a cell that starts with = + - or @ gets a quote in front. */
    static String csv(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                String v = row.get(i) == null ? "" : row.get(i);
                if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9.]+")) {
                    v = "'" + v;
                }
                if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                    v = "\"" + v.replace("\"", "\"\"") + "\"";
                }
                out.append(i == 0 ? "" : ",").append(v);
            }
            out.append("\r\n");
        }
        return out.toString();
    }
}
