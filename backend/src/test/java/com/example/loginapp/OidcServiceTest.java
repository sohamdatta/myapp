package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.IdTokenVerifier;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.MfaService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.OidcProvider;
import com.example.loginapp.core.OidcService;
import com.example.loginapp.core.OidcTokenClient;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SignInSettingsService;
import com.example.loginapp.core.Totp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Sign in with a Google account: connecting one, signing in with it, the one-time code, the authenticator code that is still
 * asked for, and the doors that stay closed (the platform console). The provider is played by a fake that returns a token
 * signed with a key made here. Needs PostgreSQL as described in {@link UserModuleTest}.
 */
class OidcServiceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final String CLIENT = "test-client.apps.example";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicLong NOW = new AtomicLong(1_900_000_010_000L);

    private static final Clock CLOCK = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(NOW.get());
        }
    };

    private static Db db;
    private static PlatformService platform;
    private static MailOutbox outbox;
    private static SessionCtx superAdmin;
    private static RSAKey key;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    /** What the fake provider returns for the next code: a token with these claims, the nonce filled in from the sign-in. */
    private static final AtomicReference<Map<String, Object>> NEXT = new AtomicReference<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        ownerUser = env("APP_DB_OWNER_USER", "postgres");
        ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_oidc with (force)");
            st.execute("create database hrms_test_oidc");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the sign-in with Google tests");
        url = "jdbc:postgresql://" + host + "/hrms_test_oidc";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        platform = new PlatformService(db);
        outbox = new MailOutbox(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY));
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        AuthService plain = new AuthService(db, new RateLimits());
        superAdmin = plain.resolve(plain.enterConsole(plain.resolve(plain.login("root@platform.test", PASSWORD))));
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
    }

    private final RateLimits limits = new RateLimits();
    private final Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
    private final AuthService auth = new AuthService(db, limits);
    private final MfaService mfa = new MfaService(db, crypto, outbox, limits, CLOCK);
    private final OidcService oidc = service(CLIENT);
    private final SignInSettingsService settings = new SignInSettingsService(db);

    private OidcService service(String clientId) {
        IdTokenVerifier verifier = new IdTokenVerifier(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())),
                new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), CLOCK);
        OidcTokenClient fake = (provider, code, codeVerifier, redirectUri) -> {
            Map<String, Object> claims = NEXT.get();
            try {
                JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(CLIENT)
                        .issueTime(Date.from(CLOCK.instant())).expirationTime(Date.from(CLOCK.instant().plusSeconds(3600)));
                claims.forEach(b::claim);
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), b.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        return new OidcService(db, crypto, outbox, limits, verifier, fake, List.of(OidcProvider.google(clientId, "secret")), "https://hr.example", CLOCK);
    }

    /** An Organization owner whose Organization accepts Google accounts of acme.com. */
    private String owner() {
        String email = ownerWithoutGoogle();
        settings.setGoogleDomain(insideOrganization(email), "acme.com", PASSWORD);
        return email;
    }

    private String ownerWithoutGoogle() {
        String slug = "oidc-" + SEQ.incrementAndGet();
        String email = "owner@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, email);
        new InvitationService(db, limits).accept((String) created.get("invitationToken"), PASSWORD, null);
        return email;
    }

    private SessionCtx insideOrganization(String email) {
        SessionCtx identity = auth.resolve(auth.login(email, PASSWORD));
        java.util.UUID tenant = java.util.UUID.fromString(((Map<?, ?>) ((List<?>) auth.destinations(identity).get("organizations")).get(0)).get("tenantId").toString());
        return auth.resolve(auth.enterOrganization(identity, tenant));
    }

    private static String param(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** The account at Google signing in as: what the fake provider will say about it. */
    private static void google(String sub, String email) {
        NEXT.set(new LinkedHashMap<>(Map.of("sub", sub, "email", email, "email_verified", true, "hd", "acme.com")));
    }

    /** Runs the provider's side for a started flow: returns where the browser goes next. */
    private OidcService.Finished comeBack(OidcService.Started started, Map<String, Object> claims) {
        Map<String, Object> withNonce = new LinkedHashMap<>(claims);
        withNonce.put("nonce", param(started.url(), "nonce"));
        NEXT.set(withNonce);
        return oidc.callback("google", "one-time-code", param(started.url(), "state"), null, started.cookie(), "10.0.0.1");
    }

    private OidcService.Finished comeBack(OidcService.Started started) {
        return comeBack(started, NEXT.get());
    }

    private void connect(String email, String sub) {
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        google(sub, email);
        assertEquals("/?oidc=linked", comeBack(oidc.startLink(ctx, "google", null)).redirect());
    }

    private static String code(OidcService.Finished done) {
        assertEquals("/?oidc=1", done.redirect(), "the code is never in the address");
        assertNotNull(done.handoff());
        return done.handoff();
    }

    private static String ownerQuery(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static ApiException refusal(org.junit.jupiter.api.function.Executable work) {
        return assertThrows(ApiException.class, work);
    }

    private static void advance(long seconds) {
        NOW.addAndGet(seconds * 1000);
    }

    private static String codeNow(String secretBase32) {
        return Totp.code(Totp.fromBase32(secretBase32), Totp.stepAt(Instant.ofEpochMilli(NOW.get())));
    }

    @Test
    void startingSendsThePersonToGoogleWithStateNonceAndPkce() {
        OidcService.Started s = oidc.startSignIn("google", null);
        assertTrue(s.url().startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), s.url());
        assertEquals("code", param(s.url(), "response_type"));
        assertEquals(CLIENT, param(s.url(), "client_id"));
        assertEquals("https://hr.example/api/auth/oidc/google/callback", param(s.url(), "redirect_uri"));
        assertEquals("S256", param(s.url(), "code_challenge_method"));
        assertEquals("select_account", param(s.url(), "prompt"));
        assertNotNull(param(s.url(), "state"));
        assertNotNull(param(s.url(), "nonce"));
        assertFalse(s.cookie().contains(param(s.url(), "state")), "the cookie is sealed, not readable");
        assertEquals(List.of("google"), oidc.available());
        assertEquals(404, refusal(() -> oidc.startSignIn("facebook", null)).status());
        assertEquals(404, refusal(() -> service("").startSignIn("google", null)).status(), "off without a client id");
        assertEquals(List.of(), service("").available());
    }

    @Test
    void connectThenSignInWithGoogleGivesASessionThatRemembersHow() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        OidcService.Linked linked = oidc.linked(auth.resolve(auth.login(email, PASSWORD))).get(0);
        assertEquals("google", linked.provider());
        assertEquals("acme.com", linked.tenant());
        String sub = ownerQuery("select subject from auth.external_login order by created_at desc limit 1");
        google(sub, email);
        OidcService.Finished done = comeBack(oidc.startSignIn("google", null));
        String code = code(done);
        AuthService.SignIn result = oidc.exchange(code, null);
        assertFalse(result.mfaRequired());
        assertEquals(email, auth.resolve(result.token()).email());
        assertEquals("google", ownerQuery("select auth_method from auth.session where refresh_token_hash = sha256(convert_to('" + result.token() + "','UTF8'))"));
        assertEquals(401, refusal(() -> oidc.exchange(code, null)).status(), "the code works once");
        assertNotNull(ownerQuery("select last_used_at from auth.external_login where subject = '" + sub + "'"));
    }

    @Test
    void aGoogleAccountThatIsNotConnectedSignsNobodyIn() {
        google("nobody-" + SEQ.incrementAndGet(), "nobody@acme.com");
        assertEquals("/?oidc_error=oidc.not_linked", comeBack(oidc.startSignIn("google", null)).redirect());
    }

    @Test
    void theOneTimeCodeExpiresAndUnknownCodesAreRefused() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        google(ownerQuery("select subject from auth.external_login order by created_at desc limit 1"), email);
        String code = code(comeBack(oidc.startSignIn("google", null)));
        db.inAuth(c -> com.example.loginapp.core.Sql.update(c, "update auth.oidc_handoff set expires_at = now() - interval '1 second'"));
        assertEquals(401, refusal(() -> oidc.exchange(code, null)).status());
        assertEquals(401, refusal(() -> oidc.exchange("not-a-code", null)).status());
        assertEquals(401, refusal(() -> oidc.exchange(null, null)).status());
    }

    @Test
    void theAuthenticatorCodeIsStillAskedForAndTheSessionKeepsTheMethod() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        MfaService.Enrolment e = mfa.start(ctx);
        mfa.confirm(ctx, PASSWORD, codeNow(e.secret()));
        advance(30);
        google(ownerQuery("select subject from auth.external_login order by created_at desc limit 1"), email);
        AuthService.SignIn step = oidc.exchange(code(comeBack(oidc.startSignIn("google", null))), null);
        assertTrue(step.mfaRequired(), "a company account is one factor, not two");
        assertEquals(401, refusal(() -> auth.resolve(step.token())).status(), "the challenge is not a session");
        String token = mfa.complete(step.token(), codeNow(e.secret()), null);
        assertEquals(email, auth.resolve(token).email());
        assertEquals("google", ownerQuery("select auth_method from auth.session where refresh_token_hash = sha256(convert_to('" + token + "','UTF8'))"));
    }

    @Test
    void aSessionStartedWithGoogleDoesNotOpenThePlatformConsole() throws Exception {
        SessionCtx ctx = auth.resolve(auth.login("root@platform.test", PASSWORD));
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute("update auth.session set auth_method = 'google' where id = '" + ctx.sessionId() + "'");
        }
        assertEquals(403, refusal(() -> auth.enterConsole(ctx)).status());
        assertEquals(403, refusal(() -> oidc.startLink(superAdmin, "google", null)).status(), "nor can accounts be connected from the console");
    }

    @Test
    void theMethodSurvivesEnteringAnOrganization() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        google(ownerQuery("select subject from auth.external_login order by created_at desc limit 1"), email);
        SessionCtx identity = auth.resolve(oidc.exchange(code(comeBack(oidc.startSignIn("google", null))), null).token());
        java.util.UUID tenant = java.util.UUID.fromString(((Map<?, ?>) ((List<?>) auth.destinations(identity).get("organizations")).get(0)).get("tenantId").toString());
        String inside = auth.enterOrganization(identity, tenant);
        assertEquals("google", ownerQuery("select auth_method from auth.session where refresh_token_hash = sha256(convert_to('" + inside + "','UTF8'))"));
    }

    @Test
    void onlyAnAccountOfACompanyTheOrganizationAcceptsCanBeConnectedOrSignIn() throws Exception {
        String without = ownerWithoutGoogle();
        SessionCtx ctx = auth.resolve(auth.login(without, PASSWORD));
        google("sub-" + SEQ.incrementAndGet(), without);
        assertEquals("/?oidc_error=oidc.not_allowed", comeBack(oidc.startLink(ctx, "google", null)).redirect(), "the Organization has not turned it on");
        String email = owner();
        SessionCtx mine = auth.resolve(auth.login(email, PASSWORD));
        Map<String, Object> other = new LinkedHashMap<>(NEXT.get());
        other.put("hd", "other.com");
        assertEquals("/?oidc_error=oidc.not_allowed", comeBack(oidc.startLink(mine, "google", null), other).redirect(), "another company's account");
        Map<String, Object> personal = new LinkedHashMap<>(NEXT.get());
        personal.remove("hd");
        assertEquals("/?oidc_error=oidc.not_allowed", comeBack(oidc.startLink(mine, "google", null), personal).redirect(), "a personal account has no company");
        assertEquals("0", ownerQuery("select count(*) from auth.external_login where identity_id = (select id from auth.identity where email = '" + email + "')"));
    }

    @Test
    void turningGoogleOffForTheOrganizationStopsTheSignIn() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        google(ownerQuery("select subject from auth.external_login order by created_at desc limit 1"), email);
        settings.setGoogleDomain(insideOrganization(email), "", null);
        assertEquals("/?oidc_error=oidc.not_allowed", comeBack(oidc.startSignIn("google", null)).redirect());
        settings.setGoogleDomain(insideOrganization(email), "ACME.com ", PASSWORD);
        assertEquals("acme.com", settings.get(insideOrganization(email)).googleDomain());
        assertTrue(comeBack(oidc.startSignIn("google", null)).redirect().startsWith("/?oidc=1"));
    }

    @Test
    void theDomainSettingNeedsTheAdminsPasswordAndARealDomain() throws Exception {
        String email = ownerWithoutGoogle();
        SessionCtx inside = insideOrganization(email);
        assertEquals(403, refusal(() -> settings.setGoogleDomain(inside, "acme.com", "wrong-password-here")).status());
        assertEquals(403, refusal(() -> settings.setGoogleDomain(inside, "acme.com", null)).status());
        assertEquals(400, refusal(() -> settings.setGoogleDomain(inside, "not a domain", PASSWORD)).status());
        assertEquals(400, refusal(() -> settings.setGoogleDomain(inside, "localhost", PASSWORD)).status());
        assertEquals(null, settings.get(inside).googleDomain());
        assertEquals("acme.com", settings.setGoogleDomain(inside, "acme.com", PASSWORD).googleDomain());
    }

    @Test
    void theRoundTripIsTiedToTheBrowserThatStartedIt() {
        OidcService.Started s = oidc.startSignIn("google", null);
        google("sub-" + SEQ.incrementAndGet(), "x@acme.com");
        Map<String, Object> claims = new LinkedHashMap<>(NEXT.get());
        claims.put("nonce", param(s.url(), "nonce"));
        NEXT.set(claims);
        String state = param(s.url(), "state");
        assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", state, null, null, null).redirect(), "no cookie");
        assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", "other-state", null, s.cookie(), null).redirect(), "wrong state");
        assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", null, null, s.cookie(), null).redirect(), "no state");
        assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", state, null, s.cookie().substring(1) + "A", null).redirect(), "tampered");
        assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", state, null, "garbage", null).redirect());
        assertEquals("/?oidc_error=oidc.cancelled", oidc.callback("google", null, null, "access_denied", s.cookie(), null).redirect());
        advance(601);
        assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", state, null, s.cookie(), null).redirect(), "older than ten minutes");
    }

    @Test
    void aTokenWithTheWrongNonceIsRefused() {
        OidcService.Started s = oidc.startSignIn("google", null);
        google("sub-" + SEQ.incrementAndGet(), "x@acme.com");
        Map<String, Object> claims = new LinkedHashMap<>(NEXT.get());
        claims.put("nonce", "someone-elses-nonce");
        NEXT.set(claims);
        assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", param(s.url(), "state"), null, s.cookie(), null).redirect());
    }

    @Test
    void aSignInCookieCannotConnectAnAccount() throws Exception {
        String email = owner();
        google("sub-" + SEQ.incrementAndGet(), email);
        OidcService.Finished done = comeBack(oidc.startSignIn("google", null));
        assertEquals("/?oidc_error=oidc.not_linked", done.redirect(), "a sign-in flow only signs in");
        assertEquals("0", ownerQuery("select count(*) from auth.external_login where email = '" + email + "'"));
    }

    @Test
    void anAccountConnectsToOneLoginAndAnUnverifiedEmailIsRefused() {
        String first = owner();
        String second = owner();
        String sub = "shared-" + SEQ.incrementAndGet();
        connect(first, sub);
        SessionCtx other = auth.resolve(auth.login(second, PASSWORD));
        google(sub, second);
        assertEquals("/?oidc_error=oidc.taken", comeBack(oidc.startLink(other, "google", null)).redirect());
        SessionCtx again = auth.resolve(auth.login(first, PASSWORD));
        google("another-" + SEQ.incrementAndGet(), first);
        assertEquals("/?oidc_error=oidc.already_connected", comeBack(oidc.startLink(again, "google", null)).redirect());
        Map<String, Object> unverified = new LinkedHashMap<>(NEXT.get());
        unverified.put("email_verified", false);
        assertEquals("/?oidc_error=oidc.email_unverified", comeBack(oidc.startLink(other, "google", null), unverified).redirect());
    }

    @Test
    void disconnectingStopsGoogleSignIn() throws Exception {
        String email = owner();
        String sub = "sub-" + SEQ.incrementAndGet();
        connect(email, sub);
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        oidc.unlink(ctx, "google");
        assertEquals(List.of(), oidc.linked(ctx));
        assertEquals(404, refusal(() -> oidc.unlink(ctx, "google")).status());
        google(sub, email);
        assertEquals("/?oidc_error=oidc.not_linked", comeBack(oidc.startSignIn("google", null)).redirect());
    }

    @Test
    void connectingAndDisconnectingNeedAFreshCodeWhenTwoStepIsOn() throws Exception {
        String email = owner();
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        MfaService.Enrolment e = mfa.start(ctx);
        mfa.confirm(ctx, PASSWORD, codeNow(e.secret()));
        advance(30);
        AuthService.SignIn step = auth.signIn(email, PASSWORD, null);
        SessionCtx signedIn = auth.resolve(mfa.complete(step.token(), codeNow(e.secret()), null));
        advance(16 * 60);
        assertEquals("stepup.required", refusal(() -> oidc.startLink(signedIn, "google", null)).code());
        assertEquals("stepup.required", refusal(() -> oidc.unlink(signedIn, "google")).code());
    }

    @Test
    void tooManyStartsFromOneAddressAreLimited() {
        OidcService limited = service(CLIENT);
        for (int i = 0; i < 300; i++) {
            limited.startSignIn("google", "10.9.9.9");
        }
        assertEquals(429, refusal(() -> limited.startSignIn("google", "10.9.9.9")).status());
        limited.startSignIn("google", "10.9.9.10");
    }

    @Test
    void successfulSignInsDoNotCountAgainstAnAddressButFailuresDo() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        String sub = ownerQuery("select subject from auth.external_login order by created_at desc limit 1");
        for (int i = 0; i < 40; i++) {   // an office signing in from one address
            google(sub, email);
            OidcService.Started s = oidc.startSignIn("google", "10.8.8.8");
            Map<String, Object> claims = new LinkedHashMap<>(NEXT.get());
            claims.put("nonce", param(s.url(), "nonce"));
            NEXT.set(claims);
            OidcService.Finished done = oidc.callback("google", "c", param(s.url(), "state"), null, s.cookie(), "10.8.8.8");
            oidc.exchange(code(done), "10.8.8.8");
        }
        for (int i = 0; i < 30; i++) {
            assertEquals("/?oidc_error=oidc.invalid", oidc.callback("google", "c", "x", null, "garbage", "10.8.8.7").redirect());
        }
        assertEquals("/?oidc_error=request.rate_limited", oidc.callback("google", "c", "x", null, "garbage", "10.8.8.7").redirect());
        assertEquals(429, refusal(() -> oidc.exchange("nope", "10.8.8.7")).status());
    }

    @Test
    void connectingAfterTheSessionEndedDoesNothing() throws Exception {
        String email = owner();
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        google("sub-" + SEQ.incrementAndGet(), email);
        OidcService.Started started = oidc.startLink(ctx, "google", null);
        auth.logout(ctx);
        assertEquals("/?oidc_error=oidc.invalid", comeBack(started).redirect());
        assertEquals("0", ownerQuery("select count(*) from auth.external_login where identity_id = (select id from auth.identity where email = '" + email + "')"));
    }

    @Test
    void aCodeIssuedBeforeDisconnectingNoLongerSignsIn() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        google(ownerQuery("select subject from auth.external_login order by created_at desc limit 1"), email);
        String code = code(comeBack(oidc.startSignIn("google", null)));
        oidc.unlink(auth.resolve(auth.login(email, PASSWORD)), "google");
        assertEquals(401, refusal(() -> oidc.exchange(code, null)).status());
    }

    @Test
    void aWorkspaceAccountThatMovedToAnotherDomainIsNotTheOneThatWasConnected() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        Map<String, Object> moved = new LinkedHashMap<>(NEXT.get());
        moved.put("hd", "other.com");
        assertEquals("/?oidc_error=oidc.invalid", comeBack(oidc.startSignIn("google", null), moved).redirect());
    }

    @Test
    void aDisabledLoginCannotSignInWithGoogle() throws Exception {
        String email = owner();
        connect(email, "sub-" + SEQ.incrementAndGet());
        google(ownerQuery("select subject from auth.external_login order by created_at desc limit 1"), email);
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute("update auth.identity set status = 'disabled' where email = '" + email + "'");
        }
        assertEquals("/?oidc_error=oidc.invalid", comeBack(oidc.startSignIn("google", null)).redirect());
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
