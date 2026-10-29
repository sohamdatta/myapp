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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.loginapp.core.PayrollRuns.parse;

/**
 * The monthly deposit of tax deducted at source. Payroll records each challan (BSR code, serial number, date,
 * the amounts) against the runs it pays for; a second person approves it. The tax on a challan must equal the
 * TDS deducted in those runs, to the paisa. The month's status, due date and days late come from the income tax
 * rule's due-date data, not from code.
 */
public final class TdsChallans {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record ChallanInput(String month, String deductorType, String minorHead, String bsrCode, String serial, String depositedOn,
                               BigDecimal tax, BigDecimal surcharge, BigDecimal cess, BigDecimal interest, BigDecimal fee,
                               List<String> runIds) {}

    public record Challan(String id, String month, String deductorType, String minorHead, String bsrCode, String serial, String depositedOn,
                          BigDecimal tax, BigDecimal surcharge, BigDecimal cess, BigDecimal interest, BigDecimal fee, BigDecimal total,
                          String status, List<String> runIds, Long daysLate) {}

    /** One month of deducted tax against deposited tax. Status: none, part, deposited or over. */
    public record MonthRow(String month, BigDecimal deducted, BigDecimal deposited, String status, String dueOn, boolean overdue,
                           List<Challan> challans) {}

    public record YearView(String taxYear, List<MonthRow> months) {}

    public record Status(String id, String status) {}

    private final Db db;
    private final Clock clock;

    public TdsChallans(Db db) {
        this(db, Clock.systemUTC());
    }

    public TdsChallans(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── the year, month by month ────────────────────────────────────────

    public YearView year(SessionCtx ctx, String taxYear) {
        needRead(ctx);
        String ty = taxYear(taxYear);
        LocalDate start = TaxYears.start(ty);
        return db.inTenant(ctx.tenantId(), c -> {
            List<MonthRow> months = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                LocalDate month = start.plusMonths(i);
                months.add(monthRow(c, month));
            }
            return new YearView(ty, months);
        });
    }

    private MonthRow monthRow(Connection c, LocalDate month) throws SQLException {
        BigDecimal deducted = deducted(c, month);
        List<Challan> challans = challans(c, month);
        BigDecimal deposited = BigDecimal.ZERO;
        for (Challan ch : challans) {
            if ("approved".equals(ch.status())) {
                deposited = deposited.add(ch.tax()).add(ch.surcharge()).add(ch.cess());
            }
        }
        String status = deposited.signum() == 0 ? "none"
                : deposited.compareTo(deducted) < 0 ? "part"
                : deposited.compareTo(deducted) == 0 ? "deposited" : "over";
        LocalDate due = due(c, month);
        boolean overdue = due != null && deducted.signum() > 0 && !"deposited".equals(status) && !"over".equals(status) && today().isAfter(due);
        return new MonthRow(month.toString(), deducted, deposited, status, due == null ? null : due.toString(), overdue, challans);
    }

    // ── record and approve ──────────────────────────────────────────────

