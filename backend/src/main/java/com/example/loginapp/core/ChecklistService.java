package com.example.loginapp.core;

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
import java.util.UUID;

/**
 * A joiner's checklist: the tasks of every template whose rules match the
 * joiner, copied when the joiner is added. A task's due date is set once its
 * trigger has happened (added, invited, the joining date, activated), and its
 * assignee is worked out from its owner: the flow's HR contact or whoever added
 * the joiner, the first IT Admin, the joiner's manager, their buddy, the joiner,
 * the first member with a role, or a named user. Whoever a task is assigned to
 * may mark it done without any other permission; HR can do everything else.
 * Overdue is worked out when tasks are read.
 */
public final class ChecklistService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record NewTask(String title, String instructions, String assigneeId, String dueDate, Boolean required) {}

    private final Db db;
    private final DocumentService documents;
    private final Clock clock;

    public ChecklistService(Db db, DocumentService documents) {
        this(db, documents, Clock.systemUTC());
    }

    public ChecklistService(Db db, DocumentService documents, Clock clock) {
        this.db = db;
        this.documents = documents;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── creating and scheduling ─────────────────────────────────────────

    /** Copies the tasks of every active template whose rules match the joiner, then schedules them. */
    static void createTasks(Connection c, JoinerService.Loaded l) throws SQLException {
        AppliesTo.Subject subject = JoinerService.subject(c, l);
        int position = 0;
        for (Map<String, Object> template : Sql.query(c, "select id, applies_to from app.checklist_template where active and kind = 'joining' order by lower(name)")) {
            if (!AppliesTo.parse((String) template.get("appliesTo")).matches(subject)) {
                continue;
            }
            for (Map<String, Object> t : Sql.query(c, "select * from app.checklist_template_task where template_id = ?::uuid order by position",
                    template.get("id"))) {
                if (!AppliesTo.parse((String) t.get("appliesTo")).matches(subject)) {
                    continue;
                }
                Sql.update(c, "insert into app.onboarding_task (tenant_id, onboarding_id, template_task_id, title, instructions, owner, "
                        + "required, needs_attachment, remind_days_before, repeat_every_days, position) values (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)",
                        l.tenantId(), l.id(), t.get("id"), t.get("title"), t.get("instructions"), t.get("owner"), t.get("required"),
                        t.get("needsAttachment"), shortOf(t.get("remindDaysBefore")), shortOf(t.get("repeatEveryDays")), (short) position++);
            }
        }
        schedule(c, l.id());
    }

    /**
     * Sets due dates from the triggers that have happened and fills in assignees and approvers not
     * yet known (a manager chosen later, a joiner who has just accepted the invitation). Tasks already
     * done keep their dates. Safe to run any number of times.
     */
    static void schedule(Connection c, UUID onboardingId) throws SQLException {
        Sql.update(c, "update app.onboarding_task t set due_date = case tt.trigger_event "
                + "  when 'added' then (o.created_at at time zone 'Asia/Kolkata')::date + tt.offset_days::int "
                + "  when 'invited' then (o.invited_at at time zone 'Asia/Kolkata')::date + tt.offset_days::int "
                + "  when 'offer_accepted' then (o.offer_accepted_at at time zone 'Asia/Kolkata')::date + tt.offset_days::int "
                + "  when 'joining_date' then e.joining_date + tt.offset_days::int "
                + "  when 'activated' then (o.activated_at at time zone 'Asia/Kolkata')::date + tt.offset_days::int "
                + "  else null end "
                + "from app.checklist_template_task tt, app.onboarding o, app.employment e "
                + "where tt.id = t.template_task_id and o.id = t.onboarding_id and e.id = o.employment_id "
                + "and t.onboarding_id = ? and t.done_at is null", onboardingId);
        JoinerService.Loaded l = JoinerService.load(c, onboardingId);
        for (Map<String, Object> t : Sql.query(c, "select t.id, t.owner, t.assignee_id, t.approver_id, tt.owner_role_id, tt.owner_membership_id, "
                + "tt.approver, tt.approver_role_id, tt.approver_membership_id from app.onboarding_task t "
                + "left join app.checklist_template_task tt on tt.id = t.template_task_id "
                + "where t.onboarding_id = ? and t.done_at is null and (t.assignee_id is null or (tt.approver is not null and t.approver_id is null))",
                onboardingId)) {
            if (t.get("assigneeId") == null) {
                UUID who = resolve(c, l, (String) t.get("owner"), t.get("ownerRoleId"), t.get("ownerMembershipId"));
                if (who != null) {
                    Sql.update(c, "update app.onboarding_task set assignee_id = ? where id = ?::uuid", who, t.get("id"));
                    notifyAssigned(c, l, who, t.get("id"));
                }
            }
            if (t.get("approver") != null && t.get("approverId") == null) {
                UUID who = resolve(c, l, (String) t.get("approver"), t.get("approverRoleId"), t.get("approverMembershipId"));
                if (who != null) {
                    Sql.update(c, "update app.onboarding_task set approver_id = ? where id = ?::uuid", who, t.get("id"));
                }
            }
        }
    }

    private static void notifyAssigned(Connection c, JoinerService.Loaded l, UUID who, Object taskId) throws SQLException {
        Map<String, Object> t = Sql.one(c, "select title, due_date from app.onboarding_task where id = ?::uuid", taskId.toString());
        Notifications.aboutJoiner(c, l, who, "task_assigned", Map.of("task.title", (String) t.get("title"), "task.due_date",
                t.get("dueDate") == null ? "when it falls due" : TemplateValues.DATE.format(LocalDate.parse((String) t.get("dueDate")))),
                "my-tasks");
    }

    /** Who an owner or approver kind means for this joiner, or null when nobody fits yet. */
    private static UUID resolve(Connection c, JoinerService.Loaded l, String kind, Object roleId, Object membershipId) throws SQLException {
        return switch (kind) {
            case "hr" -> {
                Map<String, Object> m = Sql.one(c, "select coalesce("
                        + "(select f.hr_contact_id from app.onboarding_flow f join app.membership m on m.id = f.hr_contact_id "
                        + "  where f.id = ? and m.status = 'active'), "
                        + "(select o.created_by from app.onboarding o join app.membership m on m.id = o.created_by "
                        + "  where o.id = ? and m.status = 'active')) as id", l.flowId(), l.id());
                UUID id = AuthService.uuid(m.get("id"));
                yield id != null ? id : firstWithRole(c, "hr_admin", null);
            }
            case "it" -> firstWithRole(c, "it_admin", null);
            case "manager" -> {
                Map<String, Object> m = Sql.one(c, "select boss.membership_id from app.job_assignment j join app.employment boss on boss.id = j.manager_id "
                        + "join app.membership m on m.id = boss.membership_id and m.status = 'active' "
                        + "where j.employment_id = ? order by j.effective_from desc, j.created_at desc limit 1", l.employmentId());
                yield m == null ? null : AuthService.uuid(m.get("membershipId"));
            }
            case "buddy" -> AuthService.uuid(l.row().get("buddyId"));
            case "joiner" -> l.membershipId();
            case "role" -> roleId == null ? null : firstWithRole(c, null, roleId.toString());
            case "user" -> {
                if (membershipId == null) {
                    yield null;
                }
                yield Sql.one(c, "select 1 from app.membership where id = ?::uuid and status = 'active'", membershipId) == null
                        ? null : UUID.fromString(membershipId.toString());
            }
            default -> null;
        };
    }

    private static UUID firstWithRole(Connection c, String code, String roleId) throws SQLException {
        Map<String, Object> m = Sql.one(c, "select m.id from app.membership m join app.membership_role mr on mr.membership_id = m.id "
                + "join app.role r on r.id = mr.role_id where m.status = 'active' and (r.code = ? or r.id = ?::uuid) "
                + "order by m.joined_at, m.id limit 1", code, roleId);
        return m == null ? null : AuthService.uuid(m.get("id"));
    }

    /** Required tasks due before the joining date that are not done (or done and not approved), in words. */
    static List<String> blockers(Connection c, JoinerService.Loaded l) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> t : Sql.query(c, "select title, done_at, approver_id, approved_at from app.onboarding_task "
                + "where onboarding_id = ? and required and due_date < ? and (done_at is null or (approver_id is not null and approved_at is null "
                + "and skipped_reason is null)) order by due_date, position", l.id(), l.joiningDate())) {
            out.add("Task: " + t.get("title") + (t.get("doneAt") == null ? "" : " (waiting for approval)"));
        }
        return out;
    }

    // ── reading ─────────────────────────────────────────────────────────

    private static final String TASK_SELECT = "select t.id, t.onboarding_id, t.title, t.instructions, t.owner, t.assignee_id, "
            + "ad.email as assignee_email, ap.legal_name as assignee_name, t.due_date, t.required, t.needs_attachment, t.attachment_id, "
            + "att.file_name as attachment_name, t.approver_id, pd.email as approver_email, t.approved_at, t.done_at, dd.email as done_by, "
            + "t.skipped_reason, t.position, (t.template_task_id is null) as one_off "
            + "from app.onboarding_task t "
            + "left join app.member_directory() ad on ad.membership_id = t.assignee_id "
            + "left join app.membership am on am.id = t.assignee_id left join app.person ap on ap.id = am.person_id "
            + "left join app.member_directory() pd on pd.membership_id = t.approver_id "
            + "left join app.member_directory() dd on dd.membership_id = t.done_by "
            + "left join app.document att on att.id = t.attachment_id";

    /** A joiner's checklist for HR, a verifier or the manager. */
    public Map<String, Object> forJoiner(SessionCtx ctx, UUID onboardingId) {
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            JoinerService.requireSee(c, ctx, l);
            schedule(c, onboardingId);
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, TASK_SELECT + " where t.onboarding_id = ? order by t.due_date nulls last, t.position",
                    onboardingId)) {
                tasks.add(decorate(t, ctx, today, isHr(ctx)));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tasks", tasks);
            out.put("canManage", isHr(ctx) && l.open());
            return out;
        });
    }

    /** Open tasks assigned to the caller, and done tasks waiting for their approval, across every joiner. */
    public List<Map<String, Object>> mine(SessionCtx ctx) {
        if (ctx.membershipId() == null) {
            return List.of();
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, TASK_SELECT.replace("from app.onboarding_task t ",
                    ", p.legal_name as joiner_name, p.preferred_name as joiner_preferred_name, e.joining_date, o.status as onboarding_status "
                            + "from app.onboarding_task t join app.onboarding o on o.id = t.onboarding_id "
                            + "join app.person p on p.id = o.person_id join app.employment e on e.id = o.employment_id ")
                    + " where o.status not in ('withdrawn','offer_declined') and ((t.assignee_id = ? and t.done_at is null) "
                    + "or (t.approver_id = ? and t.done_at is not null and t.approved_at is null and t.skipped_reason is null)) "
                    + "order by t.due_date nulls last, e.joining_date, t.position limit 200", ctx.membershipId(), ctx.membershipId())) {
                out.add(decorate(t, ctx, today, isHr(ctx)));
            }
            return out;
        });
    }

    /** How many tasks wait for the caller, for the menu. */
    public static int openCount(Connection c, UUID membershipId) throws SQLException {
        Map<String, Object> n = Sql.one(c, "select count(*) as n from app.onboarding_task t join app.onboarding o on o.id = t.onboarding_id "
                + "where o.status not in ('withdrawn','offer_declined') and ((t.assignee_id = ? and t.done_at is null) "
                + "or (t.approver_id = ? and t.done_at is not null and t.approved_at is null and t.skipped_reason is null))",
                membershipId, membershipId);
        return ((Number) n.get("n")).intValue();
    }

    private static Map<String, Object> decorate(Map<String, Object> t, SessionCtx ctx, LocalDate today, boolean hr) {
        Map<String, Object> row = new LinkedHashMap<>(t);
        boolean done = t.get("doneAt") != null;
        LocalDate due = t.get("dueDate") == null ? null : LocalDate.parse((String) t.get("dueDate"));
        row.put("overdue", !done && due != null && due.isBefore(today));
        boolean mineToDo = ctx.membershipId() != null && ctx.membershipId().toString().equals(t.get("assigneeId"));
        row.put("canDo", !done && (mineToDo || hr));
        row.put("canApprove", done && t.get("approvedAt") == null && t.get("skippedReason") == null && t.get("approverId") != null
                && ctx.membershipId() != null && ctx.membershipId().toString().equals(t.get("approverId")));
        row.put("state", t.get("skippedReason") != null ? "skipped" : !done ? (row.get("overdue").equals(true) ? "overdue" : "open")
                : t.get("approverId") != null && t.get("approvedAt") == null ? "awaiting_approval" : "done");
        return row;
    }

    // ── actions ─────────────────────────────────────────────────────────

    public Map<String, Object> done(SessionCtx ctx, UUID taskId) {
        return act(ctx, taskId, false, (c, t) -> {
            if (Boolean.TRUE.equals(t.get("needsAttachment")) && t.get("attachmentId") == null) {
                throw new ApiException(409, "task.needs_attachment", "Attach the file this task asks for first");
            }
            Sql.update(c, "update app.onboarding_task set done_by = ?, done_at = now() where id = ?", ctx.membershipId(), taskId);
            return "joiner.task_done";
        });
    }

    public Map<String, Object> skip(SessionCtx ctx, UUID taskId, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        return act(ctx, taskId, true, (c, t) -> {
            Sql.update(c, "update app.onboarding_task set done_by = ?, done_at = now(), skipped_reason = ? where id = ?",
                    ctx.membershipId(), reason.trim(), taskId);
            return "joiner.task_skipped";
        });
    }

    public Map<String, Object> reassign(SessionCtx ctx, UUID taskId, UUID membershipId) {
        return act(ctx, taskId, true, (c, t) -> {
            if (membershipId == null || Sql.one(c, "select 1 from app.membership where id = ? and status = 'active'", membershipId) == null) {
                throw ApiException.badRequest("Choose a user of the Organization");
            }
            Sql.update(c, "update app.onboarding_task set assignee_id = ? where id = ?", membershipId, taskId);
            notifyAssigned(c, JoinerService.load(c, AuthService.uuid(t.get("onboardingId"))), membershipId, taskId);
            return "joiner.task_reassigned";
        });
    }

    public Map<String, Object> approve(SessionCtx ctx, UUID taskId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> t = task(c, taskId);
            if (ctx.membershipId() == null || !ctx.membershipId().toString().equals(t.get("approverId"))) {
                throw new ApiException(403, "permission.denied", "Only the task's approver can approve it");
            }
            if (t.get("doneAt") == null || t.get("approvedAt") != null || t.get("skippedReason") != null) {
                throw new ApiException(409, "task.not_waiting", "This task is not waiting for approval");
            }
            Sql.update(c, "update app.onboarding_task set approved_at = now() where id = ?", taskId);
            JoinerService.noteIfReady(c, AuthService.uuid(t.get("onboardingId")));
            UserService.audit(c, ctx, "joiner.task_approved", "onboarding", AuthService.uuid(t.get("onboardingId")),
                    Map.of("task", t.get("title")));
            return one(c, ctx, taskId);
        });
    }

    /** Attaches a file to a task, such as a signed laptop handover form. The file belongs to the joiner's record. */
    public Map<String, Object> attach(SessionCtx ctx, UUID taskId, String fileName, byte[] bytes) {
        FileSniffer.Cleaned file = FileSniffer.clean(bytes, fileName);
        return act(ctx, taskId, false, (c, t) -> {
            JoinerService.Loaded l = JoinerService.load(c, AuthService.uuid(t.get("onboardingId")));
            UUID document = documents.insert(c, ctx, l.tenantId(), l.personId(), l.id(), null, file);
            Sql.update(c, "update app.onboarding_task set attachment_id = ? where id = ?", document, taskId);
            return "joiner.task_attachment";
        });
    }

    /** HR adds a task of their own to one joiner's checklist. */
    public Map<String, Object> add(SessionCtx ctx, UUID onboardingId, NewTask in) {
        requireHr(ctx);
        if (in.title() == null || in.title().isBlank() || in.title().trim().length() > 120) {
            throw ApiException.badRequest("Give the task a title of at most 120 characters");
        }
        LocalDate due;
        try {
            due = in.dueDate() == null || in.dueDate().isBlank() ? null : LocalDate.parse(in.dueDate().trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("The due date must be a date like 2026-10-12");
        }
        UUID assignee;
        try {
            assignee = in.assigneeId() == null || in.assigneeId().isBlank() ? ctx.membershipId() : UUID.fromString(in.assigneeId().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not a valid user id");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            JoinerService.requireOpen(l);
            if (Sql.one(c, "select 1 from app.membership where id = ? and status = 'active'", assignee) == null) {
                throw ApiException.badRequest("Choose a user of the Organization");
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.onboarding_task (tenant_id, onboarding_id, title, instructions, owner, "
                    + "assignee_id, due_date, required, position, created_by) values (?, ?, ?, ?, 'user', ?, ?, ?, 100, ?) returning id",
                    l.tenantId(), onboardingId, in.title().trim(), blankToNull(in.instructions()), assignee, due,
                    !Boolean.FALSE.equals(in.required()), ctx.membershipId()).get("id"));
            UserService.audit(c, ctx, "joiner.task_added", "onboarding", onboardingId, Map.of("task", in.title().trim()));
            if (!assignee.equals(ctx.membershipId())) {
                notifyAssigned(c, l, assignee, id);
            }
            return one(c, ctx, id);
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
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> t = task(c, taskId);
            boolean hr = isHr(ctx);
            boolean assignee = ctx.membershipId().toString().equals(t.get("assigneeId"));
            if (!hr && (hrOnly || !assignee)) {
                if (!assignee && !hr) {
                    throw ApiException.notFound("The task");
                }
                throw new ApiException(403, "permission.denied", "Only HR can do this");
            }
            if (t.get("doneAt") != null) {
                throw new ApiException(409, "task.done", "This task is done already");
            }
            if ("withdrawn".equals(t.get("onboardingStatus")) || "offer_declined".equals(t.get("onboardingStatus"))) {
                throw new ApiException(409, "onboarding.closed", "This joiner was withdrawn");
            }
            String event = action.apply(c, t);
            UserService.audit(c, ctx, event, "onboarding", AuthService.uuid(t.get("onboardingId")), Map.of("task", t.get("title")));
            JoinerService.noteIfReady(c, AuthService.uuid(t.get("onboardingId")));
            return one(c, ctx, taskId);
        });
    }

    private static Map<String, Object> task(Connection c, UUID taskId) throws SQLException {
        Map<String, Object> t = Sql.one(c, "select t.*, o.status as onboarding_status from app.onboarding_task t "
                + "join app.onboarding o on o.id = t.onboarding_id where t.id = ? for update of t", taskId);
        if (t == null) {
            throw ApiException.notFound("The task");
        }
        return t;
    }

    private Map<String, Object> one(Connection c, SessionCtx ctx, UUID taskId) throws SQLException {
        return decorate(Sql.one(c, TASK_SELECT + " where t.id = ?", taskId), ctx, today(), isHr(ctx));
    }

    private static boolean isHr(SessionCtx ctx) {
        return ctx.canAcrossOrganization("employee.write") && ctx.membershipId() != null;
    }

    private static void requireHr(SessionCtx ctx) {
        if (!isHr(ctx)) {
            throw ApiException.denied("employee.write");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Short shortOf(Object v) {
        return v == null ? null : ((Number) v).shortValue();
    }
}
