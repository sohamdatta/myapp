package com.nexvance.loginapp.core;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An Organization's own sign-in page. The app's address (app.public-url, such as hr.example.com) is the shared page; each
 * Organization also has one at its slug in front of it, such as acme.hr.example.com. That page shows the Organization's
 * name and logo, and lets in only its members: a login that is not one is refused there, after its password, and has to
 * use the shared page or its own Organization's. The Org Admin chooses the slug; the old address stops working at once.
 */
public final class SignInPage {

    /** 3 to 40 lowercase letters, digits or hyphens, not starting or ending with a hyphen; one label of a host name. */
    public static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");

    /** Names an Organization cannot take: they are, or may one day be, the platform's own addresses. */
    public static final Set<String> RESERVED = Set.of("www", "api", "app", "apps", "admin", "console", "platform", "auth", "login",
            "signin", "sign-in", "sso", "oauth", "mail", "email", "smtp", "imap", "pop", "mx", "ns1", "ns2", "dns", "ftp", "cdn",
            "static", "assets", "files", "img", "media", "status", "help", "support", "docs", "blog", "uat", "staging", "dev",
            "iclock", "device", "devices", "nexvance", "hrms", "localhost");

    /** The page a host name stands for: the shared one, an Organization's, or an Organization address that leads nowhere. */
    public record Page(Kind kind, String slug, Organization organization) {
        public boolean shared() {
            return kind == Kind.SHARED;
        }
    }

    public enum Kind { SHARED, ORGANIZATION, UNKNOWN }

    /** What an Organization's sign-in page shows about it: public, like its logo. */
    public record Organization(UUID id, String name, String slug, int logoVersion) {}

    /**
     * The Organization's sign-in address, for its Org Admin: the short name and the whole address it makes. enabled is false
     * when this server does not serve Organizations' addresses (ORG_ADDRESSES is not set), so the address would not open.
     */
    public record Address(String slug, String address, boolean canChange, boolean enabled) {}

    private final Db db;
    private final String baseHost;
    private final boolean enabled;

    /** For development and tests: Organizations' addresses are served only on localhost, as with no ORG_ADDRESSES. */
    public SignInPage(Db db, String publicUrl) {
        this(db, publicUrl, "");
    }