    /** Records a deposit against the runs of a month. The tax, surcharge and cess must equal the TDS deducted in those runs. */
    public Challan record(SessionCtx ctx, ChallanInput in) {
        PayrollConfig.need(ctx, "payroll.prepare");
        if (in == null || in.runIds() == null || in.runIds().isEmpty()) {
            throw ApiException.badRequest("Choose the payroll run or runs this deposit is for");
        }
        LocalDate month = month(in.month());
        String bsr = in.bsrCode() == null ? "" : in.bsrCode().trim();
        String serial = in.serial() == null ? "" : in.serial().trim();
        if (!bsr.matches("[0-9]{7}")) {
            throw ApiException.badRequest("The BSR code has 7 digits");
        }
        if (!serial.matches("[0-9]{5}")) {
            throw ApiException.badRequest("The challan serial number has 5 digits");
        }
        String deductorType = in.deductorType() == null ? "company" : in.deductorType();
        String minorHead = in.minorHead() == null ? "200" : in.minorHead();
        if (!List.of("company", "other").contains(deductorType) || !List.of("200", "400").contains(minorHead)) {
            throw ApiException.badRequest("Choose the deductor type (company or other) and the minor head (200 or 400)");
        }
        LocalDate depositedOn = date(in.depositedOn());
        if (depositedOn.isAfter(today())) {
            throw ApiException.badRequest("The deposit date cannot be in the future");
        }
        if (depositedOn.isBefore(month)) {
            throw ApiException.badRequest("The tax cannot be deposited before the month it was deducted");
        }
        BigDecimal tax = amount(in.tax(), "tax");
        BigDecimal surcharge = amount(in.surcharge(), "surcharge");
        BigDecimal cess = amount(in.cess(), "cess");
        BigDecimal interest = amount(in.interest(), "interest");
        BigDecimal fee = amount(in.fee(), "fee");
        List<UUID> runIds = in.runIds().stream().map(TdsChallans::uuid).distinct().toList();
        return db.inTenant(ctx.tenantId(), c -> {
            BigDecimal covered = BigDecimal.ZERO;
            for (UUID run : runIds) {
                Map<String, Object> r = Sql.one(c, "select period, state from app.payroll_run where id = ? for update", run);
                if (r == null) {
                    throw ApiException.notFound("A payroll run");
                }
                if (!LocalDate.parse(String.valueOf(r.get("period"))).equals(month)) {
                    throw new ApiException(409, "challan.wrong_month", "Every run on a challan must be for the month the tax was deducted");
                }
                if (!List.of("approved", "paid", "released").contains(r.get("state"))) {
                    throw new ApiException(409, "challan.run_not_approved", "A deposit is recorded for approved runs only");
                }
                if (Sql.one(c, "select 1 from app.tds_challan_run where run_id = ?", run) != null) {
                    throw new ApiException(409, "challan.run_covered", "A run is already on a challan");
                }
                covered = covered.add(runTds(c, run));
            }
            BigDecimal paid = tax.add(surcharge).add(cess);
            if (paid.compareTo(covered) != 0) {
                throw new ApiException(409, "challan.amount", "Tax, surcharge and cess add up to " + PayrollEngine.rupees(paid)
                        + " but the TDS deducted in those runs is " + PayrollEngine.rupees(covered));
            }
            if (Sql.one(c, "select 1 from app.tds_challan where bsr_code = ? and serial = ? and deposited_on = ?", bsr, serial, depositedOn) != null) {
                throw new ApiException(409, "challan.duplicate", "A challan with that BSR code, serial number and date is already recorded");
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.tds_challan (tenant_id, period, deductor_type, minor_head, bsr_code, serial, deposited_on, "
                    + "tax, surcharge, cess, interest, fee, recorded_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                    ctx.tenantId(), month, deductorType, minorHead, bsr, serial, depositedOn, tax, surcharge, cess, interest, fee,
                    ctx.membershipId()).get("id"));
            for (UUID run : runIds) {
                Sql.update(c, "insert into app.tds_challan_run (tenant_id, challan_id, run_id) values (?, ?, ?)", ctx.tenantId(), id, run);
            }
            UserService.audit(c, ctx, "tds.challan_recorded", "tds_challan", id,
                    Map.of("month", month.toString(), "bsr", bsr, "serial", serial, "total", paid.add(interest).add(fee)));
            return one(c, id);
        });
    }

