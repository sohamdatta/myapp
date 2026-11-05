package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an Organization can be sold. Core HR is always included; these are switched on per
 * Organization by the platform (V16; Leave split from Attendance in V19). Switching one off hides
 * it and deletes nothing.
 */
public final class Modules {

    public static final String ATTENDANCE = "attendance";
    /** Balances, leave requests, who is away, leave types and the year end. Not work from home. */
    public static final String LEAVE = "leave";
    public static final String PAYROLL = "payroll";

    /** In the order the console shows them, with the words people see. */
    public static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put(ATTENDANCE, "Attendance");
        NAMES.put(LEAVE, "Leave");
        NAMES.put(PAYROLL, "Payroll");
    }

    public static final List<String> ALL = List.copyOf(NAMES.keySet());

    /** What the console sells as one thing: Attendance with Leave. Switching it applies to both; each still works alone. */
    public static final String ATTENDANCE_LEAVE = "attendance_leave";

    /** The modules a console choice stands for: the pair for {@link #ATTENDANCE_LEAVE}, otherwise the module itself. */
    public static List<String> expand(String choice) {
        return ATTENDANCE_LEAVE.equals(choice) ? List.of(ATTENDANCE, LEAVE) : List.of(choice);
    }

    /** Whether this is a module, or the Attendance-with-Leave choice. */
    public static boolean known(String choice) {
        return ALL.contains(choice) || ATTENDANCE_LEAVE.equals(choice);
    }

    private Modules() {}

    public static String name(String module) {
        return NAMES.getOrDefault(module, module);
    }

    /** Whether the module is on for the Organization of this tenant transaction. */
    static boolean on(Connection c, String module) throws SQLException {
        return Boolean.TRUE.equals(Sql.one(c, "select app.module_on(?) as on", module).get("on"));
    }

    /** The refusal for an Organization without the module. */
    public static ApiException off(String module, String organization) {
        return new ApiException(403, "module.off", name(module) + " is not part of " + (organization == null ? "this Organization" : organization)
                + "'s subscription. The Org Admin can ask for it to be added");
    }
}
