package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What HR does with leave: the leave types, leave recorded on someone's behalf,
 * balance adjustments, comp-off, and closing a leave year.
 */
public final class LeaveAdminService {

    private final Db db;
    private final Clock clock;

    public LeaveAdminService(Db db) {
        this(db, Clock.systemUTC());
    }

    public LeaveAdminService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record TypeInput(String name, String kind, Boolean paid, String accrual, BigDecimal yearlyQuota, Boolean prorateJoiners,
                            BigDecimal carryForwardCap, String excessAtYearEnd, String whenShort, Boolean countDaysOff,
                            Integer expiryDays, Boolean active) {}

    // ── leave types ─────────────────────────────────────────────────────

    /** Creates a leave type (id null) or changes one. */
    public Map<String, Object> saveType(SessionCtx ctx, UUID id, TypeInput in) {
        ctx.require("leave.manage");
        if (in.name() == null || in.name().isBlank() || in.name().trim().length() > 60) {
            throw ApiException.badRequest("Give the leave type a name of up to 60 characters");
        }
        String name = in.name().trim();
        String kind = in.kind() == null ? "regular" : in.kind();
        boolean compOff = kind.equals("comp_off");
        if (!compOff && !kind.equals("regular")) {
            throw ApiException.badRequest("The kind must be regular or comp_off");
        }
        boolean paid = compOff || in.paid() == null || in.paid();
        String accrual = compOff || !paid ? "none" : in.accrual() == null ? "yearly" : in.accrual();
        if (!List.of("none", "monthly", "yearly").contains(accrual)) {
            throw ApiException.badRequest("Accrual must be none, monthly or yearly");
        }
        BigDecimal quota = compOff || accrual.equals("none") || in.yearlyQuota() == null ? BigDecimal.ZERO : in.yearlyQuota();
        if (quota.signum() < 0 || quota.compareTo(BigDecimal.valueOf(366)) > 0 || quota.scale() > 1) {
            throw ApiException.badRequest("The yearly quota must be between 0 and 366 days, in whole or half days");
        }
        if (!accrual.equals("none") && quota.signum() == 0) {
            throw ApiException.badRequest("Give the number of days a year, or set accrual to none");
        }
        BigDecimal cap = in.carryForwardCap();
        if (cap != null && (cap.signum() < 0 || cap.scale() > 1)) {
            throw ApiException.badRequest("The carry-forward cap must be zero or more, in whole or half days");
        }
        String excess = in.excessAtYearEnd() == null ? "lapse" : in.excessAtYearEnd();
        String whenShort = in.whenShort() == null ? "refuse" : in.whenShort();
        if (!List.of("lapse", "encash").contains(excess) || !List.of("refuse", "unpaid").contains(whenShort)) {
            throw ApiException.badRequest("Excess at year end is lapse or encash; a shortfall is refuse or unpaid");
        }
        Integer expiry = compOff ? (in.expiryDays() == null ? Integer.valueOf(90) : in.expiryDays()) : null;
        if (expiry != null && (expiry < 1 || expiry > 366)) {
            throw ApiException.badRequest("Comp-off must expire in 1 to 366 days");
        }
        boolean prorate = in.prorateJoiners() == null || in.prorateJoiners();
        boolean countOff = in.countDaysOff() != null && in.countDaysOff();
        boolean active = in.active() == null || in.active();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> row;
            if (id == null) {
                row = Sql.one(c, "insert into app.leave_type (tenant_id, name, kind, paid, accrual, yearly_quota, prorate_joiners, "
                                + "carry_forward_cap, excess_at_year_end, when_short, count_days_off, expiry_days, active) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                        ctx.tenantId(), name, kind, paid, accrual, quota, prorate, cap, excess, whenShort, countOff, expiry, active);
            } else {
                // What kind of leave it is, and whether it is paid, are fixed once people may hold a balance of it.
                row = Sql.one(c, "update app.leave_type set name = ?, accrual = case when paid and kind = 'regular' then ? else accrual end, "
                                + "yearly_quota = case when paid and kind = 'regular' then ? else yearly_quota end, prorate_joiners = ?, "
                                + "carry_forward_cap = ?, excess_at_year_end = ?, when_short = ?, count_days_off = ?, "
                                + "expiry_days = case when kind = 'comp_off' then ? else null end, active = ? where id = ? returning id",
                        name, accrual, quota, prorate, cap, excess, whenShort, countOff, expiry == null ? 90 : expiry, active, id);
                if (row == null) {
                    throw ApiException.notFound("The leave type");
                }
            }
            UUID saved = AuthService.uuid(row.get("id"));
            UserService.audit(c, ctx, id == null ? "leave.type_created" : "leave.type_changed", "leave_type", saved, Map.of("name", name));
            return Map.of("id", saved.toString());
        });
    }

    // ── on someone's behalf ─────────────────────────────────────────────

    /** Every current user with their balance per leave type. */
    public List<Map<String, Object>> allBalances(SessionCtx ctx) {
        if (!ctx.reachesEveryone("leave.read")) {
            throw ApiException.denied("leave.read");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> m : Sql.query(c, "select m.id as membership_id, dir.email, " + Names.of("m.id") + " as name, m.status from app.membership m "
                    + "join app.member_directory() dir on dir.membership_id = m.id "
                    + "where m.status in ('active','suspended') order by lower(coalesce(" + Names.of("m.id") + ", dir.email))")) {
                m.put("balances", LeaveService.balancesOf(c, ctx.tenantId(), AuthService.uuid(m.get("membershipId")), today));
                m.put("own", m.get("membershipId").equals(String.valueOf(ctx.membershipId())));
                out.add(m);
            }
            return out;
        });
    }

    /** Records leave for a user. It is created approved; any date in a leave year that is still open is allowed. */
    public Map<String, Object> applyOnBehalf(SessionCtx ctx, UUID membershipId, UUID leaveTypeId, String fromDate, String toDate,
                                             String half, String reason) {
        requireOther(ctx, membershipId);
        String why = LeaveService.reason(reason);
        LocalDate from = AttendanceService.date(fromDate);
        LocalDate to = toDate == null || toDate.isBlank() ? from : AttendanceService.date(toDate);
        if (to.isAfter(today().plusDays(365))) {
            throw new ApiException(400, "leave.window", "Leave can be recorded up to a year ahead");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            requireUser(c, membershipId);
            Map<String, Object> row = LeaveService.create(c, ctx, membershipId, leaveTypeId, from, to, LeaveService.half(half), why,
                    today(), true);
            UserService.audit(c, ctx, "leave.applied_on_behalf", "leave_request", AuthService.uuid(row.get("id")),
                    Map.of("from", from.toString(), "to", to.toString(), "days", row.get("days").toString(), "reason", why));
            return row;
        });
    }

    /** Adds to or takes from a balance, with a reason. This is also how opening balances are loaded. */
    public void adjust(SessionCtx ctx, UUID membershipId, UUID leaveTypeId, BigDecimal days, String onDate, String reason) {
        requireOther(ctx, membershipId);
        String why = LeaveService.reason(reason);
        if (days == null || days.signum() == 0 || days.abs().compareTo(BigDecimal.valueOf(366)) > 0 || days.scale() > 2) {
            throw ApiException.badRequest("Give the number of days to add, or a negative number to take away");
        }
        LocalDate day = onDate == null || onDate.isBlank() ? today() : AttendanceService.date(onDate);
        if (day.isAfter(today())) {
            throw ApiException.badRequest("An adjustment cannot be dated in the future");
        }
        db.inTenant(ctx.tenantId(), c -> {
            requireUser(c, membershipId);
            LeaveService.Type t = LeaveService.type(c, leaveTypeId);
            if (t == null) {
                throw ApiException.notFound("The leave type");
            }
            if (!t.paid()) {
                throw ApiException.badRequest(t.name() + " is unpaid leave and has no balance to adjust");
            }
            LeaveService.requireOpenYear(c, day, day);
            Sql.update(c, "insert into app.leave_ledger (tenant_id, membership_id, leave_type_id, on_date, days, kind, entered_by, reason) "
                    + "values (?, ?, ?, ?, ?, 'adjustment', ?, ?)", ctx.tenantId(), membershipId, leaveTypeId, day, days, ctx.membershipId(), why);
            UserService.audit(c, ctx, "leave.adjusted", "membership", membershipId,
                    Map.of("leaveType", t.name(), "days", LeaveService.plain(days), "reason", why));
            return null;
        });
    }

    // ── comp-off ────────────────────────────────────────────────────────

    /** Days off in the last 90 days that were worked for at least a half day and have no comp-off yet. */
    public List<Map<String, Object>> compOffCandidates(SessionCtx ctx) {
        ctx.require("leave.manage");
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select d.membership_id, dir.email, " + Names.of("d.membership_id") + " as name, d.work_date, d.status, d.worked_minutes, "
                        + "case when d.worked_minutes >= s.full_day_minutes then 1 else 0.5 end as days "
                        + "from app.attendance_day d join app.shift s on s.id = d.shift_id "
                        + "join app.member_directory() dir on dir.membership_id = d.membership_id "
                        + "where d.status in ('weekly_off','holiday') and d.worked_minutes >= s.half_day_minutes "
                        + "and d.work_date between ? and ? and not exists (select 1 from app.leave_ledger g "
                        + "  where g.membership_id = d.membership_id and g.kind = 'comp_off_earned' and g.on_date = d.work_date) "
                        + "order by d.work_date desc, dir.email", today.minusDays(90), today));
    }

    /** Grants comp-off for a day off that was worked. It expires as the comp-off leave type says. */
    public void grantCompOff(SessionCtx ctx, UUID membershipId, UUID leaveTypeId, String workDate, BigDecimal days) {
        requireOther(ctx, membershipId);
        if (days == null || (days.compareTo(BigDecimal.ONE) != 0 && days.compareTo(new BigDecimal("0.5")) != 0)) {
            throw ApiException.badRequest("Comp-off is granted as a half day or one day");
        }
        LocalDate day = AttendanceService.date(workDate);
        if (day.isAfter(today())) {
            throw ApiException.badRequest("Comp-off is for a day that has been worked");
        }
        db.inTenant(ctx.tenantId(), c -> {
            requireUser(c, membershipId);
            LeaveService.Type t = leaveTypeId != null ? LeaveService.type(c, leaveTypeId)
                    : LeaveService.types(c).stream().filter(x -> x.compOff() && x.active()).findFirst().orElse(null);
            if (t == null || !t.compOff()) {
                throw new ApiException(409, "leave.type_inactive", "Add a comp-off leave type first, under Leave → Setup");
            }
            if (Sql.one(c, "select 1 from app.leave_ledger where membership_id = ? and kind = 'comp_off_earned' and on_date = ?",
                    membershipId, day) != null) {
                throw new ApiException(409, "request.refused", "Comp-off for " + day + " has already been granted");
            }
            LeaveService.requireOpenYear(c, day, day);
            Sql.update(c, "insert into app.leave_ledger (tenant_id, membership_id, leave_type_id, on_date, days, kind, expires_on, "
                            + "entered_by, reason) values (?, ?, ?, ?, ?, 'comp_off_earned', ?, ?, ?)",
                    ctx.tenantId(), membershipId, t.id(), day, days, day.plusDays(t.expiryDays()), ctx.membershipId(),
                    "Worked on " + day);
            UserService.audit(c, ctx, "leave.comp_off_granted", "membership", membershipId,
                    Map.of("date", day.toString(), "days", LeaveService.plain(days)));
            return null;
        });
    }

    // ── year end ────────────────────────────────────────────────────────

    /** What closing a leave year would write: for every user and type, the excess over the carry-forward cap. */
    public Map<String, Object> yearEndPreview(SessionCtx ctx, int year) {
        ctx.require("leave.manage");
        return db.inTenant(ctx.tenantId(), c -> yearEnd(c, ctx, year, false));
    }

    /** Closes a leave year. Writes exactly what the preview shows, once. */
    public Map<String, Object> yearEndRun(SessionCtx ctx, int year) {
        ctx.require("leave.manage");
        return db.inTenant(ctx.tenantId(), c -> yearEnd(c, ctx, year, true));
    }

    private Map<String, Object> yearEnd(Connection c, SessionCtx ctx, int year, boolean commit) throws SQLException {
        int startMonth = AttendanceService.settings(c).leaveYearStartMonth();
        LocalDate start = LocalDate.of(year, startMonth, 1);
        LocalDate end = start.plusYears(1).minusDays(1);
        LocalDate today = today();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("from", start.toString());
        out.put("to", end.toString());
        boolean closed = Sql.one(c, "select 1 from app.leave_year_end_run where leave_year = ?", year) != null;
        long pending = ((Number) Sql.one(c, "select count(*) as n from app.leave_request where status = 'pending' "
                + "and from_date between ? and ?", start, end).get("n")).longValue();
        String blocked = closed ? "This leave year has already been closed"
                : !end.isBefore(today) ? "This leave year has not ended yet"
                : pending > 0 ? pending + " leave request" + (pending == 1 ? " in this year is" : "s in this year are") + " still pending"
                : null;
        out.put("closed", closed);
        out.put("blocked", blocked);
        if (commit && blocked != null) {
            throw new ApiException(409, closed ? "leave.year_closed" : !end.isBefore(today) ? "request.refused" : "leave.year_open_requests", blocked);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal lapsed = BigDecimal.ZERO;
        BigDecimal encashed = BigDecimal.ZERO;
        UUID runId = null;
        if (!closed) {
            if (commit) {
                runId = AuthService.uuid(Sql.one(c, "insert into app.leave_year_end_run (tenant_id, leave_year, run_by) values (?, ?, ?) "
                        + "returning id", ctx.tenantId(), year, ctx.membershipId()).get("id"));
            }
            List<LeaveService.Type> types = LeaveService.types(c).stream()
                    .filter(t -> t.paid() && !t.compOff() && t.carryForwardCap() != null).toList();
            for (Map<String, Object> m : Sql.query(c, "select m.id, dir.email from app.membership m "
                    + "join app.member_directory() dir on dir.membership_id = m.id order by dir.email")) {
                UUID member = AuthService.uuid(m.get("id"));
                LeaveService.accrue(c, ctx.tenantId(), member, today);
                for (LeaveService.Type t : types) {
                    BigDecimal closing = (BigDecimal) Sql.one(c, "select coalesce(sum(days), 0) as b from app.leave_ledger "
                            + "where membership_id = ? and leave_type_id = ? and on_date <= ?", member, t.id(), end).get("b");
                    BigDecimal excess = closing.subtract(t.carryForwardCap());
                    if (excess.signum() <= 0) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("membershipId", member.toString());
                    row.put("email", m.get("email"));
                    row.put("leaveType", t.name());
                    row.put("closingBalance", closing);
                    row.put("carryForward", t.carryForwardCap());
                    row.put("excess", excess);
                    row.put("action", t.excessAtYearEnd());
                    rows.add(row);
                    if (t.excessAtYearEnd().equals("encash")) {
                        encashed = encashed.add(excess);
                    } else {
                        lapsed = lapsed.add(excess);
                    }
                    if (commit) {
                        Sql.update(c, "insert into app.leave_ledger (tenant_id, membership_id, leave_type_id, on_date, days, kind, "
                                + "year_end_run_id) values (?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), member, t.id(), end, excess.negate(),
                                t.excessAtYearEnd(), runId);
                    }
                }
            }
        }
        out.put("rows", rows);
        out.put("lapsed", lapsed);
        out.put("encashed", encashed);
        if (commit) {
            UserService.audit(c, ctx, "leave.year_closed", "leave_year_end_run", runId,
                    Map.of("year", String.valueOf(year), "lapsed", LeaveService.plain(lapsed), "encashed", LeaveService.plain(encashed)));
            out.put("closed", true);
        }
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private LocalDate today() {
        return LocalDate.now(clock.withZone(AttendanceService.DEFAULT_ZONE));
    }

    /** These actions are taken by a user of the Organization for someone else, never for themselves. */
    private static void requireOther(SessionCtx ctx, UUID membershipId) {
        ctx.require("leave.manage");
        if (ctx.membershipId() == null) {
            throw new ApiException(403, "permission.denied", "Only a user of the Organization can do this");
        }
        if (ctx.membershipId().equals(membershipId)) {
            throw new ApiException(409, "leave.own_request", "You cannot do this for yourself; ask another administrator");
        }
    }

    private static void requireUser(Connection c, UUID membershipId) throws SQLException {
        if (Sql.one(c, "select 1 from app.membership where id = ?", membershipId) == null) {
            throw ApiException.notFound("The user");
        }
    }
}
