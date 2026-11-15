package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.IdTokenVerifier;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * The ID token checks for Google and Microsoft sign-in, against tokens signed with a key made here. No database. Every
 * refusal is the same error, so a caller cannot learn which check failed.
 */
class IdTokenVerifierTest {

    private static final String CLIENT = "client-id-123.apps.example";
    private static final String NONCE = "n-0S6_WzA2Mj";
    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final String OID = "99999999-8888-7777-6666-000000000000";
    private static final Instant NOW = Instant.parse("2026-11-15T08:00:00Z");

    private static RSAKey key;
    private static RSAKey msKey;
    private static RSAKey otherKey;
    private static IdTokenVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        msKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();   // same key id, different key
        verifier = new IdTokenVerifier(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), new ImmutableJWKSet<>(new JWKSet(msKey.toPublicJWK())),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JWTClaimsSet.Builder google() {
        return new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(CLIENT).subject("1234567890")
                .claim("email", "Priya@Acme.com").claim("email_verified", true).claim("hd", "acme.com").claim("nonce", NONCE)
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(3600)));
    }

    private static JWTClaimsSet.Builder microsoft() {
        return new JWTClaimsSet.Builder().issuer("https://login.microsoftonline.com/" + TENANT + "/v2.0").audience(CLIENT)
                .subject("pairwise-sub").claim("tid", TENANT).claim("oid", OID).claim("email", "priya@acme.com").claim("xms_edov", true)
                .claim("nonce", NONCE).issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(3600)));
    }

    private static String sign(JWTClaimsSet claims, RSAKey with) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(with));
        return jwt.serialize();
    }

    private static void refused(org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals("oidc.invalid", e.code());
        assertEquals(401, e.status());
    }

    @Test
    void aGoogleTokenForThisApplicationGivesTheAccount() throws Exception {
        IdTokenVerifier.Identity who = verifier.verifyGoogle(sign(google().build(), key), CLIENT, NONCE);
        assertEquals("google", who.provider());
        assertEquals("1234567890", who.subject());
        assertEquals("acme.com", who.tenant());
        assertEquals("priya@acme.com", who.email(), "lower case");
        assertTrue(who.emailVerified());
    }

    @Test
    void aPersonalGoogleAccountHasNoCompanyDomain() throws Exception {
        IdTokenVerifier.Identity who = verifier.verifyGoogle(sign(google().claim("hd", null).build(), key), CLIENT, NONCE);
        assertNull(who.tenant(), "a Gmail account made with a work address carries no hd");
    }

    @Test
    void anUnverifiedGoogleEmailIsReportedAsUnverified() throws Exception {
        assertFalse(verifier.verifyGoogle(sign(google().claim("email_verified", false).build(), key), CLIENT, NONCE).emailVerified());
        assertFalse(verifier.verifyGoogle(sign(google().claim("email_verified", null).build(), key), CLIENT, NONCE).emailVerified());
    }

    @Test
    void theOldGoogleIssuerFormIsAccepted() throws Exception {
        assertEquals("1234567890", verifier.verifyGoogle(sign(google().issuer("accounts.google.com").build(), key), CLIENT, NONCE).subject());
    }

    @Test
    void aGoogleTokenFromAnotherIssuerOrForAnotherApplicationIsRefused() throws Exception {
        refused(() -> verifier.verifyGoogle(sign(google().issuer("https://accounts.google.com.evil.test").build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().audience("someone-elses-client").build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().audience(java.util.List.of("other", "another")).build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().subject(null).build(), key), CLIENT, NONCE));
    }

    @Test
    void aTokenMadeForSeveralApplicationsIsRefused() throws Exception {
        refused(() -> verifier.verifyGoogle(sign(google().audience(java.util.List.of("other", CLIENT)).claim("azp", "other").build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().audience(java.util.List.of("other", CLIENT)).build(), key), CLIENT, NONCE));
    }

    @Test
    void theAuthorizedPartyWhenPresentMustBeThisApplication() throws Exception {
        assertEquals("1234567890", verifier.verifyGoogle(sign(google().claim("azp", CLIENT).build(), key), CLIENT, NONCE).subject());
        refused(() -> verifier.verifyGoogle(sign(google().claim("azp", "other").build(), key), CLIENT, NONCE));
    }

    @Test
    void theIssueTimeMustBePresentSensibleAndTheLifetimeShort() throws Exception {
        refused(() -> verifier.verifyGoogle(sign(google().issueTime(null).build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().issueTime(Date.from(NOW.plusSeconds(600))).build(), key), CLIENT, NONCE));
        assertEquals("1234567890", verifier.verifyGoogle(sign(google().issueTime(Date.from(NOW.plusSeconds(30))).build(), key), CLIENT, NONCE).subject());
        refused(() -> verifier.verifyGoogle(sign(google().expirationTime(Date.from(NOW.plusSeconds(3 * 3600))).build(), key), CLIENT, NONCE));
    }

    @Test
    void theExpiryAndNotBeforeSkewBoundaries() throws Exception {
        assertEquals("1234567890", verifier.verifyGoogle(sign(google().expirationTime(Date.from(NOW.minusSeconds(60))).build(), key), CLIENT, NONCE).subject());
        refused(() -> verifier.verifyGoogle(sign(google().expirationTime(Date.from(NOW.minusSeconds(61))).build(), key), CLIENT, NONCE));
        assertEquals("1234567890", verifier.verifyGoogle(sign(google().notBeforeTime(Date.from(NOW.plusSeconds(30))).build(), key), CLIENT, NONCE).subject());
    }

    @Test
    void onlyRs256WithAJwtTypeAndAKnownKeyIdIsAccepted() throws Exception {
        for (JWSAlgorithm alg : new JWSAlgorithm[] {JWSAlgorithm.RS384, JWSAlgorithm.PS256}) {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID("k1").build(), google().build());
            jwt.sign(new RSASSASigner(key));
            refused(() -> verifier.verifyGoogle(jwt.serialize(), CLIENT, NONCE));
        }
        SignedJWT atJwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").type(new JOSEObjectType("at+jwt")).build(), google().build());
        atJwt.sign(new RSASSASigner(key));
        refused(() -> verifier.verifyGoogle(atJwt.serialize(), CLIENT, NONCE));
        SignedJWT unknownKid = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("nope").build(), google().build());
        unknownKid.sign(new RSASSASigner(key));
        refused(() -> verifier.verifyGoogle(unknownKid.serialize(), CLIENT, NONCE));
    }

    @Test
    void aBlankClientIdIsRefused() throws Exception {
        refused(() -> verifier.verifyGoogle(sign(google().build(), key), "", NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().build(), key), null, NONCE));
    }

    @Test
    void aKeyFromOneProviderDoesNotVouchForTheOther() throws Exception {
        refused(() -> verifier.verifyMicrosoft(sign(microsoft().build(), key), CLIENT, NONCE));   // Google's key, Microsoft claims
        refused(() -> verifier.verifyGoogle(sign(google().build(), msKey), CLIENT, NONCE));
    }

    @Test
    void aProviderWhoseKeysCannotBeFetchedIsUnavailableNotARefusal() throws Exception {
        IdTokenVerifier down = new IdTokenVerifier((selector, ctx) -> { throw new com.nimbusds.jose.KeySourceException("down"); },
                new ImmutableJWKSet<>(new JWKSet(msKey.toPublicJWK())), Clock.fixed(NOW, ZoneOffset.UTC));
        ApiException e = assertThrows(ApiException.class, () -> down.verifyGoogle(sign(google().build(), key), CLIENT, NONCE));
        assertEquals(503, e.status());
        assertEquals("oidc.unavailable", e.code());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefusedEvenWithTheSameKeyId() throws Exception {
        refused(() -> verifier.verifyGoogle(sign(google().build(), otherKey), CLIENT, NONCE));
    }

    @Test
    void anUnsignedTokenAndAnHsTokenAreRefused() throws Exception {
        refused(() -> verifier.verifyGoogle(new PlainJWT(google().build()).serialize(), CLIENT, NONCE));
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), google().build());
        hs.sign(new MACSigner(new byte[32]));
        refused(() -> verifier.verifyGoogle(hs.serialize(), CLIENT, NONCE));
    }

    @Test
    void anExpiredTokenIsRefusedButAFewSecondsOfClockDifferenceIsNot() throws Exception {
        refused(() -> verifier.verifyGoogle(sign(google().expirationTime(Date.from(NOW.minusSeconds(120))).build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().expirationTime(null).build(), key), CLIENT, NONCE));
        assertEquals("1234567890", verifier.verifyGoogle(sign(google().expirationTime(Date.from(NOW.minusSeconds(30))).build(), key), CLIENT, NONCE).subject());
        refused(() -> verifier.verifyGoogle(sign(google().notBeforeTime(Date.from(NOW.plusSeconds(600))).build(), key), CLIENT, NONCE));
    }

    @Test
    void theNonceMustBeTheOneThisSignInStartedWith() throws Exception {
        refused(() -> verifier.verifyGoogle(sign(google().build(), key), CLIENT, "another-nonce"));
        refused(() -> verifier.verifyGoogle(sign(google().claim("nonce", null).build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(google().build(), key), CLIENT, ""));
        refused(() -> verifier.verifyGoogle(sign(google().build(), key), CLIENT, null));
    }

    @Test
    void garbageAndMissingArgumentsAreRefused() {
        refused(() -> verifier.verifyGoogle("not-a-token", CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(null, CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle("", CLIENT, NONCE));
        refused(() -> verifier.verifyMicrosoft("a.b.c", CLIENT, NONCE));
    }

    @Test
    void aMicrosoftTokenGivesTheAccountByTenantAndObjectId() throws Exception {
        IdTokenVerifier.Identity who = verifier.verifyMicrosoft(sign(microsoft().build(), msKey), CLIENT, NONCE);
        assertEquals("microsoft", who.provider());
        assertEquals(TENANT + ":" + OID, who.subject());
        assertEquals(TENANT, who.tenant());
        assertEquals("priya@acme.com", who.email());
        assertTrue(who.emailVerified());
    }

    @Test
    void aMicrosoftEmailCountsAsVerifiedOnlyWhenTheDomainIsVerified() throws Exception {
        assertFalse(verifier.verifyMicrosoft(sign(microsoft().claim("xms_edov", false).build(), msKey), CLIENT, NONCE).emailVerified());
        assertFalse(verifier.verifyMicrosoft(sign(microsoft().claim("xms_edov", null).build(), msKey), CLIENT, NONCE).emailVerified(), "the email claim alone proves nothing");
        assertTrue(verifier.verifyMicrosoft(sign(microsoft().claim("xms_edov", "1").build(), msKey), CLIENT, NONCE).emailVerified());
        assertNull(verifier.verifyMicrosoft(sign(microsoft().claim("email", null).build(), msKey), CLIENT, NONCE).email());
    }

    @Test
    void aMicrosoftIssuerMustNameTheTokensOwnTenant() throws Exception {
        String other = UUID.randomUUID().toString();
        refused(() -> verifier.verifyMicrosoft(sign(microsoft().issuer("https://login.microsoftonline.com/" + other + "/v2.0").build(), msKey), CLIENT, NONCE));
        refused(() -> verifier.verifyMicrosoft(sign(microsoft().issuer("https://login.microsoftonline.com/" + TENANT + "/v2.0/evil").build(), msKey), CLIENT, NONCE));
        refused(() -> verifier.verifyMicrosoft(sign(microsoft().issuer("https://sts.windows.net/" + TENANT + "/").build(), msKey), CLIENT, NONCE));
    }

    @Test
    void aPersonalMicrosoftAccountAndATokenWithoutTenantOrObjectIdAreRefused() throws Exception {
        String consumer = IdTokenVerifier.MICROSOFT_PERSONAL_TENANT;
        refused(() -> verifier.verifyMicrosoft(sign(microsoft().claim("tid", consumer)
                .issuer("https://login.microsoftonline.com/" + consumer + "/v2.0").build(), msKey), CLIENT, NONCE));
        refused(() -> verifier.verifyMicrosoft(sign(microsoft().claim("tid", null).build(), msKey), CLIENT, NONCE));
        refused(() -> verifier.verifyMicrosoft(sign(microsoft().claim("oid", null).build(), msKey), CLIENT, NONCE));
    }

    @Test
    void aGoogleTokenIsNotAMicrosoftTokenAndTheOtherWayRound() throws Exception {
        refused(() -> verifier.verifyMicrosoft(sign(google().build(), key), CLIENT, NONCE));
        refused(() -> verifier.verifyGoogle(sign(microsoft().build(), key), CLIENT, NONCE));
    }
}
