package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.MfaService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Totp;

/**
 * Two-step sign-in: set up and confirm, a right password alone grants nothing, a code works once, recovery codes work once,
 * wrong codes lock, and no door (an invitation link) goes round it. Needs PostgreSQL as described in {@link UserModuleTest}.
 */
class MfaServiceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQ = new AtomicInteger();
    /** Starts inside a step, a long way from a step edge, so +/- a few seconds never changes which step it is. */
    private static final AtomicLong NOW = new AtomicLong(1_900_000_010_000L);

    private static final Clock CLOCK = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        ownerUser = env("APP_DB_OWNER_USER", "postgres");
        ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_mfa with (force)");
            st.execute("create database hrms_test_mfa");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the two-step tests");
        url = "jdbc:postgresql://" + host + "/hrms_test_mfa";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        platform = new PlatformService(db);
        outbox = new MailOutbox(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY));
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        AuthService plain = new AuthService(db, new RateLimits());
        superAdmin = plain.resolve(plain.enterConsole(plain.resolve(plain.login("root@platform.test", PASSWORD))));
    }

    private final RateLimits limits = new RateLimits();
    private final AuthService auth = new AuthService(db, limits);
    private final MfaService mfa = new MfaService(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY), outbox, limits, CLOCK);

    /** An Organization owner; returns the email. */
    private String owner() {
        String slug = "mfa-" + SEQ.incrementAndGet();
        String email = "owner@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", email);
        new InvitationService(db, limits).accept((String) created.get("invitationToken"), PASSWORD, null);
        return email;
    }

    private static void advance(long seconds) {
        NOW.addAndGet(seconds * 1000);
    }

    private static String codeNow(String secretBase32) {
        return Totp.code(Totp.fromBase32(secretBase32), Totp.stepAt(Instant.ofEpochMilli(NOW.get())));
    }

    /** Enrols the person; returns [secret, first recovery code...] and leaves the clock one step on, so codes are fresh. */
    private List<String> enrol(String email) {
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        MfaService.Enrolment e = mfa.start(ctx);
        List<String> codes = mfa.confirm(ctx, PASSWORD, codeNow(e.secret()));
        advance(30);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add(e.secret());
        out.addAll(codes);
        return out;
    }

    private static ApiException refusal(org.junit.jupiter.api.function.Executable work) {
        return assertThrows(ApiException.class, work);
    }

    private static String owner(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void ownerExec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void settingUpNeedsAWorkingCodeAndShowsTenRecoveryCodesOnce() throws Exception {
        String email = owner();
        SessionCtx ctx = auth.resolve(auth.login(email, PASSWORD));
        assertFalse(mfa.status(ctx).enrolled());
        assertEquals(409, refusal(() -> mfa.confirm(ctx, PASSWORD, "123456")).status(), "nothing started yet");
        MfaService.Enrolment e = mfa.start(ctx);
        assertTrue(e.uri().startsWith("otpauth://totp/"));
        assertTrue(e.uri().contains("secret=" + e.secret()));
        assertEquals(400, refusal(() -> mfa.confirm(ctx, PASSWORD, "000000")).status(), "a wrong code does not turn it on");
        assertFalse(mfa.status(ctx).enrolled());
        List<String> codes = mfa.confirm(ctx, PASSWORD, codeNow(e.secret()));
        assertEquals(10, codes.size());
        assertEquals(10, codes.stream().distinct().count());
        assertTrue(codes.get(0).matches("[A-Z2-7]{5}-[A-Z2-7]{5}"), codes.get(0));
        MfaService.Status status = mfa.status(ctx);
        assertTrue(status.enrolled());
        assertEquals(10, status.recoveryCodesLeft());
        assertEquals(409, refusal(() -> mfa.start(ctx)).status(), "already on");
        assertNotNull(owner("select mfa_secret_enc from auth.identity where email = '" + email + "'"));
        // neither the secret nor a recovery code is anywhere in the clear
        assertFalse(owner("select encode(mfa_secret_enc, 'escape') from auth.identity where email = '" + email + "'").contains(e.secret().substring(0, 8)));
        String stored = owner("select string_agg(encode(code_hash, 'hex'), ',') from auth.mfa_recovery_code");
        assertFalse(stored.toUpperCase().contains(codes.get(0).replace("-", "")));
    }

    @Test
    void settingUpNeedsThePasswordAndEndsEarlierSessions() throws Exception {
        String email = owner();
        String before = auth.login(email, PASSWORD);
        String mine = auth.login(email, PASSWORD);
        SessionCtx ctx = auth.resolve(mine);
        MfaService.Enrolment e = mfa.start(ctx);
        assertEquals(403, refusal(() -> mfa.confirm(ctx, "wrong-password-here", codeNow(e.secret()))).status(), "a stolen session cannot turn it on");
        assertFalse(mfa.status(ctx).enrolled());
        mfa.confirm(ctx, PASSWORD, codeNow(e.secret()));
        assertEquals(401, refusal(() -> auth.resolve(before)).status(), "a session from before it was on is ended");
        assertNotNull(auth.resolve(mine), "the session that set it up stays");
    }

    @Test
    void aResetDoesNotGiveACodeGuesserFreshGuessesAndEndsOpenChallenges() throws Exception {
        String email = owner();
        enrol(email);
        AuthService.SignIn step = auth.signIn(email, PASSWORD, null);
        for (int i = 0; i < 3; i++) {
            AuthService.SignIn s = auth.signIn(email, PASSWORD, null);
            assertEquals("auth.invalid_code", refusal(() -> mfa.complete(s.token(), "000000", null)).code());
        }
        String hold = auth.signIn(email, PASSWORD, null).token();
        com.example.loginapp.core.PasswordService passwords = new com.example.loginapp.core.PasswordService(db, outbox, limits, "https://x.test", false);
        passwords.requestReset(email, "10.9.9.1");
        passwords.sendWaitingLinks();
        ownerExec("update auth.password_reset set token_hash = sha256('known-token'::bytea) where identity_id = (select id from auth.identity where email = '" + email + "') and used_at is null");
        passwords.reset("known-token", "a-new-long-password-9", null);
        assertEquals("auth.challenge_expired", refusal(() -> mfa.complete(hold, "000000", null)).code(), "open challenges end with the reset");
        assertEquals(3L, Long.parseLong(owner("select count(*) from auth.login_failure where email = '" + email + "'")), "the failures stay");
        assertNotNull(step);
    }

    @Test
    void aRightPasswordAloneGrantsNothingAndTheCodeCompletesTheSignIn() {
        String email = owner();
        String secret = enrol(email).get(0);
        assertEquals(403, refusal(() -> auth.login(email, PASSWORD)).status(), "the plain sign-in refuses a login with two steps");
        AuthService.SignIn step = auth.signIn(email, PASSWORD, "10.0.0.1");
        assertTrue(step.mfaRequired());
        assertEquals(401, refusal(() -> auth.resolve(step.token())).status(), "the challenge is not a session");
        String session = mfa.complete(step.token(), codeNow(secret), "10.0.0.1");
        SessionCtx ctx = auth.resolve(session);
        assertEquals(email, ctx.email());
        assertEquals(401, refusal(() -> mfa.complete(step.token(), codeNow(secret), "10.0.0.1")).status(), "a challenge works once");
    }

    @Test
    void aCodeWorksOnceEvenInsideItsWindow() {
        String email = owner();
        String secret = enrol(email).get(0);
        String code = codeNow(secret);
        mfa.complete(auth.signIn(email, PASSWORD, null).token(), code, null);
        ApiException replay = refusal(() -> mfa.complete(auth.signIn(email, PASSWORD, null).token(), code, null));
        assertEquals("auth.invalid_code", replay.code());
        // the step before it is refused too, and the next one is fine
        long step = Totp.stepAt(Instant.ofEpochMilli(NOW.get()));
        String before = Totp.code(Totp.fromBase32(secret), step - 1);
        assertEquals(401, refusal(() -> mfa.complete(auth.signIn(email, PASSWORD, null).token(), before, null)).status());
        advance(30);
        assertNotNull(mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(secret), null));
    }

    @Test
    void recoveryCodesWorkOnceAndTellThePerson() throws Exception {
        String email = owner();
        List<String> enrolled = enrol(email);
        String recovery = enrolled.get(1);
        assertNotNull(mfa.complete(auth.signIn(email, PASSWORD, null).token(), recovery.toLowerCase(), null), "case and the dash do not matter");
        assertEquals(401, refusal(() -> mfa.complete(auth.signIn(email, PASSWORD, null).token(), recovery, null)).status());
        assertEquals(8, mfa.status(auth.resolve(mfa.complete(auth.signIn(email, PASSWORD, null).token(), enrolled.get(2), null))).recoveryCodesLeft(), "two of ten are used");
        MailOutboxTest.Recorder sender = new MailOutboxTest.Recorder();
        for (int i = 0; i < 20 && outbox.deliverDue(sender) > 0; i++) {
            // a batch is ten messages and earlier tests left more than that
        }
        assertTrue(sender.sent.stream().anyMatch(m -> m.to().equalsIgnoreCase(email) && m.subject().contains("recovery code was used")));
    }

    @Test
    void fiveWrongCodesEndTheChallengeAndLockTheAddressLikeWrongPasswords() {
        String email = owner();
        String secret = enrol(email).get(0);
        AuthService.SignIn step = auth.signIn(email, PASSWORD, null);
        for (int i = 0; i < 4; i++) {
            assertEquals("auth.invalid_code", refusal(() -> mfa.complete(step.token(), "000000", null)).code());
        }
        // the fifth wrong code ends the challenge and is the fifth failure: the lock
        assertEquals("auth.invalid_code", refusal(() -> mfa.complete(step.token(), "000000", null)).code());
        assertEquals("auth.challenge_expired", refusal(() -> mfa.complete(step.token(), codeNow(secret), null)).code(), "even the right code, on a dead challenge");
        assertEquals(429, refusal(() -> auth.signIn(email, PASSWORD, null)).status(), "and no new challenge while locked");
    }

    @Test
    void guessingCodesCannotBeRestartedByTypingThePasswordAgain() {
        String email = owner();
        enrol(email);
        for (int round = 0; round < 5; round++) {
            AuthService.SignIn step = auth.signIn(email, PASSWORD, null);   // a right password does not clear the failures
            assertEquals("auth.invalid_code", refusal(() -> mfa.complete(step.token(), "000000", null)).code());
        }
        assertEquals(429, refusal(() -> auth.signIn(email, PASSWORD, null)).status(), "five wrong codes across five challenges lock it");
    }

    @Test
    void anOldChallengeAndAnUnknownOneAreRefusedTheSame() throws Exception {
        String email = owner();
        String secret = enrol(email).get(0);
        AuthService.SignIn step = auth.signIn(email, PASSWORD, null);
        ownerExec("update auth.mfa_challenge set expires_at = now() - interval '1 second'");
        assertEquals("auth.challenge_expired", refusal(() -> mfa.complete(step.token(), codeNow(secret), null)).code());
        assertEquals("auth.challenge_expired", refusal(() -> mfa.complete("not-a-challenge", "123456", null)).code());
    }

    @Test
    void anInvitationLinkIsNoWayRoundTheSecondStep() {
        String email = owner();
        enrol(email);
        String slug = "mfa-second-" + SEQ.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", email);
        InvitationService invitations = new InvitationService(db, limits);
        String link = (String) created.get("invitationToken");
        ApiException e = refusal(() -> invitations.accept(link, PASSWORD, null, "10.5.5.5"));
        assertEquals("auth.mfa_required", e.code());
        assertEquals(403, e.status());
        // a wrong password learns nothing about two steps: it is the ordinary refusal, and it counts
        assertEquals("invitation.email_mismatch", refusal(() -> invitations.accept(link, "wrong-password-here", null, "10.5.5.6")).code());
    }

    @Test
    void switchingOrganizationKeepsTheProofOfTheCode() throws Exception {
        String email = owner();
        String secret = enrol(email).get(0);
        String session = mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(secret), null);
        SessionCtx ctx = auth.resolve(session);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orgs = (List<Map<String, Object>>) auth.destinations(ctx).get("organizations");
        String entered = auth.enterOrganization(ctx, java.util.UUID.fromString((String) orgs.get(0).get("tenantId")));
        assertNotNull(owner("select mfa_at from auth.session where refresh_token_hash = decode('"
                + java.util.HexFormat.of().formatHex(com.example.loginapp.core.Secrets.sha256(entered)) + "', 'hex')"));
    }

    @Test
    void turningItOffNeedsThePasswordAndACodeAndEndsOtherSessions() {
        String email = owner();
        String secret = enrol(email).get(0);
        String mine = mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(secret), null);
        advance(30);
        String other = mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(secret), null);
        SessionCtx ctx = auth.resolve(mine);
        advance(30);
        assertEquals(403, refusal(() -> mfa.disable(ctx, "wrong-password-here", codeNow(secret))).status());
        assertEquals(403, refusal(() -> mfa.disable(ctx, PASSWORD, "000000")).status());
        advance(30);
        mfa.disable(ctx, PASSWORD, codeNow(secret));
        assertFalse(mfa.status(ctx).enrolled());
        assertEquals(401, refusal(() -> auth.resolve(other)).status());
        assertNotNull(auth.resolve(mine));
        assertNotNull(auth.login(email, PASSWORD), "a plain sign-in works again");
    }

    @Test
    void newRecoveryCodesReplaceTheOldOnes() {
        String email = owner();
        List<String> enrolled = enrol(email);
        SessionCtx ctx = auth.resolve(mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(enrolled.get(0)), null));
        advance(30);
        assertEquals(403, refusal(() -> mfa.regenerate(ctx, "000000")).status());
        List<String> fresh = mfa.regenerate(ctx, codeNow(enrolled.get(0)));
        assertEquals(10, fresh.size());
        assertEquals(401, refusal(() -> mfa.complete(auth.signIn(email, PASSWORD, null).token(), enrolled.get(3), null)).status(), "an old code is dead");
        assertNotNull(mfa.complete(auth.signIn(email, PASSWORD, null).token(), fresh.get(0), null));
        assertEquals(9, mfa.status(ctx).recoveryCodesLeft());
        assertNull(null);
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

    // ── required by role ────────────────────────────────────────────────

    private java.util.UUID organizationOf(SessionCtx identity) {
        return java.util.UUID.fromString(((Map<?, ?>) ((List<?>) auth.destinations(identity).get("organizations")).get(0)).get("tenantId").toString());
    }

    private void requireFor(String email, String choice) {
        requireFor(insideOrganization(email), choice);
    }

    private void requireFor(SessionCtx inside, String choice) {
        new com.example.loginapp.core.SettingsService(db).change(inside, "auth.mfa.required_for",
                new com.example.loginapp.core.SettingsService.ChangeInput("organization", null, null, choice, null, "test", PASSWORD));
    }

    private SessionCtx insideOrganization(String email) {
        SessionCtx identity = auth.resolve(auth.login(email, PASSWORD));
        return auth.resolve(auth.enterOrganization(identity, organizationOf(identity)));
    }

    @Test
    void anAdminWithoutTwoStepIsToldToSetItUpBeforeEnteringAnOrganizationThatRequiresIt() throws Exception {
        String email = owner();
        requireFor(email, "admins");
        SessionCtx identity = auth.resolve(auth.login(email, PASSWORD));
        java.util.UUID tenant = organizationOf(identity);
        assertEquals("mfa.enrolment_required", refusal(() -> auth.enterOrganization(identity, tenant)).code());
        MfaService.Enrolment e = mfa.start(identity);
        mfa.confirm(identity, PASSWORD, codeNow(e.secret()));
        advance(30);
        SessionCtx again = auth.resolve(mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(e.secret()), null));
        assertNotNull(auth.enterOrganization(again, tenant), "a session that passed a code gets in");
    }

    @Test
    void anEnrolledAdminOnASessionWithoutACodeIsToldToSignInWithOne() throws Exception {
        String email = owner();
        requireFor(email, "admins");
        SessionCtx identity = auth.resolve(auth.login(email, PASSWORD));
        MfaService.Enrolment e = mfa.start(identity);
        mfa.confirm(identity, PASSWORD, codeNow(e.secret()));
        // the session that set it up has passed a code; one that has not is what a stolen password alone could make, so clear it
        ownerExec("update auth.session set mfa_at = null where id = '" + identity.sessionId() + "'");
        assertEquals("mfa.required", refusal(() -> auth.enterOrganization(identity, organizationOf(identity))).code());
    }

    @Test
    void turningItOffDoesNotKeepTheDoorOpen() throws Exception {
        String email = owner();
        requireFor(email, "admins");
        String secret = enrol(email).get(0);
        SessionCtx passed = auth.resolve(mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(secret), null));
        assertNotNull(auth.enterOrganization(passed, organizationOf(passed)));
        advance(30);
        SessionCtx again = auth.resolve(mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(secret), null));
        advance(30);
        mfa.disable(again, PASSWORD, codeNow(secret));
        // the session still carries the code it passed, but the login no longer has two-step, so it cannot go on switching
        assertEquals("mfa.enrolment_required", refusal(() -> auth.enterOrganization(again, organizationOf(again))).code());
    }

    @Test
    void aMemberWhoseRolesAreNotCoveredEntersAndOneWhoseRolesAreCoveredDoesNot() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        com.example.loginapp.core.UserService users = new com.example.loginapp.core.UserService(db);
        String employee = "emp@" + admin.tenantName().toLowerCase().replace(' ', '-') + ".test";
        String employeeToken = (String) users.invite(admin, employee, List.of("employee")).get("token");
        requireFor(admin, "admins");
        String employeeSession = new InvitationService(db, limits).accept(employeeToken, PASSWORD, null);
        assertNotNull(auth.resolve(employeeSession), "an employee joins with a session under 'admins'");
        SessionCtx emp = auth.resolve(auth.login(employee, PASSWORD));
        assertNotNull(auth.enterOrganization(emp, organizationOf(emp)));
        // everyone: now the same employee is asked
        requireFor(admin, "everyone");
        assertEquals("mfa.enrolment_required", refusal(() -> auth.enterOrganization(auth.resolve(auth.login(employee, PASSWORD)), organizationOf(emp))).code());
    }

    @Test
    void anInvitationForARoleThatMustUseTwoStepOpensNoSession() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        com.example.loginapp.core.UserService users = new com.example.loginapp.core.UserService(db);
        String hr = "hr@" + admin.tenantName().toLowerCase().replace(' ', '-') + ".test";
        String token = (String) users.invite(admin, hr, List.of("hr_admin")).get("token");
        requireFor(admin, "admins_payroll");
        assertEquals("mfa.enrolment_required", refusal(() -> new InvitationService(db, limits).accept(token, PASSWORD, null)).code());
        assertEquals(0, Integer.parseInt(owner("select count(*) from auth.session s join auth.identity i on i.id = s.identity_id "
                + "where i.email = '" + hr + "' and s.revoked_at is null and s.kind = 'tenant'")), "no live session was left");
        // they did join, so they sign in and meet the same request
        SessionCtx identity = auth.resolve(auth.login(hr, PASSWORD));
        assertEquals("mfa.enrolment_required", refusal(() -> auth.enterOrganization(identity, organizationOf(identity))).code());
    }

    @Test
    void raisingTheRequirementAsksForNothingExtraButLoweringNeedsThePassword() throws Exception {
        String email = owner();
        SessionCtx inside = insideOrganization(email);
        com.example.loginapp.core.SettingsService svc = new com.example.loginapp.core.SettingsService(db);
        svc.change(inside, "auth.mfa.required_for",
                new com.example.loginapp.core.SettingsService.ChangeInput("organization", null, null, "everyone", null, "tighten", null));
        ApiException e = refusal(() -> svc.change(inside, "auth.mfa.required_for",
                new com.example.loginapp.core.SettingsService.ChangeInput("organization", null, null, "none", null, "loosen", null)));
        assertEquals("setting.password_required", e.code());
    }

    @Test
    void anUnknownStoredChoiceIsTreatedAsRequired() {
        assertTrue(com.example.loginapp.core.MfaPolicy.applies("something-new", List.of("employee")));
        assertTrue(com.example.loginapp.core.MfaPolicy.applies(null, List.of("employee")));
    }

    @Test
    void sensitiveActionsAskForAFreshCodeWhereTwoStepIsRequired() throws Exception {
        String email = owner();
        SessionCtx early = insideOrganization(email);
        com.example.loginapp.core.UserService users = new com.example.loginapp.core.UserService(db);
        String employee = "emp@" + early.tenantName().toLowerCase().replace(' ', '-') + ".test";
        String employeeToken = (String) users.invite(early, employee, List.of("employee")).get("token");
        requireFor(early, "admins");
        java.util.UUID member = auth.resolve(new InvitationService(db, limits).accept(employeeToken, PASSWORD, null)).membershipId();
        String secret = enrol(email).get(0);
        SessionCtx admin = auth.resolve(auth.enterOrganization(
                auth.resolve(mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(secret), null)),
                early.tenantId()));
        // signed in with a code, but changing roles asks again
        assertEquals("stepup.required", refusal(() -> users.changeRoles(admin, member, List.of("employee", "manager"))).code());
        assertEquals("stepup.required", refusal(() -> users.invite(admin, "new@" + early.tenantName().toLowerCase().replace(' ', '-') + ".test",
                List.of("owner"))).code(), "an invitation can grant roles, so it asks too");
        advance(30);
        assertEquals("auth.invalid_code", refusal(() -> mfa.stepUp(admin, "000000")).code(), "a wrong code does not count");
        mfa.stepUp(admin, codeNow(secret));
        users.changeRoles(admin, member, List.of("employee", "manager"));
        assertEquals("auth.invalid_code", refusal(() -> mfa.stepUp(admin, codeNow(secret))).code(), "the same code cannot be shown twice");
        // fifteen minutes on, it asks again
        ownerExec("update auth.session set step_up_at = now() - interval '16 minutes' where id = '" + admin.sessionId() + "'");
        assertEquals("stepup.required", refusal(() -> users.changeRoles(admin, member, List.of("employee"))).code());
    }

    @Test
    void aRecoveryCodeWorksForStepUpAndSwitchingOrganizationDropsIt() throws Exception {
        String email = owner();
        SessionCtx early = insideOrganization(email);
        requireFor(early, "admins");
        com.example.loginapp.core.UserService users = new com.example.loginapp.core.UserService(db);
        List<String> enrolment = enrol(email);
        SessionCtx admin = auth.resolve(auth.enterOrganization(
                auth.resolve(mfa.complete(auth.signIn(email, PASSWORD, null).token(), codeNow(enrolment.get(0)), null)), early.tenantId()));
        mfa.stepUp(admin, enrolment.get(1));
        assertNotNull(owner("select step_up_at from auth.session where id = '" + admin.sessionId() + "'"));
        assertEquals("auth.invalid_code", refusal(() -> mfa.stepUp(admin, enrolment.get(1))).code(), "a recovery code is used once");
        // a session made by switching starts without one, so the next sensitive action asks again
        SessionCtx switched = auth.resolve(auth.enterOrganization(admin, early.tenantId()));
        assertEquals(null, owner("select step_up_at from auth.session where id = '" + switched.sessionId() + "'"));
        assertEquals("stepup.required", refusal(() -> users.invite(switched, "x@" + early.tenantName().toLowerCase().replace(' ', '-') + ".test",
                List.of("employee"))).code());
    }

    @Test
    void aMemberWhoMustUseTwoStepAndHasNoneIsToldToSetItUpNotAskedForACode() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        requireFor(admin, "admins");
        com.example.loginapp.core.UserService users = new com.example.loginapp.core.UserService(db);
        assertEquals("mfa.enrolment_required", refusal(() -> users.invite(admin, "x@" + admin.tenantName().toLowerCase().replace(' ', '-') + ".test",
                List.of("employee"))).code());
    }

    @Test
    void sensitiveActionsAskNothingExtraWhereTwoStepIsNotRequired() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        com.example.loginapp.core.UserService users = new com.example.loginapp.core.UserService(db);
        String employee = "emp@" + admin.tenantName().toLowerCase().replace(' ', '-') + ".test";
        String token = (String) users.invite(admin, employee, List.of("employee")).get("token");
        java.util.UUID member = auth.resolve(new InvitationService(db, limits).accept(token, PASSWORD, null)).membershipId();
        users.changeRoles(admin, member, List.of("employee", "manager"));
    }

    @Test
    void theChoiceDecidesWhichRolesMustUseIt() {
        assertFalse(com.example.loginapp.core.MfaPolicy.applies("none", List.of("owner")));
        assertTrue(com.example.loginapp.core.MfaPolicy.applies("admins", List.of("employee", "hr_admin")));
        assertTrue(com.example.loginapp.core.MfaPolicy.applies("admins", List.of("owner")));
        assertTrue(com.example.loginapp.core.MfaPolicy.applies("admins", List.of("it_admin")));
        assertTrue(com.example.loginapp.core.MfaPolicy.applies("admins_payroll", List.of("payroll_operator")));
        assertFalse(com.example.loginapp.core.MfaPolicy.applies("admins", List.of("payroll_operator")));
        assertTrue(com.example.loginapp.core.MfaPolicy.applies("admins_payroll", List.of("payroll_approver")));
        assertFalse(com.example.loginapp.core.MfaPolicy.applies("admins_payroll", List.of("employee", "manager")));
        assertTrue(com.example.loginapp.core.MfaPolicy.applies("everyone", List.of("employee")));
    }
}
