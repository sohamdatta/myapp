package com.example.loginapp.core;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Getting an Organization started: what is set up so far, what payroll still needs, and filling an
 * empty Organization from a starting setup. A starting setup fills only sections that are empty, in
 * one transaction, and never changes what exists.
 *
 * <p>Locations, shifts and holidays are Core HR (payroll takes the state from them), so they do not
 * depend on the Attendance module. Leave types depend on the Leave module.
 */
public final class SetupService {

    static final int MAX_NAME = 80;
    static final BigDecimal MAX_LEAVE_DAYS = BigDecimal.valueOf(60);

    /** Counts of what exists. People are active employment records; joiners have not started yet. */
    public record Facts(int workLocations, int shifts, int leaveTypes, int people, int peopleWithLogin, int loginsToAssign, int joiners,
                        int otherLogins, int pendingInvitations) {}

    /** Which sections a starting setup can still fill. */
    public record Open(boolean calendar, boolean leave) {}

    /**
     * What payroll would stop on, counted the way payroll counts it. A person with no work location
     * has no state, so no professional tax can be worked out for them.
     */
    public record Readiness(boolean applies, int noBank, int noPan, int withoutLocation, List<String> statesWithoutRules,
                            int locationsWithoutState, boolean draftRules) {}

    /** A state or union territory, by its two-letter code. */
    public record State(String code, String name) {}

    public record Status(boolean attendanceOn, boolean leaveOn, boolean payrollOn, Facts facts, Open open,
                         List<StarterSetups.Starter> starters, List<State> states, Readiness readiness) {}

    /** A change to one kind of leave. Null days or carry keeps the starting setup's own. */
    public record LeaveChoice(String code, BigDecimal days, String carry) {}

    public record Choice(String starter, String locationName, String stateCode, List<LeaveChoice> leave) {}

    public record Applied(boolean calendar, boolean leave, int workLocations, int shifts, int holidays, int leaveTypes) {}

    private static final List<State> STATES = IdChecks.STATES.entrySet().stream().map(e -> new State(e.getKey(), e.getValue())).toList();

    private final Db db;
    private final Clock clock;

    public SetupService(Db db) {
        this(db, Clock.systemUTC());
    }