    /**
     * @param publicUrl     the app's address, app.public-url; its host is the shared page, and Organizations' pages are under it
     * @param orgAddresses  ORG_ADDRESSES, the names Caddy serves Organizations' pages at: *.&lt;the shared host&gt;, or empty
     *                      for none. On localhost they work without it: browsers send *.localhost to the machine itself.
     * @throws IllegalStateException when ORG_ADDRESSES is not the shared host's wildcard, so the two never disagree in silence
     */
    public SignInPage(Db db, String publicUrl, String orgAddresses) {
        this.db = db;
        this.baseHost = hostOf(publicUrl);
        String wanted = orgAddresses == null ? "" : orgAddresses.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z]+://", "");
        if (wanted.isEmpty()) {
            this.enabled = baseHost.equals("localhost");
        } else if (wanted.equals("*." + baseHost)) {
            this.enabled = true;
        } else {
            throw new IllegalStateException("ORG_ADDRESSES is " + orgAddresses + " but the app's address (APP_PUBLIC_URL, or DOMAIN) is "
                    + baseHost + ". Set ORG_ADDRESSES=*." + baseHost + ", or leave it out to turn Organizations' own addresses off");
        }
    }

    /** Whether this server serves Organizations' own addresses at all. Off, every address is the shared page. */
    public boolean enabled() {
        return enabled;
    }

    /** The host, lower case and without a port, of an address such as https://hr.example.com:8443/x. */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "localhost";
        }
        String host = URI.create(url.contains("://") ? url : "https://" + url).getHost();
        return host == null ? "localhost" : host.toLowerCase(Locale.ROOT);
    }

    /** The host of the shared page, such as hr.example.com. */
    public String baseHost() {
        return baseHost;
    }

    /** The address of an Organization's page, such as https://acme.hr.example.com, given the scheme and port of the shared one. */
    public String addressOf(String slug, String publicUrl) {
        String url = publicUrl == null || publicUrl.isBlank() ? "http://localhost" : publicUrl;
        URI base = URI.create(url.contains("://") ? url : "https://" + url);
        return base.getScheme() + "://" + (slug == null ? "" : slug + ".") + baseHost + (base.getPort() > 0 ? ":" + base.getPort() : "");
    }

    /**
     * The page a request's Host header names. Anything that is neither the shared host nor one label in front of it is
     * the shared page too, so that the app still answers on an address it was not told about (an IP address, a health check).
     */
    public Page forHost(String hostHeader) {
        String host = stripPort(hostHeader);
        if (!enabled || host == null || host.equals(baseHost) || !host.endsWith("." + baseHost)) {
            return new Page(Kind.SHARED, null, null);
        }
        String slug = host.substring(0, host.length() - baseHost.length() - 1);
        if (slug.contains(".") || !SLUG.matcher(slug).matches()) {
            return new Page(Kind.UNKNOWN, slug, null);
        }
        Organization org = bySlug(slug);
        return org == null ? new Page(Kind.UNKNOWN, slug, null) : new Page(Kind.ORGANIZATION, slug, org);
    }

    /** Whether a certificate may be fetched for this host: the shared one, or an active Organization's. */
    public boolean servesHost(String host) {
        String h = stripPort(host);
        return h != null && (h.equals(baseHost) || forHost(h).kind() == Kind.ORGANIZATION);
    }

    private static String stripPort(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            return h;   // an IPv6 address: never an Organization's
        }
        int colon = h.indexOf(':');
        h = colon >= 0 ? h.substring(0, colon) : h;
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /** The active Organization with this slug, or null. */
    public Organization bySlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return null;
        }
        Map<String, Object> t = db.inAuth(c -> Sql.one(c, "select id, name, slug from platform.tenant where slug = ? and status = 'active'", slug));
        if (t == null) {
            return null;
        }
        UUID id = AuthService.uuid(t.get("id"));
        return new Organization(id, (String) t.get("name"), (String) t.get("slug"), db.inTenant(id, Branding::version));
    }

    /**
     * On an Organization's page, refuses a session whose login is not one of its members, and ends that session. On the
     * shared page anything goes. Called once the password (and code) are right, so it says nothing to someone guessing.
     */
    public void requireMember(AuthService auth, Page page, String token) {
        if (page.shared()) {
            return;
        }
        SessionCtx ctx = auth.resolve(token);
        if (page.organization() == null || !isMember(ctx.identityId(), page.organization().id())) {
            boolean suspended = page.organization() != null && isSuspended(ctx.identityId(), page.organization().id());
            auth.logout(ctx);
            if (suspended) {
                throw new ApiException(403, "signin.suspended", "Your access to " + page.organization().name()
                        + " is suspended. Ask its administrator to reactivate it");
            }
            throw notMember(page);
        }
    }

    /** Whether the login's membership of the Organization is suspended (as opposed to missing). */
    private boolean isSuspended(UUID identityId, UUID tenantId) {
        return db.inTenant(tenantId, c -> Sql.one(c,
                "select 1 from app.membership where identity_id = ? and status = 'suspended'", identityId) != null);
    }

    /** On an Organization's page, only that Organization can be entered: not another, and not the console. */
    public void requireEnterable(Page page, UUID tenantId) {
        if (!page.shared() && (page.organization() == null || !page.organization().id().equals(tenantId))) {
            throw notMember(page);
        }
    }

    private boolean isMember(UUID identityId, UUID tenantId) {
        return db.inAuth(c -> Sql.one(c, "select 1 from auth.memberships_for_identity(?) where tenant_id = ?", identityId, tenantId) != null);
    }

    private ApiException notMember(Page page) {
        if (page.organization() == null) {
            return new ApiException(404, "signin.unknown_address", "No Organization signs in at this address. Check the address with your administrator");
        }
        return new ApiException(403, "signin.not_member", "This login does not belong to " + page.organization().name()
                + ". Sign in at " + baseHost + " instead");
    }

    /** The Organization's sign-in address, for its Org Admin. */
    public Address get(SessionCtx ctx, String publicUrl) {
        if (!ctx.inOrganization()) {
            throw ApiException.denied("tenant.manage");
        }
        String slug = db.inAuth(c -> (String) Sql.one(c, "select slug from platform.tenant where id = ?", ctx.tenantId()).get("slug"));
        return view(ctx, slug, publicUrl);
    }

    /** Changes the slug, and with it the address. The old address stops working at once, so the screen warns first. */
    public Address setSlug(SessionCtx ctx, String slug, String publicUrl) {
        if (!ctx.inOrganization() || ctx.isSupport() || !ctx.canAcrossOrganization("tenant.manage")) {
            throw ApiException.denied("tenant.manage");
        }
        String clean = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        checkSlug(clean);
        String old = db.inTenant(ctx.tenantId(), c -> {
            String before = (String) Sql.one(c, "select app.set_organization_slug(?) as old", clean).get("old");
            if (!clean.equals(before)) {
                UserService.audit(c, ctx, "organization.sign_in_address_changed", "organization", ctx.tenantId(),
                        Map.of("from", before, "to", clean));
            }
            return before;
        });
        return view(ctx, clean.equals(old) ? old : clean, publicUrl);
    }

    /** Refuses a slug that is not one host label, or is one the platform keeps for itself. */
    public static void checkSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches() || slug.contains("--")) {
            throw ApiException.badRequest("The short name must be 3 to 40 lowercase letters, digits or single hyphens, "
                    + "starting and ending with a letter or digit");
        }
        if (RESERVED.contains(slug)) {
            throw new ApiException(409, "tenant.slug_reserved", "\"" + slug + "\" is kept for the platform; choose another short name");
        }
    }

    private Address view(SessionCtx ctx, String slug, String publicUrl) {
        return new Address(slug, addressOf(slug, publicUrl), ctx.canAcrossOrganization("tenant.manage") && !ctx.isSupport(), enabled);
    }
}
