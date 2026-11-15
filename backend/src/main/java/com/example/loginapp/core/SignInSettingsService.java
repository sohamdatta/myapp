package com.example.loginapp.core;

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

    public record View(String googleDomain, String officialEmailDomain) {}

    private final Db db;

    public SignInSettingsService(Db db) {
        this.db = db;
    }

    public View get(SessionCtx ctx) {
        ctx.require("tenant.manage");
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx));
    }

    private static View view(java.sql.Connection c, SessionCtx ctx) throws java.sql.SQLException {
        Map<String, Object> google = Sql.one(c, "select domain from app.sign_in_provider where tenant_id = ? and provider = 'google'", ctx.tenantId());
        Map<String, Object> email = Sql.one(c, "select domain from app.official_email_domain where tenant_id = ?", ctx.tenantId());
        return new View(google == null ? null : (String) google.get("domain"), email == null ? null : (String) email.get("domain"));
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
     * The email domain this Organization says is its own, such as acme.com. HR may then propose addresses in it for members. Blank
     * clears it (and so stops new proposals; those already made stay until the person acts or HR withdraws them).
     */
    public View setOfficialEmailDomain(SessionCtx ctx, String domain, String password) {
        ctx.require("tenant.manage");
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "A support session cannot change this");
        }
        StepUp.requireIfEnrolled(db, ctx);
        String clean = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (!clean.isEmpty()) {
            if (clean.length() > 253 || !DOMAIN.matcher(clean).matches()) {
                throw ApiException.badRequest("Enter the email domain, such as acme.com");
            }
            confirmPassword(ctx, password);
        }
        db.inTenant(ctx.tenantId(), c -> {
            if (clean.isEmpty()) {
                if (Sql.update(c, "delete from app.official_email_domain where tenant_id = ?", ctx.tenantId()) > 0) {
                    UserService.audit(c, ctx, "email_domain.cleared", "organization", ctx.tenantId(), Map.of());
                }
            } else {
                Sql.update(c, "insert into app.official_email_domain (tenant_id, domain) values (?, ?) "
                        + "on conflict (tenant_id) do update set domain = excluded.domain, set_at = now()", ctx.tenantId(), clean);
                UserService.audit(c, ctx, "email_domain.set", "organization", ctx.tenantId(), Map.of("domain", clean));
            }
            return null;
        });
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
