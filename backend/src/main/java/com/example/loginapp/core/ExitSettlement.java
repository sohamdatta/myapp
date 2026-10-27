package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The full and final settlement of an exit: a statement of lines, each with its own legal due
 * date, so the wages can go out within two working days of the last day (Code on Wages, s.17(2))
 * while gratuity follows within thirty (Code on Social Security). Until the payroll module exists,
 * the app works out each line's quantity (days payable, leave to encash, notice not served, years
 * of service for gratuity, claims and recoveries from clearance) and its due date, and HR types the
 * amounts. Draft, then approved by a second person, then each line paid with its date and bank
 * reference; a paid line never changes. A notice shortfall the employee owes, and anything else the
 * law does not let the employer deduct from wages, is a demand made separately, never taken from
 * the final pay.
 */
public final class ExitSettlement {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> CODES = Set.of("salary", "leave_encashment", "notice_pay", "notice_recovery", "gratuity", "bonus",
            "reimbursement", "recovery", "tax", "other");
    private static final Set<String> DIRECTIONS = Set.of("earning", "deduction", "demand");
    private static final BigDecimal MAX = new BigDecimal("100000000");

    public record LineInput(String code, String label, String direction, BigDecimal quantity, String unit, BigDecimal amount, String dueDate,
                            String note) {}

    public record PaidInput(String paidOn, String reference) {}

    private final Db db;
    private final Crypto crypto;
    private final DocumentService documents;
    private final Clock clock;

    public ExitSettlement(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public ExitSettlement(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.documents = new DocumentService(db, crypto);
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── reading ─────────────────────────────────────────────────────────

    public Map<String, Object> get(SessionCtx ctx, UUID caseId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = load(c, caseId);
            boolean hr = ExitService.canManage(ctx);
            boolean own = ctx.membershipId() != null && ctx.membershipId().toString().equals(x.get("membershipId"));
            if (!hr && !ctx.can("payroll.approve") && !own) {
                throw ApiException.notFound("The settlement");
            }
            return view(c, ctx, x, own && !hr);
        });
    }

