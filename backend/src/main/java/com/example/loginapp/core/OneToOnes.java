package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-to-ones between a manager and a report: a shared agenda and notes both read, private notes
 * each side keeps for themselves (encrypted, and read only by their writer), and action items either
 * side can tick off. Only the two people in a one-to-one see it; HR does not.
 */
public final class OneToOnes {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record MeetingInput(String withEmploymentId, String meetingDate, String sharedNotes, String privateNotes) {}

    public record ItemInput(String text, String owner) {}

    public record DoneInput(Boolean done) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public OneToOnes(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public OneToOnes(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    /** The caller's one-to-ones with one person: their report, or their manager. */
    public Map<String, Object> with(SessionCtx ctx, UUID other) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Pair p = pair(c, ctx, other, today, false);
            return view(c, ctx, p);
        });
    }

    /** The people the caller has one-to-ones with: their reports and their manager, each with the last meeting. */
    public List<Map<String, Object>> people(SessionCtx ctx) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> me = Perf.ownEmployment(c, ctx);
            List<Map<String, Object>> out = new ArrayList<>();
            if (me == null) {
                return out;
            }
            UUID mine = AuthService.uuid(me.get("id"));
            for (Map<String, Object> r : Sql.query(c, "select e.id, " + Perf.NAME + " as name, j.designation, 'report' as relation, "
                    + "(select max(o.meeting_date) from app.one_to_one o where o.manager_id = ? and o.report_id = e.id) as last_met "
                    + "from app.employment e join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB
                    + "where e.status = 'active' and j.manager_id = ? order by " + Perf.NAME, mine, mine)) {
                out.add(r);
            }
            Map<String, Object> boss = Sql.one(c, "select e.id, " + Perf.NAME + " as name, j.designation, 'manager' as relation, "
                    + "(select max(o.meeting_date) from app.one_to_one o where o.manager_id = e.id and o.report_id = ?) as last_met "
                    + "from app.employment e join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB
                    + "where e.id = (select j2.manager_id from app.job_assignment j2 where j2.employment_id = ? and j2.effective_from <= ? "
                    + "  order by j2.effective_from desc, j2.created_at desc limit 1) and e.status = 'active'", mine, mine, today);
            if (boss != null) {
                out.add(0, boss);
            }
            return out;
        });
    }

    public Map<String, Object> create(SessionCtx ctx, MeetingInput in) {
        Perf.requireMember(ctx);
        UUID other = Perf.id(in == null ? null : in.withEmploymentId(), "person");
        LocalDate date = in.meetingDate() == null || in.meetingDate().isBlank() ? today() : Perf.date(in.meetingDate(), "meeting date");
        String shared = Perf.text(in.sharedNotes(), "the agenda", 0, 5000);
        String own = Perf.text(in.privateNotes(), "your private notes", 0, 5000);
        LocalDate today = today();
        if (date.isAfter(today.plusDays(60)) || date.isBefore(today.minusYears(1))) {
            throw ApiException.badRequest("Give a meeting date within the last year or the next 60 days");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            Pair p = pair(c, ctx, other, today, true);
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.one_to_one (tenant_id, manager_id, report_id, meeting_date, shared_notes_enc, "
                    + (p.callerIsManager ? "manager_private_enc" : "report_private_enc") + ", created_by) values (?, ?, ?, ?, ?, ?, ?) returning id",
                    ctx.tenantId(), p.manager, p.report, date, Perf.seal(crypto, ctx, shared), Perf.seal(crypto, ctx, own), ctx.membershipId())
                    .get("id"));
            UserService.audit(c, ctx, "performance.one_to_one_created", "one_to_one", id, Map.of("date", date.toString()));
            return view(c, ctx, p);
        });
    }

    public Map<String, Object> update(SessionCtx ctx, UUID id, MeetingInput in) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> o = load(c, id);
            Pair p = participant(c, ctx, o);
            if (in.sharedNotes() != null) {
                Sql.update(c, "update app.one_to_one set shared_notes_enc = ?, updated_at = now() where id = ?",
                        Perf.seal(crypto, ctx, Perf.text(in.sharedNotes(), "the notes", 0, 5000)), id);
            }
            if (in.privateNotes() != null) {
                Sql.update(c, "update app.one_to_one set " + (p.callerIsManager ? "manager_private_enc" : "report_private_enc")
                        + " = ?, updated_at = now() where id = ?", Perf.seal(crypto, ctx, Perf.text(in.privateNotes(), "your private notes", 0, 5000)), id);
            }
            if (in.meetingDate() != null && !in.meetingDate().isBlank()) {
                Sql.update(c, "update app.one_to_one set meeting_date = ?, updated_at = now() where id = ?", Perf.date(in.meetingDate(), "meeting date"), id);
            }
            return view(c, ctx, p);
        });
    }

    public Map<String, Object> addItem(SessionCtx ctx, UUID id, ItemInput in) {
        Perf.requireMember(ctx);
        String text = Perf.text(in == null ? null : in.text(), "the action", 1, 300);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> o = load(c, id);
            Pair p = participant(c, ctx, o);
            String owner = in.owner() == null || in.owner().isBlank() ? (p.callerIsManager ? "manager" : "report")
                    : Perf.oneOf(in.owner(), List.of("manager", "report"), "Say whose action it is: the manager's or the report's");
            Sql.update(c, "insert into app.one_to_one_item (tenant_id, one_to_one_id, text, owner, created_by) values (?, ?, ?, ?, ?)",
                    ctx.tenantId(), id, text, owner, ctx.membershipId());
            return view(c, ctx, p);
        });
    }

    public Map<String, Object> markItem(SessionCtx ctx, UUID itemId, DoneInput in) {
        Perf.requireMember(ctx);
        boolean done = in == null || in.done() == null || in.done();
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> item = Sql.one(c, "select * from app.one_to_one_item where id = ? for update", itemId);
            if (item == null) {
                throw ApiException.notFound("The action");
            }
            Pair p = participant(c, ctx, load(c, AuthService.uuid(item.get("oneToOneId"))));
            Sql.update(c, "update app.one_to_one_item set done_at = " + (done ? "now()" : "null") + ", done_by = ? where id = ?",
                    done ? ctx.membershipId() : null, itemId);
            return view(c, ctx, p);
        });
    }

    /**
     * Reminds a manager, at most once a week for each report, when they have not met a report for
     * the Organization's nudge days. Only in an Organization that uses one-to-ones at all.
     */
    static void remind(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        if (Sql.one(c, "select 1 from app.one_to_one limit 1") == null) {
            return;
        }
        PerformanceDefaults.ensure(c, tenantId);
        Map<String, Object> s = Sql.one(c, "select one_to_one_nudge_days from app.perf_settings");
        int nudge = s == null ? 45 : ((Number) s.get("oneToOneNudgeDays")).intValue();
        for (Map<String, Object> r : Sql.query(c, "select e.id as report, boss.id as manager from app.employment e "
                + "join lateral (select j.manager_id, j.effective_from from app.job_assignment j where j.employment_id = e.id and j.effective_from <= ? "
                + "  order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                + "join app.employment boss on boss.id = cur.manager_id "
                + "where boss.membership_id = ? and e.status = 'active' and boss.status = 'active' and cur.effective_from <= ? "
                + "and not exists (select 1 from app.one_to_one o where o.manager_id = boss.id and o.report_id = e.id and o.meeting_date > ?) "
                + "and not exists (select 1 from app.one_to_one_nudge n where n.manager_id = boss.id and n.report_id = e.id and n.nudged_on > ?)",
                today, membershipId, today.minusDays(nudge), today.minusDays(nudge), today.minusDays(7))) {
            Sql.update(c, "insert into app.one_to_one_nudge (tenant_id, manager_id, report_id, nudged_on) values (?, ?::uuid, ?::uuid, ?) "
                    + "on conflict (tenant_id, manager_id, report_id) do update set nudged_on = excluded.nudged_on",
                    tenantId, r.get("manager"), r.get("report"), today);
            Notifications.send(c, tenantId, membershipId, "one_to_one_nudge",
                    ExitValues.forEmployment(c, AuthService.uuid(r.get("report")), null), "performance");
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private record Pair(UUID manager, UUID report, boolean callerIsManager) {}

    /**
     * The caller and another person as a manager and their report, today. A report may still read
     * one-to-ones with an earlier manager, but only the current pair can start a new one.
     */
    private static Pair pair(Connection c, SessionCtx ctx, UUID other, LocalDate today, boolean current) throws SQLException {
        Map<String, Object> me = Perf.ownEmployment(c, ctx);
        if (me == null) {
            throw new ApiException(409, "request.refused", "One-to-ones are between a manager and their report");
        }
        UUID mine = AuthService.uuid(me.get("id"));
        UUID theirManager = manager(c, other, today);
        if (mine.equals(theirManager)) {
            return new Pair(mine, other, true);
        }
        if (other.equals(manager(c, mine, today))) {
            return new Pair(other, mine, false);
        }
        if (!current) {
            if (Sql.one(c, "select 1 from app.one_to_one where manager_id = ? and report_id = ?", mine, other) != null) {
                return new Pair(mine, other, true);
            }
            if (Sql.one(c, "select 1 from app.one_to_one where manager_id = ? and report_id = ?", other, mine) != null) {
                return new Pair(other, mine, false);
            }
        }
        throw ApiException.notFound("The one-to-ones");
    }

    private static Pair participant(Connection c, SessionCtx ctx, Map<String, Object> o) throws SQLException {
        Map<String, Object> me = Perf.ownEmployment(c, ctx);
        String mine = me == null ? "" : (String) me.get("id");
        if (mine.equals(o.get("managerId"))) {
            return new Pair(AuthService.uuid(o.get("managerId")), AuthService.uuid(o.get("reportId")), true);
        }
        if (mine.equals(o.get("reportId"))) {
            return new Pair(AuthService.uuid(o.get("managerId")), AuthService.uuid(o.get("reportId")), false);
        }
        throw ApiException.notFound("The one-to-one");
    }

    private static UUID manager(Connection c, UUID employment, LocalDate today) throws SQLException {
        Map<String, Object> j = Sql.one(c, "select manager_id from app.job_assignment where employment_id = ? and effective_from <= ? "
                + "order by effective_from desc, created_at desc limit 1", employment, today);
        return j == null ? null : AuthService.uuid(j.get("managerId"));
    }

    private static Map<String, Object> load(Connection c, UUID id) throws SQLException {
        Map<String, Object> o = Sql.one(c, "select * from app.one_to_one where id = ?", id);
        if (o == null) {
            throw ApiException.notFound("The one-to-one");
        }
        return o;
    }

    private Map<String, Object> view(Connection c, SessionCtx ctx, Pair p) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        UUID other = p.callerIsManager ? p.report : p.manager;
        out.put("with", Sql.one(c, "select e.id, " + Perf.NAME + " as name, j.designation from app.employment e "
                + "join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB + "where e.id = ?", other));
        out.put("youAre", p.callerIsManager ? "manager" : "report");
        List<Map<String, Object>> meetings = new ArrayList<>();
        for (Map<String, Object> o : Sql.query(c, "select id, meeting_date, created_at, updated_at from app.one_to_one "
                + "where manager_id = ? and report_id = ? order by meeting_date desc, created_at desc limit 50", p.manager, p.report)) {
            o.put("sharedNotes", Perf.open(c, crypto, ctx.tenantId(), "one_to_one", "shared_notes_enc", o.get("id")));
            o.put("privateNotes", Perf.open(c, crypto, ctx.tenantId(), "one_to_one", p.callerIsManager ? "manager_private_enc" : "report_private_enc",
                    o.get("id")));
            o.put("items", Sql.query(c, "select id, text, owner, done_at from app.one_to_one_item where one_to_one_id = ?::uuid order by created_at",
                    o.get("id")));
            meetings.add(o);
        }
        out.put("meetings", meetings);
        out.put("openItems", Sql.query(c, "select i.id, i.text, i.owner, o.meeting_date from app.one_to_one_item i "
                + "join app.one_to_one o on o.id = i.one_to_one_id where o.manager_id = ? and o.report_id = ? and i.done_at is null "
                + "order by o.meeting_date, i.created_at", p.manager, p.report));
        return out;
    }
}
