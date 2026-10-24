package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Leave inside one Organization: balances, applying, deciding and cancelling.
 *
 * A balance is never stored. It is the sum of the user's ledger entries for a
 * leave type, and entries are only ever added. Accruals that have fallen due
 * are written when a balance is next read, so there is no scheduled job.
 */
public final class LeaveService {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final Db db;
    private final Clock clock;

    public LeaveService(Db db) {
        this(db, Clock.systemUTC());
    }

    /** The clock is a parameter so tests can choose the date. */
    public LeaveService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** A leave type as the calculations need it. */
    record Type(UUID id, String name, String kind, boolean paid, String accrual, BigDecimal yearlyQuota, boolean prorateJoiners,
                BigDecimal carryForwardCap, String excessAtYearEnd, String whenShort, boolean countDaysOff, Integer expiryDays,
                boolean active, LocalDate created) {
        boolean compOff() {
            return kind.equals("comp_off");
        }
    }

    // ── the caller's own leave ──────────────────────────────────────────

    /** The leave types of the Organization, for anyone who can apply for or see leave. */
    public List<Map<String, Object>> types(SessionCtx ctx) {
        if (ctx.scopeOf("leave.read") == null && !ctx.can("leave.apply")) {
            throw ApiException.denied("leave.read");
        }
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select id, name, kind, paid, accrual, yearly_quota, prorate_joiners, carry_forward_cap, excess_at_year_end, "
                        + "when_short, count_days_off, expiry_days, active from app.leave_type order by active desc, lower(name)"));
    }

    public List<Map<String, Object>> balances(SessionCtx ctx) {
        requireOwn(ctx);
        return db.inTenant(ctx.tenantId(), c -> balancesOf(c, ctx.tenantId(), ctx.membershipId(), today()));
    }

    /** The entries behind one balance, newest first. */
    public List<Map<String, Object>> ledger(SessionCtx ctx, UUID membershipId, UUID leaveTypeId) {
        UUID member = membershipId == null ? ctx.membershipId() : membershipId;
        return db.inTenant(ctx.tenantId(), c -> {
            requireReach(c, ctx, member);
            accrue(c, ctx.tenantId(), member, today());
            return Sql.query(c, "select g.id, g.on_date, g.days, g.kind, g.reason, g.expires_on, g.created_at, "
                    + "r.from_date, r.to_date, dir.email as entered_by "
                    + "from app.leave_ledger g left join app.leave_request r on r.id = g.leave_request_id "
                    + "left join app.member_directory() dir on dir.membership_id = g.entered_by "
                    + "where g.membership_id = ? and g.leave_type_id = ? order by g.on_date desc, g.created_at desc",
                    member, leaveTypeId);
        });
    }

    /** One user's balances, for someone who can see that user. */
    public List<Map<String, Object>> userBalances(SessionCtx ctx, UUID membershipId) {
        return db.inTenant(ctx.tenantId(), c -> {
            requireReach(c, ctx, membershipId);
            return balancesOf(c, ctx.tenantId(), membershipId, today());
        });
    }

    /** Counts the days of a request and says whether the balance covers it. Writes no request. */
    public Map<String, Object> preview(SessionCtx ctx, UUID leaveTypeId, String fromDate, String toDate, String half) {
        requireApply(ctx);
        LocalDate from = AttendanceDays.date(fromDate);
        LocalDate to = toDate == null || toDate.isBlank() ? from : AttendanceDays.date(toDate);
        return db.inTenant(ctx.tenantId(), c -> {
            Plan plan = plan(c, ctx.tenantId(), ctx.membershipId(), leaveTypeId, from, to, half(half), today(), null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", plan.days);
            out.put("dates", plan.counted.stream().map(LocalDate::toString).toList());
            out.put("available", plan.available);
            out.put("unpaidDays", plan.unpaidDays);
            out.put("covered", plan.problem == null);
            out.put("message", plan.problem);
            return out;
        });
    }

    public Map<String, Object> apply(SessionCtx ctx, UUID leaveTypeId, String fromDate, String toDate, String half, String reason) {
        requireApply(ctx);
        String why = reason(reason);
        LocalDate from = AttendanceDays.date(fromDate);
        LocalDate to = toDate == null || toDate.isBlank() ? from : AttendanceDays.date(toDate);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            int back = Settings.get(c, Settings.LEAVE_BACK_DAYS).asInt();
            int ahead = Settings.get(c, Settings.LEAVE_AHEAD_DAYS).asInt();
            if (from.isBefore(today.minusDays(back)) || to.isAfter(today.plusDays(ahead))) {
                throw new ApiException(400, "leave.window", "Leave can be for the last " + back
                        + " days or the next " + ahead + ". For older dates ask HR");
            }
            ExitService.refuseLeaveDuringNotice(c, ctx.membershipId(), leaveTypeId);
            return create(c, ctx, ctx.membershipId(), leaveTypeId, from, to, half(half), why, today, false);
        });
    }

    public List<Map<String, Object>> myRequests(SessionCtx ctx) {
        requireApply(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select r.id, t.name as leave_type, r.from_date, r.to_date, r.half, r.days, r.unpaid_days, r.reason, r.status, "
                        + "r.decision_note, r.decided_at, r.created_at, (r.applied_by is not null) as applied_by_hr, "
                        + "(r.status = 'pending' or (r.status = 'approved' and r.from_date >= ?)) as can_cancel "
                        + "from app.leave_request r join app.leave_type t on t.id = r.leave_type_id "
                        + "where r.membership_id = ? order by r.from_date desc, r.created_at desc limit 200",
                today(), ctx.membershipId()));
    }

    /** Withdraws the caller's own leave: a pending request, or an approved one that has not started. */
    public void cancel(SessionCtx ctx, UUID requestId) {
        requireApply(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lock(c, requestId);
            if (r == null || !ctx.membershipId().equals(AuthService.uuid(r.get("membershipId")))
                    || !List.of("pending", "approved").contains((String) r.get("status"))) {
                throw ApiException.notFound("The leave request");
            }
            if (r.get("status").equals("approved") && LocalDate.parse((String) r.get("fromDate")).isBefore(today())) {
                throw new ApiException(409, "leave.started", "This leave has started. Ask HR to cancel it");
            }
            withdraw(c, ctx, r);
            return null;
        });
    }

    // ── other people's leave ────────────────────────────────────────────

    /** Requests the caller may decide, each with the user's balance for that leave type. */
    public List<Map<String, Object>> requests(SessionCtx ctx, String status) {
        String scope = ctx.scopeOf("leave.approve");
        if (scope == null || scope.equals("self")) {
            throw ApiException.denied("leave.approve");
        }
        String wanted = status == null || status.isBlank() ? "pending" : status;
        return db.inTenant(ctx.tenantId(), c -> {
            UUID[] team = Team.reach(c, ctx, "leave.approve", today());
            return Sql.query(c,
                "select r.id, r.membership_id, dir.email, " + Names.of("r.membership_id") + " as name, t.name as leave_type, t.paid, r.from_date, r.to_date, r.half, r.days, "
                        + "r.unpaid_days, r.reason, r.status, r.decision_note, r.decided_at, r.created_at, (r.membership_id = ?) as own, "
                        + "(select coalesce(sum(g.days), 0) from app.leave_ledger g "
                        + "  where g.membership_id = r.membership_id and g.leave_type_id = r.leave_type_id) as balance, "
                        + "array(select coalesce(" + Names.of("o.membership_id") + ", d2.email::text) from app.leave_request o "
                        + "  join app.member_directory() d2 on d2.membership_id = o.membership_id "
                        + "  where o.status = 'approved' and o.membership_id <> r.membership_id "
                        + "  and o.from_date <= r.to_date and o.to_date >= r.from_date order by 1) as also_away "
                        + "from app.leave_request r join app.leave_type t on t.id = r.leave_type_id "
                        + "join app.member_directory() dir on dir.membership_id = r.membership_id "
                        + "where r.status = ?" + (team == null ? "" : " and (r.membership_id = any(?) or r.membership_id = ?)")
                        + " order by r.from_date, r.created_at limit 200",
                    team == null ? new Object[] {ctx.membershipId(), wanted}
                            : new Object[] {ctx.membershipId(), wanted, team, ctx.membershipId()});
        });
    }

    public void decide(SessionCtx ctx, UUID requestId, boolean approve, String note) {
        String scope = ctx.scopeOf("leave.approve");
        if (scope == null || scope.equals("self") || ctx.membershipId() == null) {
            throw ApiException.denied("leave.approve");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lock(c, requestId);
            if (r == null || !"pending".equals(r.get("status"))) {
                throw ApiException.notFound("The pending leave request");
            }
            UUID owner = AuthService.uuid(r.get("membershipId"));
            if (!owner.equals(ctx.membershipId()) && !Team.reaches(c, ctx, "leave.approve", owner, today())) {
                throw ApiException.notFound("The pending leave request");
            }
            if (owner.equals(ctx.membershipId())) {
                throw new ApiException(409, "leave.own_request", "You cannot decide your own leave");
            }
            LocalDate from = LocalDate.parse((String) r.get("fromDate"));
            LocalDate to = LocalDate.parse((String) r.get("toDate"));
            requireOpenYear(c, from, to);
            String clean = note == null || note.isBlank() ? null : note.trim();
            if (approve) {
                // The balance may have moved since the request was raised.
                Plan plan = plan(c, ctx.tenantId(), owner, AuthService.uuid(r.get("leaveTypeId")), from, to,
                        (String) r.get("half"), today(), requestId);
                if (plan.problem != null) {
                    throw new ApiException(409, "leave.insufficient_balance", plan.problem);
                }
                Sql.update(c, "update app.leave_request set status = 'approved', decided_by = ?, decided_at = now(), decision_note = ?, "
                        + "days = ?, unpaid_days = ? where id = ?", ctx.membershipId(), clean, plan.days, plan.unpaidDays, requestId);
                takeEffect(c, ctx.tenantId(), owner, AuthService.uuid(r.get("leaveTypeId")), requestId, from, to,
                        plan.days.subtract(plan.unpaidDays), plan.counted);
            } else {
                Sql.update(c, "update app.leave_request set status = 'rejected', decided_by = ?, decided_at = now(), decision_note = ? "
                        + "where id = ?", ctx.membershipId(), clean, requestId);
            }
            UserService.audit(c, ctx, approve ? "leave.approved" : "leave.rejected", "leave_request", requestId,
                    Map.of("from", from.toString(), "to", to.toString(), "days", r.get("days").toString()));
            return null;
        });
    }

    /** HR cancels an approved leave, including one that has started, as long as its leave year is open. */
    public void cancelAny(SessionCtx ctx, UUID requestId) {
        ctx.require("leave.manage");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lock(c, requestId);
            if (r == null || !List.of("pending", "approved").contains((String) r.get("status"))) {
                throw ApiException.notFound("The leave request");
            }
            if (AuthService.uuid(r.get("membershipId")).equals(ctx.membershipId())) {
                throw new ApiException(409, "leave.own_request", "Cancel your own leave from My leave, or ask another administrator");
            }
            withdraw(c, ctx, r);
            return null;
        });
    }

    /** Who is away on each date in a period: leave asked for or approved, and approved days on duty or at home. */
    public List<Map<String, Object>> calendar(SessionCtx ctx, String fromDate, String toDate) {
        String scope = ctx.scopeOf("leave.read");
        if (scope == null || scope.equals("self")) {
            throw ApiException.denied("leave.read");
        }
        LocalDate from = AttendanceDays.date(fromDate);
        LocalDate to = AttendanceDays.date(toDate);
        if (to.isBefore(from) || from.plusDays(92).isBefore(to)) {
            throw ApiException.badRequest("Choose a period of up to three months");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            UUID[] team = Team.reach(c, ctx, "leave.read", today());
            List<Map<String, Object>> rows = Sql.query(c,
                "select r.membership_id, dir.email, " + Names.of("r.membership_id") + " as name, 'leave' as kind, t.name as label, r.from_date, r.to_date, r.half, r.status "
                        + "from app.leave_request r join app.leave_type t on t.id = r.leave_type_id "
                        + "join app.member_directory() dir on dir.membership_id = r.membership_id "
                        + "where r.status in ('pending','approved') and r.from_date <= ? and r.to_date >= ? "
                        + "union all "
                        + "select a.membership_id, dir.email, " + Names.of("a.membership_id") + ", a.kind, case a.kind when 'on_duty' then 'On duty' else 'Work from home' end, "
                        + "a.work_date, a.work_date, a.half, a.status "
                        + "from app.attendance_request a join app.member_directory() dir on dir.membership_id = a.membership_id "
                        + "where a.kind in ('on_duty','work_from_home') and a.status in ('pending','approved') and a.work_date between ? and ? "
                        + "order by 6, 3", to, from, from, to);
            if (team == null) {
                return rows;
            }
            Set<String> visible = new HashSet<>();
            for (UUID id : team) {
                visible.add(id.toString());
            }
            visible.add(String.valueOf(ctx.membershipId()));
            return rows.stream().filter(r -> visible.contains(String.valueOf(r.get("membershipId")))).toList();
        });
    }

    // ── shared with the admin service and with attendance ───────────────

    /** What applying for a range would mean: the days it uses and whether the balance covers them. */
    static final class Plan {
        Type type;
        BigDecimal days = BigDecimal.ZERO;
        List<LocalDate> counted = List.of();
        BigDecimal available;                    // null for an unpaid type
        BigDecimal unpaidDays = BigDecimal.ZERO;
        String problem;                          // why it cannot go ahead, or null
    }

    /**
     * @param excluding a request to leave out of the pending total, when re-checking that request itself
     */
    static Plan plan(Connection c, UUID tenantId, UUID membershipId, UUID leaveTypeId, LocalDate from, LocalDate to, String half,
                     LocalDate today, UUID excluding) throws SQLException {
        Plan p = new Plan();
        p.type = type(c, leaveTypeId);
        if (p.type == null) {
            throw ApiException.notFound("The leave type");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("The last date is before the first");
        }
        if (half != null && !from.equals(to)) {
            throw ApiException.badRequest("A half day is for a single date");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw ApiException.badRequest("A leave can be at most a year long");
        }
        Map<LocalDate, Boolean> off = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            AttendanceDays.Assigned a = AttendanceDays.assigned(c, membershipId, d);
            if (a == null) {
                throw new ApiException(409, "attendance.not_assigned",
                        "No work location and shift are assigned for " + d + ". Ask HR to set them up");
            }
            off.put(d, AttendanceDays.dayOff(c, a, d) != null);
        }
        LeaveDays.Result counted = LeaveDays.count(from, to, half, p.type.countDaysOff(), off::get);
        p.days = counted.days();
        p.counted = counted.counted();
        if (p.days.signum() == 0) {
            throw new ApiException(400, "leave.no_days", "Those dates are all days off; no leave is needed");
        }
        if (!p.type.paid()) {
            p.unpaidDays = p.days;
            return p;
        }
        accrue(c, tenantId, membershipId, today);
        BigDecimal pending = (BigDecimal) Sql.one(c, "select coalesce(sum(days - unpaid_days), 0) as held from app.leave_request "
                + "where membership_id = ? and leave_type_id = ? and status = 'pending' and id is distinct from ?::uuid",
                membershipId, leaveTypeId, excluding).get("held");
        BigDecimal future = BigDecimal.ZERO;
        if (from.isAfter(today) && !p.type.compOff()) {
            for (LeaveAccrual.Entry e : due(c, p.type, membershipId, from)) {
                if (e.onDate().isAfter(today)) {
                    future = future.add(e.days());
                }
            }
        }
        p.available = balance(c, membershipId, leaveTypeId).add(future).subtract(pending);
        if (p.days.compareTo(p.available) > 0) {
            if (p.type.whenShort().equals("unpaid")) {
                p.unpaidDays = p.days.subtract(p.available.max(BigDecimal.ZERO));
            } else {
                p.problem = "This needs " + plain(p.days) + " days of " + p.type.name() + " and " + plain(p.available.max(BigDecimal.ZERO))
                        + " are available";
            }
        }
        return p;
    }

    /** Creates a request. On behalf of someone it is created already approved by the person entering it. */
    static Map<String, Object> create(Connection c, SessionCtx ctx, UUID membershipId, UUID leaveTypeId, LocalDate from, LocalDate to,
                                      String half, String reason, LocalDate today, boolean onBehalf) throws SQLException {
        Plan plan = plan(c, ctx.tenantId(), membershipId, leaveTypeId, from, to, half, today, null);
        if (!plan.type.active()) {
            throw new ApiException(409, "leave.type_inactive", plan.type.name() + " is no longer in use");
        }
        if (plan.problem != null) {
            throw new ApiException(409, "leave.insufficient_balance", plan.problem);
        }
        requireOpenYear(c, from, to);
        Map<String, Object> row = Sql.one(c,
                "insert into app.leave_request (tenant_id, membership_id, leave_type_id, from_date, to_date, half, days, unpaid_days, "
                        + "reason, status, applied_by, decided_by, decided_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + (onBehalf ? "now()" : "null") + ") returning id, from_date, to_date, half, days, unpaid_days, reason, status",
                ctx.tenantId(), membershipId, leaveTypeId, from, to, half, plan.days, plan.unpaidDays, reason,
                onBehalf ? "approved" : "pending", onBehalf ? ctx.membershipId() : null, onBehalf ? ctx.membershipId() : null);
        if (onBehalf) {
            takeEffect(c, ctx.tenantId(), membershipId, leaveTypeId, AuthService.uuid(row.get("id")), from, to,
                    plan.days.subtract(plan.unpaidDays), plan.counted);
        }
        return row;
    }

    /** What approval does: charges the balance, withdraws days at home on those dates, and forgets the day results. */
    private static void takeEffect(Connection c, UUID tenantId, UUID membershipId, UUID leaveTypeId, UUID requestId,
                                   LocalDate from, LocalDate to, BigDecimal paidDays, List<LocalDate> counted) throws SQLException {
        if (paidDays.signum() > 0) {
            Sql.update(c, "insert into app.leave_ledger (tenant_id, membership_id, leave_type_id, on_date, days, kind, leave_request_id) "
                    + "values (?, ?, ?, ?, ?, 'taken', ?)", tenantId, membershipId, leaveTypeId, from, paidDays.negate(), requestId);
        }
        for (LocalDate d : counted) {
            Sql.update(c, "update app.attendance_request set status = 'cancelled', cancelled_at = now() "
                    + "where membership_id = ? and work_date = ? and kind = 'work_from_home' and status in ('pending','approved')",
                    membershipId, d);
        }
        AttendanceDays.forget(c, membershipId, from, to);
    }

    /** Cancels a pending or approved request; an approved one gets its days back. */
    private static void withdraw(Connection c, SessionCtx ctx, Map<String, Object> r) throws SQLException {
        UUID requestId = AuthService.uuid(r.get("id"));
        UUID owner = AuthService.uuid(r.get("membershipId"));
        LocalDate from = LocalDate.parse((String) r.get("fromDate"));
        LocalDate to = LocalDate.parse((String) r.get("toDate"));
        boolean approved = r.get("status").equals("approved");
        if (approved) {
            requireOpenYear(c, from, to);
        }
        Sql.update(c, "update app.leave_request set status = 'cancelled', cancelled_at = now() where id = ?", requestId);
        if (!approved) {
            return;
        }
        Map<String, Object> taken = Sql.one(c, "select days from app.leave_ledger where leave_request_id = ? and kind = 'taken'", requestId);
        if (taken != null) {
            Sql.update(c, "insert into app.leave_ledger (tenant_id, membership_id, leave_type_id, on_date, days, kind, leave_request_id) "
                    + "values (?, ?, ?, ?, ?, 'returned', ?)", ctx.tenantId(), owner, AuthService.uuid(r.get("leaveTypeId")), from,
                    ((BigDecimal) taken.get("days")).negate(), requestId);
        }
        AttendanceDays.forget(c, owner, from, to);
        UserService.audit(c, ctx, "leave.cancelled", "leave_request", requestId,
                Map.of("from", from.toString(), "to", to.toString()));
    }

    /** A user's balance for every leave type that is in use or still holds days. */
    static List<Map<String, Object>> balancesOf(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        accrue(c, tenantId, membershipId, today);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Type t : types(c)) {
            BigDecimal balance = t.paid() ? balance(c, membershipId, t.id()) : null;
            if (!t.active() && (balance == null || balance.signum() == 0)) {
                continue;
            }
            BigDecimal pending = (BigDecimal) Sql.one(c, "select coalesce(sum(days - unpaid_days), 0) as held from app.leave_request "
                    + "where membership_id = ? and leave_type_id = ? and status = 'pending'", membershipId, t.id()).get("held");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("leaveTypeId", t.id().toString());
            row.put("name", t.name());
            row.put("kind", t.kind());
            row.put("paid", t.paid());
            row.put("accrual", t.accrual());
            row.put("yearlyQuota", t.yearlyQuota());
            row.put("balance", balance);
            row.put("pending", pending);
            row.put("available", balance == null ? null : balance.subtract(pending));
            row.put("active", t.active());
            out.add(row);
        }
        return out;
    }

    static BigDecimal balance(Connection c, UUID membershipId, UUID leaveTypeId) throws SQLException {
        return (BigDecimal) Sql.one(c, "select coalesce(sum(days), 0) as balance from app.leave_ledger "
                + "where membership_id = ? and leave_type_id = ?", membershipId, leaveTypeId).get("balance");
    }

    /**
     * Writes what has fallen due for a user up to a date: accruals for every paid type, and the expiry of
     * comp-off that was not used in time. Safe to call on every read; nothing is written twice.
     */
    static void accrue(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        for (Type t : types(c)) {
            if (!t.active()) {
                continue;
            }
            if (t.compOff()) {
                expireCompOff(c, tenantId, membershipId, t, today);
                continue;
            }
            List<LeaveAccrual.Entry> due = due(c, t, membershipId, today);
            if (due.isEmpty()) {
                continue;
            }
            Set<String> have = new HashSet<>();
            for (Map<String, Object> g : Sql.query(c, "select on_date from app.leave_ledger where membership_id = ? "
                    + "and leave_type_id = ? and kind = 'accrual'", membershipId, t.id())) {
                have.add((String) g.get("onDate"));
            }
            for (LeaveAccrual.Entry e : due) {
                if (!have.contains(e.onDate().toString())) {
                    Sql.update(c, "insert into app.leave_ledger (tenant_id, membership_id, leave_type_id, on_date, days, kind) "
                            + "values (?, ?, ?, ?, ?, 'accrual') "
                            + "on conflict (membership_id, leave_type_id, on_date) where kind = 'accrual' do nothing",
                            tenantId, membershipId, t.id(), e.onDate(), e.days());
                }
            }
        }
    }

    /** The accrual entries due for a user and type up to a date. A user who has left stops earning on their last day. */
    private static List<LeaveAccrual.Entry> due(Connection c, Type t, UUID membershipId, LocalDate upTo) throws SQLException {
        Map<String, Object> m = Sql.one(c, "select (joined_at at time zone 'Asia/Kolkata')::date as joined, "
                + "(ended_at at time zone 'Asia/Kolkata')::date as ended from app.membership where id = ?", membershipId);
        LocalDate joined = LocalDate.parse((String) m.get("joined"));
        LocalDate since = joined.isAfter(t.created()) ? joined : t.created();
        LocalDate until = m.get("ended") != null && LocalDate.parse((String) m.get("ended")).isBefore(upTo)
                ? LocalDate.parse((String) m.get("ended")) : upTo;
        return LeaveAccrual.due(t.accrual(), t.yearlyQuota(), t.prorateJoiners(),
                AttendanceDays.settings(c).leaveYearStartMonth(), since, until);
    }

    /** Comp-off is used oldest first. An earned entry past its expiry with days still unused is closed with one entry. */
    private static void expireCompOff(Connection c, UUID tenantId, UUID membershipId, Type t, LocalDate today) throws SQLException {
        List<Map<String, Object>> earned = Sql.query(c,
                "select e.id, e.days, e.expires_on, x.days as expired from app.leave_ledger e "
                        + "left join app.leave_ledger x on x.source_ledger_id = e.id and x.kind = 'comp_off_expired' "
                        + "where e.membership_id = ? and e.leave_type_id = ? and e.kind = 'comp_off_earned' order by e.on_date, e.created_at",
                membershipId, t.id());
        if (earned.isEmpty()) {
            return;
        }
        BigDecimal used = ((BigDecimal) Sql.one(c, "select coalesce(sum(days), 0) as used from app.leave_ledger where membership_id = ? "
                + "and leave_type_id = ? and kind in ('taken','returned')", membershipId, t.id()).get("used")).negate();
        // Entries already closed used whatever they did not expire.
        for (Map<String, Object> e : earned) {
            if (e.get("expired") != null) {
                used = used.subtract(((BigDecimal) e.get("days")).add((BigDecimal) e.get("expired")));
            }
        }
        for (Map<String, Object> e : earned) {
            if (e.get("expired") != null) {
                continue;
            }
            BigDecimal days = (BigDecimal) e.get("days");
            BigDecimal consumed = used.max(BigDecimal.ZERO).min(days);
            used = used.subtract(consumed);
            LocalDate expires = LocalDate.parse((String) e.get("expiresOn"));
            BigDecimal left = days.subtract(consumed);
            if (expires.isBefore(today) && left.signum() > 0) {
                Sql.update(c, "insert into app.leave_ledger (tenant_id, membership_id, leave_type_id, on_date, days, kind, source_ledger_id) "
                        + "values (?, ?, ?, ?, ?, 'comp_off_expired', ?) "
                        + "on conflict (source_ledger_id) where kind = 'comp_off_expired' do nothing",
                        tenantId, membershipId, t.id(), expires, left.negate(), AuthService.uuid(e.get("id")));
            }
        }
    }

    /**
     * Approved leave that counts on a day, for the day calculation; null when there is none.
     *
     * @param dayOff whether the day is a weekly off or a holiday for the user
     */
    static DayCalculator.Leave leaveOn(Connection c, UUID membershipId, LocalDate day, boolean dayOff) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select r.id, r.leave_type_id, r.from_date, r.to_date, r.half, r.days, r.unpaid_days, "
                + "t.name, t.paid, t.count_days_off from app.leave_request r join app.leave_type t on t.id = r.leave_type_id "
                + "where r.membership_id = ? and r.status = 'approved' and ? between r.from_date and r.to_date limit 1", membershipId, day);
        if (r == null) {
            return null;
        }
        LocalDate from = LocalDate.parse((String) r.get("fromDate"));
        LocalDate to = LocalDate.parse((String) r.get("toDate"));
        if (dayOff && !(Boolean.TRUE.equals(r.get("countDaysOff")) && day.isAfter(from) && day.isBefore(to))) {
            return null;   // a day off inside the leave that the leave type does not count
        }
        BigDecimal days = (BigDecimal) r.get("days");
        BigDecimal unpaid = (BigDecimal) r.get("unpaidDays");
        boolean isUnpaid = !Boolean.TRUE.equals(r.get("paid")) || unpaid.compareTo(days) >= 0;
        if (!isUnpaid && unpaid.signum() > 0) {
            // Part of the leave is unpaid: the paid days come first, so the unpaid ones are the last counted dates.
            long before = 0;
            for (LocalDate d = from; d.isBefore(day); d = d.plusDays(1)) {
                AttendanceDays.Assigned a = AttendanceDays.assigned(c, membershipId, d);
                boolean off = a != null && AttendanceDays.dayOff(c, a, d) != null;
                if (!off || (Boolean.TRUE.equals(r.get("countDaysOff")) && d.isAfter(from))) {
                    before++;
                }
            }
            isUnpaid = BigDecimal.valueOf(before).compareTo(days.subtract(unpaid)) >= 0;
        }
        return new DayCalculator.Leave((String) r.get("name"), (String) r.get("half"), isUnpaid);
    }

    /** Whether the user has leave asked for or approved that covers the date. */
    static boolean hasLeave(Connection c, UUID membershipId, LocalDate day) throws SQLException {
        return Sql.one(c, "select 1 from app.leave_request where membership_id = ? and status in ('pending','approved') "
                + "and ? between from_date and to_date", membershipId, day) != null;
    }

    /** Refuses a change dated in a leave year that has been closed. */
    static void requireOpenYear(Connection c, LocalDate from, LocalDate to) throws SQLException {
        int startMonth = AttendanceDays.settings(c).leaveYearStartMonth();
        for (LocalDate d : List.of(from, to)) {
            int year = LeaveAccrual.yearLabel(d, startMonth);
            if (Sql.one(c, "select 1 from app.leave_year_end_run where leave_year = ?", year) != null) {
                throw new ApiException(409, "leave.year_closed", "The leave year that contains " + d
                        + " has been closed. Record a correction as an adjustment in the current year");
            }
        }
    }

    static List<Type> types(Connection c) throws SQLException {
        List<Type> out = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, TYPE_COLUMNS + " order by lower(name)")) {
            out.add(type(r));
        }
        return out;
    }

    static Type type(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, TYPE_COLUMNS + " where id = ?", id);
        return r == null ? null : type(r);
    }

    private static final String TYPE_COLUMNS = "select id, name, kind, paid, accrual, yearly_quota, prorate_joiners, carry_forward_cap, "
            + "excess_at_year_end, when_short, count_days_off, expiry_days, active, "
            + "(created_at at time zone 'Asia/Kolkata')::date as created from app.leave_type";

    private static Type type(Map<String, Object> r) {
        return new Type(AuthService.uuid(r.get("id")), (String) r.get("name"), (String) r.get("kind"), (Boolean) r.get("paid"),
                (String) r.get("accrual"), (BigDecimal) r.get("yearlyQuota"), (Boolean) r.get("prorateJoiners"),
                (BigDecimal) r.get("carryForwardCap"), (String) r.get("excessAtYearEnd"), (String) r.get("whenShort"),
                (Boolean) r.get("countDaysOff"), r.get("expiryDays") == null ? null : ((Number) r.get("expiryDays")).intValue(),
                (Boolean) r.get("active"), LocalDate.parse((String) r.get("created")));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> lock(Connection c, UUID requestId) throws SQLException {
        return Sql.one(c, "select id, membership_id, leave_type_id, from_date, to_date, half, days, unpaid_days, status "
                + "from app.leave_request where id = ? for update", requestId);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(AttendanceDays.DEFAULT_ZONE));
    }

    static String half(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.equals("first") && !value.equals("second")) {
            throw ApiException.badRequest("A half day is the first or the second half");
        }
        return value;
    }

    static String reason(String value) {
        if (value == null || value.trim().length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        return value.trim();
    }

    /** 3.00 → 3, 1.50 → 1.5 */
    static String plain(BigDecimal days) {
        return days.signum() == 0 ? "0" : days.stripTrailingZeros().toPlainString();
    }

    private static void requireApply(SessionCtx ctx) {
        if (!ctx.can("leave.apply") || ctx.membershipId() == null) {
            throw ApiException.denied("leave.apply");
        }
    }

    private static void requireOwn(SessionCtx ctx) {
        if (ctx.scopeOf("leave.read") == null || ctx.membershipId() == null) {
            throw ApiException.denied("leave.read");
        }
    }

    /** The caller may see their own leave, their team's, or anyone's when leave.read reaches everyone. Otherwise the user does not exist for them. */
    private void requireReach(Connection c, SessionCtx ctx, UUID membershipId) throws SQLException {
        boolean own = membershipId != null && membershipId.equals(ctx.membershipId()) && ctx.scopeOf("leave.read") != null;
        if (membershipId == null || Sql.one(c, "select 1 from app.membership where id = ?", membershipId) == null
                || (!own && !Team.reaches(c, ctx, "leave.read", membershipId, today()))) {
            throw ApiException.notFound("The user");
        }
    }
}
