package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verified expense claims paid with the salary (docs/expenses/design.md, stage X3). When a run is frozen it takes up each person's
 * verified claims: they become 'scheduled' (so they cannot also go in a bank batch) and the payslip gets one reimbursement line per
 * category, outside gross and not taxable. Approving the run pays them; a held payslip gives its claims back, and reopening the run
 * gives all of them back.
 */
final class ExpenseRunClaims {

    /** The component the lines sit under; each line is named for its category. */
    static final String COMPONENT = "REIMB";
    static final String PREFIX = "Reimbursement: ";

    private ExpenseRunClaims() {}

    /**
     * Takes up the verified claims of the run's people, before the snapshot is made, in one statement. A claim an expense advance
     * covered in part is left for a bank batch: its payslip lines, by category, would not add up to what is still to pay. A claim another transaction is
     * putting in a bank batch at the same moment is waited for and then left out (its status is checked again), so no claim is paid twice.
     */
    static void reserve(Connection c, UUID runId, UUID[] employments) throws SQLException {
        Sql.update(c, "with picked as (select distinct on (k.id) k.id, e.id as employment_id, "
                + "  (select coalesce(sum(coalesce(l.approved_amount, l.amount)), 0) from app.expense_line l where l.claim_id = k.id) as amount "
                + "  from app.expense_claim k join app.employment e on e.membership_id = k.membership_id "
                + "  where k.status = 'verified' and k.advance_applied = 0 and e.id = any(?) order by k.id, e.id), "
                + "taken as (update app.expense_claim k set status = 'scheduled', version = version + 1, updated_at = now() "
                + "  where k.id in (select id from picked where amount > 0) and k.status = 'verified' returning k.id) "
                + "insert into app.expense_run_claim (tenant_id, run_id, claim_id, employment_id, amount) "
                + "select app.current_tenant(), ?, p.id, p.employment_id, p.amount from picked p join taken t on t.id = p.id", employments, runId);
    }

    /** What the run has taken up, by person and category: employment id to the payslip's items (name, ledger, amount). One query for the run. */
    static Map<String, List<Map<String, Object>>> reserved(Connection c, UUID runId) throws SQLException {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : Sql.query(c, "select rc.employment_id, g.name, g.ledger, sum(coalesce(l.approved_amount, l.amount)) as amount "
                + "from app.expense_run_claim rc join app.expense_line l on l.claim_id = rc.claim_id join app.expense_category g on g.id = l.category_id "
                + "where rc.run_id = ? and rc.status = 'reserved' group by rc.employment_id, g.name, g.ledger order by g.name", runId)) {
            if (((BigDecimal) r.get("amount")).signum() > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.get("name"));
                m.put("ledger", r.get("ledger"));
                m.put("amount", r.get("amount"));
                out.computeIfAbsent(String.valueOf(r.get("employmentId")), k -> new ArrayList<>()).add(m);
            }
        }
        return out;
    }

    /** Gives every claim of the run back to finance, for a run that is reopened. */
    static void release(Connection c, UUID runId) throws SQLException {
        Sql.update(c, "update app.expense_claim set status = 'verified', version = version + 1, updated_at = now() "
                + "where id in (select claim_id from app.expense_run_claim where run_id = ? and status = 'reserved')", runId);
        Sql.update(c, "delete from app.expense_run_claim where run_id = ? and status = 'reserved'", runId);
    }

    /** Approving the run: claims on a payslip that goes out are paid; those on a held payslip go back to finance for the run after release. */
    static void settleRun(Connection c, UUID tenantId, UUID runId) throws SQLException {
        List<Map<String, Object>> going = Sql.query(c, "select rc.claim_id from app.expense_run_claim rc join app.payslip ps on ps.run_id = rc.run_id "
                + "and ps.employment_id = rc.employment_id where rc.run_id = ? and rc.status = 'reserved' and not ps.held", runId);
        Sql.update(c, "update app.expense_claim set status = 'paid', version = version + 1, updated_at = now() where id in "
                + "(select rc.claim_id from app.expense_run_claim rc join app.payslip ps on ps.run_id = rc.run_id and ps.employment_id = rc.employment_id "
                + " where rc.run_id = ? and rc.status = 'reserved' and not ps.held)", runId);
        Sql.update(c, "update app.expense_run_claim rc set status = 'paid' from app.payslip ps where rc.run_id = ? and rc.status = 'reserved' "
                + "and ps.run_id = rc.run_id and ps.employment_id = rc.employment_id and not ps.held", runId);
        release(c, runId);   // what is still reserved belongs to a held payslip
        List<UUID> paid = new ArrayList<>();
        for (Map<String, Object> g : going) {
            paid.add(AuthService.uuid(g.get("claimId")));
        }
        ExpenseNotices.tellClaimants(c, tenantId, "expense_paid", paid);
    }
}
