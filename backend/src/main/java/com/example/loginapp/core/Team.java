package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A manager's team: everyone whose current job assignment names the manager's
 * employment as their reporting manager. Team-scoped permissions (a Manager's
 * attendance.read, attendance.approve, leave.read and leave.approve) reach these
 * people and nobody else.
 */
public final class Team {

    private Team() {}

    /**
     * Who a permission lets the caller reach, other than themselves: null when it reaches
     * everyone in the Organization, otherwise the memberships of their direct reports
     * (empty when the scope is self, or when they manage nobody).
     */
    public static UUID[] reach(Connection c, SessionCtx ctx, String permission, LocalDate on) throws SQLException {
        if (ctx.reachesEveryone(permission)) {
            return null;
        }
        if (!"team".equals(ctx.scopeOf(permission)) || ctx.membershipId() == null) {
            return new UUID[0];
        }
        return members(c, ctx.membershipId(), on);
    }

    /** True when the permission reaches this membership (other than the caller's own). */
    public static boolean reaches(Connection c, SessionCtx ctx, String permission, UUID membershipId, LocalDate on)
            throws SQLException {
        UUID[] reach = reach(c, ctx, permission, on);
        if (reach == null) {
            return true;
        }
        for (UUID id : reach) {
            if (id.equals(membershipId)) {
                return true;
            }
        }
        return false;
    }

    /** The memberships of the people who report to this membership's employment on a date. */
    public static UUID[] members(Connection c, UUID managerMembershipId, LocalDate on) throws SQLException {
        return Sql.query(c,
                "select e.membership_id from app.employment e "
                        + "join lateral (select j.manager_id from app.job_assignment j where j.employment_id = e.id "
                        + "  and j.effective_from <= ? order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                        + "join app.employment boss on boss.id = cur.manager_id "
                        + "where boss.membership_id = ? and boss.status = 'active' "
                        + "and e.status = 'active' and e.membership_id is not null",
                on, managerMembershipId).stream()
                .map(r -> AuthService.uuid(r.get("membershipId"))).toArray(UUID[]::new);
    }

    /**
     * The employments that report to this membership's employment on a date, whether or not they
     * have a login: a manager sees the records of their whole team, not only of those who sign in.
     */
    public static UUID[] reportEmployments(Connection c, SessionCtx ctx, String permission, LocalDate on) throws SQLException {
        if (!"team".equals(ctx.scopeOf(permission)) || ctx.membershipId() == null) {
            return new UUID[0];
        }
        return Sql.query(c,
                "select e.id from app.employment e "
                        + "join lateral (select j.manager_id from app.job_assignment j where j.employment_id = e.id "
                        + "  and j.effective_from <= ? order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                        + "join app.employment boss on boss.id = cur.manager_id "
                        + "where boss.membership_id = ? and boss.status = 'active' and e.status = 'active'",
                on, ctx.membershipId()).stream()
                .map(r -> AuthService.uuid(r.get("id"))).toArray(UUID[]::new);
    }
}
