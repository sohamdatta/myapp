package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.example.loginapp.core.PayrollRuns.parse;

/**
 * The monthly statutory returns made from approved payroll: the PF ECR text file and the ESI contribution
 * file. Each is built from the approved runs of the month, checked, stored encrypted as a numbered version,
 * downloaded, and then the filing on the EPFO or ESIC portal is recorded (reference, date, amount) by one
 * person and approved by another.
 *
 * Every rate, ceiling, separator and reason code comes from the period's rule row; nothing statutory is
 * written in this class.
 */
public final class StatutoryFilings {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern UAN = Pattern.compile("[0-9]{12}");
    private static final Pattern ESI_IP = Pattern.compile("[0-9]{10}");
    private static final Set<String> KINDS = Set.of("pf_ecr", "esi");

    public record RecordInput(String referenceNumber, String paidOn, BigDecimal amount) {}

    public record ReasonRow(String exitKind, int esicCode) {}

    /** A finding from the checks run on a file: blocking until fixed, or a warning. */
    public record Check(String code, String level, String who, String message) {
        boolean blocking() {
            return "blocking".equals(level);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("level", level);
            m.put("who", who);
            m.put("message", message);
            return m;
        }
    }

    /** What a file adds up to. The fields that do not apply to a return are null. */
    public record Totals(int members, BigDecimal employeeShare, BigDecimal pension, BigDecimal employerShare, BigDecimal wages,
                         BigDecimal contributions, BigDecimal runTotal) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("members", members);
            m.put("employeeShare", employeeShare);
            m.put("pension", pension);
            m.put("employerShare", employerShare);
            m.put("wages", wages);
            m.put("contributions", contributions);
            m.put("runTotal", runTotal);
            return m;
        }

        static Totals of(Map<String, Object> m) {
            return new Totals(m.get("members") == null ? 0 : ((Number) m.get("members")).intValue(), num(m, "employeeShare"), num(m, "pension"),
                    num(m, "employerShare"), num(m, "wages"), num(m, "contributions"), num(m, "runTotal"));
        }

        private static BigDecimal num(Map<String, Object> m, String key) {
            return m.get(key) == null ? null : new BigDecimal(String.valueOf(m.get(key)));
        }
    }

    /** One built version of a return, as the filing log shows it. */
    public record Filing(String id, int version, String status, String fileName, Totals totals,
                         List<Check> checks, String generatedAt, String downloadedAt, String referenceNumber,
                         String paidOn, BigDecimal amountPaid, String recordedAt, String approvedAt, Long daysLate) {}

    /** A return for the month (PF or ESI), its registration, when it falls due, and its versions, newest first. */
    public record Slot(String kind, String registration, String dueOn, List<Filing> versions) {}

    public record MonthView(String period, List<Slot> filings) {}

    public record BuildResult(String id, int version, Totals totals, List<Check> checks, int blocking) {}

    public record Status(String id, String status) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public StatutoryFilings(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public StatutoryFilings(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    // ── the month grid ──────────────────────────────────────────────────

    /** The registrations and the filings for a month, newest version first, with days late once filed. */
    public MonthView month(SessionCtx ctx, String month) {
        needRead(ctx);
        LocalDate period = period(month);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Slot> slots = new ArrayList<>();
            for (String kind : List.of("pf_ecr", "esi")) {
                Map<String, Object> reg = Sql.one(c, "select number from app.payroll_registration where statute = ?", statute(kind));
                LocalDate due = due(period, rule(c, statute(kind), period));
                List<Filing> versions = new ArrayList<>();
                for (Map<String, Object> f : Sql.query(c, "select id, version, status, file_name, totals, checks, generated_at, downloaded_at, "
                        + "reference_number, paid_on, amount_paid, recorded_at, approved_at from app.statutory_filing "
                        + "where kind = ? and period = ? order by version desc", kind, period)) {
                    Long late = f.get("paidOn") == null ? null
                            : Math.max(0, ChronoUnit.DAYS.between(due, LocalDate.parse(String.valueOf(f.get("paidOn")))));
                    versions.add(new Filing(String.valueOf(f.get("id")), ((Number) f.get("version")).intValue(), (String) f.get("status"),
                            (String) f.get("fileName"), Totals.of(parse(f.get("totals"))), checksOf(f.get("checks")), text(f.get("generatedAt")),
                            text(f.get("downloadedAt")), (String) f.get("referenceNumber"), text(f.get("paidOn")),
                            f.get("amountPaid") == null ? null : new BigDecimal(String.valueOf(f.get("amountPaid"))),
                            text(f.get("recordedAt")), text(f.get("approvedAt")), late));
                }
                slots.add(new Slot(kind, reg == null ? null : (String) reg.get("number"), due.toString(), versions));
            }
            return new MonthView(period.toString(), slots);
        });
    }

    // ── build and check ─────────────────────────────────────────────────

    /** Builds the next version of a return for the month from its approved runs. The checks are stored with it. */
    public BuildResult build(SessionCtx ctx, String kind, String month) {
        PayrollConfig.need(ctx, "payroll.prepare");
        kind(kind);
        LocalDate period = period(month);
        return db.inTenant(ctx.tenantId(), c -> {
            String statute = statute(kind);
            Map<String, Object> reg = Sql.one(c, "select number from app.payroll_registration where statute = ?", statute);
            if (reg == null) {
                throw new ApiException(409, "filing.no_registration", "Add the " + (kind.equals("esi") ? "ESI" : "PF")
                        + " registration under payroll settings first");
            }
            String registration = (String) reg.get("number");
            List<Map<String, Object>> approved = Sql.query(c, "select id, state from app.payroll_run where period = ? "
                    + "and state in ('approved','paid','released') order by created_at", period);
            List<Map<String, Object>> open = Sql.query(c, "select id from app.payroll_run where period = ? "
                    + "and state not in ('approved','paid','released')", period);
            if (approved.isEmpty()) {
                throw new ApiException(409, "filing.no_run", "There is no approved payroll run for that month");
            }
            List<UUID> runIds = approved.stream().map(r -> AuthService.uuid(r.get("id"))).toList();
            Map<String, Object> rule = rule(c, statute, period);
            if (rule == null) {
                throw new ApiException(409, "filing.no_rule", "There is no " + statute + " rule in force for that month");
            }
            Draft built = kind.equals("pf_ecr") ? ecr(c, runIds, period, rule) : esi(c, runIds, period, rule, ctx.tenantId());
            if (!open.isEmpty()) {
                built.checks.add(check("run_open", "blocking", null, open.size() + " payroll run(s) for this month are not approved yet; "
                        + "their people are missing from the file."));
            }
            if ("draft".equals(rule.get("status"))) {
                built.checks.add(check("rules_draft", "warning", null,
                        "The " + statute + " rules used are still drafts awaiting the payroll consultant's sign-off."));
            }
            Map<String, Object> last = Sql.one(c, "select coalesce(max(version), 0) as v from app.statutory_filing "
                    + "where kind = ? and registration = ? and period = ?", kind, registration, period);
            int version = ((Number) last.get("v")).intValue() + 1;
            String name = (kind.equals("pf_ecr") ? "PF ECR " : "ESI contribution ") + YearMonth.from(period) + " v" + version
                    + (kind.equals("pf_ecr") ? ".txt" : ".csv");
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.statutory_filing (tenant_id, kind, registration, period, version, file_name, "
                            + "file_enc, totals, checks, run_ids, generated_by) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) returning id",
                    ctx.tenantId(), kind, registration, period, version, name, crypto.encrypt(ctx.tenantId(), built.file),
                    Json.write(built.totals.toMap()), Json.write(built.checks.stream().map(Check::toMap).toList()), runIds.toArray(new UUID[0]), ctx.membershipId()).get("id"));
            UserService.audit(c, ctx, "filing.built", "statutory_filing", id,
                    Map.of("kind", kind, "period", period.toString(), "version", version, "blocking", blocking(built.checks)));
            return new BuildResult(id.toString(), version, built.totals, built.checks, blocking(built.checks));
        });
    }

    /** The file itself. Refused while a blocking check stands, and a fresh two-step code is needed: it holds full UANs and ESI numbers. */
    public DocumentService.Content download(SessionCtx ctx, UUID id) {
        PayrollConfig.need(ctx, "payroll.prepare");
        StepUp.require(db, ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> f = Sql.one(c, "select id, kind, status, file_name, checks from app.statutory_filing where id = ?", id);
            if (f == null) {
                throw ApiException.notFound("That filing");
            }
            if (blocking(checksOf(f.get("checks"))) > 0) {
                throw new ApiException(409, "filing.blocked", "Fix the blocking checks and build the file again");
            }
            byte[] sealed = bytes(c, "select file_enc from app.statutory_filing where id = ?", id);
            byte[] file = crypto.decrypt(ctx.tenantId(), sealed);
            Sql.update(c, "update app.statutory_filing set downloaded_at = coalesce(downloaded_at, now()), "
                    + "status = case when status = 'draft' then 'downloaded' else status end where id = ?", id);
            UserService.audit(c, ctx, "filing.downloaded", "statutory_filing", id, Map.of("kind", f.get("kind")));
            boolean text = "pf_ecr".equals(f.get("kind"));
            return new DocumentService.Content(text ? "text/plain; charset=utf-8" : "text/csv; charset=utf-8", (String) f.get("fileName"), file);
        });
    }

    // ── the filing log ──────────────────────────────────────────────────

    /** After uploading on the portal: who paid, the reference, the date and the amount. A second person approves it. */
    public Status record(SessionCtx ctx, UUID id, RecordInput in) {
        PayrollConfig.need(ctx, "payroll.prepare");
        if (in == null || in.referenceNumber() == null || in.referenceNumber().isBlank() || in.paidOn() == null || in.amount() == null
                || in.amount().signum() < 0) {
            throw ApiException.badRequest("Give the reference number, the date paid and the amount");
        }
        LocalDate paidOn = date(in.paidOn());
        if (paidOn.isAfter(LocalDate.now(clock.withZone(IST)))) {
            throw ApiException.badRequest("The date paid cannot be in the future");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> f = Sql.one(c, "select status from app.statutory_filing where id = ? for update", id);
            if (f == null) {
                throw ApiException.notFound("That filing");
            }
            if (!"downloaded".equals(f.get("status"))) {
                throw new ApiException(409, "filing.state", "Only a downloaded file can be recorded as filed");
            }
            Sql.update(c, "update app.statutory_filing set status = 'recorded', reference_number = ?, paid_on = ?, amount_paid = ?, "
                    + "recorded_by = ?, recorded_at = now() where id = ?", in.referenceNumber().trim(), paidOn,
                    in.amount().setScale(2, RoundingMode.HALF_UP), ctx.membershipId(), id);
            UserService.audit(c, ctx, "filing.recorded", "statutory_filing", id,
                    Map.of("reference", in.referenceNumber().trim(), "paidOn", paidOn.toString(), "amount", in.amount()));
            return new Status(id.toString(), "recorded");
        });
    }

    /** Maker-checker: the person who recorded the filing cannot approve it. */
    public Status approve(SessionCtx ctx, UUID id) {
        PayrollConfig.need(ctx, "payroll.approve");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> f = Sql.one(c, "select status, recorded_by from app.statutory_filing where id = ? for update", id);
            if (f == null) {
                throw ApiException.notFound("That filing");
            }
            if (!"recorded".equals(f.get("status"))) {
                throw new ApiException(409, "filing.state", "Only a recorded filing can be approved");
            }
            if (ctx.membershipId() != null && ctx.membershipId().toString().equals(String.valueOf(f.get("recordedBy")))) {
                throw new ApiException(409, "filing.same_person", "Someone other than the person who recorded the filing must approve it");
            }
            Sql.update(c, "update app.statutory_filing set status = 'filed', approved_by = ?, approved_at = now() where id = ?",
                    ctx.membershipId(), id);
            UserService.audit(c, ctx, "filing.approved", "statutory_filing", id, Map.of());
            return new Status(id.toString(), "filed");
        });
    }

    // ── ESIC reason codes ───────────────────────────────────────────────

    /** The code ESIC is given for a member who left, by how they left: the Organization's choice, else the rule's default. */
    public List<ReasonRow> reasonMap(SessionCtx ctx) {
        needRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> rule = rule(c, "esi", LocalDate.now(clock.withZone(IST)).withDayOfMonth(1));
            Map<String, Object> defaults = PayrollEngine.map(PayrollEngine.map(rule, "data"), "exitReason");
            Map<String, Integer> merged = new LinkedHashMap<>();
            defaults.forEach((k, v) -> merged.put(k, ((Number) v).intValue()));
            for (Map<String, Object> r : Sql.query(c, "select exit_kind, esic_code from app.esi_reason_map")) {
                merged.put((String) r.get("exitKind"), ((Number) r.get("esicCode")).intValue());
            }
            return merged.entrySet().stream().map(e -> new ReasonRow(e.getKey(), e.getValue())).toList();
        });
    }

    public List<ReasonRow> setReason(SessionCtx ctx, String exitKind, int code) {
        PayrollConfig.need(ctx, "payroll.prepare");
        if (code < 0 || code > 13) {
            throw ApiException.badRequest("ESIC reason codes run from 0 to 13");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Sql.update(c, "insert into app.esi_reason_map (tenant_id, exit_kind, esic_code) values (?, ?, ?) "
                    + "on conflict (tenant_id, exit_kind) do update set esic_code = excluded.esic_code", ctx.tenantId(), exitKind, code);
            UserService.audit(c, ctx, "filing.esi_reason_set", "esi_reason_map", ctx.tenantId(), Map.of("exitKind", exitKind, "code", code));
            return null;
        });
        return reasonMap(ctx);
    }

    // ── the PF ECR file ─────────────────────────────────────────────────

    private record Draft(byte[] file, Totals totals, List<Check> checks) {}

    private Draft ecr(Connection c, List<UUID> runIds, LocalDate period, Map<String, Object> rule) throws SQLException {
        Map<String, Object> pf = PayrollEngine.map(rule, "data");
        String separator = String.valueOf(pf.get("ecrSeparator"));
        BigDecimal epsCeiling = PayrollEngine.dec(pf, "epsCeiling");
        BigDecimal edliCeiling = PayrollEngine.dec(pf, "edliCeiling");
        boolean ncpUp = !"down".equals(pf.get("ncpHalfDay"));
        LocalDate end = period.plusMonths(1).minusDays(1);
        List<Check> checks = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        BigDecimal ee = BigDecimal.ZERO;
        BigDecimal eps = BigDecimal.ZERO;
        BigDecimal er = BigDecimal.ZERO;
        BigDecimal runEe = BigDecimal.ZERO;
        BigDecimal runEr = BigDecimal.ZERO;
        int members = 0;
        for (Slip s : slips(c, runIds, Set.of("PF_EE", "PF_ER"))) {
            Line lineEe = s.lines.get("PF_EE");
            Line lineEr = s.lines.get("PF_ER");
            if (lineEe == null && lineEr == null) {
                continue;
            }
            BigDecimal amountEe = lineEe == null ? BigDecimal.ZERO : lineEe.amount;
            BigDecimal amountEr = lineEr == null ? BigDecimal.ZERO : lineEr.amount;
            runEe = runEe.add(amountEe);
            runEr = runEr.add(amountEr);
            String who = s.name;
            String uan = secret(c, s.personId, "uan", s.tenantId);
            boolean ok = true;
            if (uan == null || !UAN.matcher(uan).matches()) {
                checks.add(check("uan_invalid", "blocking", who, who + " has no valid 12-digit UAN; PF is deducted and the line would be rejected."));
                ok = false;
            }
            if (s.name == null || s.name.isBlank()) {
                checks.add(check("name_missing", "blocking", who, "A member has no name."));
                ok = false;
            }
            BigDecimal gross = whole(s.gross);
            BigDecimal epfWages = whole(lineEe != null ? lineEe.num("epfWages") : lineEr.num("epfWages"));
            BigDecimal epsWages = whole(lineEr == null ? BigDecimal.ZERO : lineEr.num("epsWages"));
            BigDecimal edliWages = epfWages.min(edliCeiling);
            BigDecimal memberEps = whole(lineEr == null ? BigDecimal.ZERO : lineEr.num("eps"));
            BigDecimal memberEe = whole(amountEe);
            BigDecimal memberEr = whole(amountEr).subtract(memberEps);
            if (epfWages.compareTo(gross) > 0) {
                checks.add(check("epf_over_gross", "blocking", who, who + ": EPF wages " + epfWages + " are above gross wages " + gross + "."));
                ok = false;
            }
            if (epsWages.compareTo(epfWages) > 0 || epsWages.compareTo(epsCeiling) > 0) {
                checks.add(check("eps_over_limit", "blocking", who, who + ": EPS wages " + epsWages + " are above EPF wages or the "
                        + epsCeiling.toPlainString() + " ceiling."));
                ok = false;
            }
            if (memberEe.compareTo(gross) > 0) {
                checks.add(check("ee_over_gross", "blocking", who, who + ": the employee share is above gross wages."));
                ok = false;
            }
            if (s.joiningDate.isAfter(end)) {
                checks.add(check("joined_after", "blocking", who, who + " joined after this wage month."));
                ok = false;
            }
            if (s.exitDate != null && s.exitDate.isBefore(period)) {
                checks.add(check("left_before", "blocking", who, who + " left before this wage month."));
                ok = false;
            }
            if (!ok) {
                continue;
            }
            BigDecimal ncp = s.lopDays.setScale(0, ncpUp ? RoundingMode.CEILING : RoundingMode.FLOOR);
            lines.add(String.join(separator, uan, s.name.trim(), gross.toPlainString(), epfWages.toPlainString(), epsWages.toPlainString(),
                    edliWages.toPlainString(), memberEe.toPlainString(), memberEps.toPlainString(), memberEr.toPlainString(),
                    ncp.toPlainString(), "0"));
            ee = ee.add(memberEe);
            eps = eps.add(memberEps);
            er = er.add(memberEr);
            members++;
        }
        BigDecimal fileTotal = ee.add(eps).add(er);
        BigDecimal runTotal = whole(runEe).add(whole(runEr));
        if (fileTotal.compareTo(runTotal) != 0 && checks.stream().noneMatch(Check::blocking)) {
            checks.add(check("totals_differ", "blocking", null, "The file's contributions total " + fileTotal + " but the run's PF lines total "
                    + runTotal + "."));
        }
        Totals totals = new Totals(members, ee, eps, er, null, fileTotal, runTotal);
        String body = String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
        return new Draft(body.getBytes(StandardCharsets.UTF_8), totals, checks);
    }

    // ── the ESI contribution file ───────────────────────────────────────

    private Draft esi(Connection c, List<UUID> runIds, LocalDate period, Map<String, Object> rule, UUID tenantId) throws SQLException {
        Map<String, Object> data = PayrollEngine.map(rule, "data");
        BigDecimal threshold = PayrollEngine.dec(data, "threshold");
        Map<String, Object> defaultReason = PayrollEngine.map(data, "exitReason");
        Map<String, Object> reasonCodes = PayrollEngine.map(data, "reasonCodes");
        Map<String, Integer> orgReason = new HashMap<>();
        for (Map<String, Object> r : Sql.query(c, "select exit_kind, esic_code from app.esi_reason_map")) {
            orgReason.put((String) r.get("exitKind"), ((Number) r.get("esicCode")).intValue());
        }
        List<Check> checks = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("IP Number", "IP Name", "No of days for which wages paid/payable during the month", "Total monthly wages",
                "Reason code for zero working days", "Last working day"));
        BigDecimal wagesTotal = BigDecimal.ZERO;
        BigDecimal fileEe = BigDecimal.ZERO;
        BigDecimal fileEr = BigDecimal.ZERO;
        BigDecimal runEe = BigDecimal.ZERO;
        BigDecimal runEr = BigDecimal.ZERO;
        int members = 0;
        for (Slip s : slips(c, runIds, Set.of("ESI_EE", "ESI_ER"))) {
            Line lineEe = s.lines.get("ESI_EE");
            Line lineEr = s.lines.get("ESI_ER");
            String ip = secret(c, s.personId, "esi_ip", tenantId);
            boolean covered = lineEe != null || lineEr != null;
            BigDecimal amountEe = lineEe == null ? BigDecimal.ZERO : lineEe.amount;
            BigDecimal amountEr = lineEr == null ? BigDecimal.ZERO : lineEr.amount;
            runEe = runEe.add(amountEe);
            runEr = runEr.add(amountEr);
            String lastDay = s.exitDate != null && !s.exitDate.isBefore(period) && s.exitDate.isBefore(period.plusMonths(1))
                    ? s.exitDate.format(DMY) : "";
            if (covered) {
                if (ip == null || !ESI_IP.matcher(ip).matches()) {
                    checks.add(check("ip_invalid", "blocking", s.name, s.name + " is covered by ESI but has no valid 10-digit ESI number."));
                    continue;
                }
                BigDecimal wages = whole((lineEr != null ? lineEr : lineEe).num("esiWages"));
                rows.add(List.of(ip, s.name, s.daysPaid.setScale(0, RoundingMode.CEILING).toPlainString(), wages.toPlainString(), "0", lastDay));
                wagesTotal = wagesTotal.add(wages);
                fileEe = fileEe.add(amountEe);
                fileEr = fileEr.add(amountEr);
                members++;
            } else if (ip != null) {
                boolean left = s.exitDate != null && s.exitDate.isBefore(period.plusMonths(1));
                boolean noDaysPaid = s.daysPaid.signum() == 0;
                if (left || noDaysPaid) {
                    Object mapped = left ? orgReason.getOrDefault(s.exitKind, intOrNull(defaultReason.get(s.exitKind))) : reasonCodes.get("onLeave");
                    if (mapped == null) {
                        checks.add(check("reason_missing", "blocking", s.name, s.name + " has no wages and no ESIC reason code is set for how "
                                + "they left."));
                        continue;
                    }
                    rows.add(List.of(ip, s.name, "0", "0", String.valueOf(mapped), lastDay));
                    members++;
                } else if (s.gross.compareTo(threshold) <= 0) {
                    checks.add(check("esi_line_missing", "blocking", s.name, s.name + " has an ESI number and wages of " + whole(s.gross)
                            + " (at or below " + threshold.toPlainString() + ") but the run has no ESI contribution for them."));
                } else {
                    checks.add(check("esi_above_threshold", "warning", s.name, s.name + " has an ESI number but wages above "
                            + threshold.toPlainString() + " and no contribution; they are left out of the file."));
                }
            }
        }
        if (fileEe.add(fileEr).compareTo(runEe.add(runEr)) != 0 && checks.stream().noneMatch(Check::blocking)) {
            checks.add(check("totals_differ", "blocking", null, "The file covers contributions of " + fileEe.add(fileEr)
                    + " but the run's ESI lines total " + runEe.add(runEr) + "."));
        }
        Totals totals = new Totals(members, fileEe, null, fileEr, wagesTotal, null, runEe.add(runEr));
        return new Draft(PayrollOutputs.csv("", rows).bytes(), totals, checks);
    }

    private static Integer intOrNull(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    // ── reading the run ─────────────────────────────────────────────────

    private static final class Line {
        private final BigDecimal amount;
        private final Map<String, Object> trace;

        Line(BigDecimal amount, Map<String, Object> trace) {
            this.amount = amount;
            this.trace = trace;
        }

        BigDecimal num(String key) {
            Object v = trace.get(key);
            return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
        }
    }

    private static final class Slip {
        UUID tenantId;
        UUID personId;
        String name;
        BigDecimal gross;
        BigDecimal daysPaid;
        BigDecimal lopDays;
        LocalDate joiningDate;
        LocalDate exitDate;
        String exitKind;
        final Map<String, Line> lines = new HashMap<>();
    }

    private List<Slip> slips(Connection c, List<UUID> runIds, Set<String> codes) throws SQLException {
        List<Slip> out = new ArrayList<>();
        for (UUID runId : runIds) {
            for (Map<String, Object> r : Sql.query(c, "select ps.id, ps.tenant_id, ps.gross, ps.days_paid, ps.lop_days, ps.info->>'name' as name, "
                    + "e.person_id, e.joining_date, e.exit_date, (select x.kind from app.exit_case x where x.employment_id = e.id "
                    + "and x.status in ('accepted','closed') order by x.started_at desc limit 1) as exit_kind "
                    + "from app.payslip ps join app.employment e on e.id = ps.employment_id where ps.run_id = ? order by lower(ps.info->>'name'), ps.id",
                    runId)) {
                Slip s = new Slip();
                s.tenantId = AuthService.uuid(r.get("tenantId"));
                s.personId = AuthService.uuid(r.get("personId"));
                s.name = (String) r.get("name");
                s.gross = PayrollEngine.dec(r, "gross");
                s.daysPaid = PayrollEngine.dec(r, "daysPaid");
                s.lopDays = PayrollEngine.dec(r, "lopDays");
                s.joiningDate = LocalDate.parse(String.valueOf(r.get("joiningDate")));
                s.exitDate = r.get("exitDate") == null ? null : LocalDate.parse(String.valueOf(r.get("exitDate")));
                s.exitKind = (String) r.get("exitKind");
                for (Map<String, Object> l : Sql.query(c, "select code, amount, trace from app.payslip_line where payslip_id = ?::uuid "
                        + "and code = any (string_to_array(?, ','))", r.get("id"), String.join(",", codes))) {
                    s.lines.put((String) l.get("code"), new Line(PayrollEngine.dec(l, "amount"), parse(l.get("trace"))));
                }
                out.add(s);
            }
        }
        return out;
    }

    /** A sealed identifier opened for the moment of writing the file; never logged. */
    private String secret(Connection c, UUID personId, String kind, UUID tenantId) throws SQLException {
        byte[] sealed;
        try (var st = c.prepareStatement("select value_enc from app.statutory_id where person_id = ? and kind = ? and replaced_at is null")) {
            st.setObject(1, personId);
            st.setString(2, kind);
            try (var rs = st.executeQuery()) {
                sealed = rs.next() ? rs.getBytes(1) : null;
            }
        }
        return sealed == null ? null : crypto.decryptText(tenantId, sealed);
    }

    @SuppressWarnings("unchecked")
    private static List<Check> checksOf(Object json) {
        Object v = json == null ? null : Json.parse(String.valueOf(json));
        List<Check> out = new ArrayList<>();
        if (v instanceof List<?> items) {
            for (Object o : items) {
                Map<String, Object> m = (Map<String, Object>) o;
                out.add(new Check((String) m.get("code"), (String) m.get("level"), (String) m.get("who"), (String) m.get("message")));
            }
        }
        return out;
    }

    private static byte[] bytes(Connection c, String sql, UUID id) throws SQLException {
        try (var st = c.prepareStatement(sql)) {
            if (id != null) {
                st.setObject(1, id);
            }
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    // ── small helpers ───────────────────────────────────────────────────

    private static Map<String, Object> rule(Connection c, String statute, LocalDate period) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select id, status, data from app.payroll_rule where statute = ? and jurisdiction = 'IN' "
                + "and valid_from <= ? and (valid_to is null or valid_to > ?)", statute, period, period);
        if (r == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("status", r.get("status"));
        out.put("data", parse(r.get("data")));
        return out;
    }

    private static LocalDate due(LocalDate period, Map<String, Object> rule) {
        int day = rule == null ? 15 : (int) PayrollEngine.dec(PayrollEngine.map(rule, "data"), "dueDay").longValue();
        return period.plusMonths(1).withDayOfMonth(Math.max(1, day));
    }

    private static Check check(String code, String level, String who, String message) {
        return new Check(code, level, who, message);
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int blocking(List<Check> checks) {
        return (int) checks.stream().filter(Check::blocking).count();
    }

    private static BigDecimal whole(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }

    private static String statute(String kind) {
        return kind.equals("pf_ecr") ? "pf" : "esi";
    }

    private static void kind(String kind) {
        if (!KINDS.contains(kind)) {
            throw ApiException.notFound("That return");
        }
    }

    private static LocalDate period(String month) {
        try {
            return YearMonth.parse(month).atDay(1);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the month as yyyy-mm");
        }
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the date as yyyy-mm-dd");
        }
    }

    private static void needRead(SessionCtx ctx) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        if (!ctx.reachesEveryone("payroll.read") && !ctx.reachesEveryone("payroll.prepare")) {
            throw ApiException.denied("payroll.read");
        }
    }
}