    /** Maker-checker: whoever recorded the challan cannot approve it. */
    public Status approve(SessionCtx ctx, UUID id) {
        PayrollConfig.need(ctx, "payroll.approve");
        StepUp.require(db, ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> ch = Sql.one(c, "select status, recorded_by from app.tds_challan where id = ? for update", id);
            if (ch == null) {
                throw ApiException.notFound("That challan");
            }
            if (!"recorded".equals(ch.get("status"))) {
                throw new ApiException(409, "challan.state", "Only a recorded challan can be approved");
            }
            if (ctx.membershipId() != null && ctx.membershipId().toString().equals(String.valueOf(ch.get("recordedBy")))) {
                throw new ApiException(409, "challan.same_person", "Someone other than the person who recorded the challan must approve it");
            }
            Sql.update(c, "update app.tds_challan set status = 'approved', approved_by = ?, approved_at = now() where id = ?", ctx.membershipId(), id);
            UserService.audit(c, ctx, "tds.challan_approved", "tds_challan", id, Map.of());
            return new Status(id.toString(), "approved");
        });
    }

    /** A challan not yet approved can be taken back by whoever recorded it, or by an approver, to correct a mistyped number. */
    public void remove(SessionCtx ctx, UUID id) {
        PayrollConfig.need(ctx, "payroll.prepare");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> ch = Sql.one(c, "select status from app.tds_challan where id = ? for update", id);
            if (ch == null) {
                throw ApiException.notFound("That challan");
            }
            if (!"recorded".equals(ch.get("status"))) {
                throw new ApiException(409, "challan.state", "An approved challan stays; record a corrected one only before approval");
            }
            Sql.update(c, "delete from app.tds_challan where id = ?", id);
            UserService.audit(c, ctx, "tds.challan_removed", "tds_challan", id, Map.of());
            return null;
        });
    }

    // ── reading ─────────────────────────────────────────────────────────

    static BigDecimal deducted(Connection c, LocalDate month) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select coalesce(sum(l.amount), 0) as tds from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                + "join app.payroll_run r on r.id = ps.run_id where r.period = ? and r.state in ('approved','paid','released') and l.code = 'TDS'", month);
        return PayrollEngine.dec(r, "tds");
    }

    static BigDecimal runTds(Connection c, UUID run) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select coalesce(sum(l.amount), 0) as tds from app.payslip_line l join app.payslip ps on ps.id = l.payslip_id "
                + "where ps.run_id = ? and l.code = 'TDS'", run);
        return PayrollEngine.dec(r, "tds");
    }

    private List<Challan> challans(Connection c, LocalDate month) throws SQLException {
        List<Challan> out = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select id from app.tds_challan where period = ? order by deposited_on, serial", month)) {
            out.add(one(c, AuthService.uuid(r.get("id"))));
        }
        return out;
    }

    private Challan one(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select id, period, deductor_type, minor_head, bsr_code, serial, deposited_on, tax, surcharge, cess, interest, "
                + "fee, total, status from app.tds_challan where id = ?", id);
        if (r == null) {
            throw ApiException.notFound("That challan");
        }
        List<String> runs = new ArrayList<>();
        for (Map<String, Object> x : Sql.query(c, "select run_id from app.tds_challan_run where challan_id = ? order by run_id", id)) {
            runs.add(String.valueOf(x.get("runId")));
        }
        LocalDate month = LocalDate.parse(String.valueOf(r.get("period")));
        LocalDate deposited = LocalDate.parse(String.valueOf(r.get("depositedOn")));
        LocalDate due = due(c, month);
        Long late = due == null ? null : Math.max(0, ChronoUnit.DAYS.between(due, deposited));
        return new Challan(String.valueOf(r.get("id")), month.toString(), (String) r.get("deductorType"), (String) r.get("minorHead"),
                (String) r.get("bsrCode"), (String) r.get("serial"), deposited.toString(), PayrollEngine.dec(r, "tax"),
                PayrollEngine.dec(r, "surcharge"), PayrollEngine.dec(r, "cess"), PayrollEngine.dec(r, "interest"), PayrollEngine.dec(r, "fee"),
                PayrollEngine.dec(r, "total"), (String) r.get("status"), runs, late);
    }

    /**
     * When the tax deducted in a month falls due: the rule's day of the next month, or its March date in April of the
     * same calendar year as that March. Null while the rule in force carries no due dates, so nothing is guessed.
     */
    static LocalDate due(Connection c, LocalDate month) throws SQLException {
        Map<String, Object> rule = Sql.one(c, "select data from app.payroll_rule where statute = 'income_tax' and jurisdiction = 'IN' "
                + "and valid_from <= ? and (valid_to is null or valid_to > ?) order by valid_from desc limit 1", month, month);
        Map<String, Object> data = rule == null ? Map.of() : parse(rule.get("data"));
        if (month.getMonthValue() == 3) {
            return data.get("tdsDueMarch") == null ? null : MonthDay.parse("--" + data.get("tdsDueMarch")).atYear(month.getYear());
        }
        return data.get("tdsDueDay") == null ? null : month.plusMonths(1).withDayOfMonth(((Number) data.get("tdsDueDay")).intValue());
    }

    // ── small helpers ───────────────────────────────────────────────────

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("That is not a payroll run id");
        }
    }

    private static BigDecimal amount(BigDecimal v, String what) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (v.signum() < 0 || v.scale() > 2) {
            throw ApiException.badRequest("The " + what + " must be an amount in rupees and paise, not negative");
        }
        return v.setScale(2);
    }

    private String taxYear(String value) {
        return TaxYears.check(value, TaxYears.of(today()));
    }

    private static LocalDate month(String value) {
        try {
            return java.time.YearMonth.parse(value).atDay(1);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the month as yyyy-mm");
        }
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the deposit date as yyyy-mm-dd");
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
