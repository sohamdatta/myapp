package com.nexvance.loginapp.core;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which company account an Organization accepts for signing in with Google: its Google Workspace domain. An Organization
 * that sets nothing accepts nothing. Setting a domain asks for the administrator's password again, and for a fresh code
 * when they use two-step sign-in, because it decides who may open the Organization without its password.
 */
public final class SignInSettingsService {

    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    /**
     * The Organization's settings. officialEmailVerified says whether the domain's DNS record has been found; until then
     * officialEmailRecordName and officialEmailRecordValue are what to publish.
     */
    public record View(String googleDomain, String officialEmailDomain, boolean officialEmailVerified, String officialEmailRecordName,
                       String officialEmailRecordValue) {}

    /** Domains anyone can have an address at: never an Organization's own. */
    private static final java.util.Set<String> PUBLIC_DOMAINS = java.util.Set.of("gmail.com", "googlemail.com", "outlook.com", "hotmail.com",
            "live.com", "msn.com", "yahoo.com", "yahoo.in", "yahoo.co.in", "ymail.com", "rediffmail.com", "icloud.com", "me.com", "mac.com",
            "aol.com", "proton.me", "protonmail.com", "pm.me", "zoho.com", "gmx.com", "gmx.net", "mail.com", "yandex.com", "hey.com",
            "fastmail.com", "tutanota.com", "outlook.in", "hotmail.co.uk", "yahoo.co.uk");

