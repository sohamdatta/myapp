package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** The clearance checklist of an exit. */
public final class ExitTasks {

    private ExitTasks() {}

    /** How many exit tasks wait for this person, for the menu. */
    public static int openCount(Connection c, UUID membershipId) throws SQLException {
        Map<String, Object> n = Sql.one(c, "select count(*) as n from app.exit_task t join app.exit_case x on x.id = t.exit_case_id "
                + "where t.assignee_id = ? and t.done_at is null and x.status in ('accepted','closed')", membershipId);
        return ((Number) n.get("n")).intValue();
    }
}
