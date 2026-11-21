package com.nexvance.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The notifications of claims and loans (docs/expenses/design.md): who has something waiting, and what became of a request.
 * Each is a message template of the Organization, so it can be edited or switched off in Setup. The default texts are written
 * the first time one is sent.
 */
final class ExpenseNotices {

    private static final String[][] MESSAGES = {
            {"expense_waiting", "Expense claim waiting", "{{employee.name}}'s claim {{claim.number}} ({{claim.title}}, {{claim.amount}}) is waiting for your decision."},
            {"expense_finance_waiting", "Expense claim for finance", "{{employee.name}}'s claim {{claim.number}} ({{claim.title}}, {{claim.amount}}) is ready for finance to verify."},
            {"expense_returned", "Claim returned", "Hello {{person.first_name}}, your claim {{claim.number}} was returned: {{claim.note}}. Change it and send it again."},
            {"expense_rejected", "Claim rejected", "Hello {{person.first_name}}, your claim {{claim.number}} was rejected: {{claim.note}}."},
            {"expense_evidence_needed", "Claim needs more evidence", "Hello {{person.first_name}}, finance needs more evidence for your claim {{claim.number}}. Add it and send the claim back."},
            {"expense_verified", "Claim verified", "Hello {{person.first_name}}, your claim {{claim.number}} ({{claim.amount}}) is verified and will be paid."},
            {"expense_paid", "Claim paid", "Hello {{person.first_name}}, your claim {{claim.number}} ({{claim.amount}}) has been paid."},
            {"loan_waiting", "Loan request waiting", "{{employee.name}} asks for {{loan.type}} {{loan.number}} of {{loan.amount}}. It waits for your decision."},
            {"loan_finance_waiting", "Loan request for finance", "{{employee.name}}'s {{loan.type}} {{loan.number}} of {{loan.amount}} is ready for finance to approve."},
            {"loan_decided", "Your request was decided", "Hello {{person.first_name}}, your {{loan.type}} {{loan.number}} was {{loan.outcome}}. {{loan.note}}"},
            {"loan_paid_out", "Payout recorded", "Hello {{person.first_name}}, your {{loan.type}} {{loan.number}} of {{loan.amount}} was paid out. Repayment starts in {{loan.first_month}}."},
            {"loan_instalment_carried", "Loan instalments carried", "{{employee.name}}: {{loan.number}} has {{loan.count}} still due, {{loan.amount}} in all, the oldest from {{loan.since}}. Look at it under Expenses, Loans."},
            {"expense_digest", "Waiting on you for three days or more", "Hello {{person.first_name}}, {{digest.what}} waiting for your decision for three days or more. Open Expenses to decide."},
    };

    private ExpenseNotices() {}

    /** Writes the default texts unless the Organization has them. */
    static void ensure(Connection c, UUID tenantId) throws SQLException {
        String[] events = java.util.Arrays.stream(MESSAGES).map(m -> m[0]).toArray(String[]::new);
        if (Sql.count(c, "select 1 from app.message_template where event = any(?)", (Object) events) >= MESSAGES.length) {
            return;
        }
        for (String[] m : MESSAGES) {   // a message added in a later release joins the ones the Organization already has
            Sql.update(c, "insert into app.message_template (tenant_id, event, subject, body) values (?, ?, ?, ?) on conflict (tenant_id, event, channel) do nothing",
                    tenantId, m[0], m[1], m[2]);
        }
    }

