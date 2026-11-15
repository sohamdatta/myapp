package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Salary advances and loans (docs/expenses/design.md, stage X4). A person asks, their manager and then finance approve, finance
 * records the payout, and the schedule is fixed. Payroll takes each instalment that is due as the {@code LOAN_EMI} deduction.
 * Both the manager and the finance step apply to every loan, and nobody approves their own.
 *
 * <p>Interest is simple interest on the reducing balance: each instalment is an equal share of the principal plus the month's
 * interest on what is still owed. The loan interest perquisite and the exit settlement are stage X5.
 */
public final class LoanService {

    public record TypeInput(String name, String kind, BigDecimal maxAmount, Integer maxMonths, BigDecimal annualRate,
                            Integer minServiceMonths, Integer maxOpen, Boolean active) {}

    public record RequestInput(String typeId, BigDecimal amount, Integer months, String purpose) {}

    public record Created(UUID id, String number, String status, int version) {}

    public record Saved(UUID id) {}

    public record Row(LocalDate month, BigDecimal principal, BigDecimal interest) {}

    public record LoanView(UUID id, UUID membershipId, String number, String person, String type, String status, BigDecimal amount, int months,
                           BigDecimal annualRate, String purpose, String decisionNote, Object disbursedOn, String disbursalRef,
                           BigDecimal outstanding, int version, List<Map<String, Object>> schedule, List<Map<String, Object>> settlements,
                           int financeApprovals, boolean firstApproved, boolean iApprovedFirst, String perqExemptReason) {}

    /** The component payroll deducts instalments under. */
    public static final String COMPONENT = "LOAN_EMI";

    /** The component the taxable interest benefit is shown under on the payslip. */
    public static final String PERQUISITE = "LOAN_PERQ";

    /** Loans from the employer count for the perquisite only once they pass this together (Income-tax Rules 2026, Table IV). */
    public static final BigDecimal PERQUISITE_THRESHOLD = new BigDecimal("200000");

    /** Deductions of a month may not take more than this share of the month's pay (Code on Wages s.18(4)). */
    public static final BigDecimal DEDUCTION_CAP = new BigDecimal("0.50");

    private static final Set<String> OPEN = Set.of("requested", "manager_approved", "approved", "active");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Db db;
    private final ExpenseService expenses;
    private final Clock clock;

    public LoanService(Db db, ExpenseService expenses) {
        this(db, expenses, Clock.systemUTC());
    }

    public LoanService(Db db, ExpenseService expenses, Clock clock) {
        this.db = db;
        this.expenses = expenses;
        this.clock = clock;
    }

