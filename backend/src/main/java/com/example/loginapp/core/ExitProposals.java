package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.UUID;

/**
 * Exits the app sees coming and proposes to HR: a fixed-term contract ending within 30 days, and
 * a retirement within 60. Each is an exit case marked proposed, which HR confirms (as any other
 * exit) or cancels when the contract is renewed or service extended; a cancelled proposal is not
 * made again for the same date. Proposals stay HR's until confirmed: the employee does not see them.
 */
final class ExitProposals {

    static final int CONTRACT_DAYS = 30;
    static final int RETIREMENT_DAYS = 60;

    private ExitProposals() {}

    /** Proposes what is due. Safe to run from any request: the open-exit index lets one of several racing requests in. */
    static void run(Connection c, UUID tenantId, LocalDate today) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select retirement_age from app.exit_settings");
        if (s == null) {
            return;   // exits never opened in this Organization
        }
        for (Map<String, Object> e : Sql.query(c, "select e.id, e.person_id, e.contract_end from app.employment e "
                + "where e.status = 'active' and e.employment_type = 'fixed_term' and e.contract_end between ? and ? "
                + "and not exists (select 1 from app.exit_case x where x.employment_id = e.id and x.kind = 'end_of_contract' "
                + "  and x.requested_last_day = e.contract_end)", today.minusDays(CONTRACT_DAYS), today.plusDays(CONTRACT_DAYS))) {
            propose(c, tenantId, e, "end_of_contract", "contract", LocalDate.parse((String) e.get("contractEnd")));
        }
        int age = ((Number) s.get("retirementAge")).intValue();
        // Retirement on the last day of the month the age is reached.
        for (Map<String, Object> e : Sql.query(c, "select e.id, e.person_id, p.date_of_birth from app.employment e "
                + "join app.person p on p.id = e.person_id where e.status = 'active' and p.date_of_birth is not null "
                + "and e.employment_type in ('permanent','probation') "
                + "and p.date_of_birth + make_interval(years => ?) <= ?", age, today.plusDays(RETIREMENT_DAYS))) {
            LocalDate day = LocalDate.parse((String) e.get("dateOfBirth")).plusYears(age).with(TemporalAdjusters.lastDayOfMonth());
            if (day.isBefore(today.minusDays(RETIREMENT_DAYS)) || Sql.one(c, "select 1 from app.exit_case where employment_id = ?::uuid "
                    + "and kind = 'retirement' and requested_last_day = ?", e.get("id"), day) != null) {
                continue;
            }
            propose(c, tenantId, e, "retirement", "retirement", day);
        }
    }

    private static void propose(Connection c, UUID tenantId, Map<String, Object> e, String kind, String reason, LocalDate day)
            throws SQLException {
        Map<String, Object> row = Sql.one(c, "insert into app.exit_case (tenant_id, employment_id, person_id, kind, status, reason_category, "
                + "requested_last_day) values (?, ?::uuid, ?::uuid, ?, 'proposed', ?, ?) on conflict do nothing returning id", tenantId,
                e.get("id"), e.get("personId"), kind, reason, day);
        if (row == null) {
            return;   // an exit is already open for this employment
        }
        UUID id = AuthService.uuid(row.get("id"));
        Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_label, action, target_type, target_id, detail) "
                + "values (?, 'system', 'system', 'exit.proposed', 'exit', ?, ?::jsonb)", tenantId, id,
                Json.object(Map.of("kind", kind, "lastWorkingDay", day.toString())));
        Map<String, String> v = ExitValues.forCase(c, id);
        for (UUID hr : ExitService.hrMembers(c)) {
            Notifications.send(c, tenantId, hr, "exit_proposed", v, "exits");
        }
    }
}
