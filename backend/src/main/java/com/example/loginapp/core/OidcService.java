package com.example.loginapp.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.loginapp.core.AuthService.SignIn;

/**
 * Sign in with a Google (later Microsoft) account. The browser is sent to the provider and comes back with a one-time code;
 * the code is traded for an ID token, the token is verified ({@link IdTokenVerifier}), and the account at the provider is
 * matched to a login by the provider's permanent id, never by email.
 *
 * <p>What binds the whole round trip to the browser that began it is a sealed cookie holding the state, the nonce and the
 * PKCE verifier; nothing about the sign-in is kept on the server until it succeeds. A success does not hand the browser a
 * session in the redirect: it hands it a code that works once for 60 seconds, which the page trades for the session.
 */
public final class OidcService {

    private static final Logger LOG = LoggerFactory.getLogger(OidcService.class);
    public static final String COOKIE = "nx_oidc";
    public static final int COOKIE_SECONDS = 600;
    public static final int HANDOFF_SECONDS = 60;
    private static final String PURPOSE = "oidc-flow";

    /** Where to send the browser, and the sealed cookie to set with it. */
    public record Started(String url, String cookie) {}

    /**
     * Where the browser goes next (a path on this site), and the one-time code to give it in a cookie (null when there is none:
     * the cookie is then cleared). The code is never in the address.
     */
    public record Finished(String redirect, String handoff) {}

    /** A connected account, as the person sees it. */
    public record Linked(String provider, String tenant, String email, String since) {}

    private record Flow(String state, String nonce, String verifier, String provider, String intent, UUID identityId, UUID sessionId, long expires) {}

    private final Db db;
    private final Crypto crypto;
    private final MailOutbox outbox;
    private final RateLimits limits;
    private final IdTokenVerifier verifier;
    private final OidcTokenClient tokens;
    private final Map<String, OidcProvider> providers = new LinkedHashMap<>();
    private final String publicUrl;
    private final Clock clock;

    public OidcService(Db db, Crypto crypto, MailOutbox outbox, RateLimits limits, IdTokenVerifier verifier, OidcTokenClient tokens,
                       List<OidcProvider> providers, String publicUrl, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.outbox = outbox;
        this.limits = limits;
        this.verifier = verifier;
        this.tokens = tokens;
        providers.stream().filter(OidcProvider::enabled).forEach(p -> this.providers.put(p.name(), p));
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.clock = clock;
    }

    /** The providers this installation offers, for the sign-in page. */
    public List<String> available() {
        return List.copyOf(providers.keySet());
    }

    // ── starting ────────────────────────────────────────────────────────

    /** Begins a sign-in (no session) from the sign-in page. */
    public Started startSignIn(String provider, String address) {
        return start(provider, "signin", null, null, address);
    }

    /** Begins connecting an account to the login the person is signed in as. */
    public Started startLink(SessionCtx ctx, String provider, String address) {
        refuseOddSessions(ctx);
        StepUp.requireIfEnrolled(db, ctx);
        return start(provider, "link", ctx.identityId(), ctx.sessionId(), address);
    }