    /** Anyone who may make claims may ask for an advance; the permission is held for themselves, not across the Organization. */
    private static void requireClaimant(SessionCtx ctx) {
        if (!ctx.can("expense.claim") || ctx.membershipId() == null) {
            throw ApiException.denied("expense.claim");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── the schedule ────────────────────────────────────────────────────

    /** Equal principal over the months, interest on the balance before each one; the instalments add up to the amount. */
    public static List<Row> schedule(BigDecimal amount, int months, BigDecimal annualRate, LocalDate firstMonth) {
        List<Row> rows = new ArrayList<>();
        BigDecimal share = amount.divide(BigDecimal.valueOf(months), 2, RoundingMode.DOWN);
        BigDecimal balance = amount;
        for (int i = 0; i < months; i++) {
            BigDecimal principal = i == months - 1 ? balance : share;
            BigDecimal interest = balance.multiply(annualRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
            rows.add(new Row(firstMonth.plusMonths(i), principal, interest));
            balance = balance.subtract(principal);
        }
        return rows;
    }

    private static LocalDate monthOf(LocalDate d) {
        return d.withDayOfMonth(1);
    }

    // ── types ───────────────────────────────────────────────────────────

    /** What can be asked for: active types for everyone with a claim to make, every type for those who manage loans. */
    public List<Map<String, Object>> types(SessionCtx ctx, boolean all) {
        boolean manage = ctx.reachesEveryone("loan.manage");
        if (all && !manage) {
            throw ApiException.denied("loan.manage");
        }
        if (!manage) {
            requireClaimant(ctx);
        }
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, "select id, name, kind, max_amount, max_months, annual_rate, min_service_months, max_open, active "
                + "from app.loan_type " + (all ? "" : "where active ") + "order by lower(name)"));
    }

    public Saved saveType(SessionCtx ctx, UUID id, TypeInput in) {
        ctx.require("loan.manage");
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 60) {
            throw ApiException.badRequest("Give the type a name of up to 60 characters");
        }
        if (!Set.of("expense_advance", "salary_advance", "loan").contains(in.kind())) {
            throw ApiException.badRequest("Choose expense advance, salary advance or loan");
        }
        boolean advance = !"loan".equals(in.kind());
        if (in.maxAmount() == null || in.maxAmount().signum() <= 0 || in.maxAmount().scale() > 2) {
            throw ApiException.badRequest("Give the largest amount, in rupees and paise");
        }
        int months = in.maxMonths() == null ? 0 : in.maxMonths();
        if (months < 1 || months > 60 || (advance && months > 3)) {
            throw ApiException.badRequest("An advance is settled or repaid in 1 to 3 months and a loan in 1 to 60");
        }
        BigDecimal rate = in.annualRate() == null ? BigDecimal.ZERO : in.annualRate();
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(30)) > 0 || rate.scale() > 2 || (advance && rate.signum() != 0)) {
            throw ApiException.badRequest("The yearly rate is between 0 and 30 percent, and an advance has none");
        }
        int service = in.minServiceMonths() == null ? 0 : in.minServiceMonths();
        int open = in.maxOpen() == null ? 1 : in.maxOpen();
        if (service < 0 || service > 120 || open < 1 || open > 10) {
            throw ApiException.badRequest("Service is 0 to 120 months and 1 to 10 loans may be open");
        }
        boolean active = in.active() == null || in.active();
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.exists(c, "select 1 from app.loan_type where lower(name) = lower(?) and (?::uuid is null or id <> ?::uuid)", name, id, id)) {
                throw new ApiException(409, "loan.type_exists", "A type called " + name + " already exists");
            }
            UUID saved = id;
            if (id == null) {
                saved = AuthService.uuid(Sql.one(c, "insert into app.loan_type (tenant_id, name, kind, max_amount, max_months, annual_rate, "
                        + "min_service_months, max_open, active) values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                        ctx.tenantId(), name, in.kind(), in.maxAmount(), months, rate, service, open, active).get("id"));
            } else {
                // Loans already asked for keep the rate they were asked for at; changing the type never changes them.
                if (Sql.update(c, "update app.loan_type set name = ?, kind = ?, max_amount = ?, max_months = ?, annual_rate = ?, min_service_months = ?, "
                        + "max_open = ?, active = ? where id = ?", name, in.kind(), in.maxAmount(), months, rate, service, open, active, id) == 0) {
                    throw ApiException.notFound("The loan type");
                }
            }
            UserService.audit(c, ctx, id == null ? "loan.type_created" : "loan.type_changed", "loan_type", saved, Map.of("name", name));
            return new Saved(saved);
        });
    }

    // ── asking ──────────────────────────────────────────────────────────

    /** The schedule a request would have, shown before it is made. The first month is the one after today's. */
    public List<Row> preview(SessionCtx ctx, UUID typeId, BigDecimal amount, int months) {
        requireClaimant(ctx);
        Map<String, Object> type = db.inTenant(ctx.tenantId(), c -> Sql.one(c, "select annual_rate, max_amount, max_months from app.loan_type where id = ? and active", typeId));
        if (type == null) {
            throw ApiException.notFound("The loan type");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || months < 1 || months > 60) {
            throw ApiException.badRequest("Give an amount and a number of months");
        }
        return schedule(amount, months, (BigDecimal) type.get("annualRate"), monthOf(today()).plusMonths(1));
    }

    public Created request(SessionCtx ctx, RequestInput in) {
        requireClaimant(ctx);
        if (!ctx.hasModule(Modules.PAYROLL)) {
            throw new ApiException(409, "loan.needs_payroll", "Advances and loans are repaid through payroll, which this Organization does not use");
        }
        if (ctx.membershipId() == null) {
            throw ApiException.denied("expense.claim");
        }
        String purpose = in.purpose() == null ? "" : in.purpose().trim();
        if (purpose.length() < 3 || purpose.length() > 400) {
            throw ApiException.badRequest("Say what the money is for, in 3 to 400 characters");
        }
        UUID typeId = ExpenseService.Requests0.id(in.typeId());
        int months = in.months() == null ? 0 : in.months();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> type = Sql.one(c, "select id, name, max_amount, max_months, annual_rate, min_service_months, max_open from app.loan_type where id = ? and active", typeId);
            if (type == null) {
                throw ApiException.notFound("The loan type");
            }
            BigDecimal amount = in.amount();
            if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
                throw ApiException.badRequest("Give the amount, in rupees and paise");
            }
            if (amount.compareTo((BigDecimal) type.get("maxAmount")) > 0) {
                throw ApiException.badRequest("The most you can ask for as " + type.get("name") + " is ₹" + ((BigDecimal) type.get("maxAmount")).toPlainString());
            }
            if (months < 1 || months > ((Number) type.get("maxMonths")).intValue()) {
                throw ApiException.badRequest("Choose 1 to " + type.get("maxMonths") + " months");
            }
            Map<String, Object> job = Sql.one(c, "select joining_date from app.employment where membership_id = ? and status = 'active' order by joining_date desc limit 1", ctx.membershipId());
            if (job == null) {
                throw new ApiException(409, "loan.not_employed", "Advances and loans are for people currently employed here");
            }
            int needed = ((Number) type.get("minServiceMonths")).intValue();
            if (needed > 0 && LocalDate.parse((String) job.get("joiningDate")).plusMonths(needed).isAfter(today())) {
                throw new ApiException(409, "loan.too_soon", "You can ask for this after " + needed + " months of service");
            }
            if (Sql.count(c, "select 1 from app.loan where membership_id = ? and type_id = ? and status = any(?)", ctx.membershipId(), typeId,
                    OPEN.toArray(String[]::new)) >= ((Number) type.get("maxOpen")).intValue()) {
                throw new ApiException(409, "loan.too_many", "You already have the most of this type that can be open at once");
            }
            UUID approver = Settings.get(c, Settings.EXPENSE_MANAGER_STEP).asBoolean() ? expenses.approverFor(c, ctx.membershipId()) : null;
            int year = today().getYear();
            Map<String, Object> n = Sql.one(c, "insert into app.loan_counter (tenant_id, year, last) values (?, ?, 1) "
                    + "on conflict (tenant_id, year) do update set last = app.loan_counter.last + 1 returning last", ctx.tenantId(), year);
            String number = "LN-" + year + "-" + String.format("%04d", ((Number) n.get("last")).intValue());
            String status = approver == null ? "manager_approved" : "requested";
            int above = Settings.get(c, Settings.LOAN_SECOND_APPROVER_ABOVE).asInt();
            int financeApprovals = above > 0 && amount.compareTo(BigDecimal.valueOf(above)) > 0 ? 2 : 1;
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.loan (tenant_id, number, membership_id, type_id, amount, months, annual_rate, purpose, status, "
                    + "approver_membership_id, finance_approvals) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), number, ctx.membershipId(), typeId,
                    amount, months, type.get("annualRate"), purpose, status, approver, financeApprovals).get("id"));
            UserService.audit(c, ctx, "loan.requested", "loan", id, Map.of("number", number, "amount", amount.toPlainString(), "months", months));
            Map<String, String> told = ExpenseNotices.loan(c, id);
            if (approver != null) {
                ExpenseNotices.tell(c, ctx.tenantId(), "loan_waiting", approver, told);
            } else {
                ExpenseNotices.tellHolders(c, ctx.tenantId(), "loan.manage", "loan_finance_waiting", told, ctx.membershipId());
            }
            return new Created(id, number, status, 1);
        });
    }

    public void withdraw(SessionCtx ctx, UUID id, int version) {
        requireClaimant(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = lock(c, id);
            if (!ctx.membershipId().equals(AuthService.uuid(l.get("membershipId")))) {
                throw ApiException.notFound("The loan");
            }
            requireVersion(l, version);
            if (!Set.of("requested", "manager_approved", "approved").contains((String) l.get("status"))) {
                throw new ApiException(409, "loan.state", "A loan that has been paid out cannot be withdrawn");
            }
            move(c, id, "withdrawn", null);
            UserService.audit(c, ctx, "loan.withdrawn", "loan", id, Map.of("number", l.get("number")));
            return null;
        });
    }

    // ── approving ───────────────────────────────────────────────────────

    /** The manager's queue: requests routed to them, and for HR and the Org Admin everyone's. */
    public List<Map<String, Object>> approvals(SessionCtx ctx) {
        String scope = ctx.scopeOf("expense.approve");
        if (scope == null || scope.equals("self")) {
            throw ApiException.denied("expense.approve");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            UUID[] team = Team.reach(c, ctx, "expense.approve", today());
            String where = "where l.status = 'requested' and l.membership_id <> ? "
                    + (team == null ? "" : "and (l.approver_membership_id = ? or (l.approver_membership_id is null and l.membership_id = any(?))) ");
            Object[] params = team == null ? new Object[] {ctx.membershipId()} : new Object[] {ctx.membershipId(), ctx.membershipId(), team};
            return Lists.withTotal(Sql.query(c, LIST + where + "order by l.created_at limit 200", params),
                    Sql.count(c, "select 1 from app.loan l " + where, params));
        });
    }

    /** Finance's list: what waits for approval or payout, and what is being repaid. */
    public List<Map<String, Object>> queue(SessionCtx ctx) {
        ctx.require("loan.manage");
        return db.inTenant(ctx.tenantId(), c -> Lists.withTotal(Sql.query(c, LIST + "where l.status in ('manager_approved','approved','active') "
                + "order by case l.status when 'manager_approved' then 0 when 'approved' then 1 else 2 end, l.created_at limit 300"),
                Sql.count(c, "select 1 from app.loan l where l.status in ('manager_approved','approved','active')")));
    }

    public List<Map<String, Object>> mine(SessionCtx ctx) {
        requireClaimant(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, LIST + "where l.membership_id = ? order by l.created_at desc limit 100", ctx.membershipId()));
    }

    private static final String LIST = "select l.id, l.number, l.status, l.amount, l.months, l.annual_rate, l.purpose, l.version, l.membership_id, "
            + Names.orEmail("l.membership_id") + " as person, t.name as type, "
            + "(select coalesce(sum(i.principal), 0) from app.loan_instalment i where i.loan_id = l.id and i.status = 'due') as outstanding, "
            + "l.finance_approvals, (l.finance_by is not null) as first_approved, l.perq_exempt_reason, t.kind "
            + "from app.loan l join app.loan_type t on t.id = l.type_id ";

    /** The manager approves or rejects. Rejecting needs a reason. */
    public void managerDecide(SessionCtx ctx, UUID id, String action, String note, int version) {
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = lock(c, id);
            UUID owner = AuthService.uuid(l.get("membershipId"));
            UUID approver = l.get("approverMembershipId") == null ? null : AuthService.uuid(l.get("approverMembershipId"));
            boolean may = approver != null && approver.equals(ctx.membershipId())
                    || ctx.reachesEveryone("expense.approve") || (approver == null && Team.reaches(c, ctx, "expense.approve", owner, today()));
            if (!may) {
                throw ApiException.notFound("The loan");
            }
            if (owner.equals(ctx.membershipId())) {
                throw new ApiException(403, "loan.own", "You cannot decide your own request");
            }
            requireStatus(l, "requested");
            requireVersion(l, version);
            decide(c, ctx, id, l, action, note, "manager_approved", "manager_by", "manager_at");
            return null;
        });
    }

    /** Finance approves or rejects after the manager. */
    public void financeDecide(SessionCtx ctx, UUID id, String action, String note, int version) {
        ctx.require("loan.manage");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = lock(c, id);
            if (ctx.membershipId().equals(AuthService.uuid(l.get("membershipId")))) {
                throw new ApiException(403, "loan.own", "You cannot decide your own request");
            }
            requireStatus(l, "manager_approved");
            requireVersion(l, version);
            boolean first = l.get("financeBy") == null;
            if ("approve".equals(action) && ((Number) l.get("financeApprovals")).intValue() == 2) {
                if (first) {   // the first of two: the loan stays with finance until someone else approves too
                    Sql.update(c, "update app.loan set finance_by = ?, finance_at = now(), version = version + 1, updated_at = now() where id = ?", ctx.membershipId(), id);
                    UserService.audit(c, ctx, "loan.approve_first", "loan", id, Map.of("number", l.get("number")));
                    // The others in finance hear that it now waits for one of them (the borrower, if in finance, cannot approve it).
                    ExpenseNotices.tellHolders(c, ctx.tenantId(), "loan.manage", "loan_finance_waiting", ExpenseNotices.loan(c, id), ctx.membershipId());
                    return null;
                }
                if (ctx.membershipId().equals(AuthService.uuid(l.get("financeBy")))) {
                    throw new ApiException(409, "loan.second_person", "A loan this large needs a second person in finance to approve it");
                }
                decide(c, ctx, id, l, action, note, "approved", "finance2_by", "finance2_at");
                return null;
            }
            boolean firstPerson = first || ctx.membershipId().equals(AuthService.uuid(l.get("financeBy")));   // the person who approved first may still reject
            decide(c, ctx, id, l, action, note, "approved", firstPerson ? "finance_by" : "finance2_by", firstPerson ? "finance_at" : "finance2_at");
            return null;
        });
    }

    private void decide(Connection c, SessionCtx ctx, UUID id, Map<String, Object> l, String action, String note, String approvedStatus,
                        String byColumn, String atColumn) throws SQLException {
        String why = note == null || note.isBlank() ? null : note.trim();
        if ("approve".equals(action)) {
            Sql.update(c, "update app.loan set status = ?, " + byColumn + " = ?, " + atColumn + " = now(), version = version + 1, updated_at = now() where id = ?",
                    approvedStatus, ctx.membershipId(), id);
        } else if ("reject".equals(action)) {
            if (why == null || why.length() < 3) {
                throw ApiException.badRequest("Say why the request is rejected");
            }
            Sql.update(c, "update app.loan set status = 'rejected', decision_note = ?, " + byColumn + " = ?, " + atColumn + " = now(), version = version + 1, "
                    + "updated_at = now() where id = ?", why, ctx.membershipId(), id);
        } else {
            throw ApiException.badRequest("Choose approve or reject");
        }
        UserService.audit(c, ctx, "loan." + action, "loan", id, Map.of("number", l.get("number")));
        Map<String, String> told = ExpenseNotices.loan(c, id);
        if ("approve".equals(action) && "manager_approved".equals(approvedStatus)) {
            ExpenseNotices.tellHolders(c, ctx.tenantId(), "loan.manage", "loan_finance_waiting", told, ctx.membershipId());
        } else {
            told.put("loan.outcome", "approve".equals(action) ? "approved" : "rejected");
            ExpenseNotices.tell(c, ctx.tenantId(), "loan_decided", ExpenseNotices.owner(told), told);
        }
    }

    // ── paying out ──────────────────────────────────────────────────────

    /** Records the payout and fixes the schedule. The first month is never before the month after the payout. */
    public void disburse(SessionCtx ctx, UUID id, String paidOn, String firstMonth, String reference, int version) {
        ctx.require("loan.manage");
        StepUp.require(db, ctx);
        String ref = reference == null ? "" : reference.trim();
        if (ref.length() < 3 || ref.length() > 60) {
            throw ApiException.badRequest("Type the bank reference of the payout, 3 to 60 characters");
        }
        LocalDate on = paidOn == null || paidOn.isBlank() ? today() : AttendanceDays.date(paidOn);
        if (on.isAfter(today())) {
            throw ApiException.badRequest("The payout date cannot be in the future");
        }
        LocalDate earliest = monthOf(on).plusMonths(1);
        LocalDate first = firstMonth == null || firstMonth.isBlank() ? earliest : monthOf(AttendanceDays.date(firstMonth.length() == 7 ? firstMonth + "-01" : firstMonth));
        if (first.isBefore(earliest)) {
            throw ApiException.badRequest("Repayment starts in the month after the payout, " + earliest.getMonth() + " " + earliest.getYear() + ", or later");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = lock(c, id);
            if (ctx.membershipId().equals(AuthService.uuid(l.get("membershipId")))) {
                throw new ApiException(403, "loan.own", "You cannot pay out your own loan");
            }
            requireStatus(l, "approved");
            requireVersion(l, version);
            LoanComponent.ensure(c, ctx.tenantId());
            // An expense advance is settled by claims; whatever is left is taken in one deduction, in the last of the months asked for.
            int months = ((Number) l.get("months")).intValue();
            boolean expenseAdvance = "expense_advance".equals(Sql.one(c, "select t.kind from app.loan l join app.loan_type t on t.id = l.type_id where l.id = ?", id).get("kind"));
            List<Row> rows = expenseAdvance ? schedule((BigDecimal) l.get("amount"), 1, BigDecimal.ZERO, first.plusMonths(months - 1))
                    : schedule((BigDecimal) l.get("amount"), months, (BigDecimal) l.get("annualRate"), first);
            for (Row r : rows) {
                Sql.update(c, "insert into app.loan_instalment (tenant_id, loan_id, month, principal, interest) values (?, ?, ?, ?, ?)",
                        ctx.tenantId(), id, r.month(), r.principal(), r.interest());
            }
            Sql.update(c, "update app.loan set status = 'active', disbursed_on = ?, disbursed_by = ?, disbursal_ref = ?, first_month = ?, "
                    + "version = version + 1, updated_at = now() where id = ?", on, ctx.membershipId(), ref, first, id);
            UserService.audit(c, ctx, "loan.disbursed", "loan", id, Map.of("number", l.get("number"), "firstMonth", first.toString(), "reference", ref));
            Map<String, String> told = ExpenseNotices.loan(c, id);
            ExpenseNotices.tell(c, ctx.tenantId(), "loan_paid_out", ExpenseNotices.owner(told), told);
            return null;
        });
    }

    // ── while it is repaid ──────────────────────────────────────────────

    /** The loan, its schedule and what was recorded against it, for its owner, its approvers and finance. */
    public LoanView loan(SessionCtx ctx, UUID id) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = Sql.one(c, LIST.replace("l.purpose,", "l.purpose, l.decision_note, l.disbursed_on, l.disbursal_ref, l.approver_membership_id,") + "where l.id = ?", id);
            if (l == null) {
                throw ApiException.notFound("The loan");
            }
            UUID owner = AuthService.uuid(l.get("membershipId"));
            UUID approver = l.get("approverMembershipId") == null ? null : AuthService.uuid(l.get("approverMembershipId"));
            boolean sees = owner.equals(ctx.membershipId()) || ctx.reachesEveryone("loan.manage") || ctx.reachesEveryone("expense.approve")
                    || ctx.membershipId().equals(approver) || Team.reaches(c, ctx, "expense.approve", owner, today());
            if (!sees) {
                throw ApiException.notFound("The loan");
            }
            List<Map<String, Object>> schedule = Sql.query(c, "select id, month, principal, interest, status, taken_amount from app.loan_instalment "
                    + "where loan_id = ? order by month, created_at", id);
            List<Map<String, Object>> settlements = Sql.query(c, "select kind, amount, note, created_at, " + Names.orEmail("by_id") + " as by_name "
                    + "from app.loan_settlement where loan_id = ? order by created_at", id);
            return new LoanView(id, owner, (String) l.get("number"), (String) l.get("person"), (String) l.get("type"), (String) l.get("status"),
                    (BigDecimal) l.get("amount"), ((Number) l.get("months")).intValue(), (BigDecimal) l.get("annualRate"), (String) l.get("purpose"),
                    (String) l.get("decisionNote"), l.get("disbursedOn"), (String) l.get("disbursalRef"), (BigDecimal) l.get("outstanding"),
                    ((Number) l.get("version")).intValue(), schedule, settlements,
                    ((Number) l.get("financeApprovals")).intValue(), Boolean.TRUE.equals(l.get("firstApproved")),
                    Sql.exists(c, "select 1 from app.loan where id = ? and finance_by = ?", id, ctx.membershipId()), (String) l.get("perqExemptReason"));
        });
    }

    /** Moves one due instalment out by a month (a month of loss of pay, say): the schedule gains a month at its end. */
    public void skip(SessionCtx ctx, UUID instalmentId, String note) {
        ctx.require("loan.manage");
        String why = note == null ? "" : note.trim();
        if (why.length() < 3) {
            throw ApiException.badRequest("Say why the month is skipped");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> i = Sql.one(c, "select i.id, i.loan_id, i.month, i.principal, i.interest, i.status, l.status as loan_status, l.number "
                    + "from app.loan_instalment i join app.loan l on l.id = i.loan_id where i.id = ? for update of i", instalmentId);
            if (i == null) {
                throw ApiException.notFound("The instalment");
            }
            if (!"due".equals(i.get("status")) || !"active".equals(i.get("loanStatus"))) {
                throw new ApiException(409, "loan.state", "Only an instalment that is still due can be skipped");
            }
            UUID loan = AuthService.uuid(i.get("loanId"));
            requireNotInRun(c, loan);
            LocalDate last = LocalDate.parse((String) Sql.one(c, "select max(month) as m from app.loan_instalment where loan_id = ? and status in ('due','taken')", loan).get("m"));
            Sql.update(c, "update app.loan_instalment set status = 'skipped' where id = ?", instalmentId);
            Sql.update(c, "insert into app.loan_instalment (tenant_id, loan_id, month, principal, interest) values (?, ?, ?, ?, ?)",
                    ctx.tenantId(), loan, last.plusMonths(1), i.get("principal"), i.get("interest"));
            UserService.audit(c, ctx, "loan.instalment_skipped", "loan", loan, Map.of("number", i.get("number"), "month", i.get("month"), "reason", why));
            return null;
        });
    }

    /** A repayment made outside payroll. The instalments still to come are worked out again over the same months. */
    public void prepay(SessionCtx ctx, UUID id, BigDecimal amount, String note) {
        ctx.require("loan.manage");
        StepUp.require(db, ctx);
        String why = note == null ? "" : note.trim();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw ApiException.badRequest("Give the amount received, in rupees and paise");
        }
        if (why.length() < 3) {
            throw ApiException.badRequest("Note how it was received, such as the bank reference");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = lock(c, id);
            requireStatus(l, "active");
            requireNotInRun(c, id);
            BigDecimal outstanding = outstanding(c, id);
            if (amount.compareTo(outstanding) > 0) {
                throw ApiException.badRequest("That is more than the " + outstanding.toPlainString() + " still owed");
            }
            repay(c, ctx.tenantId(), id, (BigDecimal) l.get("annualRate"), outstanding, amount, "prepayment", why, ctx.membershipId());
            UserService.audit(c, ctx, "loan.prepaid", "loan", id, Map.of("number", l.get("number"), "amount", amount.toPlainString()));
            return null;
        });
    }

    /** Gives up what is still owed. Not by the person who paid the loan out. */
    public void writeOff(SessionCtx ctx, UUID id, String note) {
        ctx.require("loan.manage");
        StepUp.require(db, ctx);
        String why = note == null ? "" : note.trim();
        if (why.length() < 10) {
            throw ApiException.badRequest("Write the reason for giving up the balance, at least 10 characters");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = lock(c, id);
            requireStatus(l, "active");
            requireNotInRun(c, id);
            if (ctx.membershipId().equals(l.get("disbursedBy") == null ? null : AuthService.uuid(l.get("disbursedBy")))) {
                throw new ApiException(403, "loan.same_person", "Someone other than the person who paid the loan out must write it off");
            }
            BigDecimal outstanding = outstanding(c, id);
            Sql.update(c, "update app.loan_instalment set status = 'cancelled' where loan_id = ? and status = 'due'", id);
            if (outstanding.signum() > 0) {
                Sql.update(c, "insert into app.loan_settlement (tenant_id, loan_id, kind, amount, note, by_id) values (?, ?, 'write_off', ?, ?, ?)",
                        ctx.tenantId(), id, outstanding, why, ctx.membershipId());
            }
            move(c, id, "written_off", null);
            UserService.audit(c, ctx, "loan.written_off", "loan", id, Map.of("number", l.get("number"), "amount", outstanding.toPlainString(), "reason", why));
            return null;
        });
    }

    // ── payroll ─────────────────────────────────────────────────────────

    /**
     * Takes up, for a run being frozen, the instalments of its people that fall due by the month. While an instalment is taken up, no
     * other run takes it, and the loan cannot be skipped, repaid early or written off: the payslip would deduct what the schedule no longer has.
     */
    static void reserve(Connection c, UUID runId, UUID[] employments, LocalDate period) throws SQLException {
        Sql.update(c, "update app.loan_instalment i set reserved_run_id = ? from app.loan l where l.id = i.loan_id and l.status = 'active' "
                + "and i.status = 'due' and i.reserved_run_id is null and i.month <= ? and l.membership_id in "
                + "(select e.membership_id from app.employment e where e.id = any(?) and e.membership_id is not null)", runId, period, employments);
    }

    /** Gives a run's instalments back: when it is reopened, and after it is approved (what was not deducted stays due). */
    static void release(Connection c, UUID runId) throws SQLException {
        Sql.update(c, "update app.loan_instalment set reserved_run_id = null where reserved_run_id = ?", runId);
    }

    /** What the run has taken up, by membership id: {@code amount} to deduct and the {@code ids} it is for, oldest first. One query for the run. */
    static Map<String, Map<String, Object>> reserved(Connection c, UUID runId) throws SQLException {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : Sql.query(c, "select l.membership_id, i.id, i.principal + i.interest as amount from app.loan_instalment i "
                + "join app.loan l on l.id = i.loan_id where i.reserved_run_id = ? order by i.month, i.created_at", runId)) {
            Map<String, Object> m = out.computeIfAbsent(String.valueOf(r.get("membershipId")), k -> {
                Map<String, Object> n = new java.util.LinkedHashMap<>();
                n.put("amount", BigDecimal.ZERO);
                n.put("ids", new ArrayList<String>());
                return n;
            });
            m.put("amount", ((BigDecimal) m.get("amount")).add((BigDecimal) r.get("amount")));
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) m.get("ids");
            ids.add(String.valueOf(r.get("id")));
        }
        return out;
    }

    /** A frozen person as payroll works them out when their pay is held: no expense claims, and no loan instalment among the inputs. */
    static Map<String, Object> withoutExpenses(Map<String, Object> emp) {
        if (emp.get("reimbursements") == null && emp.get("loanDue") == null) {
            return emp;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(emp);
        out.remove("reimbursements");
        out.remove("loanInstalments");
        if (emp.get("loanDue") != null) {
            Map<String, Object> inputs = new java.util.LinkedHashMap<>(PayrollEngine.map(emp, "inputs"));
            BigDecimal left = PayrollEngine.dec(inputs, COMPONENT).subtract(PayrollEngine.dec(emp, "loanDue"));
            if (left.signum() > 0) {
                inputs.put(COMPONENT, left);
            } else {
                inputs.remove(COMPONENT);
            }
            out.put("inputs", inputs);
        }
        return out;
    }

    /** Takes an amount off what is owed: the instalments still due are worked out again over the same months, and the loan closes at nothing. */
    private static void repay(Connection c, UUID tenantId, UUID id, BigDecimal annualRate, BigDecimal outstanding, BigDecimal amount, String kind, String why, UUID by)
            throws SQLException {
        List<UUID> due = dueIds(c, id);
        BigDecimal balance = outstanding.subtract(amount);
        if (balance.signum() == 0) {
            Sql.update(c, "update app.loan_instalment set status = 'cancelled' where loan_id = ? and status = 'due'", id);
        } else {
            List<LocalDate> months = Sql.query(c, "select month from app.loan_instalment where loan_id = ? and status = 'due' order by month", id)
                    .stream().map(m -> LocalDate.parse((String) m.get("month"))).toList();
            List<Row> rows = schedule(balance, months.size(), annualRate, months.get(0));
            for (int k = 0; k < due.size(); k++) {
                Sql.update(c, "update app.loan_instalment set month = ?, principal = ?, interest = ? where id = ?",
                        months.get(k), rows.get(k).principal(), rows.get(k).interest(), due.get(k));
            }
        }
        Sql.update(c, "insert into app.loan_settlement (tenant_id, loan_id, kind, amount, note, by_id) values (?, ?, ?, ?, ?, ?)", tenantId, id, kind, amount, why, by);
        closeIfDone(c, id);
    }

    /**
     * Sets a verified claim against the expense advance its owner named: the advance drops by as much of the claim as it can cover,
     * and that much is returned, for the claim not to pay again. Nothing when the advance is no longer being repaid. Refused while
     * a payroll run that is not yet approved has the advance's deduction.
     */
    static BigDecimal settleByClaim(Connection c, UUID tenantId, UUID loanId, BigDecimal payable, String claimNumber, UUID by) throws SQLException {
        Map<String, Object> l = lock(c, loanId);
        if (!"active".equals(l.get("status"))) {
            return BigDecimal.ZERO;
        }
        requireNotInRun(c, loanId);
        BigDecimal outstanding = outstanding(c, loanId);
        BigDecimal apply = payable.min(outstanding);
        if (apply.signum() > 0) {
            repay(c, tenantId, loanId, (BigDecimal) l.get("annualRate"), outstanding, apply, "claim", "Expense claim " + claimNumber, by);
        }
        return apply;
    }

    /** Refuses a change to a loan while a payroll run that is not yet approved has one of its instalments. */
    private static void requireNotInRun(Connection c, UUID loan) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select to_char(r.period, 'FMMonth YYYY') as month from app.loan_instalment i join app.payroll_run r on r.id = i.reserved_run_id "
                + "where i.loan_id = ? limit 1", loan);
        if (r != null) {
            throw new ApiException(409, "loan.in_run", "The payroll run for " + r.get("month") + " has an instalment of this loan and is not approved yet. "
                    + "Reopen that run first, or do this after it is approved");
        }
    }

    /**
     * Called when a run is approved: what each person's payslip deducted settles their instalments, oldest first. Pay that is
     * held is not touched, so its instalments are taken when the pay is released. An instalment only partly covered is split:
     * the part taken is settled and the rest becomes a new instalment at the end of the schedule.
     */
    static void settleRun(Connection c, UUID tenantId, UUID runId) throws SQLException {
        for (Map<String, Object> ps : Sql.query(c, "select ps.id, ps.info::text as info, coalesce((select sum(l.amount) from app.payslip_line l where l.payslip_id = ps.id "
                + "and l.code = ?), 0) as taken from app.payslip ps where ps.run_id = ? and not ps.held and jsonb_exists(ps.info, 'loanInstalments')", COMPONENT, runId)) {
            Object ids = Json.parse((String) ps.get("info"));
            List<?> list = (List<?>) ((Map<?, ?>) ids).get("loanInstalments");
            BigDecimal left = (BigDecimal) ps.get("taken");
            for (Object x : list) {
                UUID instalment = UUID.fromString(String.valueOf(x));
                Map<String, Object> i = Sql.one(c, "select id, loan_id, month, principal, interest, status from app.loan_instalment where id = ? for update", instalment);
                if (i == null || !"due".equals(i.get("status")) || left.signum() <= 0) {
                    continue;   // skipped or settled since the run froze, or nothing left to cover it
                }
                BigDecimal principal = (BigDecimal) i.get("principal");
                BigDecimal interest = (BigDecimal) i.get("interest");
                BigDecimal need = principal.add(interest);
                UUID loan = AuthService.uuid(i.get("loanId"));
                if (left.compareTo(need) >= 0) {
                    Sql.update(c, "update app.loan_instalment set status = 'taken', taken_run_id = ?, taken_amount = ? where id = ?", runId, need, instalment);
                    left = left.subtract(need);
                } else {
                    BigDecimal takenPrincipal = left.multiply(principal).divide(need, 2, RoundingMode.DOWN);
                    BigDecimal takenInterest = left.subtract(takenPrincipal);
                    LocalDate last = LocalDate.parse((String) Sql.one(c, "select max(month) as m from app.loan_instalment where loan_id = ? and status in ('due','taken')", loan).get("m"));
                    Sql.update(c, "update app.loan_instalment set status = 'taken', taken_run_id = ?, taken_amount = ?, principal = ?, interest = ? where id = ?",
                            runId, left, takenPrincipal, takenInterest, instalment);
                    Sql.update(c, "insert into app.loan_instalment (tenant_id, loan_id, month, principal, interest) values (?, ?, ?, ?, ?)",
                            tenantId, loan, last.plusMonths(1), principal.subtract(takenPrincipal), interest.subtract(takenInterest));
                    left = BigDecimal.ZERO;
                }
                closeIfDone(c, loan);
            }
        }
        release(c, runId);   // held pay and anything not covered stays due, free for the next run
    }

    /**
     * After a run is approved: tells finance of each loan with instalments still due from two or more months ago (pay held or too
     * small to cover them, or finance skipped nothing yet the money did not come). One notification a loan for each person in
     * finance, in the run's own month, so a loan carried for longer is mentioned again each month. Returns how many loans.
     */
    public static int alertCarried(Connection c, UUID tenantId, UUID runId, LocalDate period) throws SQLException {
        List<Map<String, Object>> loans = Sql.query(c, "select l.id, l.number, " + Names.orEmail("l.membership_id") + " as person, count(*) as n, "
                + "sum(i.principal + i.interest) as amount, min(i.month) as since from app.loan l join app.loan_instalment i on i.loan_id = l.id "
                + "where l.status = 'active' and i.status = 'due' and i.month <= ?::date - interval '1 month' "
                // Only the people paid in this run: another pay group's instalments are judged when its own run is approved.
                + "and (?::uuid is null or l.membership_id in (select ps.membership_id from app.payslip ps where ps.run_id = ?::uuid)) "
                + "group by l.id, l.number, l.membership_id order by l.number", period, runId, runId);
        for (Map<String, Object> l : loans) {
            int n = ((Number) l.get("n")).intValue();
            Map<String, String> told = ExpenseNotices.loan(c, AuthService.uuid(l.get("id")));
            told.put("loan.count", n + (n == 1 ? " instalment" : " instalments"));
            told.put("loan.amount", ExpenseNotices.rupees(l.get("amount")));
            told.put("loan.since", monthName(LocalDate.parse((String) l.get("since"))));
            ExpenseNotices.tellHolders(c, tenantId, "loan.manage", "loan_instalment_carried", told, null);
        }
        return loans.size();
    }

    private static String monthName(LocalDate d) {
        return d.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + d.getYear();
    }

    // ── the interest perquisite ─────────────────────────────────────────

    /**
     * The taxable benefit of a month, for the people of a run, by membership id: for each loan charged less than the State Bank rate,
     * what is still owed (every instalment not yet taken, overdue ones included, and those taken this month or later) times the
     * difference, a twelfth of a year. It counts only when the person's loans together are above ₹2,00,000 (all of it, not the excess),
     * and not for a loan finance has marked exempt. The rate is the setting in force on the first day of the tax year. With no rate set
     * nothing is worked out, and {@code rateMissing} says a warning is due. One read of the setting and one query, whatever the size of the run.
     */
    static Map<String, Map<String, Object>> perquisites(Connection c, UUID[] employments, LocalDate period) throws SQLException {
        LocalDate yearStart = LocalDate.of(period.getMonthValue() >= 4 ? period.getYear() : period.getYear() - 1, 4, 1);
        int bp = Settings.get(c, Settings.LOAN_MARKET_RATE, Settings.Context.ORGANIZATION, yearStart).asInt();
        BigDecimal market = BigDecimal.valueOf(bp).movePointLeft(2);
        Map<String, BigDecimal> totals = new java.util.LinkedHashMap<>();
        Map<String, BigDecimal> benefits = new java.util.LinkedHashMap<>();
        for (Map<String, Object> l : Sql.query(c, "select l.membership_id, l.annual_rate, (select coalesce(sum(i.principal), 0) from app.loan_instalment i where i.loan_id = l.id "
                + "and (i.status = 'due' or (i.status = 'taken' and i.month >= ?))) as balance from app.loan l where l.status in ('active','closed') "
                // An expense advance is the employer's money for the employer's business, not a loan to the employee.
                + "and l.disbursed_on < ? and l.perq_exempt_reason is null and (select t.kind from app.loan_type t where t.id = l.type_id) <> 'expense_advance' and l.membership_id in "
                + "(select e.membership_id from app.employment e where e.id = any(?) and e.membership_id is not null)", period, period.plusMonths(1), employments)) {
            BigDecimal balance = (BigDecimal) l.get("balance");
            BigDecimal rate = (BigDecimal) l.get("annualRate");
            if (balance.signum() <= 0 || (bp > 0 && rate.compareTo(market) >= 0)) {
                continue;
            }
            String who = String.valueOf(l.get("membershipId"));
            totals.merge(who, balance, BigDecimal::add);
            benefits.merge(who, balance.multiply(market.subtract(rate).max(BigDecimal.ZERO)).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP), BigDecimal::add);
        }
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> t : totals.entrySet()) {
            boolean over = t.getValue().compareTo(PERQUISITE_THRESHOLD) > 0;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("amount", over && bp > 0 ? benefits.get(t.getKey()) : BigDecimal.ZERO);
            m.put("rateMissing", over && bp == 0);
            out.put(t.getKey(), m);
        }
        return out;
    }

    /** Marks a loan as one whose interest benefit is not taxed (a loan for the treatment of a specified illness). */
    public void exempt(SessionCtx ctx, UUID id, String reason) {
        ctx.require("loan.manage");
        String why = reason == null ? "" : reason.trim();
        if (!why.isEmpty() && why.length() < 10) {
            throw ApiException.badRequest("Say what makes the loan exempt, and where the evidence is kept, in at least 10 characters");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> l = lock(c, id);
            if (ctx.membershipId().equals(AuthService.uuid(l.get("membershipId")))) {
                throw new ApiException(403, "loan.own", "You cannot decide your own loan");
            }
            Sql.update(c, "update app.loan set perq_exempt_reason = ?, updated_at = now() where id = ?", why.isEmpty() ? null : why, id);
            UserService.audit(c, ctx, why.isEmpty() ? "loan.perquisite_taxed" : "loan.perquisite_exempt", "loan", id, Map.of("number", l.get("number"), "reason", why));
            return null;
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void closeIfDone(Connection c, UUID loan) throws SQLException {
        if (!Sql.exists(c, "select 1 from app.loan_instalment where loan_id = ? and status = 'due'", loan)) {
            Sql.update(c, "update app.loan set status = 'closed', updated_at = now() where id = ? and status = 'active'", loan);
        }
    }

    private static BigDecimal outstanding(Connection c, UUID loan) throws SQLException {
        return (BigDecimal) Sql.one(c, "select coalesce(sum(principal), 0) as s from app.loan_instalment where loan_id = ? and status = 'due'", loan).get("s");
    }

    private static List<UUID> dueIds(Connection c, UUID loan) throws SQLException {
        return Sql.query(c, "select id from app.loan_instalment where loan_id = ? and status = 'due' order by month, created_at", loan).stream()
                .map(r -> AuthService.uuid(r.get("id"))).toList();
    }

    private static Map<String, Object> lock(Connection c, UUID id) throws SQLException {
        Map<String, Object> l = Sql.one(c, "select id, number, membership_id, status, version, amount, months, annual_rate, approver_membership_id, disbursed_by, finance_by, finance_approvals "
                + "from app.loan where id = ? for update", id);
        if (l == null) {
            throw ApiException.notFound("The loan");
        }
        return l;
    }

    private static void move(Connection c, UUID id, String status, String note) throws SQLException {
        Sql.update(c, "update app.loan set status = ?, decision_note = coalesce(?, decision_note), version = version + 1, updated_at = now() where id = ?", status, note, id);
    }

    private static void requireStatus(Map<String, Object> l, String status) {
        if (!status.equals(l.get("status"))) {
            throw new ApiException(409, "loan.state", "This request is not waiting for that step");
        }
    }

    private static void requireVersion(Map<String, Object> l, int version) {
        if (((Number) l.get("version")).intValue() != version) {
            throw new ApiException(409, "loan.stale", "The request changed since you opened it. Reload and try again");
        }
    }
}
