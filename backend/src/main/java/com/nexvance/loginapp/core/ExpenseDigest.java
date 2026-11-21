package com.nexvance.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The daily digest (docs/expenses/design.md, Notifications): each approver, and each person in finance, hears once a day of the
 * expense claims and loan requests that have waited on them for three days or more. Nothing waiting that long, nothing sent. It is
 * the message {@code expense_digest}, which an Organization can edit or switch off. Safe to run twice in a day, and on two servers.
 */
public class ExpenseDigest {

    /** How long something waits before it is mentioned. */
    static final int DAYS = 3;

    private final Db db;
    private final ExpenseService expenses;

    public ExpenseDigest(Db db, ExpenseService expenses) {
        this.db = db;
        this.expenses = expenses;
    }

    /** Sends today's digest in every Organization that has Expenses. Returns how many people were told. */
    public int run() {
        int told = 0;
        for (Map<String, Object> t : db.inPlatform(c -> Sql.query(c, "select id from platform.tenant where status <> 'closed' order by created_at"))) {
            told += runFor(AuthService.uuid(t.get("id")));
        }
        return told;
    }

    /** One Organization's digest. */
    public int runFor(UUID tenantId) {
        return db.inTenant(tenantId, c -> {
            if (!Modules.on(c, Modules.EXPENSES)
                    || !Boolean.TRUE.equals(Sql.one(c, "select pg_try_advisory_xact_lock(hashtext('expense_digest:' || ?)) as got", tenantId.toString()).get("got"))) {
                return 0;
            }
            Map<UUID, int[]> waiting = new LinkedHashMap<>();   // person -> claims, loan requests
            String old = "< now() - make_interval(days => " + DAYS + ")";
            // With an approver: the one named when it was sent, or the manager.
            for (Map<String, Object> k : Sql.query(c, "select approver_membership_id, membership_id from app.expense_claim where status = 'submitted' and submitted_at " + old)) {
                add(waiting, approver(c, k), 0);
            }
            for (Map<String, Object> l : Sql.query(c, "select approver_membership_id, membership_id from app.loan where status = 'requested' and created_at " + old)) {
                add(waiting, approver(c, l), 1);
            }
            // With finance: everyone who can verify claims, or approve loans.
            int claims = (int) Sql.count(c, "select 1 from app.expense_claim where status = 'approved' and coalesce(manager_at, submitted_at) " + old);
            int loans = (int) Sql.count(c, "select 1 from app.loan where status = 'manager_approved' and coalesce(manager_at, created_at) " + old);
            if (claims > 0) {
                for (UUID f : ExpenseNotices.holders(c, "expense.finance")) {
                    waiting.computeIfAbsent(f, x -> new int[2])[0] += claims;
                }
            }
            if (loans > 0) {
                for (UUID f : ExpenseNotices.holders(c, "loan.manage")) {
                    waiting.computeIfAbsent(f, x -> new int[2])[1] += loans;
                }
            }
            int told = 0;
            for (Map.Entry<UUID, int[]> w : waiting.entrySet()) {
                if (Sql.exists(c, "select 1 from app.notification where membership_id = ? and event = 'expense_digest' "
                        + "and created_at >= date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata'", w.getKey())) {
                    continue;   // already told today
                }
                Map<String, String> v = new LinkedHashMap<>();
                v.put("digest.what", what(w.getValue()[0], w.getValue()[1]));
                v.put("person.first_name", firstName(c, w.getKey()));
                ExpenseNotices.tell(c, tenantId, "expense_digest", w.getKey(), v);
                told++;
            }
            return told;
        });
    }

    private UUID approver(Connection c, Map<String, Object> row) throws SQLException {
        return row.get("approverMembershipId") != null ? AuthService.uuid(row.get("approverMembershipId"))
                : expenses.approverFor(c, AuthService.uuid(row.get("membershipId")));
    }

    private static void add(Map<UUID, int[]> waiting, UUID who, int slot) {
        if (who != null) {
            waiting.computeIfAbsent(who, x -> new int[2])[slot]++;
        }
    }

    static String what(int claims, int loans) {
        List<String> parts = new java.util.ArrayList<>();
        if (claims > 0) {
            parts.add(claims + (claims == 1 ? " expense claim" : " expense claims"));
        }
        if (loans > 0) {
            parts.add(loans + (loans == 1 ? " loan request" : " loan requests"));
        }
        return String.join(" and ", parts) + (claims + loans == 1 ? " has been" : " have been");
    }

    private static String firstName(Connection c, UUID membership) throws SQLException {
        String name = (String) Sql.one(c, "select " + Names.orEmail("m.id") + " as person from app.membership m where m.id = ?", membership).get("person");
        int i = name.indexOf(' ');
        return i > 0 ? name.substring(0, i) : name;
    }
}
