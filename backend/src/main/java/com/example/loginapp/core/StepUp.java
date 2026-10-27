package com.example.loginapp.core;

import java.util.Map;

/**
 * A few actions ask again for a fresh code, even in a session that already passed one: approving a pay run, the bank file,
 * showing a full identifier, changing roles. Asked only of members whose roles the Organization requires two-step for.
 */
public final class StepUp {
    /** How long a fresh code counts. */
    public static final int MINUTES = 15;

    private StepUp() {}

    public static void require(Db db, SessionCtx ctx) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot do this");   // never by support, whatever its role rows say
        }
        if (!ctx.inOrganization() || ctx.membershipId() == null) {
            return;
        }
        if (!AuthService.mfaRequired(db, ctx.tenantId(), ctx.membershipId())) {
            return;
        }
        Map<String, Object> state = db.inAuth(c -> Sql.one(c,
                "select s.step_up_at is not null and s.step_up_at > now() - make_interval(mins => ?) as fresh, "
                        + "i.mfa_enrolled_at is not null as enrolled "
                        + "from auth.session s join auth.identity i on i.id = s.identity_id where s.id = ?", MINUTES, ctx.sessionId()));
        if (state == null || !Boolean.TRUE.equals(state.get("enrolled"))) {
            throw new ApiException(403, "mfa.enrolment_required", "This Organization requires two-step sign-in. Set it up to continue");
        }
        if (!Boolean.TRUE.equals(state.get("fresh"))) {
            throw new ApiException(403, "stepup.required", "Enter a fresh code from your authenticator app to continue");
        }
    }
}
