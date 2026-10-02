package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * The four database connections the application uses. Which one a piece of
 * code runs on is the boundary between platform data and Organization data:
 *
 * <ul>
 *   <li>owner: migrations only</li>
 *   <li>platform: the console; cannot read Organization tables</li>
 *   <li>auth: sign-in, sessions and invitations; cannot read Organization tables</li>
 *   <li>tenant: one Organization per transaction, enforced by row-level security</li>
 * </ul>
 */
public final class Db {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final DataSource owner;
    private final DataSource platform;
    private final DataSource auth;
    private final DataSource tenant;

    public Db(DataSource owner, DataSource platform, DataSource auth, DataSource tenant) {
        this.owner = owner;
        this.platform = platform;
        this.auth = auth;
        this.tenant = tenant;
    }

    public <T> T inOwner(Work<T> work) {
        return tx(owner, work);
    }

    public <T> T inPlatform(Work<T> work) {
        return tx(platform, work);
    }

    public <T> T inAuth(Work<T> work) {
        return tx(auth, work);
    }

    /**
     * Runs the work as one Organization. The Organization is set for this
     * transaction only, so a pooled connection never carries it to the next request.
     */
    public <T> T inTenant(UUID tenantId, Work<T> work) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tx(tenant, c -> {
            Sql.query(c, "select set_config('app.tenant_id', ?, true)", tenantId.toString());
            return work.run(c);
        });
    }

    private static <T> T tx(DataSource ds, Work<T> work) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();   // deferred constraint triggers fire here
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** Turns the database's own rule violations into API errors. */
    private static RuntimeException translate(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (state.equals("23514") && message.contains("active owner")) {
            return new ApiException(409, "membership.last_owner",
                    "The Organization must keep at least one active Org Admin");
        }
        if (state.equals("23514") && message.contains("payroll_operator")) {
            return new ApiException(409, "role.maker_checker",
                    "One user cannot be both Payroll Operator and Payroll Approver");
        }
        if (state.equals("23505") && message.contains("invitation_pending_uq")) {
            return new ApiException(409, "membership.exists", "A pending invitation already exists for this email");
        }
        if (state.equals("23505") && message.contains("tenant_slug_key")) {
            return new ApiException(409, "tenant.slug_taken", "That slug is already in use");
        }
        if (state.equals("42501")) {
            return new ApiException(403, "permission.denied", "This action is not allowed");
        }
        if (state.equals("P0001")) {
            String first = message.lines().findFirst().orElse(message).replaceFirst("^ERROR:\\s*", "");
            return new ApiException(409, "request.refused", first);
        }
        return new IllegalStateException("Database error: " + message, e);
    }
}
