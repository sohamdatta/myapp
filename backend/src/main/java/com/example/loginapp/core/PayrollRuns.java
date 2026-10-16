package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The monthly payroll run: open a period for a pay group, collect inputs, freeze everything into a
 * snapshot, calculate (as often as needed), review exceptions, approve (someone other than whoever
 * calculated), mark paid, release payslips. Also the outputs (register, bank file, journal, payslip
 * PDF) and each employee's My pay.
 */
public final class PayrollRuns {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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
                    + "pc.name as component_name, i.amount, i.note from app.run_input i join app.employment e on e.id = i.employment_id "
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
            r.put("inputComponents", Sql.query(c, "select pc.code, pc.name, pc.kind from app.pay_component pc join lateral (select amount_type "
                    + "from app.pay_component_version v where v.component_id = pc.id and v.valid_from <= ? order by valid_from desc limit 1) v on true "
                    + "where v.amount_type = 'input' and pc.retired_at is null order by pc.position", period.plusMonths(1).minusDays(1)));
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

    public Map<String, Object> addInput(SessionCtx ctx, UUID runId, InputInput in) {
        PayrollConfig.need(ctx, "payroll.prepare");
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw ApiException.badRequest("Type an amount above zero");
        }
        UUID employment = PayrollConfig.id(in.employmentId(), "employee");
        String code = PayrollConfig.upper(in.componentCode());
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
            Map<String, Object> pc = Sql.one(c, "select pc.name from app.pay_component pc join lateral (select amount_type from app.pay_component_version v "
                    + "where v.component_id = pc.id and v.valid_from <= ? order by valid_from desc limit 1) v on true where pc.code = ? "
                    + "and v.amount_type = 'input' and pc.retired_at is null", period.plusMonths(1).minusDays(1), code);
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
            for (Map<String, Object> emp : PayrollEngine.list(snapshot, "employees")) {
                PayrollEngine.Calc calc = PayrollEngine.calculate(config, emp, period);
                UUID employment = AuthService.uuid(emp.get("employmentId"));
                // Holds are read now, not from the snapshot: pay can be held after the inputs are frozen.
                boolean heldPay = Sql.one(c, "select 1 from app.pay_hold where run_id = ? and employment_id = ?", runId, employment) != null;
                if (calc.calculated()) {
                    boolean held = heldPay;
                    Map<String, Object> info = new LinkedHashMap<>(calc.info());
                    info.put("annual", emp.get("annual"));
                    info.put("structure", PayrollEngine.map(PayrollEngine.map(config, "structures"), (String) emp.get("structureId")).get("name"));
                    info.put("compensationFrom", emp.get("compensationFrom"));
                    info.put("proration", PayrollEngine.map(config, "settings").get("proration"));
                    UUID payslip = AuthService.uuid(Sql.one(c, "insert into app.payslip (tenant_id, run_id, employment_id, membership_id, days_in_period, "
                                    + "days_paid, lop_days, gross, deductions, net, employer_cost, held, info) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                                    + "returning id", ctx.tenantId(), runId, employment, emp.get("membershipId") == null ? null : AuthService.uuid(emp.get("membershipId")),
                            calc.daysInPeriod(), calc.daysPaid(), calc.lopDays(), calc.gross(), calc.deductions(), calc.net(), calc.employer(), held,
                            Json.write(info)).get("id"));
                    for (Map<String, Object> l : calc.lines()) {
                        Sql.update(c, "insert into app.payslip_line (tenant_id, payslip_id, position, code, name, kind, amount, unrounded, full_month, "
                                        + "rule_id, trace) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?::jsonb)", ctx.tenantId(), payslip,
                                (short) PayrollEngine.intOf(l.get("position")), l.get("code"), l.get("name"), l.get("kind"), l.get("amount"),
                                l.get("unrounded"), l.get("fullMonth"), l.get("ruleId"), Json.write(l.get("trace")));
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
                    Sql.update(c, "insert into app.run_exception (tenant_id, run_id, employment_id, code, level, acceptable, message, link, "
                                    + "accepted_by, accepted_reason, accepted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?::timestamptz)",
                            ctx.tenantId(), runId, who, code.contains(":") ? code.substring(0, code.indexOf(':')) : code, x.get("level"),
                            x.get("acceptable"), x.get("message"), x.get("link"), was == null ? null : was.get("acceptedBy"),
                            was == null ? null : was.get("acceptedReason"), was == null ? null : was.get("acceptedAt"));
                    if ("blocking".equals(x.get("level"))) {
                        blocking++;
                    } else {
                        warnings++;
                    }
                }
            }
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

    /** A payslip with its lines, explanations and why it changed from the one before. */
    static Map<String, Object> payslip(Connection c, UUID payslipId, boolean forPayroll) throws SQLException {
        Map<String, Object> ps = Sql.one(c, "select ps.id, ps.run_id, ps.employment_id, ps.days_in_period, ps.days_paid, ps.lop_days, ps.gross, "
                + "ps.deductions, ps.net, ps.employer_cost, ps.held, ps.info, r.period, r.state, r.paid_on from app.payslip ps "
                + "join app.payroll_run r on r.id = ps.run_id where ps.id = ?", payslipId);
        if (ps == null) {
            throw ApiException.notFound("The payslip");
        }
        ps.put("info", parse(ps.get("info")));
        LocalDate period = LocalDate.parse((String) ps.get("period"));
        ps.put("label", monthWords(period));
        List<Map<String, Object>> lines = Sql.query(c, "select l.code, l.name, l.kind, l.amount, l.unrounded, l.full_month, l.trace, "
                + "r.status as rule_status from app.payslip_line l left join app.payroll_rule r on r.id = l.rule_id where l.payslip_id = ? "
                + "order by l.position, l.code", payslipId);
        for (Map<String, Object> l : lines) {
            Map<String, Object> trace = parse(l.get("trace"));
            if (!forPayroll) {
                // Employees see the reason in words, not the working of formulas and rounding.
                Map<String, Object> plain = new LinkedHashMap<>();
                for (String k : List.of("prorated", "working")) {
                    if (trace.get(k) != null) {
                        plain.put(k, trace.get(k));
                    }
                }
                trace = plain;
                l.remove("unrounded");
                l.remove("ruleStatus");
            }
            l.put("trace", trace);
        }
        ps.put("lines", lines);
        Map<String, Object> ytd = Sql.one(c, "select coalesce(sum(ps.gross), 0) as gross, coalesce(sum(ps.deductions), 0) as deductions, "
                + "coalesce(sum(ps.net), 0) as net from app.payslip ps join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ?::uuid "
                + "and r.state in ('approved','paid','released') and r.period between ? and ?", ps.get("employmentId"), taxYearStart(period), period);
        ps.put("ytd", ytd);
        ps.put("changes", whyChanged(c, ps, lines));
        return ps;
    }

    /** Each difference from the previous approved payslip, with its cause, largest first. */
    static Map<String, Object> whyChanged(Connection c, Map<String, Object> ps, List<Map<String, Object>> lines) throws SQLException {
        Map<String, Object> before = Sql.one(c, "select ps.id, ps.days_paid, ps.days_in_period, ps.net, ps.info, r.period from app.payslip ps "
                + "join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ?::uuid and r.period < ?::date and r.state in ('approved','paid','released') "
                + "order by r.period desc limit 1", ps.get("employmentId"), ps.get("period"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (before == null) {
            out.put("first", true);
            return out;
        }
        Map<String, Object> infoBefore = parse(before.get("info"));
        Map<String, Object> infoNow = PayrollEngine.map(ps, "info");
        Map<String, BigDecimal> was = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> l : Sql.query(c, "select code, name, kind, amount from app.payslip_line where payslip_id = ?::uuid and kind in "
                + "('earning','deduction')", before.get("id"))) {
            was.put((String) l.get("code"), signed(l));
            names.put((String) l.get("code"), (String) l.get("name"));
        }
        Map<String, BigDecimal> now = new HashMap<>();
        Map<String, Boolean> oneTime = new HashMap<>();
        for (Map<String, Object> l : lines) {
            if (Set.of("earning", "deduction").contains(l.get("kind"))) {
                now.put((String) l.get("code"), signed(l));
                names.put((String) l.get("code"), (String) l.get("name"));
                oneTime.put((String) l.get("code"), "Entered for this month".equals(PayrollEngine.map(l, "trace").get("source")));
            }
        }
        boolean daysChanged = PayrollEngine.dec(ps, "daysPaid").compareTo(PayrollEngine.dec(before, "daysPaid")) != 0
                || PayrollEngine.dec(ps, "daysInPeriod").compareTo(PayrollEngine.dec(before, "daysInPeriod")) != 0;
        boolean revised = PayrollEngine.dec(infoNow, "annual").compareTo(PayrollEngine.dec(infoBefore, "annual")) != 0;
        Map<String, BigDecimal> causes = new LinkedHashMap<>();
        Set<String> codes = new HashSet<>(was.keySet());
        codes.addAll(now.keySet());
        for (String code : codes) {
            BigDecimal d = now.getOrDefault(code, BigDecimal.ZERO).subtract(was.getOrDefault(code, BigDecimal.ZERO));
            if (d.signum() == 0) {
                continue;
            }
            String cause;
            if (Boolean.TRUE.equals(oneTime.get(code)) || (!now.containsKey(code) && was.containsKey(code) && !Set.of("PT", "LWF_EE", "TDS", "PF_EE", "ESI_EE")
                    .contains(code))) {
                cause = names.get(code) + (now.containsKey(code) ? " this month" : " last month, not this month");
            } else if (code.equals("TDS")) {
                cause = "Income tax re-worked for the year";
            } else if (code.equals("PT")) {
                cause = "Professional tax";
            } else if (code.equals("LWF_EE")) {
                cause = "Labour welfare fund, due in some months only";
            } else if (Set.of("PF_EE", "ESI_EE").contains(code)) {
                cause = "PF and ESI follow the wages";
            } else if (revised) {
                cause = "Pay revised from " + infoNow.get("compensationFrom");
            } else if (daysChanged) {
                cause = PayrollEngine.plain(PayrollEngine.dec(ps, "daysPaid")) + " of " + PayrollEngine.plain(PayrollEngine.dec(ps, "daysInPeriod"))
                        + " days paid (last month " + PayrollEngine.plain(PayrollEngine.dec(before, "daysPaid")) + " of "
                        + PayrollEngine.plain(PayrollEngine.dec(before, "daysInPeriod")) + ")";
            } else {
                cause = names.get(code) + " changed";
            }
            causes.merge(cause, d, BigDecimal::add);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : causes.entrySet()) {
            if (e.getValue().signum() != 0) {
                items.add(Map.of("cause", e.getKey(), "amount", e.getValue()));
            }
        }
        items.sort((a, b) -> ((BigDecimal) b.get("amount")).abs().compareTo(((BigDecimal) a.get("amount")).abs()));
        out.put("previousLabel", monthWords(LocalDate.parse((String) before.get("period"))));
        out.put("previousNet", before.get("net"));
        out.put("difference", PayrollEngine.dec(ps, "net").subtract(PayrollEngine.dec(before, "net")));
        out.put("items", items);
        return out;
    }

    private static BigDecimal signed(Map<String, Object> l) {
        BigDecimal a = PayrollEngine.dec(l, "amount");
        return "deduction".equals(l.get("kind")) ? a.negate() : a;
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

    private static DocumentService.Content csv(String name, List<List<String>> rows) {
        StringBuilder out = new StringBuilder("﻿");
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String v : row) {
                String s = v == null ? "" : v;
                if (s.startsWith("=") || s.startsWith("+") || s.startsWith("@")) {
                    s = "'" + s;   // never a formula when opened in a spreadsheet
                }
                cells.add(s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
            }
            out.append(String.join(",", cells)).append("\r\n");
        }
        return new DocumentService.Content("text/csv; charset=utf-8", name, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    static List<List<String>> register(Connection c, UUID runId) throws SQLException {
        List<Map<String, Object>> slips = Sql.query(c, "select id, info, days_in_period, days_paid, lop_days, gross, deductions, net, employer_cost, held "
                + "from app.payslip where run_id = ? order by lower(info->>'name')", runId);
        List<Map<String, Object>> columns = Sql.query(c, "select distinct l.code, l.name, l.kind, min(l.position) as position from app.payslip_line l "
                + "join app.payslip ps on ps.id = l.payslip_id where ps.run_id = ? group by l.code, l.name, l.kind order by min(l.position)", runId);
        List<List<String>> rows = new ArrayList<>();
        List<String> head = new ArrayList<>(List.of("Employee code", "Name", "Designation", "Department", "State", "Days in month", "Days paid",
                "Loss of pay"));
        for (Map<String, Object> col : columns) {
            head.add(col.get("name") + ("employer".equals(col.get("kind")) ? " (employer)" : ""));
        }
        head.addAll(List.of("Gross", "Deductions", "Net pay", "Employer cost", "Held"));
        rows.add(head);
        for (Map<String, Object> s : slips) {
            Map<String, Object> info = parse(s.get("info"));
            Map<String, String> amounts = new HashMap<>();
            for (Map<String, Object> l : Sql.query(c, "select code, amount from app.payslip_line where payslip_id = ?::uuid", s.get("id"))) {
                amounts.put((String) l.get("code"), money(l.get("amount")));
            }
            List<String> row = new ArrayList<>(List.of(PayrollEngine.str(info.get("code")), PayrollEngine.str(info.get("name")),
                    PayrollEngine.str(info.get("designation")), PayrollEngine.str(info.get("department")), PayrollEngine.str(info.get("state")),
                    PayrollEngine.plain(PayrollEngine.dec(s, "daysInPeriod")), PayrollEngine.plain(PayrollEngine.dec(s, "daysPaid")),
                    PayrollEngine.plain(PayrollEngine.dec(s, "lopDays"))));
            for (Map<String, Object> col : columns) {
                row.add(amounts.getOrDefault((String) col.get("code"), "0.00"));
            }
            BigDecimal employerOnly = PayrollEngine.dec(s, "employerCost");
            row.addAll(List.of(money(s.get("gross")), money(s.get("deductions")), money(s.get("net")),
                    money(PayrollEngine.dec(s, "gross").add(employerOnly)), Boolean.TRUE.equals(s.get("held")) ? "Yes" : ""));
            rows.add(row);
        }
        return rows;
    }

    /**
     * A bulk payment file for HDFC Bank: one row per employee paid, with what a bulk salary upload carries.
     * The exact column order and header follow the customer's HDFC corporate banking layout, which we
     * still need; until then this is a column-mapped CSV with a control total in the last row.
     */
    static List<List<String>> bankFile(Connection c, Crypto crypto, UUID tenantId, UUID runId, LocalDate period, LocalDate valueDate)
            throws SQLException {
        Map<String, Object> s = Sql.one(c, "select debit_account, entity_name from app.payroll_settings");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Transaction type", "Beneficiary account number", "IFSC", "Beneficiary name", "Amount", "Value date", "Narration",
                "Employee code", "Debit account"));
        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        String narration = "Salary " + period.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));
        // Sql leaves bytea out on purpose; the sealed account numbers are read here and opened one by one.
        try (var st = c.prepareStatement("select ps.net, ps.info->>'code' as code, b.holder_name, b.account_enc, b.ifsc "
                + "from app.payslip ps join app.employment e on e.id = ps.employment_id join lateral (select * from app.bank_account b "
                + "where b.person_id = e.person_id and b.effective_from <= ? order by b.effective_from desc, b.created_at desc limit 1) b on true "
                + "where ps.run_id = ? and not ps.held and ps.net > 0 order by lower(ps.info->>'name')")) {
            st.setObject(1, period.plusMonths(1).minusDays(1));
            st.setObject(2, runId);
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    String ifsc = rs.getString("ifsc");
                    String account = crypto.decryptText(tenantId, rs.getBytes("account_enc"));
                    BigDecimal net = rs.getBigDecimal("net");
                    rows.add(List.of(ifsc.startsWith("HDFC") ? "I" : "N", account, ifsc, rs.getString("holder_name"), money(net),
                            valueDate.format(DMY), narration, PayrollEngine.str(rs.getString("code")), PayrollEngine.str(s.get("debitAccount"))));
                    count++;
                    total = total.add(net);
                }
            }
        }
        rows.add(List.of("Total", count + " payments", "", "", money(total), "", "", "", ""));
        return rows;
    }

    /** Debits for pay and employer contributions, credits for what is payable, by ledger and department. */
    static List<List<String>> journal(Connection c, UUID runId, LocalDate period) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Date", "Ledger", "Cost centre", "Debit", "Credit", "Narration"));
        String narration = "Payroll " + monthWords(period);
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Map<String, Object> g : Sql.query(c, "select l.kind, l.code, l.name, coalesce(ps.info->>'department', 'No department') as dept, "
                + "sum(l.amount) as amount, (select v.ledger_code from app.pay_component pc join app.pay_component_version v on v.component_id = pc.id "
                + "  where pc.code = l.code and v.valid_from <= ? order by v.valid_from desc limit 1) as ledger "
                + "from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id where ps.run_id = ? "
                + "group by l.kind, l.code, l.name, dept order by min(l.position), dept", end, runId)) {
            String kind = (String) g.get("kind");
            BigDecimal a = PayrollEngine.dec(g, "amount");
            String ledger = g.get("ledger") == null ? (String) g.get("name") : (String) g.get("ledger");
            switch (kind) {
                case "earning" -> {
                    rows.add(List.of(end.format(DMY), ledger, (String) g.get("dept"), money(a), "", narration));
                    debits = debits.add(a);
                }
                case "employer" -> {
                    rows.add(List.of(end.format(DMY), ledger + " expense", (String) g.get("dept"), money(a), "", narration));
                    rows.add(List.of(end.format(DMY), ledger + " payable", (String) g.get("dept"), "", money(a), narration));
                    debits = debits.add(a);
                    credits = credits.add(a);
                }
                case "deduction" -> {
                    rows.add(List.of(end.format(DMY), ledger + " payable", (String) g.get("dept"), "", money(a), narration));
                    credits = credits.add(a);
                }
                default -> { }
            }
        }
        for (Map<String, Object> g : Sql.query(c, "select coalesce(info->>'department', 'No department') as dept, sum(net) as net from app.payslip "
                + "where run_id = ? group by dept order by dept", runId)) {
            rows.add(List.of(end.format(DMY), "Salary payable", (String) g.get("dept"), "", money(g.get("net")), narration));
            credits = credits.add(PayrollEngine.dec(g, "net"));
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalStateException("The payroll journal does not balance: " + debits + " against " + credits);
        }
        rows.add(List.of("", "Total", "", money(debits), money(credits), ""));
        return rows;
    }

    private static DocumentService.Content pdf(Connection c, UUID payslipId, String org) throws SQLException {
        Map<String, Object> ps = payslip(c, payslipId, false);
        Map<String, Object> s = Sql.one(c, "select entity_name, address from app.payroll_settings");
        byte[] bytes = PayslipPdf.render(s, org, ps);
        String month = LocalDate.parse((String) ps.get("period")).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return new DocumentService.Content("application/pdf", "Payslip " + month + ".pdf", bytes);
    }

    // ── the snapshot ────────────────────────────────────────────────────

    /** Who is in a pay group for a month: in the group on the month's last day, joined by then, not gone before it began. */
    static List<Map<String, Object>> members(Connection c, UUID groupId, LocalDate period) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        return Sql.query(c, "select e.id, coalesce(p.preferred_name, p.legal_name) as name, e.employee_code, j.designation "
                + "from app.pay_group_member m join app.employment e on e.id = m.employment_id join app.person p on p.id = e.person_id "
                + "left join lateral (select designation from app.job_assignment j where j.employment_id = e.id and j.effective_from <= ? "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "where m.group_id = ? and m.valid_from = (select max(m2.valid_from) from app.pay_group_member m2 where m2.employment_id = e.id "
                + "  and m2.valid_from <= ?) and e.joining_date <= ? and (e.status = 'active' or (e.status = 'exited' and e.exit_date >= ?)) "
                // Someone leaving before the month starts is settled by their exit, not paid in it.
                + "and not exists (select 1 from app.exit_case x where x.employment_id = e.id and x.status = 'accepted' and x.last_working_day < ?) "
                + "order by lower(coalesce(p.preferred_name, p.legal_name))", end, groupId, end, end, period, period);
    }

    static Map<String, Object> snapshot(Connection c, Crypto crypto, UUID groupId, UUID runId, LocalDate period, LocalDate today) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        Map<String, Object> config = PayrollConfig.load(c, end);
        List<Map<String, Object>> employees = new ArrayList<>();
        for (Map<String, Object> m : members(c, groupId, period)) {
            Map<String, Object> e = employee(c, config, AuthService.uuid(m.get("id")), period, today, runId, false);
            if (e != null) {
                employees.add(e);
            }
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("period", period.toString());
        snap.put("frozenOn", today.toString());
        snap.put("config", config);
        snap.put("employees", employees);
        @SuppressWarnings("unchecked")
        Map<String, Object> normal = (Map<String, Object>) Json.parse(Json.write(snap));
        return normal;
    }

    /**
     * Everything the engine needs about one employee for a month. With a run, inputs and holds come
     * from it; without one (a preview), the month is taken as fully paid unless leave says otherwise.
     */
    static Map<String, Object> employee(Connection c, Map<String, Object> config, UUID employmentId, LocalDate period, LocalDate today, UUID runId,
                                        boolean preview) throws SQLException {
        LocalDate end = period.plusMonths(1).minusDays(1);
        Map<String, Object> e = Sql.one(c, "select e.id, e.membership_id, e.person_id, e.employee_code, e.employment_type, e.joining_date, e.exit_date, "
                + "e.status, coalesce(p.preferred_name, p.legal_name) as name, p.legal_name, p.gender, j.designation, d.name as department "
                + "from app.employment e join app.person p on p.id = e.person_id "
                + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id and j.effective_from <= greatest(?::date, e.joining_date) "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true left join app.department d on d.id = j.department_id "
                + "where e.id = ?", end, employmentId);
        if (e == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employmentId", e.get("id"));
        out.put("membershipId", e.get("membershipId"));
        out.put("name", e.get("name"));
        out.put("code", e.get("employeeCode"));
        out.put("designation", e.get("designation"));
        out.put("department", e.get("department"));
        out.put("type", e.get("employmentType"));
        out.put("gender", e.get("gender"));
        LocalDate joined = LocalDate.parse((String) e.get("joiningDate"));
        out.put("joinedOn", joined.toString());
        LocalDate leaving = e.get("exitDate") == null ? null : LocalDate.parse((String) e.get("exitDate"));
        Map<String, Object> exit = Sql.one(c, "select last_working_day from app.exit_case where employment_id = ? and status = 'accepted' "
                + "and last_working_day is not null order by accepted_at desc limit 1", employmentId);
        if (leaving == null && exit != null) {
            leaving = LocalDate.parse((String) exit.get("lastWorkingDay"));
        }
        out.put("leavingOn", leaving == null ? null : leaving.toString());
        out.put("monthsOfService", ChronoUnit.MONTHS.between(joined, end.plusDays(1)));

        Map<String, Object> comp = Sql.one(c, "select structure_id, annual_amount, valid_from, reason from app.compensation where employment_id = ? "
                + "and superseded_at is null and valid_from <= ? order by valid_from desc limit 1", employmentId, end);
        out.put("structureId", comp == null ? null : comp.get("structureId"));
        out.put("annual", comp == null ? BigDecimal.ZERO : comp.get("annualAmount"));
        out.put("compensationFrom", comp == null ? null : comp.get("validFrom"));
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map<String, Object> o : Sql.query(c, "select component_code, monthly_amount, valid_from, reason from app.compensation_override "
                + "where employment_id = ? and removed_at is null and valid_from <= ? and (valid_to is null or valid_to >= ?) order by valid_from",
                employmentId, end, period)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("amount", o.get("monthlyAmount"));
            v.put("from", o.get("validFrom"));
            v.put("reason", o.get("reason"));
            overrides.put((String) o.get("componentCode"), v);
        }
        out.put("overrides", overrides);
        Map<String, Object> profile = Sql.one(c, "select pf_member, pf_full_wages, eps_eligible, esi_exempt, tax_regime, pt_exempt, lwf_exempt "
                + "from app.pay_profile where employment_id = ?", employmentId);
        out.put("profile", profile == null ? PayrollPay.defaultProfile((String) e.get("employmentType")) : profile);

        Map<String, Object> inputs = new LinkedHashMap<>();
        boolean held = false;
        if (runId != null) {
            for (Map<String, Object> i : Sql.query(c, "select component_code, sum(amount) as amount from app.run_input where run_id = ? "
                    + "and employment_id = ? group by component_code", runId, employmentId)) {
                inputs.put((String) i.get("componentCode"), i.get("amount"));
            }
            held = Sql.one(c, "select 1 from app.pay_hold where run_id = ? and employment_id = ?", runId, employmentId) != null;
        }
        out.put("inputs", inputs);
        out.put("held", held);

        // Where they work: the state drives PT, LWF and the metro HRA.
        UUID membership = e.get("membershipId") == null ? null : AuthService.uuid(e.get("membershipId"));
        LocalDate lastDay = leaving != null && leaving.isBefore(end) ? leaving : end;
        AttendanceService.Assigned a = membership == null ? null : AttendanceService.assigned(c, membership, lastDay);
        if (a != null) {
            Map<String, Object> loc = Sql.one(c, "select state_code from app.work_location where id = ?", a.locationId());
            out.put("location", a.locationName());
            out.put("state", loc == null ? null : loc.get("stateCode"));
            List<?> metro = (List<?>) PayrollEngine.map(config, "settings").getOrDefault("metroLocations", List.of());
            out.put("metro", metro.stream().anyMatch(x -> String.valueOf(x).equals(a.locationId().toString())));
        } else {
            out.put("location", null);
            out.put("state", null);
            out.put("metro", false);
        }

        out.put("days", days(c, config, membership, a, joined, leaving, period, today, preview));

        // Identity and bank, for the checks and the payslip (last four only).
        UUID person = AuthService.uuid(e.get("personId"));
        Map<String, Object> pan = Sql.one(c, "select last4 from app.statutory_id where person_id = ? and kind = 'pan' and replaced_at is null "
                + "and pending_reason is null limit 1", person);
        Map<String, Object> uan = Sql.one(c, "select last4 from app.statutory_id where person_id = ? and kind = 'uan' and replaced_at is null limit 1", person);
        Map<String, Object> bank = Sql.one(c, "select account_last4 from app.bank_account where person_id = ? and effective_from <= greatest(?::date, ?::date) "
                + "order by effective_from desc, created_at desc limit 1", person, end, joined);
        out.put("hasPan", pan != null);
        out.put("panLast4", pan == null ? null : pan.get("last4"));
        out.put("hasUan", uan != null);
        out.put("uanLast4", uan == null ? null : uan.get("last4"));
        out.put("hasBank", bank != null);
        out.put("bankLast4", bank == null ? null : bank.get("accountLast4"));

        // This tax year so far, from approved months (imported history comes with the switching kit).
        LocalDate yearStart = taxYearStart(period);
        Map<String, Object> ytd = new LinkedHashMap<>();
        BigDecimal taxable = BigDecimal.ZERO;
        for (Map<String, Object> p : Sql.query(c, "select ps.info from app.payslip ps join app.payroll_run r on r.id = ps.run_id "
                + "where ps.employment_id = ? and r.state in ('approved','paid','released') and r.period >= ? and r.period < ?", employmentId, yearStart, period)) {
            taxable = taxable.add(PayrollEngine.dec(PayrollEngine.map(parse(p.get("info")), "bases"), "taxable"));
        }
        ytd.put("taxable", taxable);
        Map<String, Object> byCode = new LinkedHashMap<>();
        for (Map<String, Object> l : Sql.query(c, "select l.code, sum(l.amount) as amount from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                + "join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ? and r.state in ('approved','paid','released') "
                + "and r.period >= ? and r.period < ? group by l.code", employmentId, yearStart, period)) {
            byCode.put((String) l.get("code"), l.get("amount"));
        }
        ytd.put("components", byCode);
        ytd.put("tds", byCode.getOrDefault("TDS", BigDecimal.ZERO));
        ytd.put("pt", byCode.getOrDefault("PT", BigDecimal.ZERO));
        ytd.put("pfEe", byCode.getOrDefault("PF_EE", BigDecimal.ZERO));
        out.put("ytd", ytd);

        // ESI coverage carries to the end of the contribution period (April to September, October to March).
        LocalDate esiStart = period.getMonthValue() >= 10 ? period.withMonth(10)
                : period.getMonthValue() >= 4 ? period.withMonth(4) : period.minusYears(1).withMonth(10);
        out.put("esiCoveredInPeriod", Sql.one(c, "select 1 from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                + "join app.payroll_run r on r.id = ps.run_id where ps.employment_id = ? and r.state in ('approved','paid','released') "
                + "and r.period >= ? and r.period < ? and l.code in ('ESI_EE','ESI_ER') and l.amount > 0 limit 1", employmentId, esiStart, period) != null);

        Map<String, Object> previous = Sql.one(c, "select ps.net from app.payslip ps join app.payroll_run r on r.id = ps.run_id "
                + "where ps.employment_id = ? and r.state in ('approved','paid','released') and r.period < ? order by r.period desc limit 1", employmentId, period);
        out.put("previous", previous == null ? Map.of() : previous);
        return out;
    }

    /**
     * Days in the month, days paid and loss of pay. Unpaid leave counts as loss of pay; absence
     * without leave too when the Organization says so. Days not yet come count as worked.
     */
    static Map<String, Object> days(Connection c, Map<String, Object> config, UUID membership, AttendanceService.Assigned a, LocalDate joined,
                                    LocalDate leaving, LocalDate period, LocalDate today, boolean preview) throws SQLException {
        Map<String, Object> settings = PayrollEngine.map(config, "settings");
        String method = String.valueOf(settings.getOrDefault("proration", "calendar"));
        boolean absentIsLop = Boolean.TRUE.equals(settings.get("absenceIsLop"));
        LocalDate end = period.plusMonths(1).minusDays(1);
        LocalDate from = joined.isAfter(period) ? joined : period;
        LocalDate to = leaving != null && leaving.isBefore(end) ? leaving : end;
        BigDecimal lop = BigDecimal.ZERO;
        if (membership != null && !to.isBefore(from)) {
            Map<String, Map<String, Object>> rows = new HashMap<>();
            if (!preview && !from.isAfter(today)) {
                for (Map<String, Object> d : AttendanceService.monthOf(c, (UUID) app(c), membership, java.time.YearMonth.from(period), today)) {
                    rows.put((String) d.get("workDate"), d);
                }
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                Map<String, Object> row = rows.get(d.toString());
                if (row != null) {
                    if (Boolean.TRUE.equals(row.get("leaveUnpaid"))) {
                        lop = lop.add(PayrollEngine.dec(row, "leaveDays"));
                    } else if (absentIsLop && "absent".equals(row.get("status"))) {
                        lop = lop.add(BigDecimal.ONE);
                    } else if (absentIsLop && "half_day".equals(row.get("status")) && PayrollEngine.dec(row, "leaveDays").signum() == 0) {
                        lop = lop.add(new BigDecimal("0.5"));
                    }
                } else {
                    AttendanceService.Assigned at = AttendanceService.assigned(c, membership, d);
                    boolean off = at != null && AttendanceService.dayOff(c, at, d) != null;
                    DayCalculator.Leave leave = LeaveService.leaveOn(c, membership, d, off);
                    if (leave != null && leave.unpaid()) {
                        lop = lop.add(leave.half() == null ? BigDecimal.ONE : new BigDecimal("0.5"));
                    }
                }
            }
        }
        int inMonth = end.getDayOfMonth();
        long notEmployed = inMonth - (to.isBefore(from) ? 0 : ChronoUnit.DAYS.between(from, to) + 1);
        BigDecimal base;
        BigDecimal paid;
        String used = method;
        if (method.equals("working") && a != null) {
            int all = 0;
            int employed = 0;
            for (LocalDate d = period; !d.isAfter(end); d = d.plusDays(1)) {
                AttendanceService.Assigned at = AttendanceService.assigned(c, membership, d);
                boolean off = at == null || AttendanceService.dayOff(c, at, d) != null;
                if (!off) {
                    all++;
                    if (!d.isBefore(from) && !d.isAfter(to)) {
                        employed++;
                    }
                }
            }
            base = BigDecimal.valueOf(all);
            paid = BigDecimal.valueOf(employed).subtract(lop);
        } else if (method.equals("fixed_30")) {
            base = BigDecimal.valueOf(30);
            paid = BigDecimal.valueOf(30).subtract(BigDecimal.valueOf(notEmployed)).subtract(lop);
        } else {
            used = "calendar";
            base = BigDecimal.valueOf(inMonth);
            paid = base.subtract(BigDecimal.valueOf(notEmployed)).subtract(lop);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", used);
        out.put("base", base);
        out.put("paid", paid.max(BigDecimal.ZERO));
        out.put("lop", lop);
        out.put("notEmployed", notEmployed);
        return out;
    }

    /** The Organization being worked in, for helpers that take its id. */
    private static Object app(Connection c) throws SQLException {
        return AuthService.uuid(Sql.one(c, "select app.current_tenant() as t").get("t"));
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
