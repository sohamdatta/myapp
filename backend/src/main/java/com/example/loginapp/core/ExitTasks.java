package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The clearance checklist of an exit: the tasks of every exit checklist template whose rules
 * match the leaver, copied when the exit is accepted. Due dates count from the acceptance or the
 * last working day; owners resolve to people as for joiners, falling back to HR when nobody holds
 * the role, so no task waits on nobody. A task can carry an amount the employee owes or is owed
 * (no dues), which the settlement picks up. Whoever a task is assigned to may mark it done; HR can
 * do everything else.
 */
public final class ExitTasks {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record DoneInput(BigDecimal amount, String direction, String note) {}

    public record NewTask(String title, String instructions, String assigneeId, String dueDate, Boolean required) {}

    private final Db db;
    private final Clock clock;

    public ExitTasks(Db db) {
        this(db, Clock.systemUTC());
    }

    public ExitTasks(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── creating and scheduling ─────────────────────────────────────────

    /** Copies the matching exit templates' tasks into the case, then schedules them. Runs once, when the exit is accepted. */
    static void createTasks(Connection c, UUID caseId) throws SQLException {
        if (Sql.one(c, "select 1 from app.exit_task where exit_case_id = ? limit 1", caseId) != null) {
            return;
        }
        Map<String, Object> x = Sql.one(c, "select kind, employment_id from app.exit_case where id = ?", caseId);
        AppliesTo.Subject subject = subject(c, AuthService.uuid(x.get("employmentId")));
        boolean personGone = Set.of("death", "absconding").contains((String) x.get("kind"));
        short position = 0;
        for (Map<String, Object> template : Sql.query(c, "select id, applies_to from app.checklist_template where active and kind = 'exit' "
                + "order by lower(name)")) {
            if (!AppliesTo.parse((String) template.get("appliesTo")).matches(subject)) {
                continue;
            }
            for (Map<String, Object> t : Sql.query(c, "select * from app.checklist_template_task where template_id = ?::uuid order by position",
                    template.get("id"))) {
                if (!AppliesTo.parse((String) t.get("appliesTo")).matches(subject) || (personGone && "joiner".equals(t.get("owner")))) {
                    continue;
                }
                Sql.update(c, "insert into app.exit_task (tenant_id, exit_case_id, template_task_id, title, instructions, owner, required, "
                        + "remind_days_before, repeat_every_days, position) select tenant_id, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ? "
                        + "from app.exit_case where id = ?", caseId, t.get("id"), t.get("title"), t.get("instructions"), t.get("owner"),
                        t.get("required"), shortOf(t.get("remindDaysBefore")), shortOf(t.get("repeatEveryDays")), position++, caseId);
            }
        }
        schedule(c, caseId);
    }

    /** Sets due dates from the acceptance and the last working day, and assigns tasks nobody has yet. Safe to run again. */
    static void schedule(Connection c, UUID caseId) throws SQLException {
        Sql.update(c, "update app.exit_task t set due_date = case tt.trigger_event "
                + "  when 'exit_accepted' then (x.accepted_at at time zone 'Asia/Kolkata')::date + tt.offset_days::int "
                + "  when 'last_working_day' then x.last_working_day + tt.offset_days::int "
                + "  else x.last_working_day end "
                + "from app.checklist_template_task tt, app.exit_case x "
                + "where tt.id = t.template_task_id and x.id = t.exit_case_id and t.exit_case_id = ? and t.done_at is null "
                + "and x.last_working_day is not null", caseId);
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> x = Sql.one(c, "select x.*, e.membership_id from app.exit_case x join app.employment e on e.id = x.employment_id "
                + "where x.id = ?", caseId);
        for (Map<String, Object> t : Sql.query(c, "select t.id, t.owner, tt.owner_role_id, tt.owner_membership_id from app.exit_task t "
                + "left join app.checklist_template_task tt on tt.id = t.template_task_id "
                + "where t.exit_case_id = ? and t.done_at is null and t.assignee_id is null", caseId)) {
            UUID who = resolve(c, x, (String) t.get("owner"), t.get("ownerRoleId"), t.get("ownerMembershipId"), today);
            if (who != null) {
                Sql.update(c, "update app.exit_task set assignee_id = ? where id = ?::uuid", who, t.get("id"));
                notifyAssigned(c, caseId, who, t.get("id"));
            }
        }
    }

    private static void notifyAssigned(Connection c, UUID caseId, UUID who, Object taskId) throws SQLException {
        Map<String, Object> t = Sql.one(c, "select title, due_date, tenant_id from app.exit_task where id = ?::uuid", taskId.toString());
        Map<String, String> values = ExitValues.forCase(c, caseId);
        values.put("task.title", (String) t.get("title"));
        values.put("task.due_date", t.get("dueDate") == null ? "when it falls due" : ExitValues.date(t.get("dueDate")));
        Notifications.send(c, AuthService.uuid(t.get("tenantId")), who, "exit_task_assigned", values, "my-tasks");
    }

    /** Who an owner kind means for this leaver. Nobody holding the role means HR, so clearance never waits on nobody. */
    private static UUID resolve(Connection c, Map<String, Object> x, String kind, Object roleId, Object membershipId, LocalDate today)
            throws SQLException {
        UUID who = switch (kind) {
            case "it" -> firstWithRole(c, "it_admin", null);
            case "finance" -> firstWithRole(c, "payroll_operator", null);
            case "manager" -> ExitService.managerMembership(c, AuthService.uuid(x.get("employmentId")), today);
            case "joiner" -> AuthService.uuid(x.get("membershipId"));
            case "role" -> roleId == null ? null : firstWithRole(c, null, roleId.toString());
            case "user" -> membershipId == null || Sql.one(c, "select 1 from app.membership where id = ?::uuid and status = 'active'",
                    membershipId) == null ? null : UUID.fromString(membershipId.toString());
            default -> null;
        };
        if (who != null || "joiner".equals(kind)) {
            return who;
        }
        // HR: whoever accepted the exit, if still here, else the first HR Admin.
        Object accepted = x.get("acceptedBy");
        if (accepted != null && Sql.one(c, "select 1 from app.membership where id = ?::uuid and status = 'active'", accepted) != null) {
            return UUID.fromString(accepted.toString());
        }
        List<UUID> hr = ExitService.hrMembers(c);
        return hr.isEmpty() ? null : hr.get(0);
    }

    private static UUID firstWithRole(Connection c, String code, String roleId) throws SQLException {
        Map<String, Object> m = Sql.one(c, "select m.id from app.membership m join app.membership_role mr on mr.membership_id = m.id "
                + "join app.role r on r.id = mr.role_id where m.status = 'active' and (r.code = ? or r.id = ?::uuid) "
                + "order by m.joined_at, m.id limit 1", code, roleId);
        return m == null ? null : AuthService.uuid(m.get("id"));
    }

    /** What the rules of a template see of the leaver: department, work location, type and designation now. */
    static AppliesTo.Subject subject(Connection c, UUID employmentId) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select e.employment_type, j.department_id, j.designation, a.work_location_id "
                + "from app.employment e left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "left join lateral (select a.work_location_id from app.attendance_assignment a where a.membership_id = e.membership_id "
                + "  order by a.effective_from desc limit 1) a on true where e.id = ?", employmentId);
        return new AppliesTo.Subject(AuthService.uuid(s.get("departmentId")), AuthService.uuid(s.get("workLocationId")),
                (String) s.get("employmentType"), (String) s.get("designation"));
    }