    public SetupService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(AttendanceDays.DEFAULT_ZONE));
    }

    // ── what is set up ──────────────────────────────────────────────────

    public Status status(SessionCtx ctx) {
        ctx.require("tenant.manage");
        boolean payrollOn = ctx.hasModule(Modules.PAYROLL);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Facts facts = facts(c, ctx);
            Open open = new Open(facts.workLocations() == 0 && facts.shifts() == 0, ctx.hasModule(Modules.LEAVE) && facts.leaveTypes() == 0);
            Readiness readiness = payrollOn ? readiness(c, today) : new Readiness(false, 0, 0, 0, List.of(), 0, false);
            return new Status(ctx.hasModule(Modules.ATTENDANCE), ctx.hasModule(Modules.LEAVE), payrollOn, facts, open,
                    StarterSetups.all(), STATES, readiness);
        });
    }

    private static Facts facts(java.sql.Connection c, SessionCtx ctx) throws java.sql.SQLException {
        Map<String, Object> places = Sql.one(c, """
                select (select count(*) from app.work_location) as work_locations,
                       (select count(*) from app.shift) as shifts,
                       (select count(*) from app.leave_type) as leave_types,
                       (select count(*) from app.membership where status = 'active' and id <> ?) as other_logins,
                       (select count(*) from app.invitation
                         where accepted_at is null and revoked_at is null and expires_at > now()) as pending_invitations
                """, ctx.membershipId());
        Map<String, Object> people = Sql.one(c, """
                select count(*) as people,
                       count(*) filter (where e.membership_id is not null) as people_with_login,
                       count(*) filter (where e.membership_id is not null and not exists (
                           select 1 from app.attendance_assignment a where a.membership_id = e.membership_id)) as logins_to_assign,
                       (select count(*) from app.employment where status = 'onboarding') as joiners
                from app.employment e where e.status = 'active'
                """);
        return new Facts(count(places, "workLocations"), count(places, "shifts"), count(places, "leaveTypes"), count(people, "people"),
                count(people, "peopleWithLogin"), count(people, "loginsToAssign"), count(people, "joiners"),
                count(places, "otherLogins"), count(places, "pendingInvitations"));
    }

    /** The same tests payroll applies: a PAN counts unless replaced or still only applied for; a bank account if any exists. */
    private static Readiness readiness(java.sql.Connection c, LocalDate today) throws java.sql.SQLException {
        Map<String, Object> gaps = Sql.one(c, """
                select count(*) filter (where not exists (
                           select 1 from app.bank_account b where b.person_id = e.person_id)) as no_bank,
                       count(*) filter (where not exists (
                           select 1 from app.statutory_id s where s.person_id = e.person_id and s.kind = 'pan'
                              and s.replaced_at is null and s.pending_reason is null)) as no_pan,
                       count(*) filter (where not exists (
                           select 1 from app.attendance_assignment a where a.membership_id = e.membership_id)) as without_location
                from app.employment e where e.status = 'active'
                """);
        Set<String> covered = new HashSet<>();
        for (Map<String, Object> rule : Sql.query(c, """
                select jurisdiction from app.payroll_rule
                where statute = 'pt' and valid_from <= ? and (valid_to is null or valid_to > ?)
                """, today, today)) {
            covered.add((String) rule.get("jurisdiction"));
        }
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> place : Sql.query(c, "select distinct state_code from app.work_location where active and state_code is not null order by 1")) {
            String state = (String) place.get("stateCode");
            if (!covered.contains(state)) {
                missing.add(state);
            }
        }
        int noState = count(Sql.one(c, "select count(*) as n from app.work_location where active and state_code is null"), "n");
        boolean draft = count(Sql.one(c, """
                select count(*) as n from app.payroll_rule
                where status = 'draft' and valid_from <= ? and (valid_to is null or valid_to > ?)
                """, today, today), "n") > 0;
        return new Readiness(true, count(gaps, "noBank"), count(gaps, "noPan"), count(gaps, "withoutLocation"), List.copyOf(missing), noState, draft);
    }

    /** Whether the Organization has neither a work location nor a shift yet, so a new Org Admin is best sent to Getting started. */
    static boolean isOpen(java.sql.Connection c) throws java.sql.SQLException {
        return count(Sql.one(c, "select (select count(*) from app.work_location) + (select count(*) from app.shift) as n"), "n") == 0;
    }

    private static int count(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    // ── filling an empty Organization ───────────────────────────────────

    public Applied apply(SessionCtx ctx, Choice choice) {
        ctx.require("tenant.manage");
        ctx.require("attendance.manage");
        boolean leaveOn = ctx.hasModule(Modules.LEAVE);
        if (leaveOn) {
            ctx.require("leave.manage");
        }
        Plan chosen = plan(choice);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Plan plan = chosen;
            // Two people pressing the button at once must not each fill the same empty section.
            Sql.query(c, "select pg_advisory_xact_lock(hashtext(?))", "setup:" + ctx.tenantId());
            Facts facts = facts(c, ctx);
            boolean calendar = facts.workLocations() == 0 && facts.shifts() == 0;
            boolean leave = leaveOn && facts.leaveTypes() == 0;
            if (!calendar && !leave) {
                throw new ApiException(409, "setup.already_started", "This Organization already has work locations, shifts or leave types. "
                        + "A starting setup only fills an empty one; change what exists under Attendance and Leave");
            }
            int locations = 0;
            int shifts = 0;
            int holidays = 0;
            int leaveTypes = 0;
            if (calendar) {
                plan = plan.withLocation(choice);
                insertLocation(c, ctx, plan);
                locations = 1;
                shifts = insertShifts(c, ctx, plan.starter());
                holidays = insertHolidays(c, ctx, today);
            }
            if (leave) {
                leaveTypes = insertLeaveTypes(c, ctx, plan);
            }
            Applied applied = new Applied(calendar, leave, locations, shifts, holidays, leaveTypes);
            UserService.audit(c, ctx, "setup.starter_applied", "organization", ctx.tenantId(),
                    Map.of("starter", plan.starter().id(), "workLocations", locations, "shifts", shifts, "holidays", holidays,
                            "leaveTypes", leaveTypes));
            return applied;
        });
    }

    /** The choice, checked, with the leave changes laid over the starting setup's own. */
    private record Plan(StarterSetups.Starter starter, String locationName, String stateCode, List<StarterSetups.LeavePlan> leave) {

        /** The main location, checked. Only needed when the work calendar is still empty. */
        Plan withLocation(Choice choice) {
            String name = choice.locationName() == null ? "" : choice.locationName().trim();
            if (name.isEmpty() || name.length() > MAX_NAME) {
                throw ApiException.badRequest("Give your main location a name of up to " + MAX_NAME + " characters, such as the name of your office or plant");
            }
            String state = choice.stateCode() == null ? "" : choice.stateCode().trim().toUpperCase();
            if (!IdChecks.STATES.containsKey(state)) {
                throw ApiException.badRequest("Choose the state your main location is in. Payroll's professional tax and holidays follow it");
            }
            return new Plan(starter, name, state, leave);
        }
    }

    private static Plan plan(Choice choice) {
        if (choice == null || choice.starter() == null) {
            throw ApiException.badRequest("Choose a starting setup");
        }
        StarterSetups.Starter starter = StarterSetups.find(choice.starter())
                .orElseThrow(() -> ApiException.badRequest("That starting setup does not exist. Choose one from the list"));
        Map<String, LeaveChoice> changes = new java.util.HashMap<>();
        for (LeaveChoice change : choice.leave() == null ? List.<LeaveChoice>of() : choice.leave()) {
            boolean known = starter.leave().stream().anyMatch(l -> l.code().equals(change.code()));
            if (change.code() == null || !known || changes.put(change.code(), change) != null) {
                throw ApiException.badRequest("Each leave change must name one of this setup's leave types once");
            }
        }
        List<StarterSetups.LeavePlan> leave = starter.leave().stream().map(base -> changed(base, changes.get(base.code()))).toList();
        return new Plan(starter, null, null, leave);
    }

    private static StarterSetups.LeavePlan changed(StarterSetups.LeavePlan base, LeaveChoice change) {
        if (change == null) {
            return base;
        }
        BigDecimal days = change.days() == null ? base.days() : change.days();
        boolean wholeOrHalf = days.multiply(BigDecimal.TWO).stripTrailingZeros().scale() <= 0;
        if (days.signum() <= 0 || days.compareTo(MAX_LEAVE_DAYS) > 0 || !wholeOrHalf) {
            throw ApiException.badRequest(base.name() + " must be between 0.5 and " + MAX_LEAVE_DAYS + " days a year, in whole or half days");
        }
        StarterSetups.Carry carry = base.carry();
        if (change.carry() != null) {
            try {
                carry = StarterSetups.Carry.valueOf(change.carry());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Choose what happens to unused " + base.name().toLowerCase() + " days from the list");
            }
        }
        return new StarterSetups.LeavePlan(base.code(), base.name(), days, carry);
    }

    private static void insertLocation(java.sql.Connection c, SessionCtx ctx, Plan plan) throws java.sql.SQLException {
        // "none" lets people punch from anywhere; the Org Admin tightens it once the office network or position is known.
        Map<String, Object> row = Sql.one(c, """
                insert into app.work_location (tenant_id, name, state_code, punch_check)
                values (?, ?, ?, 'none') returning id
                """, ctx.tenantId(), plan.locationName(), plan.stateCode());
        UserService.audit(c, ctx, "attendance.location_created", "work_location", AuthService.uuid(row.get("id")),
                Map.of("name", plan.locationName(), "punchCheck", "none"));
    }

    private static int insertShifts(java.sql.Connection c, SessionCtx ctx, StarterSetups.Starter starter) throws java.sql.SQLException {
        for (StarterSetups.ShiftPlan shift : starter.shifts()) {
            Map<String, Object> row = Sql.one(c, """
                    insert into app.shift (tenant_id, name, start_time, end_time, grace_minutes, full_day_minutes, half_day_minutes)
                    values (?, ?, ?, ?, ?, ?, ?) returning id
                    """, ctx.tenantId(), shift.name(), shift.start(), shift.end(), shift.graceMinutes(),
                    StarterSetups.FULL_DAY_MINUTES, StarterSetups.HALF_DAY_MINUTES);
            UserService.audit(c, ctx, "attendance.shift_created", "shift", AuthService.uuid(row.get("id")),
                    Map.of("name", shift.name(), "hours", shift.start() + " to " + shift.end()));
        }
        return starter.shifts().size();
    }

    /** The fixed national holidays for the next twelve months, for every location. */
    private static int insertHolidays(java.sql.Connection c, SessionCtx ctx, LocalDate today) throws java.sql.SQLException {
        int added = 0;
        for (StarterSetups.FixedHoliday holiday : StarterSetups.NATIONAL_HOLIDAYS) {
            for (LocalDate day : StarterSetups.holidayDates(holiday, today)) {
                Map<String, Object> row = Sql.one(c, """
                        insert into app.holiday (tenant_id, work_location_id, on_date, name) values (?, null, ?, ?) returning id
                        """, ctx.tenantId(), day, holiday.name());
                AttendanceDays.invalidateDate(c, day);
                UserService.audit(c, ctx, "attendance.holiday_added", "holiday", AuthService.uuid(row.get("id")),
                        Map.of("date", day.toString(), "name", holiday.name()));
                added++;
            }
        }
        return added;
    }

    private static int insertLeaveTypes(java.sql.Connection c, SessionCtx ctx, Plan plan) throws java.sql.SQLException {
        for (StarterSetups.LeavePlan leave : plan.leave()) {
            UUID id = AuthService.uuid(Sql.one(c, """
                    insert into app.leave_type (tenant_id, name, kind, paid, accrual, yearly_quota, prorate_joiners,
                                                carry_forward_cap, excess_at_year_end, when_short, count_days_off, active)
                    values (?, ?, 'regular', true, 'yearly', ?, true, ?, ?, 'refuse', false, true) returning id
                    """, ctx.tenantId(), leave.name(), leave.days(), leave.carry().cap(), leave.carry().excess()).get("id"));
            UserService.audit(c, ctx, "leave.type_created", "leave_type", id, Map.of("name", leave.name()));
        }
        return plan.leave().size();
    }
}
