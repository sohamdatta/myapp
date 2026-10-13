package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An employee's exit, from the resignation (or HR's decision) to the day their access ends.
 *
 * <p>A resignation is submitted by the employee, recommended by their manager when the
 * Organization wants that step, and accepted by HR, who fixes the last working day. Exits HR
 * starts (termination, death, and so on) are accepted as they are recorded. The employment stays
 * active through the notice period; once the last working day has passed it becomes exited, the
 * reports move to whoever takes them over, and the login narrows to a former employee's. There is
 * no background job: what has fallen due is applied whenever exits, employees or the person's own
 * session are read.
 */
public final class ExitService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final Set<String> KINDS = Set.of("resignation", "termination", "end_of_contract", "retirement", "death", "absconding");
    static final Set<String> REASONS = Set.of("career", "pay", "relocation", "studies", "health", "personal", "conduct", "performance",
            "contract", "retirement", "death", "absconding", "other");
    static final Set<String> OPEN = Set.of("proposed", "submitted", "recommended", "accepted");
    private static final Set<String> SHORTFALL = Set.of("waived", "leave", "owed", "paid_in_lieu");

    public record ResignInput(String reasonCategory, String note, String requestedLastDay) {}

    public record RecommendInput(String lastWorkingDay, String note) {}

    public record AcceptInput(String lastWorkingDay, String shortfallAction, String newManagerId) {}

    public record StartInput(String employmentId, String kind, String reasonCategory, String note, String lastWorkingDay,
                             String shortfallAction, String newManagerId) {}

    public record WithdrawInput(String note) {}

    public record DecisionInput(Boolean approve, String note) {}

    public record CloseInput(String rehire, String rehireNote) {}

    public record SettingsInput(Map<String, Object> noticeDays, Boolean managerStep, List<String> noticeLeaveTypeIds,
                                String shortfallDefault, Integer retirementAge, Integer abscondingDays, Integer responseDays,
                                Integer formerAccessMonths, List<String> encashableLeaveTypeIds) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public ExitService(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public ExitService(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── the employee's own exit ─────────────────────────────────────────

    /** The caller's employment, their open or latest exit, and what a resignation today would mean. */
    public Map<String, Object> mine(SessionCtx ctx) {
        requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            ExitDefaults.ensure(c, ctx.tenantId());
            applyDueFor(c, ctx.tenantId(), ctx.membershipId(), today);
            Map<String, Object> e = ownEmployment(c, ctx);
            Map<String, Object> out = new LinkedHashMap<>();
            if (e == null) {
                out.put("employed", false);
                return out;
            }
            Map<String, Object> settings = settings(c);
            out.put("employed", "active".equals(e.get("status")));
            Map<String, Object> x = Sql.one(c, "select id from app.exit_case where employment_id = ?::uuid "
                    + "order by (status in ('proposed','submitted','recommended','accepted','closed')) desc, started_at desc limit 1",
                    e.get("id"));
            if (x != null && "proposed".equals(Sql.one(c, "select status from app.exit_case where id = ?::uuid", x.get("id")).get("status"))) {
                x = null;   // a proposal (contract end, retirement) is HR's until they confirm it
            }
            int notice = noticeDays(settings, e, today);
            out.put("noticeDays", notice);
            out.put("proposedLastDay", today.plusDays(notice).toString());
            out.put("exit", x == null ? null : view(c, ctx, AuthService.uuid(x.get("id")), today, true));
            return out;
        });
    }

    /** The employee resigns. Their manager and HR are told. */
    public Map<String, Object> resign(SessionCtx ctx, ResignInput in) {
        requireMember(ctx);
        String reason = reasonOf(in.reasonCategory(), Set.of("career", "pay", "relocation", "studies", "health", "personal", "other"));
        String note = noteOf(in.note());
        LocalDate today = today();
        LocalDate requested = in.requestedLastDay() == null || in.requestedLastDay().isBlank() ? null
                : date(in.requestedLastDay(), "last day you would like");
        if (requested != null && requested.isBefore(today)) {
            throw ApiException.badRequest("The last day you would like cannot be in the past");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            ExitDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> e = ownEmployment(c, ctx);
            if (e == null || !"active".equals(e.get("status"))) {
                throw new ApiException(409, "exit.not_employed", "Only an active employee can resign");
            }
            lockEmployment(c, AuthService.uuid(e.get("id")));
            refuseIfOpen(c, AuthService.uuid(e.get("id")));
            int notice = noticeDays(settings(c), e, today);
            UUID id = insertCase(c, ctx, e, "resignation", "submitted", reason, note, requested, notice, today);
            UserService.audit(c, ctx, "exit.submitted", "exit", id,
                    Map.of("employmentId", e.get("id"), "requestedLastDay", requested == null ? "" : requested.toString()));
            Map<String, Object> s = settings(c);
            UUID manager = managerMembership(c, AuthService.uuid(e.get("id")), today);
            Map<String, String> values = ExitValues.forCase(c, id);
            if (Boolean.TRUE.equals(s.get("managerStep")) && manager != null) {
                Notifications.send(c, ctx.tenantId(), manager, "resignation_submitted", values, "exits");
            }
            for (UUID hr : hrMembers(c)) {
                if (!hr.equals(manager) && !hr.equals(ctx.membershipId())) {
                    Notifications.send(c, ctx.tenantId(), hr, "resignation_submitted", values, "exits");
                }
            }
            return view(c, ctx, id, today, true);
        });
    }

    /**
     * Withdraws the caller's resignation: at once while it is not yet accepted; after that it
     * becomes a request HR decides. Once the last working day has passed it cannot be withdrawn.
     */
    public Map<String, Object> withdraw(SessionCtx ctx, WithdrawInput in) {
        requireMember(ctx);
        String note = in == null ? null : noteOf(in.note());
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = ownEmployment(c, ctx);
            Map<String, Object> x = e == null ? null : Sql.one(c, "select * from app.exit_case where employment_id = ?::uuid "
                    + "and kind = 'resignation' and status in ('submitted','recommended','accepted') for update", e.get("id"));
            if (x == null) {
                throw new ApiException(409, "exit.none_open", "You have no resignation to withdraw");
            }
            UUID id = AuthService.uuid(x.get("id"));
            Map<String, String> values = ExitValues.forCase(c, id);
            if (!"accepted".equals(x.get("status"))) {
                Sql.update(c, "update app.exit_case set status = 'withdrawn', withdrawn_at = now(), withdrawal_note = ? where id = ?", note, id);
                UserService.audit(c, ctx, "exit.withdrawn", "exit", id, Map.of());
                UUID manager = managerMembership(c, AuthService.uuid(e.get("id")), today);
                Notifications.send(c, ctx.tenantId(), manager, "resignation_withdrawn", values, "exits");
                for (UUID hr : hrMembers(c)) {
                    if (!hr.equals(manager)) {
                        Notifications.send(c, ctx.tenantId(), hr, "resignation_withdrawn", values, "exits");
                    }
                }
                return view(c, ctx, id, today, true);
            }
            if (LocalDate.parse((String) x.get("lastWorkingDay")).isBefore(today)) {
                throw new ApiException(409, "exit.past", "Your last working day has passed, so the resignation cannot be withdrawn");
            }
            if (x.get("withdrawalRequestedAt") != null && x.get("withdrawalRefusedAt") == null) {
                throw new ApiException(409, "exit.withdrawal_pending", "You have already asked to withdraw; HR will decide");
            }
            Sql.update(c, "update app.exit_case set withdrawal_requested_at = now(), withdrawal_note = ?, withdrawal_refused_at = null "
                    + "where id = ?", note, id);
            UserService.audit(c, ctx, "exit.withdrawal_requested", "exit", id, Map.of());
            for (UUID hr : hrMembers(c)) {
                Notifications.send(c, ctx.tenantId(), hr, "withdrawal_requested", values, "exits");
            }
            return view(c, ctx, id, today, true);
        });
    }

    // ── HR and managers ─────────────────────────────────────────────────

    /** Exits the caller can see: everyone's for HR, their team's for a manager. Status: open (default), closed or all. */
    public List<Map<String, Object>> list(SessionCtx ctx, String status) {
        String scope = ctx.scopeOf("exit.read");
        if (scope == null || "self".equals(scope)) {
            throw ApiException.denied("exit.read");
        }
        String wanted = status == null || status.isBlank() ? "open" : status;
        if (!Set.of("open", "closed", "all").contains(wanted)) {
            throw ApiException.badRequest("Status must be open, closed or all");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            ExitDefaults.ensure(c, ctx.tenantId());
            applyDue(c, ctx.tenantId(), today);
            UUID[] reach = Team.reach(c, ctx, "exit.read", today);
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder(LIST_SELECT).append(" where true");
            if (wanted.equals("open")) {
                sql.append(" and (x.status in ('proposed','submitted','recommended') "
                        + "or (x.status = 'accepted' and (x.ended_at is null or x.settlement_state <> 'paid')))");
            } else if (wanted.equals("closed")) {
                sql.append(" and (x.status in ('closed','withdrawn','cancelled') or (x.status = 'accepted' and x.ended_at is not null "
                        + "and x.settlement_state = 'paid'))");
            }
            if (reach != null) {
                sql.append(" and e.membership_id = any(?)");
                params.add(reach);
            }
            sql.append(" order by coalesce(x.last_working_day, x.notice_end, (x.started_at at time zone 'Asia/Kolkata')::date), p.legal_name "
                    + "limit 500");
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : Sql.query(c, sql.toString(), params.toArray())) {
                out.add(decorateRow(row, today));
            }
            return out;
        });
    }

    /** One exit, for HR, the employee's manager, or the employee. */
    public Map<String, Object> get(SessionCtx ctx, UUID id) {
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            applyDue(c, ctx.tenantId(), today);
            requireSee(c, ctx, id, today);
            return view(c, ctx, id, today, false);
        });
    }

    /** The manager agrees a last working day for a report's resignation. */
    public Map<String, Object> recommend(SessionCtx ctx, UUID id, RecommendInput in) {
        LocalDate today = today();
        LocalDate last = date(in.lastWorkingDay(), "last working day");
        String note = noteOf(in.note());
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, id);
            if (!isManagerOf(c, ctx, x, today)) {
                throw new ApiException(403, "permission.denied", "Only the employee's manager can recommend");
            }
            if (!"submitted".equals(x.get("status"))) {
                throw new ApiException(409, "exit.not_waiting", "This resignation is not waiting for the manager");
            }
            if (last.isBefore(today)) {
                throw ApiException.badRequest("The last working day cannot be in the past");
            }
            Sql.update(c, "update app.exit_case set status = 'recommended', recommended_by = ?, recommended_at = now(), "
                    + "recommended_last_day = ?, recommendation_note = ? where id = ?", ctx.membershipId(), last, note, id);
            UserService.audit(c, ctx, "exit.recommended", "exit", id, Map.of("lastWorkingDay", last.toString()));
            Map<String, String> values = ExitValues.forCase(c, id);
            for (UUID hr : hrMembers(c)) {
                if (!hr.equals(ctx.membershipId())) {
                    Notifications.send(c, ctx.tenantId(), hr, "resignation_recommended", values, "exits");
                }
            }
            return view(c, ctx, id, today, false);
        });
    }

    /** HR accepts a resignation (or confirms a proposed exit) and fixes the last working day. */
    public Map<String, Object> accept(SessionCtx ctx, UUID id, AcceptInput in) {
        requireManage(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, id);
            refuseOwn(ctx, x);
            String status = (String) x.get("status");
            if (!Set.of("submitted", "recommended", "proposed").contains(status)) {
                throw new ApiException(409, "exit.not_waiting", "This exit is not waiting to be accepted");
            }
            UUID employment = AuthService.uuid(x.get("employmentId"));
            if ("submitted".equals(status) && Boolean.TRUE.equals(settings(c).get("managerStep"))
                    && managerMembership(c, employment, today) != null && !ctx.membershipId().equals(managerMembership(c, employment, today))) {
                throw new ApiException(409, "exit.awaiting_manager", "This resignation waits for the manager's recommendation first");
            }
            LocalDate last = in.lastWorkingDay() == null || in.lastWorkingDay().isBlank()
                    ? firstDate(x.get("recommendedLastDay"), x.get("requestedLastDay"), x.get("noticeEnd"))
                    : date(in.lastWorkingDay(), "last working day");
            if (last == null) {
                throw ApiException.badRequest("Give the last working day");
            }
            fix(c, ctx, x, last, in.shortfallAction(), in.newManagerId(), today);
            return view(c, ctx, id, today, false);
        });
    }

    /** HR starts an exit: a resignation given on paper (waits for acceptance), or any other kind with its last day. */
    public Map<String, Object> start(SessionCtx ctx, StartInput in) {
        requireManage(ctx);
        String kind = in.kind() == null ? "" : in.kind().trim();
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("Choose the kind of exit: resignation, termination, end of contract, retirement, death or absconding");
        }
        UUID employment = id(in.employmentId(), "employee");
        String reason = in.reasonCategory() == null || in.reasonCategory().isBlank() ? defaultReason(kind) : reasonOf(in.reasonCategory(), REASONS);
        String note = noteOf(in.note());
        LocalDate today = today();
        LocalDate last = in.lastWorkingDay() == null || in.lastWorkingDay().isBlank() ? null : date(in.lastWorkingDay(), "last working day");
        if (last == null && !kind.equals("resignation")) {
            throw ApiException.badRequest("Give the last working day");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            ExitDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> e = lockEmployment(c, employment);
            if (!"active".equals(e.get("status"))) {
                throw new ApiException(409, "employee.not_active", "Only an active employee can leave");
            }
            if (ctx.membershipId() != null && ctx.membershipId().toString().equals(e.get("membershipId"))) {
                throw new ApiException(409, "request.refused", "You cannot start your own exit here; resign from My exit instead");
            }
            refuseIfOpen(c, employment);
            int notice = kind.equals("resignation") ? noticeDays(settings(c), e, today) : 0;
            UUID id = insertCase(c, ctx, e, kind, "submitted", reason, note, last, notice, today);
            UserService.audit(c, ctx, "exit.started", "exit", id, Map.of("kind", kind, "employmentId", employment.toString()));
            if (last != null) {
                // Recorded with its last day: accepted at once (HR is both the one who starts and accepts it).
                fix(c, ctx, lockCase(c, id), last, in.shortfallAction(), in.newManagerId(), today);
            }
            return view(c, ctx, id, today, false);
        });
    }

    /** HR decides a request to withdraw an accepted resignation. */
    public Map<String, Object> decideWithdrawal(SessionCtx ctx, UUID id, DecisionInput in) {
        requireManage(ctx);
        if (in == null || in.approve() == null) {
            throw ApiException.badRequest("Say whether the withdrawal is approved");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, id);
            refuseOwn(ctx, x);
            if (!"accepted".equals(x.get("status")) || x.get("withdrawalRequestedAt") == null || x.get("withdrawalRefusedAt") != null) {
                throw new ApiException(409, "exit.no_withdrawal", "There is no request to withdraw this resignation");
            }
            if (x.get("endedAt") != null) {
                throw new ApiException(409, "exit.past", "The last working day has passed");
            }
            boolean approve = in.approve();
            Map<String, String> values = ExitValues.forCase(c, id);
            if (approve) {
                Sql.update(c, "update app.exit_case set status = 'withdrawn', withdrawn_at = now() where id = ?", id);
                Sql.update(c, "update app.exit_task set done_by = ?, done_at = now(), skipped_reason = 'The resignation was withdrawn' "
                        + "where exit_case_id = ? and done_at is null", ctx.membershipId(), id);
                values.put("note", "HR approved your request: your resignation is withdrawn, and you stay.");
            } else {
                Sql.update(c, "update app.exit_case set withdrawal_refused_at = now() where id = ?", id);
                values.put("note", "HR could not approve your request" + (blank(in.note()) ? "" : ": " + in.note().trim())
                        + ". Your last working day stays " + values.get("exit.last_working_day") + ".");
            }
            UserService.audit(c, ctx, approve ? "exit.withdrawal_approved" : "exit.withdrawal_refused", "exit", id,
                    blank(in.note()) ? Map.of() : Map.of("note", in.note().trim()));
            Notifications.send(c, ctx.tenantId(), employeeMembership(c, x), "withdrawal_decided", values, "my-exit");
            return view(c, ctx, id, today, false);
        });
    }

    /** HR cancels an exit that will not happen: a proposal not taken up, or a case started in error before its last day. */
    public Map<String, Object> cancel(SessionCtx ctx, UUID id, WithdrawInput in) {
        requireManage(ctx);
        String reason = in == null ? null : noteOf(in.note());
        if (reason == null || reason.length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, id);
            refuseOwn(ctx, x);
            if (!OPEN.contains((String) x.get("status")) || x.get("endedAt") != null) {
                throw new ApiException(409, "exit.closed", "Only an exit whose last day has not passed can be cancelled");
            }
            Sql.update(c, "update app.exit_case set status = 'cancelled', cancelled_reason = ? where id = ?", reason, id);
            Sql.update(c, "update app.exit_task set done_by = ?, done_at = now(), skipped_reason = 'The exit was cancelled' "
                    + "where exit_case_id = ? and done_at is null", ctx.membershipId(), id);
            UserService.audit(c, ctx, "exit.cancelled", "exit", id, Map.of("reason", reason));
            return view(c, ctx, id, today, false);
        });
    }

    /** HR closes an exit once everything is done, recording whether the person could be hired again. */
    public Map<String, Object> close(SessionCtx ctx, UUID id, CloseInput in) {
        requireManage(ctx);
        String rehire = in == null || in.rehire() == null ? null : in.rehire().trim();
        if (rehire == null || !Set.of("yes", "no", "maybe").contains(rehire)) {
            throw ApiException.badRequest("Say whether they could be hired again: yes, no or maybe");
        }
        String note = in.rehireNote() == null || in.rehireNote().isBlank() ? null : in.rehireNote().trim();
        if (note != null && note.length() > 500) {
            throw ApiException.badRequest("Keep the note to 500 characters");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            applyDue(c, ctx.tenantId(), today);
            Map<String, Object> x = lockCase(c, id);
            refuseOwn(ctx, x);
            if ("closed".equals(x.get("status"))) {
                Sql.update(c, "update app.exit_case set rehire = ?, rehire_note = ? where id = ?", rehire, note, id);
                UserService.audit(c, ctx, "exit.rehire_changed", "exit", id, Map.of("rehire", rehire));
                return view(c, ctx, id, today, false);
            }
            if (!"accepted".equals(x.get("status")) || x.get("endedAt") == null) {
                throw new ApiException(409, "exit.not_ended", "An exit can be closed once the last working day has passed");
            }
            List<String> left = closeBlockers(c, id);
            if (!left.isEmpty()) {
                throw new ApiException(409, "exit.not_finished", "Finish these first: " + String.join("; ", left));
            }
            Sql.update(c, "update app.exit_case set status = 'closed', closed_by = ?, closed_at = now(), rehire = ?, rehire_note = ? "
                    + "where id = ?", ctx.membershipId(), rehire, note, id);
            UserService.audit(c, ctx, "exit.closed", "exit", id, Map.of("rehire", rehire));
            return view(c, ctx, id, today, false);
        });
    }

    /** What stops an exit from being closed, in words. */
    static List<String> closeBlockers(Connection c, UUID id) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> t : Sql.query(c, "select title from app.exit_task where exit_case_id = ? and required and done_at is null "
                + "order by due_date nulls last, position", id)) {
            out.add("Task: " + t.get("title"));
        }
        Map<String, Object> x = Sql.one(c, "select settlement_state from app.exit_case where id = ?", id);
        if (!"paid".equals(x.get("settlementState"))) {
            out.add("the settlement is not paid");
        }
        return out;
    }

    // ── settings ────────────────────────────────────────────────────────

    public Map<String, Object> getSettings(SessionCtx ctx) {
        String scope = ctx.scopeOf("exit.read");
        if (scope == null || "self".equals(scope)) {
            throw ApiException.denied("exit.read");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            ExitDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> out = new LinkedHashMap<>(settings(c));
            out.put("canConfigure", ctx.canAcrossOrganization("onboarding.configure"));
            return out;
        });
    }

    public Map<String, Object> saveSettings(SessionCtx ctx, SettingsInput in) {
        if (!ctx.canAcrossOrganization("onboarding.configure")) {
            throw ApiException.denied("onboarding.configure");
        }
        Map<String, Object> notice = new LinkedHashMap<>();
        for (String type : List.of("permanent", "probation", "fixed_term", "intern", "consultant")) {
            Object v = in.noticeDays() == null ? null : in.noticeDays().get(type);
            int days;
            try {
                days = v == null ? -1 : Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException e) {
                days = -1;
            }
            if (days < 0 || days > 180) {
                throw ApiException.badRequest("Give the notice for each employment type, from 0 to 180 days");
            }
            notice.put(type, days);
        }
        String shortfall = in.shortfallDefault() == null ? "leave_then_owed" : in.shortfallDefault().trim();
        if (!Set.of("waived", "leave_then_owed", "owed").contains(shortfall)) {
            throw ApiException.badRequest("Choose how a notice shortfall is settled by default");
        }
        int retirement = between(in.retirementAge(), 60, 50, 75, "The retirement age must be from 50 to 75");
        int absconding = between(in.abscondingDays(), 7, 3, 60, "Days absent before absconding must be from 3 to 60");
        int response = between(in.responseDays(), 7, 1, 30, "Days to respond must be from 1 to 30");
        int access = between(in.formerAccessMonths(), 12, 0, 36, "Former employees' access must be from 0 to 36 months");
        UUID[] noticeLeave = in.noticeLeaveTypeIds() == null ? null : ids(in.noticeLeaveTypeIds());
        UUID[] encashable = ids(in.encashableLeaveTypeIds() == null ? List.of() : in.encashableLeaveTypeIds());
        return db.inTenant(ctx.tenantId(), c -> {
            ExitDefaults.ensure(c, ctx.tenantId());
            for (UUID t : concat(noticeLeave, encashable)) {
                if (Sql.one(c, "select 1 from app.leave_type where id = ?", t) == null) {
                    throw ApiException.badRequest("A leave type chosen does not exist");
                }
            }
            Sql.update(c, "update app.exit_settings set notice_days = ?::jsonb, manager_step = ?, notice_leave_type_ids = ?, "
                    + "shortfall_default = ?, retirement_age = ?, absconding_days = ?, response_days = ?, former_access_months = ?, "
                    + "encashable_leave_type_ids = ?, updated_by = ?, updated_at = now()",
                    Json.write(notice), !Boolean.FALSE.equals(in.managerStep()), noticeLeave, shortfall, (short) retirement,
                    (short) absconding, (short) response, (short) access, encashable, ctx.membershipId());
            UserService.audit(c, ctx, "exit.settings_changed", "tenant", ctx.tenantId(), Map.of("noticeDays", notice));
            Map<String, Object> out = new LinkedHashMap<>(settings(c));
            out.put("canConfigure", true);
            return out;
        });
    }

    // ── falling due ─────────────────────────────────────────────────────

    /**
     * Applies every exit whose last working day has passed: the employment becomes exited, the
     * reports move to whoever takes them over, and the case records when it ended. The
     * membership itself is ended by {@link EmployeeService#applyDueExits}, which calls this first.
     */
    public static void applyDue(Connection c, UUID tenantId, LocalDate today) throws SQLException {
        for (Map<String, Object> x : Sql.query(c, "select id from app.exit_case where status = 'accepted' and ended_at is null "
                + "and last_working_day < ?", today)) {
            end(c, tenantId, AuthService.uuid(x.get("id")), today);
        }
    }

    /** The same, for one person's session. */
    static void applyDueFor(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        if (membershipId == null) {
            return;
        }
        for (Map<String, Object> x : Sql.query(c, "select x.id from app.exit_case x join app.employment e on e.id = x.employment_id "
                + "where e.membership_id = ? and x.status = 'accepted' and x.ended_at is null and x.last_working_day < ?", membershipId, today)) {
            end(c, tenantId, AuthService.uuid(x.get("id")), today);
        }
    }

    private static void end(Connection c, UUID tenantId, UUID id, LocalDate today) throws SQLException {
        // Claimed first: of several requests arriving together, one applies it.
        Map<String, Object> x = Sql.one(c, "update app.exit_case set ended_at = now() where id = ? and ended_at is null "
                + "returning employment_id, last_working_day, kind, reason_category, new_manager_id", id);
        if (x == null) {
            return;
        }
        LocalDate last = LocalDate.parse((String) x.get("lastWorkingDay"));
        Map<String, Object> months = Sql.one(c, "select former_access_months from app.exit_settings");
        int access = months == null ? 12 : ((Number) months.get("formerAccessMonths")).intValue();
        Sql.update(c, "update app.exit_case set former_access_until = ? where id = ?", last.plusMonths(access), id);
        Sql.update(c, "update app.employment set status = 'exited', exit_date = ?, exit_reason = ? where id = ?::uuid and status = 'active'",
                last, ExitValues.KINDS.getOrDefault((String) x.get("kind"), (String) x.get("kind"))
                        + (x.get("reasonCategory") == null ? "" : ", " + x.get("reasonCategory")), x.get("employmentId"));
        handOver(c, AuthService.uuid(x.get("employmentId")), AuthService.uuid(x.get("newManagerId")), last.plusDays(1));
        Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_label, action, target_type, target_id, detail) "
                + "values (?, 'system', 'system', 'exit.ended', 'exit', ?, ?::jsonb)", tenantId, id,
                Json.object(Map.of("lastWorkingDay", last.toString())));
    }

    /**
     * The leaver's reports move to the employment HR named, from the day after the last working
     * day, as a dated job change that keeps everything else about the report's job. With nobody
     * named, they move to the leaver's own manager.
     */
    static void handOver(Connection c, UUID leaver, UUID successor, LocalDate from) throws SQLException {
        UUID to = successor;
        if (to == null) {
            Map<String, Object> boss = Sql.one(c, "select manager_id from app.job_assignment where employment_id = ? and effective_from <= ? "
                    + "order by effective_from desc, created_at desc limit 1", leaver, from);
            to = boss == null ? null : AuthService.uuid(boss.get("managerId"));
        }
        for (Map<String, Object> j : Sql.query(c, "select distinct on (j.employment_id) j.* from app.job_assignment j "
                + "join app.employment e on e.id = j.employment_id and e.status = 'active' "
                + "where j.effective_from <= ? order by j.employment_id, j.effective_from desc, j.created_at desc", from)) {
            if (!leaver.toString().equals(j.get("managerId"))) {
                continue;
            }
            Sql.update(c, "insert into app.job_assignment (tenant_id, employment_id, effective_from, designation, department_id, "
                    + "manager_id, reason) values (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?)", j.get("tenantId"), j.get("employmentId"), from,
                    j.get("designation"), j.get("departmentId"), to, "Their manager left");
        }
    }

    /**
     * During notice only the leave types the Organization allows may be applied for. Called when
     * an employee applies for their own leave.
     */
    static void refuseLeaveDuringNotice(Connection c, UUID membershipId, UUID leaveTypeId) throws SQLException {
        Map<String, Object> x = Sql.one(c, "select s.notice_leave_type_ids from app.exit_case x join app.employment e on e.id = x.employment_id "
                + "cross join app.exit_settings s where e.membership_id = ? and x.status in ('submitted','recommended','accepted') "
                + "and x.ended_at is null and s.notice_leave_type_ids is not null limit 1", membershipId);
        if (x == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) x.get("noticeLeaveTypeIds");
        if (allowed == null || !allowed.contains(leaveTypeId.toString())) {
            throw new ApiException(409, "leave.not_during_notice", "This kind of leave cannot be taken during your notice period");
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    /** Fixes the last working day: works out the shortfall, accepts, tells people, and applies it if already past. */
    private void fix(Connection c, SessionCtx ctx, Map<String, Object> x, LocalDate last, String shortfallAction, String newManager,
                     LocalDate today) throws SQLException {
        UUID id = AuthService.uuid(x.get("id"));
        UUID employment = AuthService.uuid(x.get("employmentId"));
        Map<String, Object> e = Sql.one(c, "select * from app.employment where id = ?", employment);
        if (last.isBefore(LocalDate.parse((String) e.get("joiningDate")))) {
            throw ApiException.badRequest("The last working day cannot be before the joining date");
        }
        if (e.get("membershipId") != null && EmployeeService.isOnlyOrgAdmin(c, AuthService.uuid(e.get("membershipId")))) {
            throw new ApiException(409, "membership.last_owner", "This employee is the only Org Admin. Make someone else Org Admin first");
        }
        UUID successor = null;
        if (newManager != null && !newManager.isBlank()) {
            successor = id(newManager, "new manager");
            if (successor.equals(employment)
                    || Sql.one(c, "select 1 from app.employment where id = ? and status = 'active'", successor) == null) {
                throw ApiException.badRequest("The new manager must be another active employee");
            }
        }
        LocalDate noticeEnd = x.get("noticeEnd") == null ? null : LocalDate.parse((String) x.get("noticeEnd"));
        int shortfall = noticeEnd == null || !last.isBefore(noticeEnd) ? 0 : (int) ChronoUnit.DAYS.between(last, noticeEnd);
        String action = null;
        if (shortfall > 0) {
            String given = shortfallAction == null || shortfallAction.isBlank() ? null : shortfallAction.trim();
            if (given != null && !SHORTFALL.contains(given)) {
                throw ApiException.badRequest("Choose how the notice shortfall is settled: waived, set against leave, owed, or paid in lieu");
            }
            if (given == null) {
                String d = (String) settings(c).get("shortfallDefault");
                given = d.equals("waived") ? "waived" : d.equals("owed") ? "owed" : "leave";
            }
            action = given;
        } else if ("paid_in_lieu".equals(shortfallAction) && !"resignation".equals(x.get("kind"))) {
            action = "paid_in_lieu";   // termination with notice paid instead of served
        }
        Sql.update(c, "update app.exit_case set status = 'accepted', last_working_day = ?, accepted_by = ?, accepted_at = now(), "
                + "shortfall_days = ?, shortfall_action = ?, new_manager_id = ? where id = ?",
                last, ctx.membershipId(), (short) shortfall, action, successor, id);
        UserService.audit(c, ctx, "exit.accepted", "exit", id, Map.of("lastWorkingDay", last.toString(), "shortfallDays", shortfall,
                "shortfallAction", action == null ? "" : action));
        afterAccept(c, ctx, id, today);
        Map<String, String> values = ExitValues.forCase(c, id);
        UUID manager = managerMembership(c, employment, today);
        if ("resignation".equals(x.get("kind"))) {
            Notifications.send(c, ctx.tenantId(), employeeMembership(c, x), "resignation_accepted", values, "my-exit");
        }
        if (manager != null && !manager.equals(ctx.membershipId())) {
            Notifications.send(c, ctx.tenantId(), manager, "exit_started", values, "exits");
        }
        if (last.isBefore(today)) {
            applyDue(c, ctx.tenantId(), today);
            EmployeeService.applyDueExits(c, ctx.tenantId(), today);
        }
    }

    /** What acceptance starts besides the case itself; the clearance checklist and the acceptance letter hook in here. */
    void afterAccept(Connection c, SessionCtx ctx, UUID id, LocalDate today) throws SQLException {
        ExitTasks.createTasks(c, id);
    }

    private UUID insertCase(Connection c, SessionCtx ctx, Map<String, Object> e, String kind, String status, String reason, String note,
                            LocalDate requested, int notice, LocalDate today) throws SQLException {
        byte[] sealed = note == null ? null : crypto.encrypt(ctx.tenantId(), note);
        return AuthService.uuid(Sql.one(c, "insert into app.exit_case (tenant_id, employment_id, person_id, kind, status, started_by, "
                + "reason_category, reason_note_enc, requested_last_day, notice_days, notice_end) values (?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "returning id", ctx.tenantId(), e.get("id"), e.get("personId"), kind, status, ctx.membershipId(), reason, sealed,
                requested, (short) notice, notice > 0 ? today.plusDays(notice) : null).get("id"));
    }

    /** Notice owed: the probation notice while on probation, otherwise the employment type's. */
    static int noticeDays(Map<String, Object> settings, Map<String, Object> e, LocalDate today) {
        @SuppressWarnings("unchecked")
        Map<String, Object> days = (Map<String, Object>) Json.parse((String) settings.get("noticeDays"));
        String type = (String) e.get("employmentType");
        boolean probation = "probation".equals(type) || (e.get("probationEnd") != null && !LocalDate.parse((String) e.get("probationEnd")).isBefore(today));
        Object v = days.get(probation ? "probation" : type);
        return v == null ? 30 : ((Number) v).intValue();
    }

    static Map<String, Object> settings(Connection c) throws SQLException {
        return Sql.one(c, "select notice_days::text as notice_days, manager_step, notice_leave_type_ids, shortfall_default, retirement_age, "
                + "absconding_days, response_days, former_access_months, encashable_leave_type_ids, interview_version, "
                + "interview_questions::text as interview_questions, updated_at from app.exit_settings");
    }

    private static final String LIST_SELECT = "select x.id, x.kind, x.status, x.started_at, x.requested_last_day, x.notice_days, "
            + "x.notice_end, x.recommended_last_day, x.last_working_day, x.shortfall_days, x.withdrawal_requested_at, x.withdrawal_refused_at, "
            + "x.ended_at, x.settlement_state, x.reason_category, e.id as employment_id, e.employee_code, e.membership_id, "
            + "p.legal_name, p.preferred_name, j.designation, d.name as department_name, "
            + "(select count(*) from app.exit_task t where t.exit_case_id = x.id and t.done_at is null) as open_tasks "
            + "from app.exit_case x join app.employment e on e.id = x.employment_id join app.person p on p.id = x.person_id "
            + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
            + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
            + "left join app.department d on d.id = j.department_id";

    private static Map<String, Object> decorateRow(Map<String, Object> row, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("stage", stage(row, today));
        return out;
    }

    /** Where the exit is, in one word for lists: waiting for whom, serving notice, or settling. */
    static String stage(Map<String, Object> x, LocalDate today) {
        String status = (String) x.get("status");
        return switch (status) {
            case "proposed" -> "to_confirm";
            case "submitted" -> "awaiting_manager";
            case "recommended" -> "awaiting_hr";
            case "accepted" -> x.get("withdrawalRequestedAt") != null && x.get("withdrawalRefusedAt") == null ? "withdrawal_asked"
                    : x.get("endedAt") == null ? "notice" : "paid".equals(x.get("settlementState")) ? "to_close" : "settling";
            default -> status;
        };
    }

    /** The full view of one exit. {@code own} views hide what only HR may see. */
    Map<String, Object> view(Connection c, SessionCtx ctx, UUID id, LocalDate today, boolean own) throws SQLException {
        Map<String, Object> x = Sql.one(c, LIST_SELECT + " where x.id = ?", id);
        Map<String, Object> full = Sql.one(c, "select x.*, rb.email as recommended_by_email, ab.email as accepted_by_email, "
                + "np.legal_name as new_manager_name, (x.started_at at time zone 'Asia/Kolkata')::date as started_on "
                + "from app.exit_case x left join app.member_directory() rb on rb.membership_id = x.recommended_by "
                + "left join app.member_directory() ab on ab.membership_id = x.accepted_by "
                + "left join app.employment ne on ne.id = x.new_manager_id left join app.person np on np.id = ne.person_id "
                + "where x.id = ?", id);
        Map<String, Object> out = new LinkedHashMap<>(x);
        for (String k : List.of("startedOn", "recommendedBy", "recommendedAt", "recommendationNote", "recommendedByEmail", "acceptedAt",
                "acceptedByEmail", "shortfallAction", "newManagerId", "newManagerName", "withdrawalNote", "withdrawnAt", "cancelledReason",
                "formerAccessUntil", "closedAt")) {
            out.put(k, full.get(k));
        }
        out.put("stage", stage(x, today));
        boolean hr = canManage(ctx);
        boolean mine = ctx.membershipId() != null && ctx.membershipId().toString().equals(x.get("membershipId"));
        if (hr || mine) {
            out.put("reasonNote", readNote(c, ctx.tenantId(), id));
        }
        if (hr) {
            out.put("rehire", full.get("rehire"));
            out.put("rehireNote", full.get("rehireNote"));
            out.put("legalHold", full.get("legalHold"));
        }
        String status = (String) x.get("status");
        boolean open = OPEN.contains(status);
        boolean ended = x.get("endedAt") != null;
        Map<String, Object> s = settings(c);
        UUID employment = AuthService.uuid(x.get("employmentId"));
        UUID manager = managerMembership(c, employment, today);
        out.put("managerStep", Boolean.TRUE.equals(s.get("managerStep")) && manager != null);
        out.put("canRecommend", !own && "submitted".equals(status) && isManagerOf(c, ctx, x, today));
        out.put("canAccept", !own && hr && !mine && Set.of("submitted", "recommended", "proposed").contains(status)
                && !("submitted".equals(status) && Boolean.TRUE.equals(s.get("managerStep")) && manager != null
                && !manager.equals(ctx.membershipId())));
        out.put("canDecideWithdrawal", !own && hr && !mine && "accepted".equals(status) && !ended
                && x.get("withdrawalRequestedAt") != null && x.get("withdrawalRefusedAt") == null);
        out.put("canCancel", !own && hr && !mine && open && !ended);
        out.put("canClose", !own && hr && !mine && (("accepted".equals(status) && ended) || "closed".equals(status)));
        out.put("canWithdraw", mine && "resignation".equals(x.get("kind")) && (Set.of("submitted", "recommended").contains(status)
                || ("accepted".equals(status) && !ended && !(x.get("withdrawalRequestedAt") != null && x.get("withdrawalRefusedAt") == null))));
        out.put("closeBlockers", hr && "accepted".equals(status) ? closeBlockers(c, id) : List.of());
        List<Map<String, Object>> tasks = ExitTasks.forCase(c, ctx, id, today);
        out.put("clearance", Map.of("done", tasks.stream().filter(t -> t.get("doneAt") != null).count(), "total", tasks.size()));
        out.put("tasks", own ? tasks.stream().filter(t -> ctx.membershipId().toString().equals(t.get("assigneeId"))).toList() : tasks);
        out.put("canManageTasks", !own && hr && !mine && "accepted".equals(status));
        return out;
    }

    private String readNote(Connection c, UUID tenantId, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select reason_note_enc from app.exit_case where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBytes(1) != null ? crypto.decryptText(tenantId, rs.getBytes(1)) : null;
            }
        }
    }

    private void requireSee(Connection c, SessionCtx ctx, UUID id, LocalDate today) throws SQLException {
        Map<String, Object> x = Sql.one(c, "select x.id, e.membership_id from app.exit_case x join app.employment e on e.id = x.employment_id "
                + "where x.id = ?", id);
        if (x == null) {
            throw ApiException.notFound("The exit");
        }
        boolean mine = ctx.membershipId() != null && ctx.membershipId().toString().equals(x.get("membershipId"));
        if (mine) {
            return;
        }
        String scope = ctx.scopeOf("exit.read");
        if (scope == null || "self".equals(scope) || (x.get("membershipId") == null && !ctx.reachesEveryone("exit.read"))
                || (x.get("membershipId") != null && !Team.reaches(c, ctx, "exit.read", AuthService.uuid(x.get("membershipId")), today))) {
            throw ApiException.notFound("The exit");
        }
    }

    private static boolean isManagerOf(Connection c, SessionCtx ctx, Map<String, Object> x, LocalDate today) throws SQLException {
        if (ctx.membershipId() == null || ctx.isSupport()) {
            return false;
        }
        UUID manager = managerMembership(c, AuthService.uuid(x.get("employmentId")), today);
        return ctx.membershipId().equals(manager);
    }

    /** The membership of the employment's current manager, when they have a login and are active. */
    static UUID managerMembership(Connection c, UUID employmentId, LocalDate on) throws SQLException {
        Map<String, Object> m = Sql.one(c, "select boss.membership_id from app.job_assignment j join app.employment boss on boss.id = j.manager_id "
                + "join app.membership m on m.id = boss.membership_id and m.status = 'active' "
                + "where j.employment_id = ? and j.effective_from <= ? order by j.effective_from desc, j.created_at desc limit 1", employmentId, on);
        return m == null ? null : AuthService.uuid(m.get("membershipId"));
    }

    private static UUID employeeMembership(Connection c, Map<String, Object> x) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?::uuid", x.get("employmentId"));
        return e == null ? null : AuthService.uuid(e.get("membershipId"));
    }

    /** Everyone HR should tell: HR Admins, or the Org Admins when there is no HR Admin. */
    static List<UUID> hrMembers(Connection c) throws SQLException {
        List<UUID> out = new ArrayList<>();
        for (String code : List.of("hr_admin", "owner")) {
            for (Map<String, Object> m : Sql.query(c, "select distinct m.id from app.membership m join app.membership_role mr on mr.membership_id = m.id "
                    + "join app.role r on r.id = mr.role_id where m.status = 'active' and r.code = ? order by m.id", code)) {
                out.add(AuthService.uuid(m.get("id")));
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return out;
    }

    private static Map<String, Object> ownEmployment(Connection c, SessionCtx ctx) throws SQLException {
        return Sql.one(c, "select * from app.employment where membership_id = ? and status in ('active','exited') "
                + "order by (status = 'active') desc, joining_date desc limit 1", ctx.membershipId());
    }

    private static Map<String, Object> lockEmployment(Connection c, UUID id) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select * from app.employment where id = ? for update", id);
        if (e == null) {
            throw ApiException.notFound("The employee");
        }
        return e;
    }

    private static Map<String, Object> lockCase(Connection c, UUID id) throws SQLException {
        Map<String, Object> x = Sql.one(c, "select x.*, e.membership_id from app.exit_case x join app.employment e on e.id = x.employment_id "
                + "where x.id = ? for update of x", id);
        if (x == null) {
            throw ApiException.notFound("The exit");
        }
        return x;
    }

    private static void refuseIfOpen(Connection c, UUID employment) throws SQLException {
        if (Sql.one(c, "select 1 from app.exit_case where employment_id = ? and status in ('proposed','submitted','recommended','accepted')",
                employment) != null) {
            throw new ApiException(409, "exit.already_open", "This employee already has an exit in progress");
        }
    }

    private static void refuseOwn(SessionCtx ctx, Map<String, Object> x) {
        if (ctx.membershipId() != null && ctx.membershipId().toString().equals(x.get("membershipId"))) {
            throw new ApiException(409, "request.refused", "Nobody can decide their own exit");
        }
    }

    static boolean canManage(SessionCtx ctx) {
        return ctx.canAcrossOrganization("exit.manage") && ctx.membershipId() != null;
    }

    private static void requireManage(SessionCtx ctx) {
        if (!canManage(ctx)) {
            throw ApiException.denied("exit.manage");
        }
    }

    private static void requireMember(SessionCtx ctx) {
        if (ctx.membershipId() == null || ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "Only a user of the Organization can do this");
        }
    }

    private static String reasonOf(String value, Set<String> allowed) {
        String r = value == null ? "" : value.trim();
        if (!allowed.contains(r)) {
            throw ApiException.badRequest("Choose a reason");
        }
        return r;
    }

    private static String defaultReason(String kind) {
        return switch (kind) {
            case "end_of_contract" -> "contract";
            case "retirement" -> "retirement";
            case "death" -> "death";
            case "absconding" -> "absconding";
            default -> "other";
        };
    }

    private static String noteOf(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.trim().length() > 1000) {
            throw ApiException.badRequest("Keep the note to 1000 characters");
        }
        return note.trim();
    }

    private static LocalDate date(String value, String what) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Give the " + what);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("The " + what + " must be a date like 2026-10-31");
        }
    }

    private static LocalDate firstDate(Object... values) {
        for (Object v : values) {
            if (v != null) {
                return LocalDate.parse(v.toString().substring(0, 10));
            }
        }
        return null;
    }

    static UUID id(String value, String what) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Choose the " + what);
        }
    }

    private static UUID[] ids(List<String> values) {
        return values.stream().map(v -> id(v, "leave type")).distinct().toArray(UUID[]::new);
    }

    private static List<UUID> concat(UUID[] a, UUID[] b) {
        List<UUID> out = new ArrayList<>();
        if (a != null) {
            out.addAll(List.of(a));
        }
        out.addAll(List.of(b));
        return out;
    }

    private static int between(Integer value, int fallback, int min, int max, String message) {
        int v = value == null ? fallback : value;
        if (v < min || v > max) {
            throw ApiException.badRequest(message);
        }
        return v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
