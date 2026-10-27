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
            for (Map<String, Object> x : Sql.query(c, "select employment_id, code, accepted_by, accepted_reason, accepted_at from app.run_exception "
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
            for (Map<String, Object> emp : PayrollEngine.list(snapshot, "employees")) {
                PayrollEngine.Calc calc = PayrollEngine.calculate(config, emp, period);
                UUID employment = AuthService.uuid(emp.get("employmentId"));
                boolean heldPay = onHold.contains(employment);
                if (calc.calculated()) {
                    boolean held = heldPay;
                    Map<String, Object> info = new LinkedHashMap<>(calc.info());
                    info.put("annual", emp.get("annual"));
                    info.put("structure", PayrollEngine.map(PayrollEngine.map(config, "structures"), (String) emp.get("structureId")).get("name"));
                    info.put("compensationFrom", emp.get("compensationFrom"));
                    info.put("proration", PayrollEngine.map(config, "settings").get("proration"));
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
                    boolean level = code.startsWith("missing_registration") || code.equals("missing_state_pack");
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
                    exceptionRows.add(new Object[] {ctx.tenantId(), runId, who, code.contains(":") ? code.substring(0, code.indexOf(':')) : code,
                            x.get("level"), x.get("acceptable"), x.get("message"), x.get("link"), was == null ? null : was.get("acceptedBy"),
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
            Sql.batch(c, "insert into app.run_exception (tenant_id, run_id, employment_id, code, level, acceptable, message, link, "
                    + "accepted_by, accepted_reason, accepted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?::timestamptz)", exceptionRows);
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
            if (!"calculated".equals(run(c, runId).get("state"))) {
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