    /** How many exit tasks wait for this person, for the menu. */
    public static int openCount(Connection c, UUID membershipId) throws SQLException {
        Map<String, Object> n = Sql.one(c, "select count(*) as n from app.exit_task t join app.exit_case x on x.id = t.exit_case_id "
                + "where t.assignee_id = ? and t.done_at is null and x.status in ('accepted','closed')", membershipId);
        return ((Number) n.get("n")).intValue();
    }

    // ── reading ─────────────────────────────────────────────────────────

    private static final String TASK_SELECT = "select t.id, t.exit_case_id, t.title, t.instructions, t.owner, t.assignee_id, "
            + "ad.email as assignee_email, ap.legal_name as assignee_name, t.due_date, t.required, t.amount, t.amount_direction, "
            + "t.amount_note, t.done_at, dd.email as done_by, t.skipped_reason, t.position, (t.template_task_id is null) as one_off, "
            + "x.status as exit_status, x.last_working_day, p.legal_name as leaver_name, p.preferred_name as leaver_preferred_name "
            + "from app.exit_task t join app.exit_case x on x.id = t.exit_case_id join app.person p on p.id = x.person_id "
            + "left join app.member_directory() ad on ad.membership_id = t.assignee_id "
            + "left join app.membership am on am.id = t.assignee_id left join app.person ap on ap.id = am.person_id "
            + "left join app.member_directory() dd on dd.membership_id = t.done_by";

