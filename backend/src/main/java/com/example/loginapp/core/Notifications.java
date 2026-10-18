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
 * In-app messages, worded by the Organization's message templates. Events
 * (a section returned, a task assigned, a joiner ready, an activation) write
 * a notification for the person concerned. Reminders are worked out when a
 * person opens their notifications: a task coming due or overdue, or a joiner
 * who has not finished with a week to go. Each is sent at most once a day.
 * The same templates will word emails once email exists.
 */
public final class Notifications {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record MessageInput(String subject, String body, Boolean active) {}

    private final Db db;
    private final Clock clock;

    public Notifications(Db db) {
        this(db, Clock.systemUTC());
    }

    public Notifications(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ── sending ─────────────────────────────────────────────────────────

    /** Writes one notification from the event's template, if the Organization has it switched on. */
    static void send(Connection c, UUID tenantId, UUID membershipId, String event, Map<String, String> values, String link)
            throws SQLException {
        if (membershipId == null) {
            return;
        }
        Map<String, Object> t = Sql.one(c, "select subject, body from app.message_template where event = ? and channel = 'in_app' and active",
                event);
        if (t == null) {
            return;
        }
        Sql.update(c, "insert into app.notification (tenant_id, membership_id, event, subject, body, link) values (?, ?, ?, ?, ?, ?)",
                tenantId, membershipId, event, Templates.render((String) t.get("subject"), values),
                Templates.render((String) t.get("body"), values), link);
    }

    /** Sends an event about a joiner, filled from the joiner's details and any extra values. */
    static void aboutJoiner(Connection c, JoinerService.Loaded l, UUID to, String event, Map<String, String> extra, String link)
            throws SQLException {
        if (to == null) {
            return;
        }
        Map<String, String> values = TemplateValues.forJoiner(c, l, null);
        values.put("link", "");
        if (extra != null) {
            values.putAll(extra);
        }
        send(c, l.tenantId(), to, event, values, link);
    }

    // ── reading ─────────────────────────────────────────────────────────

    /** The caller's latest notifications, after adding any reminders that have fallen due. */
    public Map<String, Object> list(SessionCtx ctx) {
        if (ctx.membershipId() == null) {
            return Map.of("unread", 0, "items", List.of());
        }
        LocalDate today = LocalDate.now(clock.withZone(IST));
        return db.inTenant(ctx.tenantId(), c -> {
            remind(c, ctx.tenantId(), ctx.membershipId(), today);
            remindExits(c, ctx.tenantId(), ctx.membershipId(), today);
            ExitSettlement.remind(c, ctx.tenantId(), today);
            ExitProposals.run(c, ctx.tenantId(), today);
            Probation.remind(c, ctx.tenantId(), ctx.membershipId(), today);
            OneToOnes.remind(c, ctx.tenantId(), ctx.membershipId(), today);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("unread", ((Number) Sql.one(c, "select count(*) as n from app.notification where membership_id = ? and read_at is null",
                    ctx.membershipId()).get("n")).intValue());
            out.put("items", Sql.query(c, "select id, event, subject, body, link, created_at, read_at from app.notification "
                    + "where membership_id = ? order by created_at desc limit 30", ctx.membershipId()));
            return out;
        });
    }

    /** Marks the given notifications read, or all of them when none are given. */
    public Map<String, Object> read(SessionCtx ctx, List<String> ids) {
        if (ctx.membershipId() == null) {
            return Map.of("unread", 0);
        }
        return db.inTenant(ctx.tenantId(), c -> {
            if (ids == null || ids.isEmpty()) {
                Sql.update(c, "update app.notification set read_at = now() where membership_id = ? and read_at is null", ctx.membershipId());
            } else {
                for (String id : ids) {
                    UUID n;
                    try {
                        n = UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        throw ApiException.badRequest("Not a valid notification id");
                    }
                    Sql.update(c, "update app.notification set read_at = now() where id = ? and membership_id = ? and read_at is null",
                            n, ctx.membershipId());
                }
            }
            return Map.of("unread", ((Number) Sql.one(c, "select count(*) as n from app.notification where membership_id = ? and read_at is null",
                    ctx.membershipId()).get("n")).intValue());
        });
    }

    /**
     * Reminders for one person: their open tasks coming due (within the task's reminder days, one by
     * default) or overdue (again every few days, three by default), and, for a joiner, unfinished
     * sections with a week or less to go. Never more than one reminder a day for the same thing.
     */
    static void remind(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        for (Map<String, Object> t : Sql.query(c, "select t.id, t.title, t.due_date, t.onboarding_id, coalesce(t.remind_days_before, 1) as before, "
                + "coalesce(t.repeat_every_days, 3) as every, (t.last_reminded_at at time zone 'Asia/Kolkata')::date as last "
                + "from app.onboarding_task t join app.onboarding o on o.id = t.onboarding_id "
                + "where t.assignee_id = ? and t.done_at is null and t.due_date is not null "
                + "and o.status not in ('withdrawn','offer_declined')", membershipId)) {
            LocalDate due = LocalDate.parse((String) t.get("dueDate"));
            LocalDate last = t.get("last") == null ? null : LocalDate.parse((String) t.get("last"));
            String event = null;
            if (due.isBefore(today)) {
                int every = ((Number) t.get("every")).intValue();
                if (last == null || !last.plusDays(every).isAfter(today) || last.isBefore(due.plusDays(1))) {
                    event = "task_overdue";
                }
            } else if (!due.minusDays(((Number) t.get("before")).intValue()).isAfter(today) && last == null) {
                event = "task_due";
            }
            // Claimed before reminding: of several requests reading the same task at once, one sends it.
            if (event != null && Sql.update(c, "update app.onboarding_task set last_reminded_at = (?::date)::timestamp at time zone 'Asia/Kolkata' "
                    + "where id = ?::uuid and (last_reminded_at is null or (last_reminded_at at time zone 'Asia/Kolkata')::date < ?::date)",
                    today, t.get("id"), today) > 0) {
                JoinerService.Loaded l = JoinerService.load(c, AuthService.uuid(t.get("onboardingId")));
                aboutJoiner(c, l, membershipId, event, Map.of("task.title", (String) t.get("title"),
                        "task.due_date", TemplateValues.DATE.format(due)), "my-tasks");
            }
        }
        Map<String, Object> o = Sql.one(c, "select o.id, e.joining_date, (o.reminded_at at time zone 'Asia/Kolkata')::date as last "
                + "from app.onboarding o join app.employment e on e.id = o.employment_id join app.membership m on m.person_id = o.person_id "
                + "where m.id = ? and o.status in ('invited','in_progress') and (o.notice_id is null or exists ("
                + "  select 1 from app.onboarding_section s where s.onboarding_id = o.id and s.section <> 'employment' "
                + "  and s.status in ('not_started','draft','returned')))", membershipId);
        if (o != null) {
            LocalDate joining = LocalDate.parse((String) o.get("joiningDate"));
            LocalDate last = o.get("last") == null ? null : LocalDate.parse((String) o.get("last"));
            if (!joining.minusDays(7).isAfter(today) && (last == null || last.isBefore(today))
                    && Sql.update(c, "update app.onboarding set reminded_at = (?::date)::timestamp at time zone 'Asia/Kolkata' where id = ?::uuid "
                    + "and (reminded_at is null or (reminded_at at time zone 'Asia/Kolkata')::date < ?::date)", today, o.get("id"), today) > 0) {
                JoinerService.Loaded l = JoinerService.load(c, AuthService.uuid(o.get("id")));
                aboutJoiner(c, l, membershipId, "reminder_joiner", null, "my-onboarding");
            }
        }
    }

    /**
     * Exit reminders for one person: their exit tasks overdue (every few days, three by default),
     * and, for every exit they are part of, the day before the last working day (sent once to the
     * leaver, their manager and HR, whoever looks first).
     */
    static void remindExits(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        for (Map<String, Object> t : Sql.query(c, "select t.id, t.title, t.due_date, t.exit_case_id, coalesce(t.repeat_every_days, 3) as every, "
                + "(t.last_reminded_at at time zone 'Asia/Kolkata')::date as last from app.exit_task t join app.exit_case x on x.id = t.exit_case_id "
                + "where t.assignee_id = ? and t.done_at is null and t.due_date < ? and x.status = 'accepted'", membershipId, today)) {
            LocalDate last = t.get("last") == null ? null : LocalDate.parse((String) t.get("last"));
            int every = ((Number) t.get("every")).intValue();
            if (last != null && last.plusDays(every).isAfter(today)) {
                continue;
            }
            if (Sql.update(c, "update app.exit_task set last_reminded_at = (?::date)::timestamp at time zone 'Asia/Kolkata' where id = ?::uuid "
                    + "and (last_reminded_at is null or (last_reminded_at at time zone 'Asia/Kolkata')::date < ?::date)",
                    today, t.get("id"), today) > 0) {
                Map<String, String> values = ExitValues.forCase(c, AuthService.uuid(t.get("exitCaseId")));
                values.put("task.title", (String) t.get("title"));
                values.put("task.due_date", ExitValues.date(t.get("dueDate")));
                send(c, tenantId, membershipId, "exit_task_overdue", values, "my-tasks");
            }
        }
        for (Map<String, Object> x : Sql.query(c, "select x.id, x.employment_id, e.membership_id from app.exit_case x "
                + "join app.employment e on e.id = x.employment_id where x.status = 'accepted' and x.last_working_day = ? "
                + "and x.last_day_reminded_at is null and x.kind <> 'death'", today.plusDays(1))) {
            if (Sql.update(c, "update app.exit_case set last_day_reminded_at = now() where id = ?::uuid and last_day_reminded_at is null",
                    x.get("id")) == 0) {
                continue;
            }
            Map<String, String> values = ExitValues.forCase(c, AuthService.uuid(x.get("id")));
            java.util.Set<UUID> to = new java.util.LinkedHashSet<>(ExitService.hrMembers(c));
            UUID manager = ExitService.managerMembership(c, AuthService.uuid(x.get("employmentId")), today);
            if (manager != null) {
                to.add(manager);
            }
            if (x.get("membershipId") != null) {
                to.add(AuthService.uuid(x.get("membershipId")));
            }
            for (UUID m : to) {
                send(c, tenantId, m, "last_day_tomorrow", values, m.toString().equals(x.get("membershipId")) ? "my-exit" : "exits");
            }
        }
    }

    // ── the Organization's wording ──────────────────────────────────────

    public List<Map<String, Object>> messages(SessionCtx ctx) {
        if (!ctx.reachesEveryone("employee.read")) {
            throw ApiException.denied("employee.read");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> m : Sql.query(c, "select id, event, channel, subject, body, active from app.message_template "
                    + "where channel = 'in_app' order by event")) {
                out.add(m);
            }
            return out;
        });
    }

    public Map<String, Object> saveMessage(SessionCtx ctx, UUID id, MessageInput in) {
        ctx.require("onboarding.configure");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> current = Sql.one(c, "select * from app.message_template where id = ? for update", id);
            if (current == null) {
                throw ApiException.notFound("The message");
            }
            String subject = in.subject() != null ? in.subject().trim() : (String) current.get("subject");
            String body = in.body() != null ? in.body().trim() : (String) current.get("body");
            if (body == null || body.isBlank() || body.length() > 2000) {
                throw ApiException.badRequest("Write the message, in at most 2,000 characters");
            }
            if (subject != null && subject.length() > 150) {
                throw ApiException.badRequest("Keep the subject to 150 characters");
            }
            Templates.check(subject, Templates.MESSAGE);
            Templates.check(body, Templates.MESSAGE);
            boolean active = in.active() != null ? in.active() : (Boolean) current.get("active");
            Sql.update(c, "update app.message_template set subject = ?, body = ?, active = ? where id = ?",
                    subject == null || subject.isBlank() ? null : subject, body, active, id);
            Map<String, Object> after = Sql.one(c, "select id, event, channel, subject, body, active from app.message_template where id = ?", id);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("event", current.get("event"));
            detail.put("before", current.get("body"));
            detail.put("after", body);
            UserService.audit(c, ctx, "onboarding.message_changed", "message_template", id, detail);
            return after;
        });
    }
}