    /** Everyone active who holds the permission across the Organization. */
    static java.util.List<UUID> holders(Connection c, String permission) throws SQLException {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : Sql.query(c, "select distinct m.id from app.membership m join app.membership_role mr on mr.membership_id = m.id "
                + "join app.role_permission rp on rp.role_id = mr.role_id where m.status = 'active' and rp.permission_code = ? and rp.scope = 'tenant' order by m.id", permission)) {
            out.add(AuthService.uuid(m.get("id")));
        }
        return out;
    }

    static void tell(Connection c, UUID tenantId, String event, UUID to, Map<String, String> values) throws SQLException {
        if (to == null) {
            return;
        }
        ensure(c, tenantId);
        Notifications.send(c, tenantId, to, event, values, "expenses");
    }

    /** Everyone who holds the permission across the Organization, except one person (the one who just acted, or the claimant). */
    static void tellHolders(Connection c, UUID tenantId, String permission, String event, Map<String, String> values, UUID except) throws SQLException {
        ensure(c, tenantId);
        for (Map<String, Object> m : Sql.query(c, "select distinct m.id from app.membership m join app.membership_role mr on mr.membership_id = m.id "
                + "join app.role_permission rp on rp.role_id = mr.role_id where m.status = 'active' and rp.permission_code = ? and rp.scope = 'tenant' order by m.id", permission)) {
            UUID id = AuthService.uuid(m.get("id"));
            if (!id.equals(except)) {
                Notifications.send(c, tenantId, id, event, values, "expenses");
            }
        }
    }

    /** Tells the claimant of each claim, with one read of the claims and one write of the notifications, however many there are. */
    static void tellClaimants(Connection c, UUID tenantId, String event, java.util.List<UUID> claimIds) throws SQLException {
        if (claimIds.isEmpty()) {
            return;
        }
        ensure(c, tenantId);
        Map<String, Object> t = Sql.one(c, "select subject, body from app.message_template where event = ? and channel = 'in_app' and active", event);
        if (t == null) {
            return;
        }
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (Map<String, Object> k : Sql.query(c, "select k.number, k.title, k.membership_id, k.decision_note, " + Names.orEmail("k.membership_id") + " as person, "
                + "(select coalesce(sum(coalesce(l.approved_amount, l.amount)), 0) from app.expense_line l where l.claim_id = k.id) as amount "
                + "from app.expense_claim k where k.id = any(?)", (Object) claimIds.toArray(new UUID[0]))) {
            Map<String, String> v = values(k);
            rows.add(new Object[] {tenantId, AuthService.uuid(k.get("membershipId")), event, Templates.render((String) t.get("subject"), v),
                    Templates.render((String) t.get("body"), v), "expenses"});
        }
        Sql.batch(c, "insert into app.notification (tenant_id, membership_id, event, subject, body, link) values (?, ?, ?, ?, ?, ?)", rows);
    }

    static String rupees(Object amount) {
        return "₹" + ((BigDecimal) amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** The facts of a claim, for the texts. {@code note} is the reason of the latest decision. */
    static Map<String, String> claim(Connection c, UUID claimId) throws SQLException {
        Map<String, Object> k = Sql.one(c, "select k.number, k.title, k.membership_id, k.decision_note, " + Names.orEmail("k.membership_id") + " as person, "
                + "(select coalesce(sum(coalesce(l.approved_amount, l.amount)), 0) from app.expense_line l where l.claim_id = k.id) as amount "
                + "from app.expense_claim k where k.id = ?", claimId);
        return values(k);
    }

    private static Map<String, String> values(Map<String, Object> k) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("claim.number", (String) k.get("number"));
        v.put("claim.title", (String) k.get("title"));
        v.put("claim.amount", rupees(k.get("amount")));
        v.put("claim.note", k.get("decisionNote") == null ? "no reason given" : (String) k.get("decisionNote"));
        v.put("employee.name", (String) k.get("person"));
        v.put("person.first_name", first((String) k.get("person")));
        v.put("owner", String.valueOf(k.get("membershipId")));
        return v;
    }

    /** The facts of a loan, for the texts. */
    static Map<String, String> loan(Connection c, UUID loanId) throws SQLException {
        Map<String, Object> l = Sql.one(c, "select l.number, l.amount, l.membership_id, l.decision_note, l.first_month, t.name as type, " + Names.orEmail("l.membership_id") + " as person "
                + "from app.loan l join app.loan_type t on t.id = l.type_id where l.id = ?", loanId);
        Map<String, String> v = new LinkedHashMap<>();
        v.put("loan.number", (String) l.get("number"));
        v.put("loan.type", ((String) l.get("type")).toLowerCase());
        v.put("loan.amount", rupees(l.get("amount")));
        v.put("loan.note", l.get("decisionNote") == null ? "" : "Reason: " + l.get("decisionNote"));
        v.put("loan.first_month", l.get("firstMonth") == null ? "" : String.valueOf(l.get("firstMonth")).substring(0, 7));
        v.put("employee.name", (String) l.get("person"));
        v.put("person.first_name", first((String) l.get("person")));
        v.put("owner", String.valueOf(l.get("membershipId")));
        return v;
    }

    static UUID owner(Map<String, String> v) {
        return UUID.fromString(v.get("owner"));
    }

    private static String first(String name) {
        int i = name == null ? -1 : name.indexOf(' ');
        return name == null ? "" : i > 0 ? name.substring(0, i) : name;
    }
}
