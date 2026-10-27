package com.example.loginapp.core;

import java.util.List;
import java.util.Set;

/** Which roles must use two-step sign-in under each choice of the Organization setting. */
public final class MfaPolicy {
    private static final Set<String> ADMINS = Set.of("owner", "hr_admin", "it_admin");
    private static final Set<String> PAYROLL = Set.of("payroll_operator", "payroll_approver");

    private MfaPolicy() {}

    /** Whether a member holding these role codes must use two-step sign-in under the chosen setting. */
    public static boolean applies(String choice, List<String> roles) {
        if (choice == null) {
            return true;   // an unreadable setting asks for the code rather than waving everyone in
        }
        return switch (choice) {
            case "everyone" -> true;
            case "admins" -> roles.stream().anyMatch(ADMINS::contains);
            case "admins_payroll" -> roles.stream().anyMatch(r -> ADMINS.contains(r) || PAYROLL.contains(r));
            case "none" -> false;
            default -> true;   // a value this code does not know is treated as the strictest
        };
    }
}