    /** Shared endings where the name before them is what is owned: not a domain of an Organization. */
    private static final java.util.Set<String> PUBLIC_SUFFIXES = java.util.Set.of("co.in", "org.in", "net.in", "gov.in", "ac.in", "nic.in", "res.in",
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.za", "com.sg", "com.my", "co.jp");

    private static final String RECORD_PREFIX = "_nexvance-verify.";
    private static final String VALUE_PREFIX = "nexvance-verify=";

    private final Db db;
    private final DnsTxtLookup dns;

    public SignInSettingsService(Db db, DnsTxtLookup dns) {
        this.db = db;
        this.dns = dns;
    }

    public View get(SessionCtx ctx) {
        ctx.require("tenant.manage");
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx));
    }

    private static View view(java.sql.Connection c, SessionCtx ctx) throws java.sql.SQLException {
        Map<String, Object> google = Sql.one(c, "select domain from app.sign_in_provider where tenant_id = ? and provider = 'google'", ctx.tenantId());
        Map<String, Object> email = Sql.one(c, "select domain, verify_token, verified_at is not null as verified from app.official_email_domain where tenant_id = ?",
                ctx.tenantId());
        String domain = email == null ? null : (String) email.get("domain");
        return new View(google == null ? null : (String) google.get("domain"), domain, email != null && Boolean.TRUE.equals(email.get("verified")),
                domain == null ? null : RECORD_PREFIX + domain, email == null ? null : VALUE_PREFIX + email.get("verifyToken"));
    }

    /** Sets the Google Workspace domain, or turns Google sign-in off for the Organization when the domain is blank. */
    public View setGoogleDomain(SessionCtx ctx, String domain, String password) {
        ctx.require("tenant.manage");
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot change how people sign in");
        }
        StepUp.requireIfEnrolled(db, ctx);
        String clean = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) {
            db.inTenant(ctx.tenantId(), c -> {
                if (Sql.update(c, "delete from app.sign_in_provider where tenant_id = ? and provider = 'google'", ctx.tenantId()) > 0) {
                    UserService.audit(c, ctx, "signin.google_off", "organization", ctx.tenantId(), Map.of());
                }
                return null;
            });
            return db.inTenant(ctx.tenantId(), c -> view(c, ctx));
        }
        if (clean.length() > 253 || !DOMAIN.matcher(clean).matches()) {
            throw ApiException.badRequest("Enter the Google Workspace domain, such as acme.com");
        }
        confirmPassword(ctx, password);
        db.inTenant(ctx.tenantId(), c -> {
            Sql.update(c, "insert into app.sign_in_provider (tenant_id, provider, domain) values (?, 'google', ?) "
                    + "on conflict (tenant_id, provider) do update set domain = excluded.domain, enabled_at = now()", ctx.tenantId(), clean);
            UserService.audit(c, ctx, "signin.google_on", "organization", ctx.tenantId(), Map.of("domain", clean));
            return null;
        });
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx));
    }

    /**
     * The email domain this Organization says is its own, such as acme.com. It is not believed until the Organization publishes a
     * DNS record showing it controls the domain ({@link #checkEmailDomain}); only then may HR propose addresses in it. Public mail
     * domains, and a domain another Organization has verified, are refused. Changing or clearing the domain withdraws every
     * proposal that is not in the new one.
     */
    public View setOfficialEmailDomain(SessionCtx ctx, String domain, String password) {
        ctx.require("tenant.manage");
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot change this");
        }
        StepUp.requireIfEnrolled(db, ctx);
        String clean = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (!clean.isEmpty()) {
            if (clean.length() > 253 || !DOMAIN.matcher(clean).matches() || PUBLIC_DOMAINS.contains(clean) || PUBLIC_SUFFIXES.contains(clean)) {
                throw ApiException.badRequest("Enter your organization's own email domain, such as acme.com (not a public mail service)");
            }
            confirmPassword(ctx, password);
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> before = Sql.one(c, "select domain from app.official_email_domain where tenant_id = ? for update", ctx.tenantId());
            String previous = before == null ? null : (String) before.get("domain");
            if (clean.equals(previous == null ? "" : previous)) {
                return null;   // nothing changes: a verified domain stays verified
            }
            if (clean.isEmpty()) {
                Sql.update(c, "delete from app.official_email_domain where tenant_id = ?", ctx.tenantId());
            } else {
                Sql.update(c, "insert into app.official_email_domain (tenant_id, domain, verify_token) values (?, ?, ?) "
                        + "on conflict (tenant_id) do update set domain = excluded.domain, verify_token = excluded.verify_token, "
                        + "verified_at = null, set_at = now()", ctx.tenantId(), clean, Secrets.newToken());
            }
            int withdrawn = Sql.update(c, "update app.membership set proposed_email = null, proposed_at = null "
                    + "where proposed_email is not null and (?::text = '' or lower(proposed_email::text) not like '%@' || ?)", clean, clean);
            UserService.audit(c, ctx, clean.isEmpty() ? "email_domain.cleared" : "email_domain.set", "organization", ctx.tenantId(),
                    Map.of("domain", clean, "previous", previous == null ? "" : previous, "proposalsWithdrawn", withdrawn));
            return null;
        });
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx));
    }

    /** Looks for the DNS record that shows the Organization controls its email domain, and if it is there, the domain is verified. */
    public View checkEmailDomain(SessionCtx ctx) {
        ctx.require("tenant.manage");
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot change this");
        }
        StepUp.requireIfEnrolled(db, ctx);
        View now = db.inTenant(ctx.tenantId(), c -> view(c, ctx));
        if (now.officialEmailDomain() == null) {
            throw new ApiException(409, "official_email.no_domain", "Enter the organization's email domain first");
        }
        if (!now.officialEmailVerified()) {
            boolean found = dns.txt(now.officialEmailRecordName()).stream().anyMatch(v -> v.equals(now.officialEmailRecordValue()));
            if (!found) {
                throw new ApiException(409, "official_email.record_not_found",
                        "The DNS record was not found yet. It can take a while to appear; check the name and value and try again later");
            }
            db.inTenant(ctx.tenantId(), c -> {
                if (Sql.exists(c, "select 1 from app.official_email_domain where tenant_id = ? and domain = ? and verified_at is not null", ctx.tenantId(), now.officialEmailDomain())) {
                    return null;
                }
                Sql.update(c, "update app.official_email_domain set verified_at = now() where tenant_id = ? and domain = ?", ctx.tenantId(), now.officialEmailDomain());
                UserService.audit(c, ctx, "email_domain.verified", "organization", ctx.tenantId(), Map.of("domain", now.officialEmailDomain()));
                return null;
            });
        }
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx));
    }

    /** The administrator's own password, checked and counted like any other sign-in attempt. */
    private void confirmPassword(SessionCtx ctx, String password) {
        String outcome = db.inAuth(c -> {
            AuthService.lockEmail(c, ctx.email());
            if (AuthService.lockedOut(c, ctx.email())) {
                return "locked";
            }
            Map<String, Object> identity = Sql.one(c, "select password_hash from auth.identity where id = ? and status = 'active'", ctx.identityId());
            if (identity == null || password == null || !Secrets.verifyPassword(password, (String) identity.get("passwordHash"))) {
                AuthService.recordFailure(c, ctx.email());
                return "wrong";
            }
            return "ok";
        });
        switch (outcome) {
            case "ok" -> { }
            case "locked" -> throw AuthService.tooManyFailures();
            default -> throw new ApiException(403, "auth.invalid_credentials", "That password is not right");
        }
    }
}
