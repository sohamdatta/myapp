package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.loginapp.core.PayrollOutputs.bankFile;
import static com.example.loginapp.core.PayrollOutputs.csv;
import static com.example.loginapp.core.PayrollOutputs.journal;
import static com.example.loginapp.core.PayrollOutputs.pdf;
import static com.example.loginapp.core.PayrollOutputs.register;
import static com.example.loginapp.core.PayrollSnapshot.members;
import static com.example.loginapp.core.PayrollSnapshot.snapshot;
import static com.example.loginapp.core.Payslips.payslip;

/**
 * The monthly payroll run: open a period for a pay group, collect inputs, freeze everything into a
 * snapshot, calculate (as often as needed), review exceptions, approve (someone other than whoever
 * calculated), mark paid, release payslips. Also the outputs (register, bank file, journal, payslip
 * PDF) and each employee's My pay.
 *
 * The work behind these lives beside it: {@link PayrollSnapshot} freezes the month,
 * {@link Payslips} reads a payslip back, and {@link PayrollOutputs} writes the files.
 */
public final class PayrollRuns {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    public record InputInput(String employmentId, String componentCode, BigDecimal amount, String note) {}

    public record AcceptManyInput(List<String> ids, String reason) {}

    public record ReasonInput(String reason) {}

    public record PaidInput(String paidOn, String reference) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public PayrollRuns(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public PayrollRuns(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    static String monthWords(LocalDate period) {
        return period.format(MONTH);
    }

    // ── the runs list ───────────────────────────────────────────────────

    public Map<String, Object> list(SessionCtx ctx) {
        PayrollConfig.needRead(ctx);
        requireRuns(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            List<Map<String, Object>> groups = PayrollConfig.groups(c, today);
            for (Map<String, Object> g : groups) {
                Map<String, Object> open = Sql.one(c, "select id, period, state, totals from app.payroll_run where group_id = ?::uuid "
                        + "and state <> 'released' order by period limit 1", g.get("id"));
                Map<String, Object> last = Sql.one(c, "select max(period) as period from app.payroll_run where group_id = ?::uuid", g.get("id"));
                LocalDate next = last == null || last.get("period") == null ? today.withDayOfMonth(1)
                        : LocalDate.parse((String) last.get("period")).plusMonths(1);
                if (open != null) {
                    open.put("totals", parse(open.get("totals")));
                    open.put("label", monthWords(LocalDate.parse((String) open.get("period"))));
                }
                g.put("open", open);
                g.put("nextPeriod", next.toString());
                g.put("nextLabel", monthWords(next));
            }
            List<Map<String, Object>> runs = Sql.query(c, "select r.id, r.period, r.state, r.totals, r.group_id, g.name as group_name, r.approved_at, "
                    + "r.paid_on, r.released_at from app.payroll_run r join app.pay_group g on g.id = r.group_id order by r.period desc, g.name limit 60");
            for (Map<String, Object> r : runs) {
                r.put("totals", parse(r.get("totals")));
                r.put("label", monthWords(LocalDate.parse((String) r.get("period"))));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("groups", groups);
            out.put("runs", runs);
            out.put("unassigned", PayrollConfig.unassigned(c, today).size());
            out.put("draftRules", Sql.one(c, "select count(*) as n from app.payroll_rule where status = 'draft' and valid_from <= ? "
                    + "and (valid_to is null or valid_to > ?)", today, today).get("n"));
            out.put("canPrepare", ctx.reachesEveryone("payroll.prepare"));
            return out;
        });
    }

    public Map<String, Object> open(SessionCtx ctx, UUID groupId, String period) {
        PayrollConfig.need(ctx, "payroll.prepare");
        LocalDate p = PayrollConfig.date(period, "period");
        if (p.getDayOfMonth() != 1) {
            throw ApiException.badRequest("A run is for a whole month; give its first day");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            if (Sql.one(c, "select 1 from app.pay_group where id = ?", groupId) == null) {
                throw ApiException.notFound("The pay group");
            }
            Map<String, Object> earlier = Sql.one(c, "select period from app.payroll_run where group_id = ? and period < ? and state <> 'released' "
                    + "order by period limit 1", groupId, p);
            if (earlier != null) {
                throw new ApiException(409, "payroll.earlier_open", monthWords(LocalDate.parse((String) earlier.get("period")))
                        + " is still open for this group. Finish it first");
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.payroll_run (tenant_id, group_id, period) values (?, ?, ?) returning id",
                    ctx.tenantId(), groupId, p).get("id"));
            UserService.audit(c, ctx, "payroll.run_opened", "payroll_run", id, Map.of("period", p.toString()));
            return Map.of("id", id.toString());
        });
    }

    // ── one run ─────────────────────────────────────────────────────────

