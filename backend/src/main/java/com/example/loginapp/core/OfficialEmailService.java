package com.example.loginapp.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Moving a person from the personal address they were invited at to their company address. HR may propose an address, but only
 * in the email domain the Organization has said is its own; nothing changes by itself. The person sees the proposal in Security
 * and asks for the change there with their own password: the link goes to the new address, so only whoever owns it can finish
 * it (see {@link EmailChangeService}). A proposal for an address outside the Organization's domain is refused, so an
 * administrator cannot send someone to an address they control themselves.
 */
public final class OfficialEmailService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** What a person is asked to switch to, and by which Organization. */
    public record Proposal(String organization, String email, String proposedAt) {}

    private final Db db;
    private final MailOutbox outbox;

    public OfficialEmailService(Db db, MailOutbox outbox) {
        this.db = db;
        this.outbox = outbox;
    }

    /** Proposes an address for a member. */
    public void propose(SessionCtx ctx, UUID membershipId, String email) {
        ctx.require("user.manage");
        refuseSupport(ctx);
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (address.length() > 254 || !EMAIL.matcher(address).matches()) {
            throw ApiException.badRequest("Enter a valid email address");
        }
        StepUp.requireIfEnrolled(db, ctx);
        UUID identityId = db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> target = UserService.lock(c, membershipId);
            if ("ended".equals(target.get("status"))) {
                throw new ApiException(409, "request.refused", "This user has left");
            }
            UserService.requireReach(c, ctx, membershipId);
            Map<String, Object> domain = Sql.one(c, "select domain from app.official_email_domain where tenant_id = ?", ctx.tenantId());
            if (domain == null) {
                throw new ApiException(409, "official_email.no_domain", "Set the organization's email domain first, under Organization settings");
            }
            if (!address.endsWith("@" + domain.get("domain"))) {
                throw ApiException.badRequest("That address is not in the organization's email domain, " + domain.get("domain"));
            }
            Sql.update(c, "update app.membership set proposed_email = ?, proposed_at = now() where id = ?", address, membershipId);
            UserService.audit(c, ctx, "user.official_email_proposed", "membership", membershipId, Map.of("email", address));
            return AuthService.uuid(Sql.one(c, "select identity_id from app.membership where id = ?", membershipId).get("identityId"));
        });
        db.inAuth(c -> {
            Map<String, Object> identity = Sql.one(c, "select email from auth.identity where id = ?", identityId);
            if (identity != null && !address.equalsIgnoreCase((String) identity.get("email"))) {
                outbox.enqueue(c, null, (String) identity.get("email"), "official-email",
                        "Your organization asks you to use your work email to sign in",
                        "Your organization has asked you to sign in with " + address + " from now on, instead of " + identity.get("email")
                                + ".\n\nSign in, open Security, and choose Use your work email. You will be asked for your password, and a link "
                                + "goes to " + address + " to finish. Nothing changes until you do.\n\nIf you did not expect this, tell your Org Admin.\n",
                        "official-email:" + membershipId + ":" + address);
            }
            return null;
        });
    }

    /** Takes a proposal back. */
    public void withdraw(SessionCtx ctx, UUID membershipId) {
        ctx.require("user.manage");
        refuseSupport(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            UserService.lock(c, membershipId);
            UserService.requireReach(c, ctx, membershipId);
            if (Sql.update(c, "update app.membership set proposed_email = null, proposed_at = null where id = ? and proposed_email is not null", membershipId) > 0) {
                UserService.audit(c, ctx, "user.official_email_withdrawn", "membership", membershipId, Map.of());
            }
            return null;
        });
    }

    /** What the signed-in person has been asked to switch to, across their Organizations. */
    public List<Proposal> mine(SessionCtx ctx) {
        return db.inAuth(c -> Sql.query(c, "select organization, email, to_char(proposed_at at time zone 'utc', 'YYYY-MM-DD') as proposed_at "
                + "from auth.official_email_proposals(?)", ctx.identityId()).stream()
                .map(r -> new Proposal((String) r.get("organization"), (String) r.get("email"), (String) r.get("proposedAt"))).toList());
    }

    private static void refuseSupport(SessionCtx ctx) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot do this");
        }
    }
}
