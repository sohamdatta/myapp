package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the performance services share: who a caller may see and write about, names, encrypted
 * text, and the checks every input goes through.
 */
final class Perf {

    private Perf() {}

    /** A person's name in a list: the preferred name when there is one. */
    static final String NAME = "coalesce(p.preferred_name, p.legal_name)";

    /** The display name of a membership: the name on their record when they have one, otherwise their email. */
    static String who(String membershipColumn) {
        return "coalesce((select coalesce(wp.preferred_name, wp.legal_name) from app.employment we join app.person wp on wp.id = we.person_id "
                + "where we.membership_id = " + membershipColumn + " order by (we.status = 'active') desc, we.joining_date desc limit 1), "
                + "(select wd.email::text from app.member_directory() wd where wd.membership_id = " + membershipColumn + "))";
    }

    /** The current job of an employment, joined as {@code j}, with its department {@code d} and manager's person {@code mp}. */
    static final String CURRENT_JOB = "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
            + "  and j.effective_from <= current_date order by j.effective_from desc, j.created_at desc limit 1) j on true "
            + "left join app.department d on d.id = j.department_id "
            + "left join app.employment me on me.id = j.manager_id left join app.person mp on mp.id = me.person_id ";

    // ── who may see and write ───────────────────────────────────────────

    /**
     * The employments a permission lets the caller reach: null when it reaches everyone, otherwise
     * the people who report to them today (empty when the scope is self, or when they manage nobody).
     */
    static UUID[] reach(Connection c, SessionCtx ctx, String permission, LocalDate today) throws SQLException {
        if (ctx.reachesEveryone(permission)) {
            return null;
        }
        return Team.reportEmployments(c, ctx, permission, today);
    }

    static boolean reaches(Connection c, SessionCtx ctx, String permission, UUID employmentId, LocalDate today) throws SQLException {
        UUID[] reach = reach(c, ctx, permission, today);
        return reach == null || Arrays.asList(reach).contains(employmentId);
    }

    /** True when the employment is the caller's own. */
    static boolean own(Connection c, SessionCtx ctx, UUID employmentId) throws SQLException {
        if (ctx.membershipId() == null) {
            return false;
        }
        return Sql.one(c, "select 1 from app.employment where id = ? and membership_id = ?", employmentId, ctx.membershipId()) != null;
    }

    /** The caller's own current employment, or null. */
    static Map<String, Object> ownEmployment(Connection c, SessionCtx ctx) throws SQLException {
        if (ctx.membershipId() == null) {
            return null;
        }
        return Sql.one(c, "select * from app.employment where membership_id = ? and status = 'active' order by joining_date desc limit 1",
                ctx.membershipId());
    }

    static void requireMember(SessionCtx ctx) {
        if (ctx.membershipId() == null || ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "Only a user of the Organization can do this");
        }
    }

    static void requireSomeone(SessionCtx ctx, String permission) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        String scope = ctx.scopeOf(permission);
        if (scope == null || scope.equals("self")) {
            throw ApiException.denied(permission);
        }
    }

    static boolean canManage(SessionCtx ctx) {
        return ctx.canAcrossOrganization("performance.manage") && ctx.membershipId() != null && !ctx.isSupport();
    }

    static void requireManage(SessionCtx ctx) {
        if (!canManage(ctx)) {
            throw ApiException.denied("performance.manage");
        }
    }

    // ── encrypted text ──────────────────────────────────────────────────

    static byte[] seal(Crypto crypto, SessionCtx ctx, String text) {
        return text == null ? null : crypto.encrypt(ctx.tenantId(), text);
    }

    /** Reads one encrypted column of one row; null when it is empty. */
    static String open(Connection c, Crypto crypto, UUID tenantId, String table, String column, Object id) throws SQLException {
        if (!table.matches("[a-z_]+") || !column.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Not a column: " + table + "." + column);
        }
        try (PreparedStatement ps = c.prepareStatement("select " + column + " from app." + table + " where id = ?::uuid")) {
            ps.setObject(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBytes(1) != null ? crypto.decryptText(tenantId, rs.getBytes(1)) : null;
            }
        }
    }

    // ── inputs ──────────────────────────────────────────────────────────

    static String text(String value, String what, int min, int max) {
        String t = value == null ? "" : value.trim();
        if (t.length() < min) {
            throw ApiException.badRequest(min <= 1 ? "Write " + what : "Write " + what + ", in at least " + min + " characters");
        }
        if (t.length() > max) {
            throw ApiException.badRequest("Keep " + what + " to " + String.format("%,d", max) + " characters");
        }
        return t.isEmpty() ? null : t;
    }

    static LocalDate date(String value, String what) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Give the " + what);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("The " + what + " is not a date");
        }
    }

    static UUID id(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("Choose the " + what);
        }
    }

    static String oneOf(String value, List<String> allowed, String message) {
        String v = value == null ? "" : value.trim();
        if (!allowed.contains(v)) {
            throw ApiException.badRequest(message);
        }
        return v;
    }

    static String date(Object iso) {
        return iso == null ? "" : TemplateValues.DATE.format(LocalDate.parse(iso.toString().substring(0, 10)));
    }
}