    public Map<String, Object> get(SessionCtx ctx, UUID runId) {
        requireRuns(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = run(c, runId);
            LocalDate period = LocalDate.parse((String) r.get("period"));
            r.put("label", monthWords(period));
            r.put("totals", parse(r.get("totals")));
            r.remove("snapshot");
            r.put("groupName", Sql.one(c, "select name from app.pay_group where id = ?::uuid", r.get("groupId")).get("name"));
            for (String who : List.of("frozenBy", "calculatedBy", "approvedBy", "paidBy", "releasedBy")) {
                r.put(who + "Name", r.get(who) == null ? null : who(c, AuthService.uuid(r.get(who))));
            }
            String state = (String) r.get("state");
            List<Map<String, Object>> people = new ArrayList<>();
            if (state.equals("draft")) {
                for (Map<String, Object> m : members(c, AuthService.uuid(r.get("groupId")), period)) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("employmentId", m.get("id"));
                    p.put("name", m.get("name"));
                    p.put("employeeCode", m.get("employeeCode"));
                    p.put("designation", m.get("designation"));
                    people.add(p);
                }
            } else {
                people = Sql.query(c, "select ps.id, ps.employment_id, ps.info->>'name' as name, ps.info->>'code' as employee_code, "
                        + "ps.info->>'designation' as designation, ps.days_paid, ps.days_in_period, ps.lop_days, ps.gross, ps.deductions, ps.net, "
                        + "ps.employer_cost, ps.held from app.payslip ps where ps.run_id = ? order by lower(ps.info->>'name')", runId);
            }
            r.put("people", people);
            r.put("inputs", Sql.query(c, "select i.id, i.employment_id, coalesce(p.preferred_name, p.legal_name) as name, i.component_code, "
                    + "coalesce(pc.name, case when i.component_code = '" + LOP_DAYS + "' then 'Loss of pay (days)' end) as component_name, "
                    + "i.amount, i.note from app.run_input i join app.employment e on e.id = i.employment_id "
                    + "join app.person p on p.id = e.person_id left join app.pay_component pc on pc.code = i.component_code "
                    + "where i.run_id = ? order by i.created_at", runId));
            r.put("holds", Sql.query(c, "select h.employment_id, coalesce(p.preferred_name, p.legal_name) as name, h.reason, h.held_at "
                    + "from app.pay_hold h join app.employment e on e.id = h.employment_id join app.person p on p.id = e.person_id "
                    + "where h.run_id = ?", runId));
            r.put("exceptions", Sql.query(c, "select x.id, x.employment_id, coalesce(p.preferred_name, p.legal_name) as name, x.code, x.level, "
                    + "x.acceptable, x.message, x.link, x.accepted_reason, x.accepted_at, " + Names.of("x.accepted_by") + " as accepted_by_name "
                    + "from app.run_exception x left join app.employment e on e.id = x.employment_id left join app.person p on p.id = e.person_id "
                    + "where x.run_id = ? order by (x.level = 'blocking') desc, x.accepted_at nulls first, lower(coalesce(p.legal_name, '')), x.code",
                    runId));
            r.put("attempts", Sql.query(c, "select number, at, totals, " + Names.of("by_id") + " as by_name from app.run_attempt where run_id = ? "
                    + "order by number desc", runId));
            for (Map<String, Object> a : PayrollEngine.list(r, "attempts")) {
                a.put("totals", parse(a.get("totals")));
            }
            List<Map<String, Object>> inputComponents = new java.util.ArrayList<>(Sql.query(c, "select pc.code, pc.name, pc.kind from app.pay_component pc join lateral (select amount_type "
                    + "from app.pay_component_version v where v.component_id = pc.id and v.valid_from <= ? order by valid_from desc limit 1) v on true "
                    + "where v.amount_type = 'input' and pc.retired_at is null order by pc.position", period.plusMonths(1).minusDays(1)));
            // Days without pay, entered by hand: how a payroll-only Organization gives loss of pay.
            inputComponents.add(new LinkedHashMap<>(Map.of("code", LOP_DAYS, "name", "Loss of pay (days)", "kind", "days")));
            r.put("inputComponents", inputComponents);
            r.put("attendanceOn", Modules.on(c, Modules.ATTENDANCE));
            r.put("leaveOn", Modules.on(c, Modules.LEAVE));
            long open = Sql.query(c, "select 1 from app.run_exception where run_id = ? and level = 'blocking' and accepted_at is null", runId).size();
            boolean me = ctx.membershipId() != null;
            r.put("openBlocking", open);
            r.put("draftRules", Sql.one(c, "select count(*) as n from app.payroll_rule where status = 'draft' and valid_from <= ? "
                    + "and (valid_to is null or valid_to > ?)", period, period).get("n"));
            r.put("canPrepare", ctx.reachesEveryone("payroll.prepare"));
            r.put("canApprove", ctx.reachesEveryone("payroll.approve") && state.equals("calculated") && open == 0
                    && !(me && ctx.membershipId().toString().equals(r.get("calculatedBy"))));
            r.put("approveBlockedBy", state.equals("calculated") && me && ctx.membershipId().toString().equals(r.get("calculatedBy"))
                    ? "You calculated this run; someone else approves it" : open > 0 ? open + " blocking exception" + (open == 1 ? "" : "s") + " to fix or accept" : null);
            r.put("canAccept", ctx.reachesEveryone("payroll.approve"));
            r.put("canPay", ctx.reachesEveryone("payroll.release"));
            r.put("today", today.toString());
            return r;
        });
    }

    /** The run input that carries loss-of-pay days rather than an amount. */
    static final String LOP_DAYS = "LOP_DAYS";

    public Map<String, Object> addInput(SessionCtx ctx, UUID runId, InputInput in) {
        PayrollConfig.need(ctx, "payroll.prepare");
        String code = PayrollConfig.upper(in.componentCode());
        boolean lopDays = LOP_DAYS.equals(code);
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw ApiException.badRequest(lopDays ? "Type the days without pay, above zero" : "Type an amount above zero");
        }
        if (lopDays && (in.amount().compareTo(BigDecimal.valueOf(31)) > 0
                || in.amount().multiply(BigDecimal.valueOf(2)).stripTrailingZeros().scale() > 0)) {
            throw ApiException.badRequest("Loss of pay is in whole or half days, up to 31");
        }
        UUID employment = PayrollConfig.id(in.employmentId(), "employee");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = run(c, runId);
            if (!"draft".equals(r.get("state"))) {
                throw new ApiException(409, "payroll.not_draft", "Inputs can be added only before the run is frozen. Reopen it first");
            }
            LocalDate period = LocalDate.parse((String) r.get("period"));
            boolean member = members(c, AuthService.uuid(r.get("groupId")), period).stream().anyMatch(m -> employment.toString().equals(m.get("id")));
            if (!member) {
                throw ApiException.badRequest("That employee is not in this pay group for " + monthWords(period));
            }
            Map<String, Object> pc = lopDays ? Map.of("name", "Loss of pay (days)") : Sql.one(c, "select pc.name from app.pay_component pc join lateral (select amount_type from app.pay_component_version v "
                    + "where v.component_id = pc.id and v.valid_from <= ? order by valid_from desc limit 1) v on true where pc.code = ? "
                    + "and v.amount_type = 'input' and pc.retired_at is null", period.plusMonths(1).minusDays(1), code);
            if (lopDays) {
                BigDecimal already = (BigDecimal) Sql.one(c, "select coalesce(sum(amount), 0) as n from app.run_input where run_id = ? "
                        + "and employment_id = ? and component_code = ?", runId, employment, LOP_DAYS).get("n");
                int inMonth = period.lengthOfMonth();
                if (already.add(in.amount()).compareTo(BigDecimal.valueOf(inMonth)) > 0) {
                    throw ApiException.badRequest("That makes more days without pay than the " + inMonth + " days in " + monthWords(period));
                }
            }
            if (pc == null) {
                throw ApiException.badRequest("Choose a component that takes an amount each month");
            }
            Map<String, Object> row = Sql.one(c, "insert into app.run_input (tenant_id, run_id, employment_id, component_code, amount, note, created_by) "
                    + "values (?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), runId, employment, code, in.amount(),
                    PayrollConfig.blank(in.note()), ctx.membershipId());
            UserService.audit(c, ctx, "payroll.input_added", "employment", employment, Map.of("component", pc.get("name"), "amount", in.amount()));
            return row;
        });
    }

    public void removeInput(SessionCtx ctx, UUID runId, UUID inputId) {
        PayrollConfig.need(ctx, "payroll.prepare");
        db.inTenant(ctx.tenantId(), c -> {
            if (!"draft".equals(run(c, runId).get("state"))) {
                throw new ApiException(409, "payroll.not_draft", "Inputs can be removed only before the run is frozen");
            }
            if (Sql.update(c, "delete from app.run_input where id = ? and run_id = ?", inputId, runId) == 0) {
                throw ApiException.notFound("The input");
            }
            return null;
        });
    }

    public void hold(SessionCtx ctx, UUID runId, UUID employmentId, String reason, boolean hold) {
        PayrollConfig.need(ctx, "payroll.prepare");
        String why = PayrollConfig.blank(reason);
        if (hold && (why == null || why.length() < 3)) {
            throw ApiException.badRequest("Say why the pay is held");
        }
        db.inTenant(ctx.tenantId(), c -> {
            String state = (String) run(c, runId).get("state");
            if (Set.of("approved", "paid", "released").contains(state)) {
                throw new ApiException(409, "payroll.locked", "This run is approved; pay can no longer be held here");
            }
            if (hold) {
                Sql.update(c, "insert into app.pay_hold (tenant_id, run_id, employment_id, reason, held_by) values (?, ?, ?, ?, ?) "
                        + "on conflict (run_id, employment_id) do update set reason = excluded.reason", ctx.tenantId(), runId, employmentId, why,
                        ctx.membershipId());
            } else {
                Sql.update(c, "delete from app.pay_hold where run_id = ? and employment_id = ?", runId, employmentId);
            }
            Sql.update(c, "update app.payslip set held = ? where run_id = ? and employment_id = ?", hold, runId, employmentId);
            UserService.audit(c, ctx, hold ? "payroll.pay_held" : "payroll.pay_released", "employment", employmentId,
                    hold ? Map.of("reason", why) : Map.of());
            return null;
        });
    }

    // ── the steps ───────────────────────────────────────────────────────

    public Map<String, Object> freeze(SessionCtx ctx, UUID runId) {
        PayrollConfig.need(ctx, "payroll.prepare");
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockRun(c, runId);
            if (!"draft".equals(r.get("state"))) {
                throw new ApiException(409, "payroll.state", "Only a run being prepared can be frozen");
            }
            LocalDate period = LocalDate.parse((String) r.get("period"));
            Map<String, Object> snapshot = snapshot(c, crypto, AuthService.uuid(r.get("groupId")), runId, period, today);
            Sql.update(c, "update app.payroll_run set state = 'frozen', snapshot = ?::jsonb, frozen_at = now(), frozen_by = ?, note = null where id = ?",
                    Json.write(snapshot), ctx.membershipId(), runId);
            UserService.audit(c, ctx, "payroll.run_frozen", "payroll_run", runId,
                    Map.of("period", period.toString(), "people", PayrollEngine.list(snapshot, "employees").size()));
            return Map.of("people", PayrollEngine.list(snapshot, "employees").size());
        });
    }

    public Map<String, Object> calculate(SessionCtx ctx, UUID runId) {
        PayrollConfig.need(ctx, "payroll.prepare");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockRun(c, runId);
            String state = (String) r.get("state");
            if (!Set.of("frozen", "calculated").contains(state)) {
                throw new ApiException(409, "payroll.state", "Freeze the run before calculating it");
            }
            LocalDate period = LocalDate.parse((String) r.get("period"));
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) Json.parse((String) r.get("snapshot"));
            Map<String, Object> config = PayrollEngine.map(snapshot, "config");
            // Keep accepted exceptions when the same one comes back.
            Map<String, Map<String, Object>> accepted = new HashMap<>();
            for (Map<String, Object> x : Sql.query(c, "select employment_id, code, detail_key, accepted_by, accepted_reason, accepted_at from app.run_exception "
                    + "where run_id = ? and accepted_at is not null", runId)) {
                accepted.put(x.get("employmentId") + "|" + x.get("code"), x);
            }
            Sql.update(c, "delete from app.payslip where run_id = ?", runId);
            Sql.update(c, "delete from app.run_exception where run_id = ?", runId);
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal deductions = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            BigDecimal employer = BigDecimal.ZERO;
            int people = 0;
            int blocking = 0;
            int warnings = 0;
            Set<String> runLevel = new HashSet<>();
            // Holds are read now, not from the snapshot: pay can be held after the inputs are frozen.
            // One query for the run, not one per person.
            Set<UUID> onHold = new HashSet<>();
            for (Map<String, Object> h : Sql.query(c, "select employment_id from app.pay_hold where run_id = ?", runId)) {
                onHold.add(AuthService.uuid(h.get("employmentId")));
            }
            // Rows are collected and written in three batches after the loop (payslips, then their lines,
            // then exceptions), instead of one statement per row.
            List<Object[]> payslipRows = new ArrayList<>();
            List<Object[]> lineRows = new ArrayList<>();
            List<Object[]> exceptionRows = new ArrayList<>();
            Map<UUID, LocalDate> lastDays = currentLastDays(c, PayrollEngine.list(snapshot, "employees"));
            BankRead bank = bankChecks(c, ctx.tenantId(), PayrollEngine.list(snapshot, "employees"), period);
            for (Map<String, Object> emp : PayrollEngine.list(snapshot, "employees")) {
                PayrollEngine.Calc calc = PayrollEngine.calculate(config, emp, period);
                UUID employment = AuthService.uuid(emp.get("employmentId"));
                lastDayCheck(calc, emp, lastDays.get(employment), period);
                if (calc.calculated()) {
                    calc.exceptions().addAll(bank.flags.getOrDefault(employment, List.of()));
                }
                boolean heldPay = onHold.contains(employment);
                if (calc.calculated()) {
                    boolean held = heldPay;
                    Map<String, Object> info = new LinkedHashMap<>(calc.info());
                    info.put("annual", emp.get("annual"));
                    info.put("structure", PayrollEngine.map(PayrollEngine.map(config, "structures"), (String) emp.get("structureId")).get("name"));
                    info.put("compensationFrom", emp.get("compensationFrom"));
                    info.put("proration", PayrollEngine.map(config, "settings").get("proration"));
                    if (bank.current.get(employment) != null) {
                        info.put("bankAccountId", bank.current.get(employment));   // the account this payslip is paid to; approval checks it is unchanged
                    }
                    UUID payslip = UUID.randomUUID();   // made here so the lines can name it without waiting for the insert
                    payslipRows.add(new Object[] {payslip, ctx.tenantId(), runId, employment,
                            emp.get("membershipId") == null ? null : AuthService.uuid(emp.get("membershipId")),
                            calc.daysInPeriod(), calc.daysPaid(), calc.lopDays(), calc.gross(), calc.deductions(), calc.net(), calc.employer(), held,
                            Json.write(info)});
                    for (Map<String, Object> l : calc.lines()) {
                        lineRows.add(new Object[] {ctx.tenantId(), payslip,
                                (short) PayrollEngine.intOf(l.get("position")), l.get("code"), l.get("name"), l.get("kind"), l.get("amount"),
                                l.get("unrounded"), l.get("fullMonth"), l.get("ruleId"), Json.write(l.get("trace"))});
                    }
                    gross = gross.add(calc.gross());
                    deductions = deductions.add(calc.deductions());
                    net = net.add(calc.net());
                    employer = employer.add(calc.employer());
                    people++;
                }
                for (Map<String, Object> x : calc.exceptions()) {
                    String code = (String) x.get("code");
                    boolean level = code.startsWith("missing_registration") || code.equals("missing_state_pack") || code.equals("after_last_day");
                    if (heldPay && !level && "blocking".equals(x.get("level"))) {
                        // Held pay is not paid this month: its problems are fixed before it is released, not before the run.
                        x = new LinkedHashMap<>(x);
                        x.put("level", "warning");
                        x.put("message", "Pay held. " + x.get("message"));
                    }
                    UUID who = level ? null : employment;
                    if (level && !runLevel.add(code + "|" + x.get("message"))) {
                        continue;
                    }
                    Map<String, Object> was = accepted.get((who == null ? "null" : who.toString()) + "|" + code);
                    // An acceptance stands only while the same thing is still wrong: a net pay change accepted at one figure is
                    // looked at again when the figure moves.
                    if (was != null && (!java.util.Objects.equals(was.get("detailKey"), x.get("detailKey")) || !"blocking".equals(x.get("level"))
                            || !Boolean.TRUE.equals(x.get("acceptable")))) {
                        was = null;   // also when pay is now held: a warning is never accepted
                    }
                    exceptionRows.add(new Object[] {ctx.tenantId(), runId, who, code.contains(":") ? code.substring(0, code.indexOf(':')) : code,
                            x.get("level"), x.get("acceptable"), x.get("message"), x.get("link"), x.get("detailKey"),
                            was == null ? null : was.get("acceptedBy"),
                            was == null ? null : was.get("acceptedReason"), was == null ? null : was.get("acceptedAt")});
                    if ("blocking".equals(x.get("level"))) {
                        blocking++;
                    } else {
                        warnings++;
                    }
                }
            }
            Sql.batch(c, "insert into app.payslip (id, tenant_id, run_id, employment_id, membership_id, days_in_period, days_paid, lop_days, "
                    + "gross, deductions, net, employer_cost, held, info) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", payslipRows);
            Sql.batch(c, "insert into app.payslip_line (tenant_id, payslip_id, position, code, name, kind, amount, unrounded, full_month, "
                    + "rule_id, trace) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?::jsonb)", lineRows);
            Sql.batch(c, "insert into app.run_exception (tenant_id, run_id, employment_id, code, level, acceptable, message, link, detail_key, "
                    + "accepted_by, accepted_reason, accepted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?::timestamptz)", exceptionRows);
            int attempt = ((Number) r.get("attempt")).intValue() + 1;
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("people", people);
            totals.put("gross", gross);
            totals.put("deductions", deductions);
            totals.put("net", net);
            totals.put("employerCost", gross.add(employer));
            totals.put("blocking", blocking);
            totals.put("warnings", warnings);
            Sql.update(c, "update app.payroll_run set state = 'calculated', attempt = ?, calculated_by = ?, calculated_at = now(), totals = ?::jsonb, "
                    + "note = null where id = ?", attempt, ctx.membershipId(), Json.write(totals), runId);
            Sql.update(c, "insert into app.run_attempt (tenant_id, run_id, number, by_id, totals) values (?, ?, ?, ?, ?::jsonb)", ctx.tenantId(), runId,
                    attempt, ctx.membershipId(), Json.write(totals));
            UserService.audit(c, ctx, "payroll.run_calculated", "payroll_run", runId, Map.of("attempt", attempt, "people", people, "net", net));
            return totals;
        });
    }

    /**
     * Each person's last day as it stands now, read when the run is calculated: the exit date, or the last working day of an accepted
     * exit. The run froze its own copy of it; the two can differ if the exit changed since.
     */
    private static Map<UUID, LocalDate> currentLastDays(Connection c, List<Map<String, Object>> employees) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> e : employees) {
            ids.add(AuthService.uuid(e.get("employmentId")));
        }
        Map<UUID, LocalDate> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : Sql.query(c, "select e.id, coalesce(e.exit_date, (select ec.last_working_day from app.exit_case ec "
                + "where ec.employment_id = e.id and ec.status = 'accepted' and ec.last_working_day is not null order by ec.accepted_at desc limit 1)) as leaving "
                + "from app.employment e where e.id = any(?)", (Object) ids.toArray(UUID[]::new))) {
            if (r.get("leaving") != null) {
                out.put(AuthService.uuid(r.get("id")), LocalDate.parse(String.valueOf(r.get("leaving"))));
            }
        }
        return out;
    }

    /** A bank account is "recent" when it was changed within this many days of the calculation. */
    private static final int BANK_RECENT_DAYS = 7;
    /** How many earlier accounts are looked at to tell a real change from the same account entered again. */
    private static final int BANK_HISTORY = 5;

    /** What the bank read found: the flags per person, and the account row each person is paid to. */
    private static final class BankRead {
        final Map<UUID, List<Map<String, Object>>> flags;
        final Map<UUID, String> current;

        BankRead(Map<UUID, List<Map<String, Object>>> flags, Map<UUID, String> current) {
            this.flags = flags;
            this.current = current;
        }
    }

    /**
     * Risk signals on where the pay goes, read when the run is calculated, from the account the run pays (the latest effective by the end
     * of the month, as the bank file does): it was replaced by a different account in the last few days (a changed account is the
     * usual sign of diverted pay), or the same account is on two people. The same account entered again (a branch corrected, a section
     * saved twice) is not a change. Accounts are compared by decrypting inside this method; only the last four digits and the other
     * person's name ever reach a message.
     */
    private BankRead bankChecks(Connection c, UUID tenantId, List<Map<String, Object>> employees, LocalDate period) throws SQLException {
        Map<UUID, List<Map<String, Object>>> flags = new HashMap<>();
        Map<UUID, String> current = new HashMap<>();
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> e : employees) {
            ids.add(AuthService.uuid(e.get("employmentId")));
        }
        if (ids.isEmpty()) {
            return new BankRead(flags, current);
        }
        java.time.Instant recentSince = clock.instant().minus(java.time.Duration.ofDays(BANK_RECENT_DAYS));
        record Row(UUID id, java.time.Instant createdAt, String key, String last4) {}
        Map<UUID, List<Row>> rows = new HashMap<>();   // newest first, per employment
        Map<UUID, UUID> personOf = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        // Plain JDBC: the encrypted account number is bytes, which the row helper does not return.
        try (java.sql.PreparedStatement ps = c.prepareStatement("select * from (select e.id as employment, e.person_id, "
                + "coalesce(p.preferred_name, p.legal_name) as name, x.id, x.account_enc, x.account_last4, x.ifsc, x.created_at, "
                + "row_number() over (partition by e.id order by x.effective_from desc, x.created_at desc) as rn "
                + "from app.employment e join app.person p on p.id = e.person_id join app.bank_account x on x.person_id = e.person_id "
                + "and x.effective_from <= ? where e.id = any(?)) t where rn <= " + BANK_HISTORY + " order by employment, rn")) {
            ps.setObject(1, period.plusMonths(1).minusDays(1));
            ps.setArray(2, c.createArrayOf("uuid", ids.toArray(UUID[]::new)));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID employment = rs.getObject("employment", UUID.class);
                    personOf.put(employment, rs.getObject("person_id", UUID.class));
                    names.put(employment, rs.getString("name"));
                    String ifsc = rs.getString("ifsc");
                    String key;
                    try {
                        // Accounts are numbered within a bank, not a branch: the bank is the first four letters of the IFSC.
                        key = BankHashes.key(ifsc, crypto.decryptText(tenantId, rs.getBytes("account_enc")));
                    } catch (IllegalStateException e) {
                        key = "unreadable|" + rs.getObject("id", UUID.class);   // cannot be compared; never equal to another
                    }
                    rows.computeIfAbsent(employment, k -> new ArrayList<>())
                            .add(new Row(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(), key, rs.getString("account_last4")));
                }
            }
        }
        Map<String, Map<UUID, UUID>> byAccount = new HashMap<>();   // account -> person -> an employment of theirs
        Map<UUID, String> latestKey = new HashMap<>();
        for (Map.Entry<UUID, List<Row>> entry : rows.entrySet()) {
            UUID employment = entry.getKey();
            List<Row> history = entry.getValue();
            Row latest = history.get(0);
            current.put(employment, latest.id().toString());
            latestKey.put(employment, latest.key());
            byAccount.computeIfAbsent(latest.key(), k -> new HashMap<>()).put(personOf.get(employment), employment);
            // Walk back over the same account entered again; what comes before is the account it replaced, if there is one.
            Row first = latest;
            int i = 1;
            while (i < history.size() && history.get(i).key().equals(latest.key())) {
                first = history.get(i);
                i++;
            }
            boolean replaced = i < history.size();
            if (replaced && first.createdAt().isAfter(recentSince)) {
                flags.computeIfAbsent(employment, k -> new ArrayList<>()).add(new LinkedHashMap<>(Map.of("code", "bank_changed_recent", "level", "blocking",
                        "acceptable", true, "message", "The bank account (ending " + latest.last4() + ") was changed in the last " + BANK_RECENT_DAYS
                                + " days. Check with the employee before paying it.", "link", "people", "detailKey", first.id().toString())));
            }
        }
        // The same accounts on people who are not in this run (another pay group, someone who has left), found by their fingerprints.
        Map<String, List<String>> outside = new HashMap<>();   // account key -> who else, by name
        Map<String, List<UUID>> outsidePeople = new HashMap<>();
        if (!byAccount.isEmpty()) {
            Map<String, String> keyOfHash = new HashMap<>();
            List<byte[]> hashes = new ArrayList<>();
            for (String key : byAccount.keySet()) {
                if (key.startsWith("unreadable|")) {
                    continue;
                }
                byte[] h = crypto.fingerprint(tenantId, "bank", key);
                hashes.add(h);
                keyOfHash.put(java.util.HexFormat.of().formatHex(h), key);
            }
            if (!hashes.isEmpty()) {
                byte[][] hashArray = hashes.toArray(new byte[0][]);
                UUID[] inRun = new HashSet<>(personOf.values()).toArray(UUID[]::new);
                try (java.sql.PreparedStatement ps = c.prepareStatement("select p.id, coalesce(p.preferred_name, p.legal_name) as name, t.account_hash, "
                        + "exists (select 1 from app.employment e where e.person_id = p.id and e.status = 'active') as active "
                        + "from (select distinct on (x.person_id) x.person_id, x.account_hash from app.bank_account x where x.effective_from <= ? "
                        + "and x.person_id in (select person_id from app.bank_account where account_hash = any(?)) "
                        + "order by x.person_id, x.effective_from desc, x.created_at desc) t join app.person p on p.id = t.person_id "
                        + "where t.account_hash = any(?) and t.person_id <> all(?)")) {
                    ps.setObject(1, period.plusMonths(1).minusDays(1));
                    ps.setArray(2, c.createArrayOf("bytea", hashArray));
                    ps.setArray(3, c.createArrayOf("bytea", hashArray));
                    ps.setArray(4, c.createArrayOf("uuid", inRun));
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String key = keyOfHash.get(java.util.HexFormat.of().formatHex(rs.getBytes("account_hash")));
                            outside.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString("name") + (rs.getBoolean("active") ? "" : " (no longer employed)"));
                            outsidePeople.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getObject("id", UUID.class));
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, Map<UUID, UUID>> account : byAccount.entrySet()) {
            Map<UUID, UUID> persons = account.getValue();
            List<String> away = outside.getOrDefault(account.getKey(), List.of());
            if (persons.size() + away.size() < 2) {
                continue;   // one person, even with two employments, is not sharing with anyone
            }
            List<UUID> sorted = new ArrayList<>(persons.keySet());
            sorted.sort(java.util.Comparator.comparing(UUID::toString));
            StringBuilder key = new StringBuilder();
            for (UUID person : sorted) {
                key.append(person).append(':').append(current.get(persons.get(person))).append(',');   // the people and their rows: a new account is a new thing to accept
            }
            List<UUID> awayIds = new ArrayList<>(outsidePeople.getOrDefault(account.getKey(), List.of()));
            awayIds.sort(java.util.Comparator.comparing(UUID::toString));
            awayIds.forEach(id -> key.append(id).append(','));
            for (Map.Entry<UUID, UUID> who : persons.entrySet()) {
                List<String> others = new ArrayList<>();
                for (Map.Entry<UUID, UUID> other : persons.entrySet()) {
                    if (!other.getKey().equals(who.getKey())) {
                        others.add(names.get(other.getValue()));
                    }
                }
                others.addAll(away);
                String shown = others.size() <= 3 ? String.join(", ", others) : String.join(", ", others.subList(0, 3)) + " and " + (others.size() - 3) + " others";
                // Every employment of this person is told, not only the one that was kept in the map.
                for (Map.Entry<UUID, UUID> mine : personOf.entrySet().stream().filter(x -> x.getValue().equals(who.getKey())).toList()) {
                    flags.computeIfAbsent(mine.getKey(), k -> new ArrayList<>()).add(new LinkedHashMap<>(Map.of("code", "bank_shared", "level", "blocking",
                            "acceptable", true, "message", "The same bank account (ending " + rows.get(mine.getKey()).get(0).last4() + ") is also on " + shown
                                    + ". Accept it only if it is a joint account.", "link", "people", "detailKey", key.toString())));
                }
            }
        }
        return new BankRead(flags, current);
    }

    /**
     * Pay must stop on someone's last day, and the run froze its own copy of it. If the exit was moved, brought forward, put back or
     * cancelled since, the frozen figures pay the wrong days (and, for tax, the wrong number of months left). Reopen and freeze again.
     */
    private static void lastDayCheck(PayrollEngine.Calc calc, Map<String, Object> emp, LocalDate now, LocalDate period) {
        if (!calc.calculated()) {
            return;
        }
        LocalDate frozen = emp.get("leavingOn") == null ? null : LocalDate.parse((String) emp.get("leavingOn"));
        if (java.util.Objects.equals(frozen, now)) {
            return;
        }
        LocalDate end = period.plusMonths(1);   // first day after the month
        boolean matters = (frozen != null && frozen.isBefore(end)) || (now != null && now.isBefore(end));
        if (!matters) {
            return;
        }
        String message = now == null ? "Their exit was cancelled after the inputs were frozen, so the pay is worked out as if they left on " + frozen + ". Reopen the run and freeze it again."
                : "Their last day is now " + now + (frozen == null ? "" : ", not " + frozen + " as when the inputs were frozen") + ". Reopen the run and freeze it again.";
        calc.exceptions().add(new LinkedHashMap<>(Map.of("code", "after_last_day", "level", "blocking", "acceptable", false,
                "message", message, "link", "payroll")));
    }

    /** Accepts several exceptions at once, each with the same reason, each recorded in the audit as its own event. */
    public int acceptMany(SessionCtx ctx, UUID runId, List<UUID> ids, String reason) {
        PayrollConfig.need(ctx, "payroll.approve");
        String why = PayrollConfig.blank(reason);
        if (why == null || why.length() < 3) {
            throw ApiException.badRequest("Say why these are accepted");
        }
        if (ids == null || ids.isEmpty() || ids.size() > 500) {
            throw ApiException.badRequest("Choose between 1 and 500 exceptions");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            if (!"calculated".equals(lockRun(c, runId).get("state"))) {
                throw new ApiException(409, "payroll.state", "Exceptions are accepted on a calculated run");
            }
            int done = 0;
            for (UUID id : new java.util.LinkedHashSet<>(ids)) {
                Map<String, Object> x = Sql.one(c, "select level, acceptable, code, employment_id, accepted_at from app.run_exception where id = ? and run_id = ?", id, runId);
                if (x == null) {
                    throw ApiException.notFound("The exception");
                }
                if (!"blocking".equals(x.get("level")) || !Boolean.TRUE.equals(x.get("acceptable"))) {
                    throw new ApiException(409, "payroll.not_acceptable", "One of these cannot be accepted; it has to be fixed");
                }
                if (String.valueOf(x.get("code")).startsWith("bank_")) {
                    throw new ApiException(409, "payroll.one_by_one", "Bank account checks are accepted one at a time, each with its own reason");
                }
                if (x.get("acceptedAt") != null) {
                    continue;
                }
                if (Sql.update(c, "update app.run_exception set accepted_by = ?, accepted_reason = ?, accepted_at = now() where id = ? and accepted_at is null",
                        ctx.membershipId(), why, id) == 0) {
                    continue;
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("code", x.get("code"));
                detail.put("reason", why);
                if (x.get("employmentId") != null) {
                    detail.put("employmentId", x.get("employmentId"));
                }
                UserService.audit(c, ctx, "payroll.exception_accepted", "payroll_run", runId, detail);
                done++;
            }
            return done;
        });
    }

    public void reopen(SessionCtx ctx, UUID runId, String reason) {
        PayrollConfig.need(ctx, "payroll.prepare");
        String why = PayrollConfig.blank(reason);
        if (why == null || why.length() < 3) {
            throw ApiException.badRequest("Say why the run is reopened");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockRun(c, runId);
            if (!Set.of("frozen", "calculated").contains(r.get("state"))) {
                throw new ApiException(409, "payroll.state", "Only a frozen or calculated run can be reopened");
            }
            back(c, runId, why);
            UserService.audit(c, ctx, "payroll.run_reopened", "payroll_run", runId, Map.of("reason", why));
            return null;
        });
    }

    private static void back(Connection c, UUID runId, String note) throws SQLException {
        Sql.update(c, "delete from app.payslip where run_id = ?", runId);
        Sql.update(c, "delete from app.run_exception where run_id = ?", runId);
        Sql.update(c, "update app.payroll_run set state = 'draft', snapshot = null, totals = null, note = ? where id = ?", note, runId);
    }

    public void accept(SessionCtx ctx, UUID runId, UUID exceptionId, String reason) {
        PayrollConfig.need(ctx, "payroll.approve");
        String why = PayrollConfig.blank(reason);
        if (why == null || why.length() < 3) {
            throw ApiException.badRequest("Say why this is accepted");
        }
        db.inTenant(ctx.tenantId(), c -> {
            if (!"calculated".equals(lockRun(c, runId).get("state"))) {
                throw new ApiException(409, "payroll.state", "Exceptions are accepted on a calculated run");
            }
            Map<String, Object> x = Sql.one(c, "select level, acceptable, code from app.run_exception where id = ? and run_id = ?", exceptionId, runId);
            if (x == null) {
                throw ApiException.notFound("The exception");
            }
            if (!"blocking".equals(x.get("level")) || !Boolean.TRUE.equals(x.get("acceptable"))) {
                throw new ApiException(409, "payroll.not_acceptable", "This cannot be accepted; it has to be fixed");
            }
            Sql.update(c, "update app.run_exception set accepted_by = ?, accepted_reason = ?, accepted_at = now() where id = ?", ctx.membershipId(), why,
                    exceptionId);
            UserService.audit(c, ctx, "payroll.exception_accepted", "payroll_run", runId, Map.of("code", x.get("code"), "reason", why));
            return null;
        });
    }

    public void approve(SessionCtx ctx, UUID runId) {
        PayrollConfig.need(ctx, "payroll.approve");
        StepUp.require(db, ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockRun(c, runId);
            if (!"calculated".equals(r.get("state"))) {
                throw new ApiException(409, "payroll.state", "Only a calculated run can be approved");
            }
            if (ctx.membershipId() != null && ctx.membershipId().toString().equals(r.get("calculatedBy"))) {
                throw new ApiException(409, "payroll.maker_checker", "The person who calculated a run cannot approve it");
            }
            // Pay goes to the account that was checked: if one was changed after the calculation, calculate again.
            long moved = Sql.query(c, "select 1 from app.payslip ps join app.employment e on e.id = ps.employment_id where ps.run_id = ? and not ps.held "
                    + "and jsonb_exists(ps.info, 'bankAccountId') and ps.info->>'bankAccountId' is distinct from (select x.id::text from app.bank_account x "
                    + "where x.person_id = e.person_id and x.effective_from <= ?::date order by x.effective_from desc, x.created_at desc limit 1)",
                    runId, LocalDate.parse((String) r.get("period")).plusMonths(1).minusDays(1).toString()).size();
            if (moved > 0) {
                throw new ApiException(409, "payroll.bank_changed", "The bank account of " + moved + (moved == 1 ? " person was" : " people were")
                        + " changed after the run was calculated. Calculate it again so the new account is checked.");
            }
            long open = Sql.query(c, "select 1 from app.run_exception where run_id = ? and level = 'blocking' and accepted_at is null", runId).size();
            if (open > 0) {
                throw new ApiException(409, "payroll.blocked", open + " blocking exception" + (open == 1 ? " is" : "s are") + " still open");
            }
            Sql.update(c, "update app.payroll_run set state = 'approved', approved_by = ?, approved_at = now() where id = ?", ctx.membershipId(), runId);
            UserService.audit(c, ctx, "payroll.run_approved", "payroll_run", runId, Map.of("period", r.get("period")));
            return null;
        });
    }

    public void sendBack(SessionCtx ctx, UUID runId, String note) {
        PayrollConfig.need(ctx, "payroll.approve");
        String why = PayrollConfig.blank(note);
        if (why == null || why.length() < 3) {
            throw ApiException.badRequest("Say what needs to change");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockRun(c, runId);
            if (!"calculated".equals(r.get("state"))) {
                throw new ApiException(409, "payroll.state", "Only a calculated run can be sent back");
            }
            back(c, runId, why);
            Map<String, String> v = new HashMap<>();
            v.put("period", monthWords(LocalDate.parse((String) r.get("period"))));
            v.put("group", (String) Sql.one(c, "select name from app.pay_group where id = ?::uuid", r.get("groupId")).get("name"));
            v.put("note", why);
            if (r.get("calculatedBy") != null) {
                Notifications.send(c, ctx.tenantId(), AuthService.uuid(r.get("calculatedBy")), "payroll_sent_back", v, "payroll");
            }
            UserService.audit(c, ctx, "payroll.run_sent_back", "payroll_run", runId, Map.of("note", why));
            return null;
        });
    }

    public void markPaid(SessionCtx ctx, UUID runId, PaidInput in) {
        PayrollConfig.need(ctx, "payroll.release");
        StepUp.require(db, ctx);
        LocalDate on = in.paidOn() == null || in.paidOn().isBlank() ? today() : PayrollConfig.date(in.paidOn(), "paid on");
        String ref = PayrollConfig.blank(in.reference());
        if (ref == null) {
            throw ApiException.badRequest("Type the bank reference of the payment batch");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockRun(c, runId);
            if (!"approved".equals(r.get("state"))) {
                throw new ApiException(409, "payroll.state", "Only an approved run can be marked paid");
            }
            Sql.update(c, "update app.payroll_run set state = 'paid', paid_on = ?, paid_reference = ?, paid_by = ? where id = ?", on, ref,
                    ctx.membershipId(), runId);
            UserService.audit(c, ctx, "payroll.run_paid", "payroll_run", runId, Map.of("paidOn", on.toString(), "reference", ref));
            if (Boolean.TRUE.equals(Sql.one(c, "select release_auto from app.payroll_settings").get("releaseAuto"))) {
                release(c, ctx, runId);
            }
            return null;
        });
    }

    public void release(SessionCtx ctx, UUID runId) {
        if (!ctx.reachesEveryone("payroll.prepare") && !ctx.reachesEveryone("payroll.release")) {
            throw ApiException.denied("payroll.prepare");
        }
        StepUp.require(db, ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = lockRun(c, runId);
            if (!"paid".equals(r.get("state"))) {
                throw new ApiException(409, "payroll.state", "Payslips are released once the run is paid");
            }
            release(c, ctx, runId);
            return null;
        });
    }

    private static void release(Connection c, SessionCtx ctx, UUID runId) throws SQLException {
        Map<String, Object> r = run(c, runId);
        Sql.update(c, "update app.payroll_run set state = 'released', released_at = now(), released_by = ? where id = ?", ctx.membershipId(), runId);
        String period = monthWords(LocalDate.parse((String) r.get("period")));
        for (Map<String, Object> p : Sql.query(c, "select ps.membership_id, coalesce(pe.preferred_name, pe.legal_name) as name from app.payslip ps "
                + "join app.employment e on e.id = ps.employment_id join app.person pe on pe.id = e.person_id where ps.run_id = ? and ps.membership_id is not null",
                runId)) {
            Map<String, String> v = new HashMap<>();
            v.put("period", period);
            v.put("person.first_name", String.valueOf(p.get("name")).split(" ")[0]);
            Notifications.send(c, ctx.tenantId(), AuthService.uuid(p.get("membershipId")), "payslip_released", v, "my-pay");
        }
        UserService.audit(c, ctx, "payroll.payslips_released", "payroll_run", runId, Map.of("period", r.get("period")));
    }

    // ── one employee in a run ───────────────────────────────────────────

    public Map<String, Object> employee(SessionCtx ctx, UUID runId, UUID employmentId) {
        requireRuns(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> ps = Sql.one(c, "select id from app.payslip where run_id = ? and employment_id = ?", runId, employmentId);
            if (ps == null) {
                throw ApiException.notFound("The payslip");
            }
            Map<String, Object> out = payslip(c, AuthService.uuid(ps.get("id")), true);
            out.put("exceptions", Sql.query(c, "select id, code, level, acceptable, message, link, accepted_reason from app.run_exception "
                    + "where run_id = ? and employment_id = ? order by (level = 'blocking') desc", runId, employmentId));
            return out;
        });
    }

    // ── My pay ──────────────────────────────────────────────────────────

    public Map<String, Object> myPay(SessionCtx ctx) {
        if (ctx.membershipId() == null || !"tenant".equals(ctx.kind())) {
            throw new ApiException(403, "tenant.required", "My pay is for employees");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> slips = Sql.query(c, "select ps.id, r.period, ps.gross, ps.deductions, ps.net, ps.days_paid, ps.days_in_period, "
                    + "r.paid_on from app.payslip ps join app.payroll_run r on r.id = ps.run_id where ps.membership_id = ? and r.state = 'released' "
                    + "order by r.period desc", ctx.membershipId());
            for (Map<String, Object> s : slips) {
                s.put("label", monthWords(LocalDate.parse((String) s.get("period"))));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("payslips", slips);
            Map<String, Object> e = Sql.one(c, "select e.id from app.employment e where e.membership_id = ? and e.status in ('active','exited') "
                    + "order by e.joining_date desc limit 1", ctx.membershipId());
            if (e != null) {
                out.put("profile", Sql.one(c, "select tax_regime from app.pay_profile where employment_id = ?::uuid", e.get("id")));
            }
            return out;
        });
    }

    public Map<String, Object> myPayslip(SessionCtx ctx, UUID payslipId) {
        return db.inTenant(ctx.tenantId(), c -> {
            ownReleased(c, ctx, payslipId);
            return payslip(c, payslipId, false);
        });
    }

    public DocumentService.Content myPayslipPdf(SessionCtx ctx, UUID payslipId) {
        return db.inTenant(ctx.tenantId(), c -> {
            ownReleased(c, ctx, payslipId);
            return pdf(c, payslipId, ctx.tenantName());
        });
    }

    private static void ownReleased(Connection c, SessionCtx ctx, UUID payslipId) throws SQLException {
        if (ctx.membershipId() == null || Sql.one(c, "select 1 from app.payslip ps join app.payroll_run r on r.id = ps.run_id where ps.id = ? "
                + "and ps.membership_id = ? and r.state = 'released'", payslipId, ctx.membershipId()) == null) {
            throw ApiException.notFound("The payslip");
        }
    }

    // ── outputs ─────────────────────────────────────────────────────────

    public DocumentService.Content output(SessionCtx ctx, UUID runId, String kind) {
        requireRuns(ctx);
        if (kind.equals("bank")) {
            PayrollConfig.need(ctx, "payroll.release");
            StepUp.require(db, ctx);
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = run(c, runId);
            String state = (String) r.get("state");
            if (state.equals("draft") || state.equals("frozen")) {
                throw new ApiException(409, "payroll.state", "Calculate the run first");
            }
            LocalDate period = LocalDate.parse((String) r.get("period"));
            String month = period.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            return switch (kind) {
                case "register" -> csv("Salary register " + month + ".csv", register(c, runId));
                case "bank" -> {
                    if (!Set.of("approved", "paid", "released").contains(state)) {
                        throw new ApiException(409, "payroll.state", "The bank file is made from an approved run");
                    }
                    UserService.audit(c, ctx, "payroll.bank_file_downloaded", "payroll_run", runId, Map.of("period", period.toString()));
                    yield csv("HDFC bank file " + month + ".csv", bankFile(c, crypto, ctx.tenantId(), runId, period, today()));
                }
                case "journal" -> csv("Payroll journal " + month + ".csv", journal(c, runId, period));
                default -> throw ApiException.notFound("That output");
            };
        });
    }

    public DocumentService.Content payslipPdf(SessionCtx ctx, UUID runId, UUID employmentId) {
        requireRuns(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> ps = Sql.one(c, "select id from app.payslip where run_id = ? and employment_id = ?", runId, employmentId);
            if (ps == null) {
                throw ApiException.notFound("The payslip");
            }
            return pdf(c, AuthService.uuid(ps.get("id")), ctx.tenantName());
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    static LocalDate taxYearStart(LocalDate period) {
        return period.getMonthValue() >= 4 ? LocalDate.of(period.getYear(), 4, 1) : LocalDate.of(period.getYear() - 1, 4, 1);
    }

    private static void requireRuns(SessionCtx ctx) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        if (!ctx.reachesEveryone("payroll.read") && !ctx.reachesEveryone("payroll.prepare")) {
            throw ApiException.denied("payroll.read");
        }
    }

    static Map<String, Object> run(Connection c, UUID runId) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select * from app.payroll_run where id = ?", runId);
        if (r == null) {
            throw ApiException.notFound("The run");
        }
        return r;
    }

    private static Map<String, Object> lockRun(Connection c, UUID runId) throws SQLException {
        Sql.one(c, "select id from app.payroll_run where id = ? for update", runId);
        return run(c, runId);
    }

    private static String who(Connection c, UUID membership) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select coalesce(" + Names.of("?::uuid") + ", (select d.email::text from app.member_directory() d "
                + "where d.membership_id = ?::uuid)) as name", membership, membership);
        return r == null ? null : (String) r.get("name");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(Object json) {
        if (json == null) {
            return new LinkedHashMap<>();
        }
        Object v = Json.parse(String.valueOf(json));
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    static String money(Object v) {
        return v == null ? "0.00" : new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
