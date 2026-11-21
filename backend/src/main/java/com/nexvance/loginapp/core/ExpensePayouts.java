package com.nexvance.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paying verified expense claims by bank file (docs/expenses/design.md, stage X3). Finance gathers verified claims into a batch,
 * downloads the bank file, pays it at the bank, and marks the batch paid with the bank's reference. Claims in an open batch are
 * {@code scheduled}; marking the batch paid makes them {@code paid}. Cancelling an open batch returns its claims to {@code verified}.
 */
public final class ExpensePayouts {

    public record BatchMade(UUID id, String number, BigDecimal total, int claims) {}

    public record PaidInput(String paidOn, String reference) {}

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public ExpensePayouts(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public ExpensePayouts(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    /** Verified claims waiting for payment, with whether the person has a bank account to pay into. */
    public List<Map<String, Object>> payable(SessionCtx ctx) {
        ctx.require("expense.finance");
        return db.inTenant(ctx.tenantId(), c -> Lists.withTotal(Sql.query(c,
                "select k.id, k.number, k.title, k.membership_id, " + Names.orEmail("k.membership_id") + " as claimant, k.finance_at as verified_at, "
                        + "(select coalesce(sum(coalesce(l.approved_amount, l.amount)), 0) from app.expense_line l where l.claim_id = k.id) - k.advance_applied as payable, "
                        + "exists (select 1 from app.employment e join app.bank_account b on b.person_id = e.person_id "
                        + "  where e.membership_id = k.membership_id and b.effective_from <= ?) as has_bank "
                        + "from app.expense_claim k where k.status = 'verified' order by k.finance_at limit 500", today()),
                Sql.count(c, "select 1 from app.expense_claim k where k.status = 'verified'")));
    }

    public List<Map<String, Object>> batches(SessionCtx ctx) {
        ctx.require("expense.finance");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select b.id, b.number, b.status, b.total, b.created_at, b.paid_on, b.bank_reference, " + Names.orEmail("b.created_by") + " as created_by_name, "
                        + "(select count(*) from app.expense_payment p where p.batch_id = b.id) as claims "
                        + "from app.expense_batch b order by b.created_at desc limit 100"));
    }

