package com.nexvance.loginapp.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Checks the ID token Google or Microsoft hands back after a person signs in there, and says who it is for. The token is
 * trusted only when its signature is one of the provider's published keys (RS256 only, so an unsigned token or one signed
 * with a shared secret is refused), it was issued by that provider for this application, it has not expired, and it carries
 * the {@code nonce} this sign-in started with. Nothing else about the token is believed: whether the email counts as
 * verified, and which company the account belongs to, are returned for the caller to decide on.
 *
 * <p>Every failure is one refusal with one message, so a caller cannot learn which check failed.
 */
public final class IdTokenVerifier {

    /** The tenant Microsoft uses for personal accounts (Outlook.com, Xbox). Never a company: refused. */
    public static final String MICROSOFT_PERSONAL_TENANT = "9188040d-6c67-4c5b-b112-36a304b66dad";

    private static final Logger LOG = LoggerFactory.getLogger(IdTokenVerifier.class);
    private static final long SKEW_SECONDS = 60;
    /** Google and Microsoft ID tokens last about an hour; anything much longer is not one of theirs. */
    private static final long MAX_LIFETIME_SECONDS = 2 * 3600;
    private static final String GOOGLE = "google";
    private static final String MICROSOFT = "microsoft";

    /**
     * Who the token is for. {@code subject} is the provider's permanent id for the account (Google {@code sub}; Microsoft
     * {@code tid:oid}), the one thing to match on after the first link. {@code tenant} is the company the account belongs to
     * (Google Workspace domain {@code hd}, or null for a personal Google account; Microsoft tenant id).
     * {@code emailVerified} is whether the provider says the company or the person has proven they own the address.
     */
    public record Identity(String provider, String subject, String tenant, String email, boolean emailVerified) {}

    private final DefaultJWTProcessor<SecurityContext> google;
    private final DefaultJWTProcessor<SecurityContext> microsoft;
    private final Clock clock;

    /** Each provider has its own key set, so a key from one can never vouch for a token claiming to be from the other. */
    public IdTokenVerifier(JWKSource<SecurityContext> googleKeys, JWKSource<SecurityContext> microsoftKeys, Clock clock) {
        this.google = processor(googleKeys);
        this.microsoft = processor(microsoftKeys);
        this.clock = clock;
    }

    private static DefaultJWTProcessor<SecurityContext> processor(JWKSource<SecurityContext> keys) {
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // Signature only here. Expiry, audience and nonce are checked in common() against the injected clock, in one place.
        p.setJWTClaimsSetVerifier((claims, context) -> { });
        return p;
    }

    public Identity verifyGoogle(String idToken, String clientId, String nonce) {
        JWTClaimsSet claims = common(google, idToken, clientId, nonce);
        String issuer = claims.getIssuer();
        if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
            throw refused();
        }
        String subject = string(claims, "sub");
        if (subject == null) {
            throw refused();
        }
        return new Identity(GOOGLE, subject, string(claims, "hd"), lower(string(claims, "email")), truthy(claims.getClaim("email_verified")));
    }

    public Identity verifyMicrosoft(String idToken, String clientId, String nonce) {
        JWTClaimsSet claims = common(microsoft, idToken, clientId, nonce);
        String tenant = string(claims, "tid");
        String oid = string(claims, "oid");
        // One signing key set serves every company, so the issuer must name the company the token itself says it is for.
        if (tenant == null || oid == null || MICROSOFT_PERSONAL_TENANT.equals(tenant)
                || !("https://login.microsoftonline.com/" + tenant + "/v2.0").equals(claims.getIssuer())) {
            throw refused();
        }
        // Microsoft's email claim is not proof of ownership: it can be set by the account holder. Only xms_edov says the
        // address's domain is verified by its owner. It is an optional claim: the app registration must list xms_edov under
        // optional ID-token claims, or every Microsoft email here counts as unverified (safe, but nothing links by email).
        return new Identity(MICROSOFT, tenant + ":" + oid, tenant, lower(string(claims, "email")), truthy(claims.getClaim("xms_edov")));
    }

    private JWTClaimsSet common(DefaultJWTProcessor<SecurityContext> processor, String idToken, String clientId, String nonce) {
        if (idToken == null || idToken.isBlank() || clientId == null || clientId.isBlank() || nonce == null || nonce.isBlank()) {
            throw refused();
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (Exception e) {
            if (e instanceof KeySourceException || e.getCause() instanceof KeySourceException) {
                // The provider's key list could not be fetched: not the person's fault, and worth telling the operator.
                LOG.warn("Could not fetch the provider's signing keys: {}", e.toString());
                throw new ApiException(503, "oidc.unavailable", "Sign-in with that provider is not available right now. Try again shortly");
            }
            LOG.debug("ID token refused: {}", e.toString());
            throw refused();   // unparsable, unsigned, wrong algorithm, unknown key, bad signature
        }
        List<String> audience = claims.getAudience();
        long now = clock.instant().getEpochSecond();
        long issuedAt = claims.getIssueTime() == null ? Long.MIN_VALUE : claims.getIssueTime().toInstant().getEpochSecond();
        long expires = claims.getExpirationTime() == null ? Long.MIN_VALUE : claims.getExpirationTime().toInstant().getEpochSecond();
        // One audience, ours (a list means a token minted for several applications); azp, when present, is ours too.
        if (audience == null || audience.size() != 1 || !clientId.equals(audience.get(0))
                || (claims.getClaim("azp") != null && !clientId.equals(string(claims, "azp")))
                || issuedAt == Long.MIN_VALUE || issuedAt - SKEW_SECONDS > now || expires - issuedAt > MAX_LIFETIME_SECONDS
                || claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().getEpochSecond() + SKEW_SECONDS < now
                || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().getEpochSecond() - SKEW_SECONDS > now)) {
            throw refused();
        }
        String sent = string(claims, "nonce");
        if (sent == null || !MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))) {
            throw refused();
        }
        return claims;
    }

    private static String string(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String lower(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Providers send booleans as true or "true" (Microsoft sometimes "1"). Anything else is false. */
    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static ApiException refused() {
        return new ApiException(401, "oidc.invalid", "That sign-in could not be verified. Try again");
    }
}
