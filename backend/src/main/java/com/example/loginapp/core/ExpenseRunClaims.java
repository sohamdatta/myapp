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

    /** What a person's verified claims come to, by category: {@code items} (name, ledger, amount) for the payslip, {@code claims} (id, amount) to reserve. */
    static Map<String, Object> dueFor(Connection c, UUID membershipId) throws SQLException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select g.name, g.ledger, sum(coalesce(l.approved_amount, l.amount)) as amount "
                + "from app.expense_claim k join app.expense_line l on l.claim_id = k.id join app.expense_category g on g.id = l.category_id "
                + "where k.membership_id = ? and k.status = 'verified' group by g.name, g.ledger order by g.name", membershipId)) {
            if (((BigDecimal) r.get("amount")).signum() > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.get("name"));
                m.put("ledger", r.get("ledger"));
                m.put("amount", r.get("amount"));
                items.add(m);
            }
        }
        List<Map<String, Object>> claims = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select k.id, (select sum(coalesce(l.approved_amount, l.amount)) from app.expense_line l where l.claim_id = k.id) as amount "
                + "from app.expense_claim k where k.membership_id = ? and k.status = 'verified' order by k.finance_at", membershipId)) {
            if (r.get("amount") != null && ((BigDecimal) r.get("amount")).signum() > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", String.valueOf(r.get("id")));
                m.put("amount", r.get("amount"));
                claims.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("claims", claims);
        return out;
    }

    /** Takes the claims up for this run. */
    static void reserve(Connection c, UUID tenantId, UUID runId, UUID employmentId, List<Map<String, Object>> claims) throws SQLException {
        for (Map<String, Object> k : claims) {
            UUID id = AuthService.uuid(k.get("id"));
            if (Sql.update(c, "update app.expense_claim set status = 'scheduled', version = version + 1, updated_at = now() where id = ? and status = 'verified'", id) == 1) {
                Sql.update(c, "insert into app.expense_run_claim (tenant_id, run_id, claim_id, employment_id, amount) values (?, ?, ?, ?, ?)",
                        tenantId, runId, id, employmentId, k.get("amount"));
            }
        }
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
        for (Map<String, Object> g : going) {
            Map<String, String> told = ExpenseNotices.claim(c, AuthService.uuid(g.get("claimId")));
            ExpenseNotices.tell(c, tenantId, "expense_paid", ExpenseNotices.owner(told), told);
        }
    }
}