    /** The claims of one batch, for the screen. */
    public List<Map<String, Object>> batchClaims(SessionCtx ctx, UUID batchId) {
        ctx.require("expense.finance");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select k.id, k.number, k.title, " + Names.orEmail("k.membership_id") + " as claimant, p.amount, p.status "
                        + "from app.expense_payment p join app.expense_claim k on k.id = p.claim_id where p.batch_id = ? order by k.number", batchId));
    }

    /** Puts verified claims into a new batch. Nobody includes their own claim, and everyone in it needs a bank account. */
    public BatchMade createBatch(SessionCtx ctx, List<UUID> claimIds) {
        ctx.require("expense.finance");
        if (claimIds == null || claimIds.isEmpty()) {
            throw ApiException.badRequest("Choose at least one claim");
        }
        if (claimIds.size() > 500) {
            throw ApiException.badRequest("A batch holds at most 500 claims");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            BigDecimal total = BigDecimal.ZERO;
            List<Object[]> rows = new ArrayList<>();
            List<String> noBank = new ArrayList<>();
            for (UUID id : new java.util.LinkedHashSet<>(claimIds)) {
                Map<String, Object> k = Sql.one(c, "select id, number, membership_id, status from app.expense_claim where id = ? for update", id);
                if (k == null) {
                    throw ApiException.notFound("A claim");
                }
                if (!"verified".equals(k.get("status"))) {
                    throw new ApiException(409, "expense.not_payable", "Claim " + k.get("number") + " is not waiting for payment");
                }
                if (ctx.membershipId().equals(AuthService.uuid(k.get("membershipId")))) {
                    throw new ApiException(403, "expense.own_claim", "You cannot pay your own claim");
                }
                BigDecimal amount = (BigDecimal) Sql.one(c, "select coalesce(sum(coalesce(approved_amount, amount)), 0) "   // less what an advance covered
                        + "- (select advance_applied from app.expense_claim where id = ?) as s from app.expense_line where claim_id = ?", id, id).get("s");
                if (amount.signum() <= 0) {
                    throw new ApiException(409, "expense.nothing_payable", "Claim " + k.get("number") + " has nothing to pay");
                }
                if (!Sql.exists(c, "select 1 from app.employment e join app.bank_account b on b.person_id = e.person_id "
                        + "where e.membership_id = ? and b.effective_from <= ?", AuthService.uuid(k.get("membershipId")), today())) {
                    noBank.add((String) k.get("number"));
                }
                total = total.add(amount);
                rows.add(new Object[] {id, amount});
            }
            if (!noBank.isEmpty()) {
                throw new ApiException(409, "expense.no_bank", "No bank account on record for the claimant of " + String.join(", ", noBank)
                        + ". Add it in their employee record, or leave the claim out");
            }
            int year = today().getYear();
            Map<String, Object> n = Sql.one(c, "insert into app.expense_batch_counter (tenant_id, year, last) values (?, ?, 1) "
                    + "on conflict (tenant_id, year) do update set last = app.expense_batch_counter.last + 1 returning last", ctx.tenantId(), year);
            String number = "EXB-" + year + "-" + String.format("%04d", ((Number) n.get("last")).intValue());
            UUID batch = AuthService.uuid(Sql.one(c, "insert into app.expense_batch (tenant_id, number, total, created_by) values (?, ?, ?, ?) returning id",
                    ctx.tenantId(), number, total, ctx.membershipId()).get("id"));
            for (Object[] r : rows) {
                Sql.update(c, "insert into app.expense_payment (tenant_id, claim_id, batch_id, amount) values (?, ?, ?, ?)", ctx.tenantId(), r[0], batch, r[1]);
                Sql.update(c, "update app.expense_claim set status = 'scheduled', version = version + 1, updated_at = now() where id = ?", r[0]);
            }
            UserService.audit(c, ctx, "expense.batch_created", "expense_batch", batch, Map.of("number", number, "claims", rows.size(), "total", total.toPlainString()));
            return new BatchMade(batch, number, total, rows.size());
        });
    }

    /** Takes an open batch apart: its claims are verified again and can join another batch. */
    public void cancel(SessionCtx ctx, UUID batchId) {
        ctx.require("expense.finance");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> b = lockOpen(c, batchId);
            Sql.update(c, "update app.expense_claim set status = 'verified', version = version + 1, updated_at = now() "
                    + "where id in (select claim_id from app.expense_payment where batch_id = ? and status = 'scheduled')", batchId);
            Sql.update(c, "update app.expense_payment set status = 'cancelled' where batch_id = ? and status = 'scheduled'", batchId);
            Sql.update(c, "update app.expense_batch set status = 'cancelled', cancelled_by = ?, cancelled_at = now() where id = ?", ctx.membershipId(), batchId);
            UserService.audit(c, ctx, "expense.batch_cancelled", "expense_batch", batchId, Map.of("number", b.get("number")));
            return null;
        });
    }

    /** Records that the bank paid the batch. Every claim in it becomes paid. */
    public void markPaid(SessionCtx ctx, UUID batchId, PaidInput in) {
        ctx.require("expense.finance");
        StepUp.require(db, ctx);
        String reference = in == null || in.reference() == null ? "" : in.reference().trim();
        if (reference.length() < 3 || reference.length() > 60) {
            throw ApiException.badRequest("Type the bank reference of the payment, 3 to 60 characters");
        }
        LocalDate on = in.paidOn() == null || in.paidOn().isBlank() ? today() : AttendanceDays.date(in.paidOn());
        if (on.isAfter(today())) {
            throw ApiException.badRequest("The payment date cannot be in the future");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> b = lockOpen(c, batchId);
            Sql.update(c, "update app.expense_claim set status = 'paid', version = version + 1, updated_at = now() "
                    + "where id in (select claim_id from app.expense_payment where batch_id = ? and status = 'scheduled')", batchId);
            Sql.update(c, "update app.expense_payment set status = 'paid' where batch_id = ? and status = 'scheduled'", batchId);
            Sql.update(c, "update app.expense_batch set status = 'paid', paid_on = ?, paid_by = ?, bank_reference = ? where id = ?",
                    on, ctx.membershipId(), reference, batchId);
            UserService.audit(c, ctx, "expense.batch_paid", "expense_batch", batchId,
                    Map.of("number", b.get("number"), "paidOn", on.toString(), "reference", reference));
            ExpenseNotices.tellClaimants(c, ctx.tenantId(), "expense_paid", Sql.query(c, "select claim_id from app.expense_payment where batch_id = ?", batchId)
                    .stream().map(p -> AuthService.uuid(p.get("claimId"))).toList());
            return null;
        });
    }

    /** The bank file for a batch: the same columns as the salary file, one row for each claim, with a control total. */
    public DocumentService.Content bankFile(SessionCtx ctx, UUID batchId) {
        ctx.require("expense.finance");
        StepUp.require(db, ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> b = Sql.one(c, "select id, number, status from app.expense_batch where id = ?", batchId);
            if (b == null) {
                throw ApiException.notFound("The batch");
            }
            if (!"open".equals(b.get("status")) && !"paid".equals(b.get("status"))) {
                throw new ApiException(409, "expense.batch_state", "A cancelled batch has no bank file");
            }
            Map<String, Object> s = Sql.one(c, "select debit_account from app.payroll_settings");
            String debit = s == null || s.get("debitAccount") == null ? "" : String.valueOf(s.get("debitAccount"));
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("Transaction type", "Beneficiary account number", "IFSC", "Beneficiary name", "Amount", "Value date", "Narration",
                    "Employee code", "Debit account"));
            BigDecimal total = BigDecimal.ZERO;
            int count = 0;
            // Sql leaves bytea out on purpose; the sealed account numbers are read here and opened one by one.
            try (var st = c.prepareStatement("select p.amount, k.number, e.employee_code, ba.holder_name, ba.account_enc, ba.ifsc "
                    + "from app.expense_payment p join app.expense_claim k on k.id = p.claim_id "
                    + "join lateral (select * from app.employment e where e.membership_id = k.membership_id order by e.joining_date desc limit 1) e on true "
                    + "join lateral (select * from app.bank_account x where x.person_id = e.person_id and x.effective_from <= ? "
                    + "  order by x.effective_from desc, x.created_at desc limit 1) ba on true "
                    + "where p.batch_id = ? and p.status <> 'cancelled' order by k.number")) {
                st.setObject(1, today());
                st.setObject(2, batchId);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        String ifsc = rs.getString("ifsc");
                        BigDecimal amount = rs.getBigDecimal("amount");
                        rows.add(List.of(ifsc.startsWith("HDFC") ? "I" : "N", crypto.decryptText(ctx.tenantId(), rs.getBytes("account_enc")), ifsc,
                                rs.getString("holder_name"), PayrollRuns.money(amount), today().format(DMY), "Expense claim " + rs.getString("number"),
                                rs.getString("employee_code") == null ? "" : rs.getString("employee_code"), debit));
                        total = total.add(amount);
                        count++;
                    }
                }
            }
            rows.add(List.of("Total", count + " payments", "", "", PayrollRuns.money(total), "", "", "", ""));
            UserService.audit(c, ctx, "expense.bank_file_downloaded", "expense_batch", batchId, Map.of("number", b.get("number")));
            return PayrollOutputs.csv("Expense payments " + b.get("number") + ".csv", rows);
        });
    }

    private static Map<String, Object> lockOpen(Connection c, UUID batchId) throws SQLException {
        Map<String, Object> b = Sql.one(c, "select id, number, status from app.expense_batch where id = ? for update", batchId);
        if (b == null) {
            throw ApiException.notFound("The batch");
        }
        if (!"open".equals(b.get("status"))) {
            throw new ApiException(409, "expense.batch_state", "Only an open batch can be changed");
        }
        return b;
    }
}