    private Started start(String name, String intent, UUID identityId, UUID sessionId, String address) {
        OidcProvider provider = provider(name);
        if (address != null && !limits.tryHit(RateLimits.OIDC_START_PER_ADDRESS, address)) {
            throw tooMany();
        }
        String state = Secrets.newToken();
        String nonce = Secrets.newToken();
        String verifierText = Secrets.newToken();
        long expires = clock.instant().getEpochSecond() + COOKIE_SECONDS;
        String cookie = seal(new Flow(state, nonce, verifierText, name, intent, identityId, sessionId, expires));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", provider.clientId());
        query.put("redirect_uri", redirectUri(name));
        query.put("scope", "openid email profile");
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(Secrets.sha256(verifierText)));
        query.put("code_challenge_method", "S256");
        query.put("prompt", "select_account");
        StringBuilder url = new StringBuilder(provider.authorizeUrl());
        char sep = '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            url.append(sep).append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return new Started(url.toString(), cookie);
    }

    // ── coming back ─────────────────────────────────────────────────────

    /**
     * The provider sent the browser back. Never throws: whatever happened, the browser is sent to a page on this site, with
     * a short code saying what went wrong (the page words it).
     */
    public Finished callback(String provider, String code, String state, String error, String cookie, String address) {
        Finished done = finish(provider, code, state, error, cookie, address);
        if (done.handoff() == null && done.redirect().startsWith("/?oidc_error=") && address != null) {
            limits.hit(RateLimits.OIDC_FAILURES_PER_ADDRESS, address);
        }
        return done;
    }

    private Finished finish(String provider, String code, String state, String error, String cookie, String address) {
        try {
            if (address != null && !limits.allowed(RateLimits.OIDC_FAILURES_PER_ADDRESS, address)) {
                return fail("request.rate_limited");
            }
            if (error != null && !error.isBlank()) {
                return fail("oidc.cancelled");
            }
            Flow flow = open(cookie);
            if (flow == null || !flow.provider().equals(provider) || state == null || code == null || code.isBlank()
                    || !MessageDigest.isEqual(flow.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
                return fail("oidc.invalid");
            }
            OidcProvider config = provider(provider);
            String idToken = tokens.idToken(config, code, flow.verifier(), redirectUri(provider));
            IdTokenVerifier.Identity who = switch (provider) {
                case "google" -> verifier.verifyGoogle(idToken, config.clientId(), flow.nonce());
                default -> throw new ApiException(404, "not_found", "That sign-in is not available");
            };
            return flow.intent().equals("link") ? link(flow, who) : signIn(who);
        } catch (ApiException e) {
            return fail(e.code());
        } catch (RuntimeException e) {
            LOG.warn("Sign-in with {} failed unexpectedly", provider, e);
            return fail("oidc.failed");
        }
    }

    private Finished signIn(IdTokenVerifier.Identity who) {
        String handoff = Secrets.newToken();
        db.inAuth(c -> {
            Map<String, Object> link = Sql.one(c, "select l.id, l.identity_id, l.tenant, i.status from auth.external_login l "
                    + "join auth.identity i on i.id = l.identity_id where l.provider = ? and l.subject = ? for update of l",
                    who.provider(), who.subject());
            if (link == null) {
                throw new ApiException(401, "oidc.not_linked", "No login is connected to that account");
            }
            // An account moved to another company (or from a personal to a company account) is not the one that was connected.
            if (!"active".equals(link.get("status")) || (link.get("tenant") != null && !link.get("tenant").equals(who.tenant()))) {
                throw new ApiException(401, "oidc.invalid", "That sign-in could not be verified. Try again");
            }
            Sql.update(c, "update auth.external_login set last_used_at = now() where id = ?", AuthService.uuid(link.get("id")));
            Sql.update(c, "delete from auth.oidc_handoff where expires_at < now() - interval '1 hour'");
            Sql.update(c, "insert into auth.oidc_handoff (code_hash, identity_id, provider, tenant, expires_at) "
                    + "values (?, ?, ?, ?, now() + interval '60 seconds')",
                    Secrets.sha256(handoff), AuthService.uuid(link.get("identityId")), who.provider(), who.tenant());
            return null;
        });
        return new Finished("/?oidc=1", handoff);
    }

    private Finished link(Flow flow, IdTokenVerifier.Identity who) {
        if (flow.identityId() == null) {
            return fail("oidc.invalid");
        }
        if (!who.emailVerified() && who.provider().equals("google")) {
            return fail("oidc.email_unverified");
        }
        db.inAuth(c -> {
            Map<String, Object> identity = Sql.one(c, "select status, email from auth.identity where id = ? for update", flow.identityId());
            // Still the session that started it: signing out, or a reset, in the meantime ends the attempt.
            if (identity == null || !"active".equals(identity.get("status")) || flow.sessionId() == null
                    || !Sql.exists(c, "select 1 from auth.session where id = ? and identity_id = ? and revoked_at is null and expires_at > now()",
                            flow.sessionId(), flow.identityId())) {
                throw new ApiException(401, "oidc.invalid", "That sign-in could not be verified. Try again");
            }
            if (Sql.exists(c, "select 1 from auth.external_login where identity_id = ? and provider = ?", flow.identityId(), who.provider())) {
                throw new ApiException(409, "oidc.already_connected", "This login already has an account connected there. Disconnect it first");
            }
            if (Sql.exists(c, "select 1 from auth.external_login where provider = ? and subject = ?", who.provider(), who.subject())) {
                throw new ApiException(409, "oidc.taken", "That account is already connected to another login");
            }
            Sql.update(c, "insert into auth.external_login (identity_id, provider, subject, tenant, email) values (?, ?, ?, ?, ?)",
                    flow.identityId(), who.provider(), who.subject(), who.tenant(), who.email());
            notice(c, (String) identity.get("email"), "A " + label(who.provider()) + " account was connected to your login",
                    "The " + label(who.provider()) + " account " + (who.email() == null ? "" : who.email() + " ") + "was just connected to " + identity.get("email")
                            + " and can now be used to sign in. If this was not you, reset your password: that disconnects it, and signs out every device.\n");
            return null;
        });
        return new Finished("/?oidc=linked", null);
    }

    private static Finished fail(String code) {
        return new Finished("/?oidc_error=" + URLEncoder.encode(code, StandardCharsets.UTF_8), null);
    }

    // ── trading the code for a session ─────────────────────────────────

    /** The page trades the one-time code for a session, or for a challenge when the login has two-step sign-in. */
    public SignIn exchange(String code, String address) {
        if (address != null && !limits.allowed(RateLimits.OIDC_FAILURES_PER_ADDRESS, address)) {
            throw tooMany();
        }
        String token = Secrets.newToken();
        SignIn result = db.inAuth(c -> {
            Map<String, Object> row = Sql.one(c, "select h.identity_id, h.provider, h.tenant, i.status, i.mfa_enrolled_at is not null as mfa "
                    + "from auth.oidc_handoff h join auth.identity i on i.id = h.identity_id "
                    + "where h.code_hash = ? and h.used_at is null and h.expires_at > now() "
                    + "and exists (select 1 from auth.external_login l where l.identity_id = h.identity_id and l.provider = h.provider) for update of h",
                    Secrets.sha256(code == null ? "" : code));
            if (row == null) {
                return null;
            }
            Sql.update(c, "update auth.oidc_handoff set used_at = now() where code_hash = ?", Secrets.sha256(code));
            if (!"active".equals(row.get("status"))) {
                return null;
            }
            UUID identityId = AuthService.uuid(row.get("identityId"));
            String provider = (String) row.get("provider");
            String tenant = (String) row.get("tenant");
            if (Boolean.TRUE.equals(row.get("mfa"))) {
                // The code from the authenticator app is still asked for: a company account is one factor, not two.
                Sql.update(c, "insert into auth.mfa_challenge (token_hash, identity_id, expires_at, auth_method, provider_tenant) "
                        + "values (?, ?, now() + interval '5 minutes', ?, ?)", Secrets.sha256(token), identityId, provider, tenant);
                return new SignIn(token, true);
            }
            Sql.update(c, "update auth.identity set last_login_at = now() where id = ?", identityId);
            AuthService.insertSession(c, token, identityId, "identity", null, null, null, "30 minutes", provider, tenant);
            return new SignIn(token, false);
        });
        if (result == null) {
            if (address != null) {
                limits.hit(RateLimits.OIDC_FAILURES_PER_ADDRESS, address);
            }
            throw new ApiException(401, "oidc.invalid", "That sign-in could not be verified. Try again");
        }
        return result;
    }

    // ── connected accounts ──────────────────────────────────────────────

    public List<Linked> linked(SessionCtx ctx) {
        return db.inAuth(c -> Sql.query(c, "select provider, tenant, email, to_char(created_at at time zone 'utc', 'YYYY-MM-DD') as since "
                + "from auth.external_login where identity_id = ? order by provider", ctx.identityId()).stream()
                .map(r -> new Linked((String) r.get("provider"), (String) r.get("tenant"), (String) r.get("email"), (String) r.get("since")))
                .toList());
    }

    public void unlink(SessionCtx ctx, String provider) {
        refuseOddSessions(ctx);
        StepUp.requireIfEnrolled(db, ctx);
        int removed = db.inAuth(c -> {
            int n = Sql.update(c, "delete from auth.external_login where identity_id = ? and provider = ?", ctx.identityId(), provider);
            if (n > 0) {
                Sql.update(c, "delete from auth.oidc_handoff where identity_id = ? and provider = ?", ctx.identityId(), provider);
                notice(c, ctx.email(), "A " + label(provider) + " account was disconnected from your login",
                        "The " + label(provider) + " account connected to " + ctx.email() + " was just disconnected. You sign in with your password from now on. "
                                + "If this was not you, reset your password.\n");
            }
            return n;
        });
        if (removed == 0) {
            throw ApiException.notFound("That connected account");
        }
    }

    private static void refuseOddSessions(SessionCtx ctx) {
        if (ctx.isSupport() || "platform".equals(ctx.kind())) {
            throw new ApiException(403, "permission.denied", "Connected accounts cannot be changed from this session");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private OidcProvider provider(String name) {
        OidcProvider p = providers.get(name);
        if (p == null) {
            throw new ApiException(404, "not_found", "That sign-in is not available");
        }
        return p;
    }

    private static ApiException tooMany() {
        return new ApiException(429, "request.rate_limited", "Too many attempts. Try again in a few minutes");
    }

    private static String label(String provider) {
        return provider.equals("google") ? "Google" : "Microsoft";
    }

    private void notice(java.sql.Connection c, String email, String subject, String body) throws java.sql.SQLException {
        outbox.enqueue(c, null, email, "connected-account", subject, body, "connected-account:" + UUID.randomUUID());
    }

    private String redirectUri(String provider) {
        return publicUrl + "/api/auth/oidc/" + provider + "/callback";
    }

    private String seal(Flow f) {
        String text = String.join("|", "1", f.state(), f.nonce(), f.verifier(), f.provider(), f.intent(),
                f.identityId() == null ? "-" : f.identityId().toString(), f.sessionId() == null ? "-" : f.sessionId().toString(), Long.toString(f.expires()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(crypto.seal(PURPOSE, text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The flow a cookie holds, or null when it is missing, not ours, tampered with, or older than ten minutes. */
    private Flow open(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        try {
            String[] p = new String(crypto.open(PURPOSE, Base64.getUrlDecoder().decode(cookie)), StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != 9 || !p[0].equals("1") || Long.parseLong(p[8]) < clock.instant().getEpochSecond()) {
                return null;
            }
            return new Flow(p[1], p[2], p[3], p[4], p[5], p[6].equals("-") ? null : UUID.fromString(p[6]),
                    p[7].equals("-") ? null : UUID.fromString(p[7]), Long.parseLong(p[8]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
