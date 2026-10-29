package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.example.loginapp.core.PayrollRuns.parse;

/**
 * The quarterly TDS statement (Form 138), one per TAN per quarter, built from approved payroll runs and approved
 * challans. Building runs the checks and stores a numbered version with a review spreadsheet (encrypted: it holds
 * full PANs). A blocking check stops the download unless an approver accepts it, where the check allows that.
 * After the employer files with the department's own tools, the token number is recorded and the statement is
 * locked; a later build is a correction and holds only the lines that changed.
 *
 * Not built here: the text file in the layout of the department's return preparation utility (its exact layout
 * and version are an open question for the payroll consultant), and the fourth quarter's annual salary detail.
 */
public final class TdsStatements {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");

    public record Check(String code, String level, boolean acceptable, String who, String message) {
        boolean blocking() {
            return "blocking".equals(level);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("level", level);
            m.put("acceptable", acceptable);
            m.put("who", who);
            m.put("message", message);
            return m;
        }
    }

    /** A check an approver accepted, and why. */
    public record Accepted(String code, String who, String reason) {}

    public record Totals(int lines, BigDecimal amountPaid, BigDecimal taxDeducted, BigDecimal challanTotal) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lines", lines);
            m.put("amountPaid", amountPaid);
            m.put("taxDeducted", taxDeducted);
            m.put("challanTotal", challanTotal);
            return m;
        }

        static Totals of(Map<String, Object> m) {
            return new Totals(m.get("lines") == null ? 0 : ((Number) m.get("lines")).intValue(), num(m, "amountPaid"), num(m, "taxDeducted"),
                    num(m, "challanTotal"));
        }

        private static BigDecimal num(Map<String, Object> m, String key) {
            return m.get(key) == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(m.get(key)));
        }
    }

    public record Statement(String id, int version, String correctionOf, String tan, String status, String fileName, Totals totals,
                            List<Check> checks, List<Accepted> accepted, int unresolved, String generatedAt, String tokenNumber,
                            String filedOn, Long daysLate) {}

    public record QuarterView(String taxYear, int quarter, String dueOn, List<Statement> statements) {}

    public record BuildResult(String id, int version, boolean correction, Totals totals, List<Check> checks, int unresolved) {}

    public record AcceptInput(String code, String who, String reason) {}

    public record FiledInput(String tokenNumber, String filedOn) {}

    public record Status(String id, String status) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public TdsStatements(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public TdsStatements(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── reading ─────────────────────────────────────────────────────────

    public QuarterView quarter(SessionCtx ctx, String taxYear, int quarter) {
        needRead(ctx);
        String ty = TaxYears.check(taxYear, TaxYears.of(today()));
        quarter(quarter);
        return db.inTenant(ctx.tenantId(), c -> {
            LocalDate due = due(c, ty, quarter);
            List<Statement> out = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, "select id from app.tds_statement where tax_year = ? and quarter = ? order by version desc", ty, quarter)) {
                out.add(statement(c, AuthService.uuid(r.get("id")), due));
            }
            return new QuarterView(ty, quarter, due.toString(), out);
        });
    }

    // ── build and check ─────────────────────────────────────────────────

    public BuildResult build(SessionCtx ctx, String taxYear, int quarter) {
        PayrollConfig.need(ctx, "payroll.prepare");
        String ty = TaxYears.check(taxYear, TaxYears.of(today()));
        quarter(quarter);
        LocalDate first = TaxYears.quarterStart(ty, quarter);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> settings = Sql.one(c, "select entity_name, pan, tan, address from app.payroll_settings");
            List<Check> checks = new ArrayList<>();
            String tan = settings == null ? null : (String) settings.get("tan");
            if (tan == null) {
                throw new ApiException(409, "statement.no_tan", "Add the Organization's TAN under payroll settings first");
            }
            if (settings.get("entityName") == null || settings.get("pan") == null || settings.get("address") == null) {
                checks.add(new Check("deductor_incomplete", "blocking", false, null,
                        "The deductor's name, PAN and address must all be filled in under payroll settings."));
            }

            Map<String, Row> rows = new LinkedHashMap<>();
            BigDecimal challanTotal = BigDecimal.ZERO;
            Map<UUID, BigDecimal> seenChallans = new HashMap<>();
            for (int m = 0; m < 3; m++) {
                LocalDate month = first.plusMonths(m);
                int open = Sql.query(c, "select 1 from app.payroll_run where period = ? and state not in ('approved','paid','released')", month).size();
                if (open > 0) {
                    checks.add(new Check("run_open", "blocking", false, month.toString().substring(0, 7),
                            open + " payroll run(s) for " + month.toString().substring(0, 7) + " are not approved; their tax is missing from the statement."));
                }
                for (Map<String, Object> run : Sql.query(c, "select id from app.payroll_run where period = ? and state in ('approved','paid','released') "
                        + "order by created_at", month)) {
                    UUID runId = AuthService.uuid(run.get("id"));
                    BigDecimal tds = TdsChallans.runTds(c, runId);
                    if (tds.signum() == 0) {
                        continue;
                    }
                    String label = month.toString().substring(0, 7);
                    Map<String, Object> challan = Sql.one(c, "select ch.id, ch.status from app.tds_challan_run cr join app.tds_challan ch on ch.id = cr.challan_id "
                            + "where cr.run_id = ?", runId);
                    if (challan == null) {
                        checks.add(new Check("challan_missing", "blocking", false, label, "The tax deducted in " + label + " ("
                                + PayrollEngine.rupees(tds) + ") has no challan recorded."));
                    } else if (!"approved".equals(challan.get("status"))) {
                        checks.add(new Check("challan_unapproved", "blocking", false, label, "The challan for " + label
                                + " is recorded but not yet approved."));
                    }
                    UUID challanId = challan == null ? null : AuthService.uuid(challan.get("id"));
                    if (challanId != null && seenChallans.putIfAbsent(challanId, BigDecimal.ZERO) == null) {
                        Map<String, Object> t = Sql.one(c, "select ch.tax + ch.surcharge + ch.cess as paid, (select coalesce(sum(l.amount), 0) from app.tds_challan_run cr "
                                + "join app.payslip ps on ps.run_id = cr.run_id join app.payslip_line l on l.payslip_id = ps.id and l.code = 'TDS' "
                                + "where cr.challan_id = ch.id) as deducted from app.tds_challan ch where ch.id = ?", challanId);
                        BigDecimal paid = PayrollEngine.dec(t, "paid");
                        challanTotal = challanTotal.add(paid);
                        if (paid.compareTo(PayrollEngine.dec(t, "deducted")) != 0) {
                            checks.add(new Check("challan_mismatch", "blocking", false, label, "The challan for " + label + " pays "
                                    + PayrollEngine.rupees(paid) + " but the runs it covers deducted " + PayrollEngine.rupees(PayrollEngine.dec(t, "deducted")) + "."));
                        }
                    }
                    for (Map<String, Object> p : Sql.query(c, "select ps.employment_id, ps.gross, ps.info->>'name' as name, ps.info->>'code' as code, "
                            + "e.person_id, (select coalesce(sum(l.amount), 0) from app.payslip_line l where l.payslip_id = ps.id and l.code = 'TDS') as tds "
                            + "from app.payslip ps join app.employment e on e.id = ps.employment_id where ps.run_id = ? order by lower(ps.info->>'name'), ps.id", runId)) {
                        BigDecimal tax = PayrollEngine.dec(p, "tds");
                        if (tax.signum() == 0) {
                            continue;
                        }
                        UUID employment = AuthService.uuid(p.get("employmentId"));
                        String pan = pan(c, AuthService.uuid(p.get("personId")), ctx.tenantId());
                        boolean valid = pan != null && PAN.matcher(pan).matches();
                        String name = (String) p.get("name");
                        if (!valid) {
                            checks.add(new Check("pan_invalid", "blocking", true, name, name + " has no valid PAN, so tax at the higher rate may apply "
                                    + "and the department can reject the line."));
                        }
                        Row row = new Row(employment, (String) p.get("code"), name, valid ? pan : null, month, challanId, PayrollEngine.dec(p, "gross"), tax, !valid, false);
                        rows.merge(employment + "|" + month, row, (a, b) -> a.plus(b));
                    }
                }
            }
            if (quarter == 4) {
                checks.add(new Check("annexure_pending", "warning", false, null,
                        "The fourth quarter also carries each employee's salary detail for the year; that part is not in this build."));
            }
            Map<String, Object> rule = Sql.one(c, "select status from app.payroll_rule where statute = 'income_tax' and jurisdiction = 'IN' and valid_from <= ? "
                    + "and (valid_to is null or valid_to > ?)", first, first);
            if (rule != null && "draft".equals(rule.get("status"))) {
                checks.add(new Check("rules_draft", "warning", false, null, "The income tax rules used are still drafts awaiting the payroll consultant's sign-off."));
            }

            // A correction holds only what differs from what was filed before.
            Map<String, Row> filed = effective(c, ty, quarter);
            UUID correctionOf = latestFiled(c, ty, quarter);
            List<Row> lines = new ArrayList<>();
            List<String> changes = new ArrayList<>();
            if (correctionOf == null) {
                lines.addAll(rows.values());
                rows.values().forEach(r -> changes.add("new"));
            } else {
                for (Map.Entry<String, Row> e : rows.entrySet()) {
                    Row before = filed.get(e.getKey());
                    if (before == null) {
                        lines.add(e.getValue());
                        changes.add("added");
                    } else if (!before.sameAs(e.getValue())) {
                        lines.add(e.getValue());
                        changes.add("changed");
                    }
                }
                for (Map.Entry<String, Row> e : filed.entrySet()) {
                    if (!rows.containsKey(e.getKey())) {
                        lines.add(e.getValue().withdrawn());
                        changes.add("removed");
                    }
                }
                if (lines.isEmpty()) {
                    checks.add(new Check("no_changes", "warning", false, null, "Nothing differs from the filed statement."));
                }
            }
            BigDecimal amountPaid = BigDecimal.ZERO;
            BigDecimal taxDeducted = BigDecimal.ZERO;
            for (Row r : lines) {
                if (!r.removed) {
                    amountPaid = amountPaid.add(r.amountPaid);
                    taxDeducted = taxDeducted.add(r.tax);
                }
            }
            Totals totals = new Totals(lines.size(), amountPaid, taxDeducted, challanTotal);

            Map<String, Object> last = Sql.one(c, "select coalesce(max(version), 0) as v from app.tds_statement where tax_year = ? and quarter = ?", ty, quarter);
            int version = ((Number) last.get("v")).intValue() + 1;
            String name = "TDS statement " + ty + " Q" + quarter + " v" + version + ".csv";
            byte[] file = review(settings, ty, quarter, version, lines, changes, totals);
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.tds_statement (tenant_id, tax_year, quarter, version, correction_of, tan, file_name, file_enc, "
                    + "totals, checks, generated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) returning id", ctx.tenantId(), ty, quarter, version,
                    correctionOf, tan, name, crypto.encrypt(ctx.tenantId(), file), Json.write(totals.toMap()),
                    Json.write(checks.stream().map(Check::toMap).toList()), ctx.membershipId()).get("id"));
            for (Row r : lines) {
                Sql.update(c, "insert into app.tds_statement_row (tenant_id, statement_id, employment_id, month, challan_id, amount_paid, tax_deducted, "
                        + "higher_rate, removed) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), id, r.employment, r.month, r.challan,
                        r.amountPaid, r.tax, r.higherRate, r.removed);
            }
            int unresolved = (int) checks.stream().filter(Check::blocking).count();
            UserService.audit(c, ctx, "tds.statement_built", "tds_statement", id,
                    Map.of("taxYear", ty, "quarter", quarter, "version", version, "correction", correctionOf != null, "blocking", unresolved));
            return new BuildResult(id.toString(), version, correctionOf != null, totals, checks, unresolved);
        });
    }

    // ── accept, download, file ──────────────────────────────────────────

    /** An approver accepts a check that allows it (a missing PAN, say), with the reason. */
    public Status accept(SessionCtx ctx, UUID id, AcceptInput in) {
        PayrollConfig.need(ctx, "payroll.approve");
        if (in == null || in.code() == null || in.reason() == null || in.reason().trim().length() < 3 || in.reason().trim().length() > 200) {
            throw ApiException.badRequest("Name the check and give the reason in a few words");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> s = Sql.one(c, "select status, checks, accepted from app.tds_statement where id = ? for update", id);
            if (s == null) {
                throw ApiException.notFound("That statement");
            }
            if (!"draft".equals(s.get("status"))) {
                throw new ApiException(409, "statement.state", "A filed statement cannot change");
            }
            List<Check> checks = checksOf(s.get("checks"));
            Check target = checks.stream().filter(x -> x.code().equals(in.code()) && java.util.Objects.equals(x.who(), in.who())).findFirst().orElse(null);
            if (target == null) {
                throw ApiException.notFound("That check");
            }
            if (!target.blocking() || !target.acceptable()) {
                throw new ApiException(409, "statement.not_acceptable", "That check has to be fixed, not accepted");
            }
            List<Accepted> accepted = acceptedOf(s.get("accepted"));
            accepted.add(new Accepted(in.code(), in.who(), in.reason().trim()));
            Sql.update(c, "update app.tds_statement set accepted = ?::jsonb where id = ?",
                    Json.write(accepted.stream().map(a -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", a.code());
                        m.put("who", a.who());
                        m.put("reason", a.reason());
                        return m;
                    }).toList()), id);
            UserService.audit(c, ctx, "tds.statement_check_accepted", "tds_statement", id,
                    Map.of("code", in.code(), "who", in.who() == null ? "" : in.who(), "reason", in.reason().trim()));
            return new Status(id.toString(), "draft");
        });
    }

    /** The review spreadsheet. Refused while a blocking check stands unaccepted; a fresh two-step code is needed for the PANs in it. */
    public DocumentService.Content download(SessionCtx ctx, UUID id) {
        PayrollConfig.need(ctx, "payroll.prepare");
        StepUp.require(db, ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> s = Sql.one(c, "select file_name, checks, accepted from app.tds_statement where id = ?", id);
            if (s == null) {
                throw ApiException.notFound("That statement");
            }
            if (unresolved(checksOf(s.get("checks")), acceptedOf(s.get("accepted"))) > 0) {
                throw new ApiException(409, "statement.blocked", "Fix the blocking checks, or have an approver accept those that allow it");
            }
            byte[] sealed;
            try (var st = c.prepareStatement("select file_enc from app.tds_statement where id = ?")) {
                st.setObject(1, id);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    sealed = rs.getBytes(1);
                }
            }
            UserService.audit(c, ctx, "tds.statement_downloaded", "tds_statement", id, Map.of());
            return new DocumentService.Content("text/csv; charset=utf-8", (String) s.get("fileName"), crypto.decrypt(ctx.tenantId(), sealed));
        });
    }

    /** After filing with the department's own tools: the token number and date. The statement is final from here. */
    public Status filed(SessionCtx ctx, UUID id, FiledInput in) {
        PayrollConfig.need(ctx, "payroll.prepare");
        if (in == null || in.tokenNumber() == null || in.tokenNumber().trim().length() < 6 || in.filedOn() == null) {
            throw ApiException.badRequest("Give the token number from the department's receipt and the date filed");
        }
        LocalDate on;
        try {
            on = LocalDate.parse(in.filedOn());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the date filed as yyyy-mm-dd");
        }
        if (on.isAfter(today())) {
            throw ApiException.badRequest("The date filed cannot be in the future");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> s = Sql.one(c, "select status, tax_year, quarter, version, checks, accepted from app.tds_statement where id = ? for update", id);
            if (s == null) {
                throw ApiException.notFound("That statement");
            }
            if (!"draft".equals(s.get("status"))) {
                throw new ApiException(409, "statement.state", "That statement is already filed");
            }
            Map<String, Object> latest = Sql.one(c, "select max(version) as v from app.tds_statement where tax_year = ? and quarter = ?", s.get("taxYear"), s.get("quarter"));
            if (((Number) latest.get("v")).intValue() != ((Number) s.get("version")).intValue()) {
                throw new ApiException(409, "statement.stale", "A newer version of this statement exists; file that one");
            }
            if (unresolved(checksOf(s.get("checks")), acceptedOf(s.get("accepted"))) > 0) {
                throw new ApiException(409, "statement.blocked", "Fix the blocking checks, or have an approver accept those that allow it");
            }
            Sql.update(c, "update app.tds_statement set status = 'filed', token_number = ?, filed_on = ?, filed_by = ? where id = ?",
                    in.tokenNumber().trim(), on, ctx.membershipId(), id);
            UserService.audit(c, ctx, "tds.statement_filed", "tds_statement", id, Map.of("token", in.tokenNumber().trim(), "filedOn", on.toString()));
            return new Status(id.toString(), "filed");
        });
    }

    // ── lines ───────────────────────────────────────────────────────────

    private record Row(UUID employment, String code, String name, String pan, LocalDate month, UUID challan, BigDecimal amountPaid,
                       BigDecimal tax, boolean higherRate, boolean removed) {
        Row plus(Row other) {
            return new Row(employment, code, name, pan, month, challan, amountPaid.add(other.amountPaid), tax.add(other.tax), higherRate, removed);
        }

        boolean sameAs(Row other) {
            return amountPaid.compareTo(other.amountPaid) == 0 && tax.compareTo(other.tax) == 0 && java.util.Objects.equals(challan, other.challan)
                    && higherRate == other.higherRate;
        }

        Row withdrawn() {
            return new Row(employment, code, name, pan, month, challan, BigDecimal.ZERO, BigDecimal.ZERO, higherRate, true);
        }
    }

    /** What the filed statements of a quarter add up to: version 1, then each later filed version's changes applied over it. */
    private Map<String, Row> effective(Connection c, String ty, int quarter) throws SQLException {
        Map<String, Row> out = new LinkedHashMap<>();
        for (Map<String, Object> s : Sql.query(c, "select id from app.tds_statement where tax_year = ? and quarter = ? and status = 'filed' order by version", ty, quarter)) {
            for (Map<String, Object> r : Sql.query(c, "select r.employment_id, r.month, r.challan_id, r.amount_paid, r.tax_deducted, r.higher_rate, r.removed, "
                    + "ps.name, ps.code from app.tds_statement_row r join lateral (select e.employee_code as code, p.legal_name as name from app.employment e "
                    + "join app.person p on p.id = e.person_id where e.id = r.employment_id) ps on true where r.statement_id = ?::uuid", s.get("id"))) {
                UUID employment = AuthService.uuid(r.get("employmentId"));
                LocalDate month = LocalDate.parse(String.valueOf(r.get("month")));
                String key = employment + "|" + month;
                if (Boolean.TRUE.equals(r.get("removed"))) {
                    out.remove(key);
                } else {
                    out.put(key, new Row(employment, (String) r.get("code"), (String) r.get("name"), null, month,
                            r.get("challanId") == null ? null : AuthService.uuid(r.get("challanId")), PayrollEngine.dec(r, "amountPaid"),
                            PayrollEngine.dec(r, "taxDeducted"), Boolean.TRUE.equals(r.get("higherRate")), false));
                }
            }
        }
        return out;
    }

    private static UUID latestFiled(Connection c, String ty, int quarter) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select id from app.tds_statement where tax_year = ? and quarter = ? and status = 'filed' order by version desc limit 1", ty, quarter);
        return r == null ? null : AuthService.uuid(r.get("id"));
    }

    private byte[] review(Map<String, Object> settings, String ty, int quarter, int version, List<Row> lines, List<String> changes, Totals totals) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Deductor", String.valueOf(settings.get("entityName")), "TAN", String.valueOf(settings.get("tan")), "PAN", String.valueOf(settings.get("pan"))));
        rows.add(List.of("Tax year", ty, "Quarter", "Q" + quarter, "Version", String.valueOf(version)));
        rows.add(List.of());
        rows.add(List.of("Employee code", "Name", "PAN", "Month", "Amount paid", "Tax deducted", "Higher rate", "Change", "Challan"));
        for (int i = 0; i < lines.size(); i++) {
            Row r = lines.get(i);
            rows.add(List.of(r.code == null ? "" : r.code, r.name == null ? "" : r.name, r.pan == null ? "" : r.pan, r.month.toString().substring(0, 7),
                    PayrollRuns.money(r.amountPaid), PayrollRuns.money(r.tax), r.higherRate ? "Yes" : "", changes.get(i), r.challan == null ? "" : r.challan.toString()));
        }
        rows.add(List.of("Total", "", "", "", PayrollRuns.money(totals.amountPaid()), PayrollRuns.money(totals.taxDeducted()), "", "", ""));
        return PayrollOutputs.csv("", rows).bytes();
    }

    private String pan(Connection c, UUID person, UUID tenantId) throws SQLException {
        byte[] sealed;
        try (var st = c.prepareStatement("select value_enc from app.statutory_id where person_id = ? and kind = 'pan' and replaced_at is null "
                + "and pending_reason is null limit 1")) {
            st.setObject(1, person);
            try (var rs = st.executeQuery()) {
                sealed = rs.next() ? rs.getBytes(1) : null;
            }
        }
        return sealed == null ? null : crypto.decryptText(tenantId, sealed);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Statement statement(Connection c, UUID id, LocalDate due) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select id, version, correction_of, tan, status, file_name, totals, checks, accepted, generated_at, token_number, "
                + "filed_on from app.tds_statement where id = ?", id);
        List<Check> checks = checksOf(s.get("checks"));
        List<Accepted> accepted = acceptedOf(s.get("accepted"));
        Long late = s.get("filedOn") == null ? null : Math.max(0, ChronoUnit.DAYS.between(due, LocalDate.parse(String.valueOf(s.get("filedOn")))));
        return new Statement(String.valueOf(s.get("id")), ((Number) s.get("version")).intValue(),
                s.get("correctionOf") == null ? null : String.valueOf(s.get("correctionOf")), (String) s.get("tan"), (String) s.get("status"),
                (String) s.get("fileName"), Totals.of(parse(s.get("totals"))), checks, accepted, unresolved(checks, accepted),
                String.valueOf(s.get("generatedAt")), (String) s.get("tokenNumber"), s.get("filedOn") == null ? null : String.valueOf(s.get("filedOn")), late);
    }

    private static int unresolved(List<Check> checks, List<Accepted> accepted) {
        return (int) checks.stream().filter(Check::blocking).filter(x -> accepted.stream().noneMatch(a -> a.code().equals(x.code())
                && java.util.Objects.equals(a.who(), x.who()))).count();
    }

    @SuppressWarnings("unchecked")
    private static List<Check> checksOf(Object json) {
        List<Check> out = new ArrayList<>();
        Object v = json == null ? null : Json.parse(String.valueOf(json));
        if (v instanceof List<?> items) {
            for (Object o : items) {
                Map<String, Object> m = (Map<String, Object>) o;
                out.add(new Check((String) m.get("code"), (String) m.get("level"), Boolean.TRUE.equals(m.get("acceptable")), (String) m.get("who"),
                        (String) m.get("message")));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Accepted> acceptedOf(Object json) {
        List<Accepted> out = new ArrayList<>();
        Object v = json == null ? null : Json.parse(String.valueOf(json));
        if (v instanceof List<?> items) {
            for (Object o : items) {
                Map<String, Object> m = (Map<String, Object>) o;
                out.add(new Accepted((String) m.get("code"), (String) m.get("who"), (String) m.get("reason")));
            }
        }
        return out;
    }

    /** When a quarter's statement falls due, from the rule: quarters 3 and 4 fall in the next calendar year. */
    private static LocalDate due(Connection c, String ty, int quarter) throws SQLException {
        LocalDate start = TaxYears.start(ty);
        Map<String, Object> rule = Sql.one(c, "select data from app.payroll_rule where statute = 'income_tax' and jurisdiction = 'IN' and valid_from <= ? "
                + "and (valid_to is null or valid_to > ?)", start, start);
        Map<String, Object> dues = rule == null ? Map.of() : PayrollEngine.map(parse(rule.get("data")), "statementDue");
        String text = dues.get(String.valueOf(quarter)) == null ? null : String.valueOf(dues.get(String.valueOf(quarter)));
        if (text == null) {
            text = switch (quarter) {
                case 1 -> "07-31";
                case 2 -> "10-31";
                case 3 -> "01-31";
                default -> "05-31";
            };
        }
        return MonthDay.parse("--" + text).atYear(quarter >= 3 ? start.getYear() + 1 : start.getYear());
    }

    private static void quarter(int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw ApiException.badRequest("The quarter is 1 to 4");
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