    /** The checklist of one exit, for whoever can see the exit. */
    static List<Map<String, Object>> forCase(Connection c, SessionCtx ctx, UUID caseId, LocalDate today) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : Sql.query(c, TASK_SELECT + " where t.exit_case_id = ? order by t.due_date nulls last, t.position", caseId)) {
            out.add(decorate(t, ctx, today));
        }
        return out;
    }

    /** Open exit tasks assigned to the caller, across every exit. */
    public List<Map<String, Object>> mine(SessionCtx ctx) {
        if (ctx.membershipId() == null) {
            return List.of();
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, TASK_SELECT + " where t.assignee_id = ? and t.done_at is null "
                    + "and x.status in ('accepted','closed') order by t.due_date nulls last, x.last_working_day, t.position limit 200",
                    ctx.membershipId())) {
                out.add(decorate(t, ctx, today));
            }
            return out;
        });
    }

    private static Map<String, Object> decorate(Map<String, Object> t, SessionCtx ctx, LocalDate today) {
        Map<String, Object> row = new LinkedHashMap<>(t);
        boolean done = t.get("doneAt") != null;
        LocalDate due = t.get("dueDate") == null ? null : LocalDate.parse((String) t.get("dueDate"));
        boolean overdue = !done && due != null && due.isBefore(today);
        row.put("overdue", overdue);
        boolean mineToDo = ctx.membershipId() != null && ctx.membershipId().toString().equals(t.get("assigneeId"));
        boolean live = "accepted".equals(t.get("exitStatus")) || "closed".equals(t.get("exitStatus"));
        row.put("canDo", live && !done && (mineToDo || ExitService.canManage(ctx)));
        row.put("canApprove", false);
        row.put("state", t.get("skippedReason") != null ? "skipped" : !done ? (overdue ? "overdue" : "open") : "done");
        return row;
    }

    // ── actions ─────────────────────────────────────────────────────────

    /** Marks a task done, with any amount the employee owes or is owed for it. */
    public Map<String, Object> done(SessionCtx ctx, UUID taskId, DoneInput in) {
        BigDecimal amount = in == null ? null : in.amount();
        String direction = in == null || in.direction() == null || in.direction().isBlank() ? null : in.direction().trim();
        String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (amount != null && amount.signum() <= 0) {
            amount = null;
        }
        if ((amount == null) != (direction == null) || (direction != null && !Set.of("owed_by_employee", "owed_to_employee").contains(direction))) {
            throw ApiException.badRequest("Give an amount and say who owes it, or neither");
        }
        if (amount != null && amount.compareTo(new BigDecimal("10000000")) > 0) {
            throw ApiException.badRequest("The amount is too large");
        }
        if (note != null && note.length() > 200) {
            throw ApiException.badRequest("Keep the note to 200 characters");
        }
        BigDecimal value = amount;
        return act(ctx, taskId, false, (c, t) -> {
            Sql.update(c, "update app.exit_task set done_by = ?, done_at = now(), amount = ?, amount_direction = ?, amount_note = ? where id = ?",
                    ctx.membershipId(), value, direction, note, taskId);
            return "exit.task_done";
        });
    }

    public Map<String, Object> skip(SessionCtx ctx, UUID taskId, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        return act(ctx, taskId, true, (c, t) -> {
            Sql.update(c, "update app.exit_task set done_by = ?, done_at = now(), skipped_reason = ? where id = ?",
                    ctx.membershipId(), reason.trim(), taskId);
            return "exit.task_skipped";
        });
    }

    public Map<String, Object> reassign(SessionCtx ctx, UUID taskId, UUID membershipId) {
        return act(ctx, taskId, true, (c, t) -> {
            if (membershipId == null || Sql.one(c, "select 1 from app.membership where id = ? and status = 'active'", membershipId) == null) {
                throw ApiException.badRequest("Choose a user of the Organization");
            }
            Sql.update(c, "update app.exit_task set assignee_id = ? where id = ?", membershipId, taskId);
            notifyAssigned(c, AuthService.uuid(t.get("exitCaseId")), membershipId, taskId);
            return "exit.task_reassigned";
        });
    }

    /** HR adds a task of their own to one exit's checklist. */
    public Map<String, Object> add(SessionCtx ctx, UUID caseId, NewTask in) {
        if (!ExitService.canManage(ctx)) {
            throw ApiException.denied("exit.manage");
        }
        if (in.title() == null || in.title().isBlank() || in.title().trim().length() > 120) {
            throw ApiException.badRequest("Give the task a title of at most 120 characters");
        }
        LocalDate due;
        try {
            due = in.dueDate() == null || in.dueDate().isBlank() ? null : LocalDate.parse(in.dueDate().trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("The due date must be a date like 2026-10-12");
        }
        UUID assignee = in.assigneeId() == null || in.assigneeId().isBlank() ? ctx.membershipId() : ExitService.id(in.assigneeId(), "user");
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = Sql.one(c, "select status, tenant_id from app.exit_case where id = ?", caseId);
            if (x == null) {
                throw ApiException.notFound("The exit");
            }
            if (!"accepted".equals(x.get("status"))) {
                throw new ApiException(409, "exit.not_accepted", "Tasks can be added once the exit is accepted");
            }
            if (Sql.one(c, "select 1 from app.membership where id = ? and status = 'active'", assignee) == null) {
                throw ApiException.badRequest("Choose a user of the Organization");
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.exit_task (tenant_id, exit_case_id, title, instructions, owner, assignee_id, "
                    + "due_date, required, position, created_by) values (?::uuid, ?, ?, ?, 'user', ?, ?, ?, 100, ?) returning id",
                    x.get("tenantId"), caseId, in.title().trim(), in.instructions() == null || in.instructions().isBlank() ? null
                            : in.instructions().trim(), assignee, due, !Boolean.FALSE.equals(in.required()), ctx.membershipId()).get("id"));
            UserService.audit(c, ctx, "exit.task_added", "exit", caseId, Map.of("task", in.title().trim()));
            if (!assignee.equals(ctx.membershipId())) {
                notifyAssigned(c, caseId, assignee, id);
            }
            return decorate(Sql.one(c, TASK_SELECT + " where t.id = ?", id), ctx, today);
        });
    }

    private interface Action {
        String apply(Connection c, Map<String, Object> task) throws SQLException;
    }

    /** Runs an action on an open task: HR always; the assignee too unless {@code hrOnly}. */
    private Map<String, Object> act(SessionCtx ctx, UUID taskId, boolean hrOnly, Action action) {
        if (ctx.membershipId() == null) {
            throw new ApiException(403, "permission.denied", "Only a user of the Organization can do this");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> t = Sql.one(c, "select t.*, x.status as exit_status from app.exit_task t join app.exit_case x on x.id = t.exit_case_id "
                    + "where t.id = ? for update of t", taskId);
            if (t == null) {
                throw ApiException.notFound("The task");
            }
            boolean hr = ExitService.canManage(ctx);
            boolean assignee = ctx.membershipId().toString().equals(t.get("assigneeId"));
            if (!hr && !assignee) {
                throw ApiException.notFound("The task");
            }
            if (!hr && hrOnly) {
                throw new ApiException(403, "permission.denied", "Only HR can do this");
            }
            if (t.get("doneAt") != null) {
                throw new ApiException(409, "task.done", "This task is done already");
            }
            if (!Set.of("accepted", "closed").contains((String) t.get("exitStatus"))) {
                throw new ApiException(409, "exit.closed", "This exit was withdrawn or cancelled");
            }
            String event = action.apply(c, t);
            UserService.audit(c, ctx, event, "exit", AuthService.uuid(t.get("exitCaseId")), Map.of("task", t.get("title")));
            return decorate(Sql.one(c, TASK_SELECT + " where t.id = ?", taskId), ctx, today);
        });
    }

    private static Short shortOf(Object v) {
        return v == null ? null : ((Number) v).shortValue();
    }
}
