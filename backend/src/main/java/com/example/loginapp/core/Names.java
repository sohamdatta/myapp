package com.example.loginapp.core;

/** SQL for the name people know a user by: the preferred or legal name on their employee record, when it is linked. */
final class Names {

    private Names() {}

    /** A scalar subquery giving the name of the membership in {@code membershipColumn}, or null when no record is linked. */
    static String of(String membershipColumn) {
        return "(select coalesce(p.preferred_name, p.legal_name) from app.membership nm join app.person p on p.id = nm.person_id "
                + "where nm.id = " + membershipColumn + ")";
    }
}