    /** The settlement as HR or the employee sees it. The employee sees the lines once they are approved. */
    static Map<String, Object> view(Connection c, SessionCtx ctx, Map<String, Object> x, boolean own) throws SQLException {
        String state = (String) x.get("settlementState");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state);
        boolean show = !own || Set.of("approved", "paid").contains(state);
        List<Map<String, Object>> lines = show ? Sql.query(c, "select l.id, l.code, l.label, l.direction, l.quantity, l.unit, l.amount, "
                + "l.due_date, l.note, l.paid_on, l.paid_reference, l.exit_task_id from app.exit_settlement_line l "
                + "where l.exit_case_id = ?::uuid order by l.position, l.created_at", x.get("id")) : List.of();
        out.put("lines", lines);
        BigDecimal earnings = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal demands = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            BigDecimal a = l.get("amount") == null ? BigDecimal.ZERO : (BigDecimal) l.get("amount");
            switch ((String) l.get("direction")) {
                case "earning" -> earnings = earnings.add(a);
                case "deduction" -> deductions = deductions.add(a);
                default -> demands = demands.add(a);
            }
        }
        out.put("earnings", earnings);
        out.put("deductions", deductions);
        out.put("net", earnings.subtract(deductions));
        out.put("demands", demands);
        out.put("gratuity", gratuity(x));
        out.put("preparedByEmail", email(c, x.get("settlementPreparedBy")));
        out.put("approvedByEmail", email(c, x.get("settlementApprovedBy")));
        out.put("approvedAt", x.get("settlementApprovedAt"));
        Map<String, Object> letter = Sql.one(c, "select document_id from app.issued_letter where exit_case_id = ?::uuid and kind = 'settlement' "
                + "order by issued_at desc limit 1", x.get("id"));
        out.put("statementDocumentId", letter == null ? null : letter.get("documentId"));
        boolean hr = ExitService.canManage(ctx) && !own;
        boolean mine = ctx.membershipId() != null && ctx.membershipId().toString().equals(x.get("membershipId"));
        boolean accepted = "accepted".equals(x.get("status")) || "closed".equals(x.get("status"));
        out.put("canPrepare", hr && !mine && accepted && Set.of("none", "draft").contains(state));
        out.put("canEdit", hr && !mine && "draft".equals(state));
        out.put("canApprove", !own && !mine && "draft".equals(state) && (ExitService.canManage(ctx) || ctx.can("payroll.approve"))
                && ctx.membershipId() != null && !ctx.membershipId().toString().equals(x.get("settlementPreparedBy")));
        out.put("canPay", hr && !mine && "approved".equals(state));
        return out;
    }

    /** Gratuity in words: eligible or not, and the years that count. */
    static Map<String, Object> gratuity(Map<String, Object> x) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (x.get("lastWorkingDay") == null) {
            out.put("eligible", false);
            out.put("reason", "The last working day is not fixed yet");
            return out;
        }
        LocalDate joining = LocalDate.parse((String) x.get("joiningDate"));
        LocalDate last = LocalDate.parse((String) x.get("lastWorkingDay"));
        Period service = Period.between(joining, last.plusDays(1));
        int years = service.getYears() + (service.getMonths() > 6 || (service.getMonths() == 6 && service.getDays() > 0) ? 1 : 0);
        boolean fixedTerm = "fixed_term".equals(x.get("employmentType"));
        boolean death = "death".equals(x.get("kind"));
        int needed = fixedTerm ? 1 : 5;
        boolean eligible = death || service.getYears() >= needed;
        out.put("eligible", eligible);
        out.put("service", ExitValues.tenure(joining, last));
        out.put("years", years);
        out.put("reason", eligible ? (death ? "Due on death, whatever the length of service" : fixedTerm
                ? "Fixed-term staff qualify after one year, pro rata" : "Five years or more of continuous service")
                : "Needs " + needed + (needed == 1 ? " year" : " years") + " of continuous service; served " + ExitValues.tenure(joining, last));
        return out;
    }

    // ── preparing ───────────────────────────────────────────────────────

    /**
     * Works out the lines from the exit, attendance, leave and clearance: on the first call it
     * writes them; later calls, while still a draft, refresh the quantities and due dates of the
     * worked-out lines and keep every amount HR typed.
     */
    public Map<String, Object> prepare(SessionCtx ctx, UUID caseId) {
        requireHr(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, caseId);
            refuseOwn(ctx, x);
            if (!"accepted".equals(x.get("status")) && !"closed".equals(x.get("status"))) {
                throw new ApiException(409, "exit.not_accepted", "The settlement can be prepared once the exit is accepted");
            }
            if (!Set.of("none", "draft").contains((String) x.get("settlementState"))) {
                throw new ApiException(409, "settlement.locked", "The settlement is approved; it can no longer change");
            }
            for (Computed line : compute(c, x, today)) {
                Map<String, Object> existing = line.taskId() != null
                        ? Sql.one(c, "select id from app.exit_settlement_line where exit_case_id = ? and exit_task_id = ?", caseId, line.taskId())
                        : Sql.one(c, "select id from app.exit_settlement_line where exit_case_id = ? and code = ? and exit_task_id is null",
                                caseId, line.code());
                if (existing == null) {
                    Sql.update(c, "insert into app.exit_settlement_line (tenant_id, exit_case_id, position, code, label, direction, quantity, "
                            + "unit, amount, due_date, exit_task_id, note) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), caseId,
                            (short) line.position(), line.code(), line.label(), line.direction(), line.quantity(), line.unit(), line.amount(),
                            line.dueDate(), line.taskId(), line.note());
                } else {
                    Sql.update(c, "update app.exit_settlement_line set quantity = ?, due_date = ?, note = coalesce(note, ?), "
                            + "amount = coalesce(amount, ?) where id = ?::uuid and paid_on is null", line.quantity(), line.dueDate(), line.note(),
                            line.amount(), existing.get("id"));
                }
            }
            if ("none".equals(x.get("settlementState"))) {
                Sql.update(c, "update app.exit_case set settlement_state = 'draft', settlement_prepared_by = ? where id = ?",
                        ctx.membershipId(), caseId);
            }
            UserService.audit(c, ctx, "exit.settlement_prepared", "exit", caseId, Map.of());
            return view(c, ctx, load(c, caseId), false);
        });
    }

    private record Computed(int position, String code, String label, String direction, BigDecimal quantity, String unit, BigDecimal amount,
                            LocalDate dueDate, UUID taskId, String note) {}

    private static List<Computed> compute(Connection c, Map<String, Object> x, LocalDate today) throws SQLException {
        List<Computed> out = new ArrayList<>();
        LocalDate last = LocalDate.parse((String) x.get("lastWorkingDay"));
        LocalDate joining = LocalDate.parse((String) x.get("joiningDate"));
        LocalDate wagesDue = workingDaysAfter(c, last, 2);
        UUID membership = AuthService.uuid(x.get("membershipId"));

        // Salary to the last working day: the days of its month worked, less unpaid leave.
        LocalDate from = last.withDayOfMonth(1).isBefore(joining) ? joining : last.withDayOfMonth(1);
        BigDecimal days = BigDecimal.valueOf(last.toEpochDay() - from.toEpochDay() + 1);
        BigDecimal unpaid = BigDecimal.ZERO;
        if (membership != null) {
            Map<String, Object> u = Sql.one(c, "select coalesce(sum(case when t.paid then r.unpaid_days else r.days end), 0) as unpaid "
                    + "from app.leave_request r join app.leave_type t on t.id = r.leave_type_id where r.membership_id = ? "
                    + "and r.status = 'approved' and r.from_date between ? and ?", membership, from, last);
            unpaid = (BigDecimal) u.get("unpaid");
        }
        out.add(new Computed(1, "salary", "Salary, " + TemplateValues.DATE.format(from) + " to " + TemplateValues.DATE.format(last),
                "earning", days.subtract(unpaid).max(BigDecimal.ZERO), "days", null, wagesDue, null,
                unpaid.signum() > 0 ? unpaid.stripTrailingZeros().toPlainString() + " days of unpaid leave left out" : null));

        // Leave encashed: the balance of each encashable type on the last day, less notice set against it.
        int shortfall = ((Number) x.get("shortfallDays")).intValue();
        String action = (String) x.get("shortfallAction");
        BigDecimal balance = BigDecimal.ZERO;
        @SuppressWarnings("unchecked")
        List<String> encashable = (List<String>) Sql.one(c, "select encashable_leave_type_ids from app.exit_settings").get("encashableLeaveTypeIds");
        if (membership != null && encashable != null && !encashable.isEmpty()) {
            LeaveService.accrue(c, AuthService.uuid(x.get("tenantId")), membership, last.isBefore(today) ? last : today);
            Map<String, Object> b = Sql.one(c, "select coalesce(sum(days), 0) as balance from app.leave_ledger where membership_id = ? "
                    + "and leave_type_id = any(?) and on_date <= ?", membership,
                    encashable.stream().map(UUID::fromString).toArray(UUID[]::new), last);
            balance = ((BigDecimal) b.get("balance")).max(BigDecimal.ZERO);
        }
        BigDecimal setAgainst = "leave".equals(action) ? balance.min(BigDecimal.valueOf(shortfall)) : BigDecimal.ZERO;
        if (encashable != null && !encashable.isEmpty()) {
            out.add(new Computed(2, "leave_encashment", "Leave encashed", "earning", balance.subtract(setAgainst), "days", null, wagesDue, null,
                    setAgainst.signum() > 0 ? setAgainst.stripTrailingZeros().toPlainString() + " days set against the notice not served" : null));
        }

        // Notice: paid in lieu by the employer, or not served and owed by the employee, separately.
        if ("paid_in_lieu".equals(action)) {
            out.add(new Computed(3, "notice_pay", "Notice pay in lieu", "earning", shortfall > 0 ? BigDecimal.valueOf(shortfall) : null,
                    shortfall > 0 ? "days" : null, null, wagesDue, null, null));
        }
        BigDecimal owed = "owed".equals(action) ? BigDecimal.valueOf(shortfall)
                : "leave".equals(action) ? BigDecimal.valueOf(shortfall).subtract(setAgainst) : BigDecimal.ZERO;
        if (owed.signum() > 0) {
            out.add(new Computed(4, "notice_recovery", "Notice not served, owed by the employee", "demand", owed, "days", null, null, null,
                    "Asked for separately; not deducted from the final wages"));
        }

        // Gratuity, when the service qualifies: due within 30 days.
        Map<String, Object> g = gratuity(x);
        if (Boolean.TRUE.equals(g.get("eligible"))) {
            out.add(new Computed(5, "gratuity", "Gratuity", "earning", BigDecimal.valueOf(((Number) g.get("years")).intValue()), "years", null,
                    last.plusDays(30), null, "15 days' wages for each year counted"));
        }

        // Claims and recoveries recorded during clearance.
        int position = 10;
        for (Map<String, Object> t : Sql.query(c, "select id, title, amount, amount_direction, amount_note from app.exit_task "
                + "where exit_case_id = ?::uuid and amount is not null order by position", x.get("id"))) {
            boolean toEmployee = "owed_to_employee".equals(t.get("amountDirection"));
            String label = t.get("amountNote") != null ? (String) t.get("amountNote") : (String) t.get("title");
            out.add(new Computed(position++, toEmployee ? "reimbursement" : "recovery", label.length() > 120 ? label.substring(0, 120) : label,
                    toEmployee ? "earning" : "demand", null, null, (BigDecimal) t.get("amount"), toEmployee ? wagesDue : null,
                    AuthService.uuid(t.get("id")), toEmployee ? null : "Asked for separately unless the law allows a deduction"));
        }

        // The final income tax of the year: worked out by payroll, typed by HR until then.
        out.add(new Computed(30, "tax", "Income tax (TDS) for the year", "deduction", null, null, null, wagesDue, null, null));
        return out;
    }

    /** The date a number of working days after a day: Sundays and holidays at every location skipped. */
    static LocalDate workingDaysAfter(Connection c, LocalDate day, int count) throws SQLException {
        Set<String> holidays = new HashSet<>();
        for (Map<String, Object> h : Sql.query(c, "select on_date from app.holiday where work_location_id is null and on_date between ? and ?",
                day, day.plusDays(30))) {
            holidays.add((String) h.get("onDate"));
        }
        LocalDate d = day;
        int left = count;
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d.toString())) {
                left--;
            }
        }
        return d;
    }

    // ── changing lines ──────────────────────────────────────────────────

    public Map<String, Object> addLine(SessionCtx ctx, UUID caseId, LineInput in) {
        requireHr(ctx);
        Checked v = check(in, true);
        return db.inTenant(ctx.tenantId(), c -> {
            editable(c, ctx, caseId);
            if (Set.of("salary", "leave_encashment", "notice_pay", "notice_recovery", "gratuity", "bonus", "tax").contains(v.code)
                    && Sql.one(c, "select 1 from app.exit_settlement_line where exit_case_id = ? and code = ?", caseId, v.code) != null) {
                throw new ApiException(409, "settlement.line_exists", "There is already a line of that kind; change it instead");
            }
            Sql.update(c, "insert into app.exit_settlement_line (tenant_id, exit_case_id, position, code, label, direction, quantity, unit, "
                    + "amount, due_date, note) values (?, ?, 50, ?, ?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), caseId, v.code, v.label, v.direction,
                    v.quantity, v.unit, v.amount, v.dueDate, v.note);
            UserService.audit(c, ctx, "exit.settlement_line_added", "exit", caseId, Map.of("label", v.label));
            return view(c, ctx, load(c, caseId), false);
        });
    }

    public Map<String, Object> changeLine(SessionCtx ctx, UUID caseId, UUID lineId, LineInput in) {
        requireHr(ctx);
        Checked v = check(in, false);
        return db.inTenant(ctx.tenantId(), c -> {
            editable(c, ctx, caseId);
            Map<String, Object> before = Sql.one(c, "select * from app.exit_settlement_line where id = ? and exit_case_id = ?", lineId, caseId);
            if (before == null) {
                throw ApiException.notFound("The line");
            }
            Sql.update(c, "update app.exit_settlement_line set label = coalesce(?, label), direction = coalesce(?, direction), "
                    + "quantity = ?, unit = ?, amount = ?, due_date = ?, note = ? where id = ?", v.label, v.direction, v.quantity,
                    v.unit != null ? v.unit : before.get("unit"), v.amount, v.dueDate, v.note, lineId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("label", before.get("label"));
            detail.put("amountBefore", before.get("amount") == null ? "" : before.get("amount").toString());
            detail.put("amountAfter", v.amount == null ? "" : v.amount.toPlainString());
            UserService.audit(c, ctx, "exit.settlement_line_changed", "exit", caseId, detail);
            return view(c, ctx, load(c, caseId), false);
        });
    }

    public Map<String, Object> removeLine(SessionCtx ctx, UUID caseId, UUID lineId) {
        requireHr(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            editable(c, ctx, caseId);
            Map<String, Object> l = Sql.one(c, "select label from app.exit_settlement_line where id = ? and exit_case_id = ?", lineId, caseId);
            if (l == null) {
                throw ApiException.notFound("The line");
            }
            Sql.update(c, "delete from app.exit_settlement_line where id = ?", lineId);
            UserService.audit(c, ctx, "exit.settlement_line_removed", "exit", caseId, Map.of("label", l.get("label")));
            return view(c, ctx, load(c, caseId), false);
        });
    }

    /** A second person approves the statement; it goes to the employee as a PDF. */
    public Map<String, Object> approve(SessionCtx ctx, UUID caseId) {
        if (!ExitService.canManage(ctx) && !(ctx.can("payroll.approve") && ctx.membershipId() != null)) {
            throw ApiException.denied("exit.manage");
        }
        if (ctx.can("payroll.approve")) {
            StepUp.require(db, ctx);
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, caseId);
            refuseOwn(ctx, x);
            if (!"draft".equals(x.get("settlementState"))) {
                throw new ApiException(409, "settlement.not_draft", "There is no draft settlement to approve");
            }
            if (ctx.membershipId().toString().equals(x.get("settlementPreparedBy"))) {
                throw new ApiException(409, "settlement.same_person", "Someone other than the person who prepared it must approve it");
            }
            List<String> missing = new ArrayList<>();
            for (Map<String, Object> l : Sql.query(c, "select label from app.exit_settlement_line where exit_case_id = ? and amount is null "
                    + "order by position", caseId)) {
                missing.add((String) l.get("label"));
            }
            if (!missing.isEmpty()) {
                throw new ApiException(409, "settlement.amount_missing", "Give an amount (0 if nothing is due) for: " + String.join("; ", missing));
            }
            Sql.update(c, "update app.exit_case set settlement_state = 'approved', settlement_approved_by = ?, settlement_approved_at = now() "
                    + "where id = ?", ctx.membershipId(), caseId);
            UserService.audit(c, ctx, "exit.settlement_approved", "exit", caseId, Map.of());
            Map<String, Object> approved = load(c, caseId);
            issueStatement(c, ctx, approved);
            settleIfPaid(c, caseId);
            return view(c, ctx, load(c, caseId), false);
        });
    }

    /** Records that a line was paid (or a demand received), with the date and the bank reference. */
    public Map<String, Object> paid(SessionCtx ctx, UUID caseId, UUID lineId, PaidInput in) {
        requireHr(ctx);
        LocalDate on;
        try {
            on = in == null || in.paidOn() == null || in.paidOn().isBlank() ? today() : LocalDate.parse(in.paidOn().trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("The date paid must be a date like 2026-10-31");
        }
        if (on.isAfter(today())) {
            throw ApiException.badRequest("The date paid cannot be in the future");
        }
        String reference = in == null || in.reference() == null ? "" : in.reference().trim();
        if (reference.isEmpty() || reference.length() > 80) {
            throw ApiException.badRequest("Give the bank reference (UTR) or another reference, up to 80 characters");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, caseId);
            refuseOwn(ctx, x);
            if (!"approved".equals(x.get("settlementState"))) {
                throw new ApiException(409, "settlement.not_approved", "Only an approved settlement can be paid");
            }
            Map<String, Object> l = Sql.one(c, "select label, paid_on from app.exit_settlement_line where id = ? and exit_case_id = ?", lineId, caseId);
            if (l == null) {
                throw ApiException.notFound("The line");
            }
            if (l.get("paidOn") != null) {
                throw new ApiException(409, "settlement.paid", "This line is already paid");
            }
            Sql.update(c, "update app.exit_settlement_line set paid_on = ?, paid_reference = ?, paid_by = ? where id = ?", on, reference,
                    ctx.membershipId(), lineId);
            UserService.audit(c, ctx, "exit.line_paid", "exit", caseId, Map.of("label", l.get("label"), "paidOn", on.toString(),
                    "reference", reference));
            settleIfPaid(c, caseId);
            return view(c, ctx, load(c, caseId), false);
        });
    }

    /** Once every line the employer pays (or deducts) is paid, the settlement is paid. Demands are followed up apart. */
    private static void settleIfPaid(Connection c, UUID caseId) throws SQLException {
        if (Sql.one(c, "select 1 from app.exit_settlement_line where exit_case_id = ? and direction <> 'demand' and paid_on is null "
                + "and amount > 0", caseId) == null) {
            Sql.update(c, "update app.exit_case set settlement_state = 'paid' where id = ? and settlement_state = 'approved'", caseId);
        }
    }

    // ── the statement ───────────────────────────────────────────────────

    /** The approved statement as a PDF in the employee's documents, and a message to them. */
    private void issueStatement(Connection c, SessionCtx ctx, Map<String, Object> x) throws SQLException {
        UUID caseId = AuthService.uuid(x.get("id"));
        Map<String, String> v = ExitValues.forCase(c, caseId);
        byte[] pdf = Pdf.letter("Full and final settlement", statementText(c, x, v));
        FileSniffer.Cleaned file = FileSniffer.clean(pdf, "Full and final settlement.pdf");
        UUID document = documents.insert(c, ctx, ctx.tenantId(), AuthService.uuid(x.get("personId")), null, null, file);
        Sql.update(c, "insert into app.issued_letter (tenant_id, person_id, document_id, issued_by, kind, title, response_needed, exit_case_id) "
                + "values (?, ?::uuid, ?, ?, 'settlement', 'Full and final settlement', 'none', ?)", ctx.tenantId(), x.get("personId"), document,
                ctx.membershipId(), caseId);
        UUID employee = AuthService.uuid(x.get("membershipId"));
        if (employee != null && !"death".equals(x.get("kind"))) {
            v.put("letter.title", "full and final settlement statement");
            Notifications.send(c, ctx.tenantId(), employee, "exit_letter_issued", v, "my-exit");
        }
    }

    static String statementText(Connection c, Map<String, Object> x, Map<String, String> v) throws SQLException {
        StringBuilder s = new StringBuilder();
        s.append(v.get("organization.name")).append("\n\nFull and final settlement\n\n").append(v.get("today")).append("\n\n");
        s.append("Name: ").append(v.get("person.legal_name")).append('\n');
        if (!v.get("employment.code").isEmpty()) {
            s.append("Employee code: ").append(v.get("employment.code")).append('\n');
        }
        s.append("Designation: ").append(v.get("job.designation")).append('\n');
        s.append("Joined: ").append(v.get("employment.joining_date")).append('\n');
        s.append("Last working day: ").append(v.get("exit.last_working_day")).append('\n');
        s.append("Service: ").append(v.get("employment.tenure")).append("\n\n");
        BigDecimal earnings = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        List<String> demands = new ArrayList<>();
        s.append("Paid to you\n");
        for (Map<String, Object> l : Sql.query(c, "select label, direction, quantity, unit, amount, due_date from app.exit_settlement_line "
                + "where exit_case_id = ?::uuid order by position, created_at", x.get("id"))) {
            BigDecimal a = (BigDecimal) l.get("amount");
            String line = "  " + l.get("label") + qty(l) + ": " + money(a) + (l.get("dueDate") == null ? "" : ", by " + ExitValues.date(l.get("dueDate")));
            switch ((String) l.get("direction")) {
                case "earning" -> {
                    s.append(line).append('\n');
                    earnings = earnings.add(a);
                }
                case "deduction" -> {
                    deductions = deductions.add(a);
                    demands.add("-" + line.substring(1));
                }
                default -> demands.add("D" + line.substring(1));
            }
        }
        s.append("  Total: ").append(money(earnings)).append("\n\n");
        List<String> taken = demands.stream().filter(d -> d.startsWith("-")).map(d -> " " + d.substring(1)).toList();
        if (!taken.isEmpty()) {
            s.append("Deducted\n");
            taken.forEach(d -> s.append(d).append('\n'));
            s.append("  Total: ").append(money(deductions)).append("\n\n");
        }
        s.append("Net paid to you: ").append(money(earnings.subtract(deductions))).append("\n\n");
        List<String> owed = demands.stream().filter(d -> d.startsWith("D")).map(d -> " " + d.substring(1)).toList();
        if (!owed.isEmpty()) {
            s.append("Owed by you, asked for separately\n");
            owed.forEach(d -> s.append(d).append('\n'));
            s.append('\n');
        }
        s.append("Wages are paid within two working days of the last working day, and gratuity within thirty days, as the law requires. ")
                .append("Please write to HR about anything that looks wrong.\n\nFor ").append(v.get("organization.name"));
        return s.toString();
    }

    private static String qty(Map<String, Object> l) {
        if (l.get("quantity") == null || l.get("unit") == null) {
            return "";
        }
        return " (" + ((BigDecimal) l.get("quantity")).stripTrailingZeros().toPlainString() + " " + l.get("unit") + ")";
    }

    static String money(BigDecimal a) {
        if (a == null) {
            return "-";
        }
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("en-IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return "Rs. " + f.format(a.setScale(2, RoundingMode.HALF_UP));
    }

    // ── reminders ───────────────────────────────────────────────────────

    /** Tells HR, once, the day before an unpaid line falls due. */
    static void remind(Connection c, UUID tenantId, LocalDate today) throws SQLException {
        for (Map<String, Object> l : Sql.query(c, "select l.id, l.label, l.due_date, l.exit_case_id from app.exit_settlement_line l "
                + "join app.exit_case x on x.id = l.exit_case_id where l.paid_on is null and l.direction <> 'demand' and l.reminded_at is null "
                + "and l.due_date <= ? and x.settlement_state in ('draft','approved')", today.plusDays(1))) {
            if (Sql.update(c, "update app.exit_settlement_line set reminded_at = now() where id = ?::uuid and reminded_at is null", l.get("id")) == 0) {
                continue;
            }
            Map<String, String> v = ExitValues.forCase(c, AuthService.uuid(l.get("exitCaseId")));
            v.put("settlement.line", (String) l.get("label"));
            v.put("settlement.due_date", ExitValues.date(l.get("dueDate")));
            for (UUID hr : ExitService.hrMembers(c)) {
                Notifications.send(c, tenantId, hr, "settlement_due", v, "exits");
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private record Checked(String code, String label, String direction, BigDecimal quantity, String unit, BigDecimal amount, LocalDate dueDate,
                           String note) {}

    private static Checked check(LineInput in, boolean adding) {
        String code = in.code() == null || in.code().isBlank() ? (adding ? "other" : null) : in.code().trim();
        if (code != null && !CODES.contains(code)) {
            throw ApiException.badRequest("Not a kind of settlement line");
        }
        String label = in.label() == null || in.label().isBlank() ? null : in.label().trim();
        if (adding && label == null) {
            throw ApiException.badRequest("Say what the line is for");
        }
        if (label != null && label.length() > 120) {
            throw ApiException.badRequest("Keep the label to 120 characters");
        }
        String direction = in.direction() == null || in.direction().isBlank() ? (adding ? "earning" : null) : in.direction().trim();
        if (direction != null && !DIRECTIONS.contains(direction)) {
            throw ApiException.badRequest("A line is paid to the employee, deducted, or asked of them");
        }
        if (in.amount() != null && (in.amount().signum() < 0 || in.amount().compareTo(MAX) > 0)) {
            throw ApiException.badRequest("Give an amount from 0");
        }
        if (in.quantity() != null && (in.quantity().signum() < 0 || in.quantity().compareTo(new BigDecimal("10000")) > 0)) {
            throw ApiException.badRequest("Give a quantity from 0");
        }
        String unit = in.unit() == null || in.unit().isBlank() ? null : in.unit().trim();
        if (unit != null && !Set.of("days", "years", "months").contains(unit)) {
            throw ApiException.badRequest("The unit is days, months or years");
        }
        LocalDate due;
        try {
            due = in.dueDate() == null || in.dueDate().isBlank() ? null : LocalDate.parse(in.dueDate().trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("The due date must be a date like 2026-10-31");
        }
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 300) {
            throw ApiException.badRequest("Keep the note to 300 characters");
        }
        return new Checked(code, label, direction, in.quantity(), unit,
                in.amount() == null ? null : in.amount().setScale(2, RoundingMode.HALF_UP), due, note);
    }

    private static Map<String, Object> editable(Connection c, SessionCtx ctx, UUID caseId) throws SQLException {
        Map<String, Object> x = lockCase(c, caseId);
        refuseOwn(ctx, x);
        if (!"draft".equals(x.get("settlementState"))) {
            throw new ApiException(409, "settlement.locked", "Only a draft settlement can change");
        }
        return x;
    }

    static Map<String, Object> load(Connection c, UUID caseId) throws SQLException {
        Map<String, Object> x = Sql.one(c, "select x.*, e.membership_id, e.joining_date, e.employment_type from app.exit_case x "
                + "join app.employment e on e.id = x.employment_id where x.id = ?", caseId);
        if (x == null) {
            throw ApiException.notFound("The exit");
        }
        return x;
    }

    private static Map<String, Object> lockCase(Connection c, UUID caseId) throws SQLException {
        Sql.one(c, "select id from app.exit_case where id = ? for update", caseId);
        return load(c, caseId);
    }

    private static void refuseOwn(SessionCtx ctx, Map<String, Object> x) {
        if (ctx.membershipId() != null && ctx.membershipId().toString().equals(x.get("membershipId"))) {
            throw new ApiException(409, "request.refused", "Nobody handles their own settlement");
        }
    }

    private static void requireHr(SessionCtx ctx) {
        if (!ExitService.canManage(ctx)) {
            throw ApiException.denied("exit.manage");
        }
    }

    private static String email(Connection c, Object membershipId) throws SQLException {
        if (membershipId == null) {
            return null;
        }
        Map<String, Object> m = Sql.one(c, "select email from app.member_directory() where membership_id = ?::uuid", membershipId);
        return m == null ? null : (String) m.get("email");
    }
}
