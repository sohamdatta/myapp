package com.nexvance.loginapp.core;

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

    /** Plain letters, digits and . _ % + - before the @, and a lower-case domain: nothing that could add lines or links to a mail. */
    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9][a-z0-9._%+-]{0,63}@[a-z0-9.-]+\\.[a-z]{2,}$");

    /** What a person is asked to switch to, and by which Organization. */
    public record Proposal(String organization, String email, String proposedAt) {}

    private final Db db;
    private final MailOutbox outbox;
    private final RateLimits limits;

    public OfficialEmailService(Db db, MailOutbox outbox, RateLimits limits) {
        this.db = db;
        this.outbox = outbox;
        this.limits = limits;
    }

    /**
     * Proposes an address for a member. Only in the Organization's verified email domain, and not for a login that is also in
     * another Organization or is a platform login: moving its address would hand whoever owns the new mailbox control of those
     * too, so those people change their own address in Security.
     */
    public void propose(SessionCtx ctx, UUID membershipId, String email) {
        ctx.require("user.manage");
        refuseSupport(ctx);
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (address.length() > 254 || !EMAIL.matcher(address).matches()) {
            throw ApiException.badRequest("Enter a valid email address, with letters, digits and . _ % + - before the @");
        }
        StepUp.requireIfEnrolled(db, ctx);
        UUID identityId = db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> target = UserService.lock(c, membershipId);
            if ("ended".equals(target.get("status"))) {
                throw new ApiException(409, "request.refused", "This user has left");
            }
            UserService.requireReach(c, ctx, membershipId);
            Map<String, Object> domain = Sql.one(c, "select domain, verified_at is not null as verified from app.official_email_domain where tenant_id = ?", ctx.tenantId());
            if (domain == null) {
                throw new ApiException(409, "official_email.no_domain", "Set the organization's email domain first, under Organization settings");
            }
            if (!Boolean.TRUE.equals(domain.get("verified"))) {
                throw new ApiException(409, "official_email.unverified", "Verify the organization's email domain first: publish the DNS record shown in Organization settings");
            }
            if (!address.endsWith("@" + domain.get("domain"))) {
                throw ApiException.badRequest("That address is not in the organization's email domain, " + domain.get("domain"));
            }
            return AuthService.uuid(Sql.one(c, "select identity_id from app.membership where id = ?", membershipId).get("identityId"));
        });
        boolean shared = db.inAuth(c -> Sql.one(c, "select 1 from platform.platform_user where identity_id = ?", identityId) != null
                || ((Number) Sql.one(c, "select auth.other_memberships(?, ?) as n", identityId, ctx.tenantId()).get("n")).longValue() > 0);
        if (shared) {
            throw new ApiException(409, "request.refused",
                    "This person also belongs to another organization or has a platform role, so their address is theirs to change, in Security");
        }
        if (!limits.tryHit(RateLimits.OFFICIAL_EMAIL_PER_MEMBER, membershipId.toString())) {
            throw new ApiException(429, "request.rate_limited", "This person has been asked several times today. Try again tomorrow");
        }
        // The proposal, its audit entry and the notice are one transaction: a proposal is never left without its mail.
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> now = Sql.one(c, "select d.email, m.proposed_email from app.membership m "
                    + "join app.member_directory() d on d.membership_id = m.id where m.id = ? for update of m", membershipId);
            String current = (String) now.get("email");
            Sql.update(c, "update app.membership set proposed_email = ?, proposed_at = now() where id = ?", address, membershipId);
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("email", address);
            if (now.get("proposedEmail") != null) {
                detail.put("replaced", now.get("proposedEmail"));
            }
            UserService.audit(c, ctx, "user.official_email_proposed", "membership", membershipId, detail);
            if (!address.equalsIgnoreCase(current)) {
                outbox.enqueue(c, ctx.tenantId(), current, "official-email", "Your organization asks you to use your work email to sign in",
                        ctx.tenantName() + " has asked you to sign in with " + address + " from now on, instead of " + current
                                + ".\n\nSign in, open Security, and under \"Change your sign-in address\" choose \"Use this address\". You will be asked "
                                + "for your password, and a link goes to " + address + " to finish. Nothing changes until you do, and the request "
                                + "ends after 30 days.\n\nIf you did not expect this, tell your Org Admin.\n",
                        "official-email:" + membershipId + ":" + UUID.randomUUID());
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
