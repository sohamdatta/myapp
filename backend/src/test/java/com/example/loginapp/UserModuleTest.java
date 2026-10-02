package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * Exercises the user module against a real PostgreSQL, because the rules that
 * matter most (isolation between Organizations, the last Org Admin guard, the
 * payroll role rule) live in the database.
 *
 * Needs PostgreSQL on localhost:5432 with the owner account from
 * application.properties; the tests are skipped when it cannot be reached.
 * Override with APP_TEST_DB_HOST, APP_DB_OWNER_USER and APP_DB_OWNER_PASSWORD.
 */
class UserModuleTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static SessionCtx superAdmin;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));   // as the application does
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test with (force)");
            st.execute("create database hrms_test");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the user module tests");

        String url = "jdbc:postgresql://" + host + "/hrms_test";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);   // a second run must change nothing
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── sign-in ─────────────────────────────────────────────────────────

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameError() {
        assertError(401, "auth.invalid_credentials", () -> auth.login("root@platform.test", "not-the-password"));
        assertError(401, "auth.invalid_credentials", () -> auth.login("nobody@nowhere.test", "not-the-password"));
    }

    @Test
    void fiveFailedSignInsLockTheLogin() {
        Org org = newOrg();
        for (int i = 0; i < 5; i++) {
            assertError(401, "auth.invalid_credentials", () -> auth.login(org.adminEmail, "wrong-password-here"));
        }
        assertError(423, "auth.locked", () -> auth.login(org.adminEmail, PASSWORD));
    }

    @Test
    void emailIsNotCaseSensitive() {
        Org org = newOrg();
        assertNotNull(auth.login(org.adminEmail.toUpperCase(), PASSWORD));
    }

    // ── the three levels ────────────────────────────────────────────────

    @Test
    void firstInvitationMakesAnOrgAdminAndActivatesTheOrganization() {
        Org org = newOrg();
        assertEquals("tenant", org.admin.kind());
        assertTrue(org.admin.canAcrossOrganization("user.invite"));
        assertTrue(org.admin.canAcrossOrganization("billing.manage"));
        Map<String, Object> row = organization(org);
        assertEquals("active", row.get("status"));
        assertEquals(1L, ((Number) row.get("owners")).longValue());
    }

    @Test
    void superAdminHasNoWayIntoOrganizationData() {
        Org org = newOrg();
        assertError(403, "tenant.required", () -> users.users(superAdmin));
        // The console's database role cannot even name the table.
        assertError(403, "permission.denied", () -> db.inPlatform(c -> Sql.query(c, "select * from app.membership")));
        assertError(403, "permission.denied", () -> db.inAuth(c -> Sql.query(c, "select * from app.membership")));
        // Once the Organization has a member, the platform can no longer invite into it.
        assertError(409, "request.refused", () -> platform.inviteFirstAdmin(superAdmin, org.id, "intruder@x.test"));
    }

    @Test
    void organizationsCannotSeeEachOther() {
        Org a = newOrg();
        Org b = newOrg();
        Map<String, Object> pending = users.invite(b.admin, "pending@b.test", List.of("employee"));
        List<String> emailsSeenByA = users.users(a.admin).stream().map(u -> (String) u.get("email")).toList();
        assertEquals(List.of(a.adminEmail), emailsSeenByA);
        assertTrue(users.invitations(a.admin).isEmpty());
        UUID bInvitation = UUID.fromString((String) pending.get("id"));
        assertError(404, "not_found", () -> users.revokeInvitation(a.admin, bInvitation));
        UUID bAdmin = b.admin.membershipId();
        assertError(404, "not_found", () -> users.suspend(a.admin, bAdmin));
        // Writing a row for another Organization is refused by row-level security.
        assertError(403, "permission.denied", () -> db.inTenant(a.id, c -> Sql.query(c,
                "insert into app.invitation (tenant_id, email, role_ids, token_hash, expires_at) "
                        + "select ?, 'x@x.test', array[id], '\\x01', now() from app.role where code = 'employee' returning id",
                b.id)));
    }

    // ── invitations ─────────────────────────────────────────────────────

    @Test
    void invitedUserSetsAPasswordAndJoinsWithTheOfferedRoles() {
        Org org = newOrg();
        SessionCtx employee = join(org, "emp", List.of("employee"));
        assertEquals(org.id, employee.tenantId());
        assertTrue(employee.can("employee.read"));
        assertFalse(employee.canAcrossOrganization("user.read"));
        assertError(403, "permission.denied", () -> users.users(employee));
        assertTrue(actions(org).contains("invitation.accepted"));
    }

    @Test
    void invitationLinkWorksOnceAndNeedsAStrongPassword() {
        Org org = newOrg();
        String token = (String) users.invite(org.admin, "once@" + org.slug + ".test", List.of("employee")).get("token");
        assertError(400, "auth.weak_password", () -> invitations.accept(token, "short", null));
        invitations.accept(token, PASSWORD, null);
        assertError(410, "invitation.invalid", () -> invitations.accept(token, PASSWORD, null));
        assertError(410, "invitation.invalid", () -> invitations.preview("no-such-token"));
    }

    @Test
    void duplicateAndRevokedInvitationsAreRefused() {
        Org org = newOrg();
        String email = "dup@" + org.slug + ".test";
        Map<String, Object> first = users.invite(org.admin, email, List.of("employee"));
        assertError(409, "membership.exists", () -> users.invite(org.admin, email.toUpperCase(), List.of("employee")));
        assertError(409, "membership.exists", () -> users.invite(org.admin, org.adminEmail, List.of("employee")));
        users.revokeInvitation(org.admin, UUID.fromString((String) first.get("id")));
        assertError(410, "invitation.invalid", () -> invitations.accept((String) first.get("token"), PASSWORD, null));
        Map<String, Object> again = users.invite(org.admin, email, List.of("employee"));
        String resent = (String) users.resendInvitation(org.admin, UUID.fromString((String) again.get("id"))).get("token");
        assertError(410, "invitation.invalid", () -> invitations.accept((String) again.get("token"), PASSWORD, null));
        assertNotNull(invitations.accept(resent, PASSWORD, null));
    }

    @Test
    void oneLoginCanBelongToTwoOrganizations() {
        Org a = newOrg();
        Org b = newOrg();
        String token = (String) users.invite(b.admin, a.adminEmail, List.of("auditor")).get("token");
        assertTrue((Boolean) invitations.preview(token).get("identityExists"));
        assertError(403, "invitation.email_mismatch", () -> invitations.accept(token, "not-their-password", null));
        SessionCtx inB = auth.resolve(invitations.accept(token, PASSWORD, null));
        assertEquals(b.id, inB.tenantId());
        assertEquals(2, ((List<?>) auth.destinations(inB).get("organizations")).size());
        // Switching issues a new session and ends the old one.
        SessionCtx inA = auth.resolve(auth.enterOrganization(inB, a.id));
        assertEquals(a.id, inA.tenantId());
        assertTrue(inA.canAcrossOrganization("billing.manage"));
        assertFalse(auth.resolve(auth.enterOrganization(inA, b.id)).canAcrossOrganization("billing.manage"));
    }

    // ── who may grant what ──────────────────────────────────────────────

    @Test
    void hrAdminCanGrantEmployeeRolesButNotPayrollOrAdminRoles() {
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        assertNotNull(users.invite(hr, "new-emp@" + org.slug + ".test", List.of("employee", "manager")));
        assertError(403, "grant.not_allowed", () -> users.invite(hr, "pay@" + org.slug + ".test", List.of("payroll_operator")));
        assertError(403, "grant.not_allowed", () -> users.invite(hr, "boss@" + org.slug + ".test", List.of("owner")));
        assertError(403, "grant.not_allowed", () -> users.invite(hr, "aud@" + org.slug + ".test", List.of("auditor")));
        assertError(403, "grant.not_allowed", () -> users.invite(hr, "ex@" + org.slug + ".test", List.of("ex_employee")));
        // Nor can an HR Admin demote, suspend or remove an Org Admin.
        UUID admin = org.admin.membershipId();
        assertError(403, "grant.not_allowed", () -> users.changeRoles(hr, admin, List.of("employee")));
        assertError(403, "grant.not_allowed", () -> users.suspend(hr, admin));
        assertError(403, "grant.not_allowed", () -> users.end(hr, admin));
        Map<String, Boolean> grantable = new java.util.HashMap<>();
        users.roles(hr).forEach(r -> grantable.put((String) r.get("code"), (Boolean) r.get("grantable")));
        assertEquals(Map.of("auditor", false, "employee", true, "ex_employee", false, "hr_admin", true, "manager", true,
                "owner", false, "payroll_approver", false, "payroll_operator", false), grantable);
    }

    @Test
    void nobodyChangesTheirOwnRolesOrStatus() {
        Org org = newOrg();
        UUID self = org.admin.membershipId();
        assertError(409, "request.refused", () -> users.changeRoles(org.admin, self, List.of("employee")));
        assertError(409, "request.refused", () -> users.suspend(org.admin, self));
        assertError(409, "request.refused", () -> users.end(org.admin, self));
    }

    @Test
    void oneUserCannotHoldBothPayrollRoles() {
        Org org = newOrg();
        SessionCtx operator = join(org, "pay", List.of("payroll_operator"));
        UUID id = operator.membershipId();
        assertError(409, "role.maker_checker", () -> users.changeRoles(org.admin, id, List.of("payroll_operator", "payroll_approver")));
        users.changeRoles(org.admin, id, List.of("payroll_approver"));   // swapping is fine
        assertTrue(auth.resolve(tokenOf(org, "pay")).can("payroll.approve"));
        assertError(409, "role.maker_checker", () -> users.invite(org.admin, "both@" + org.slug + ".test",
                List.of("payroll_operator", "payroll_approver")));
    }

    @Test
    void theLastOrgAdminIsProtectedByTheDatabase() {
        Org org = newOrg();
        UUID admin = org.admin.membershipId();
        assertError(409, "membership.last_owner", () -> db.inTenant(org.id, c ->
                Sql.update(c, "update app.membership set status = 'suspended' where id = ?", admin)));
        assertError(409, "membership.last_owner", () -> db.inTenant(org.id, c ->
                Sql.update(c, "delete from app.membership_role where membership_id = ?", admin)));
        // With a second Org Admin, the first can be suspended.
        SessionCtx second = join(org, "second", List.of("owner"));
        users.suspend(second, admin);
        assertError(401, "auth.session_revoked", () -> auth.resolve(org.adminToken));
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    @Test
    void roleChangesApplyOnTheNextRequest() {
        Org org = newOrg();
        SessionCtx employee = join(org, "emp", List.of("employee"));
        String token = tokenOf(org, "emp");
        assertFalse(auth.resolve(token).canAcrossOrganization("user.invite"));
        users.changeRoles(org.admin, employee.membershipId(), List.of("employee", "hr_admin"));
        assertTrue(auth.resolve(token).canAcrossOrganization("user.invite"));
        users.changeRoles(org.admin, employee.membershipId(), List.of("employee"));
        assertFalse(auth.resolve(token).canAcrossOrganization("user.invite"));
        assertTrue(actions(org).containsAll(List.of("role.granted", "role.removed")));
    }

    @Test
    void suspendingEndsSessionsAndReactivatingRestoresAccess() {
        Org org = newOrg();
        SessionCtx employee = join(org, "emp", List.of("employee"));
        String token = tokenOf(org, "emp");
        users.suspend(org.admin, employee.membershipId());
        assertError(401, "auth.session_revoked", () -> auth.resolve(token));
        SessionCtx fresh = auth.resolve(auth.login("emp@" + org.slug + ".test", PASSWORD));
        assertTrue(((List<?>) auth.destinations(fresh).get("organizations")).isEmpty());
        assertError(403, "tenant.unavailable", () -> auth.enterOrganization(fresh, org.id));
        users.reactivate(org.admin, employee.membershipId());
        assertEquals(org.id, auth.resolve(auth.enterOrganization(fresh, org.id)).tenantId());
    }

    @Test
    void endedUserCannotEnterAndCanBeInvitedBack() {
        Org org = newOrg();
        SessionCtx employee = join(org, "emp", List.of("employee"));
        String email = "emp@" + org.slug + ".test";
        users.end(org.admin, employee.membershipId());
        SessionCtx fresh = auth.resolve(auth.login(email, PASSWORD));
        assertTrue(((List<?>) auth.destinations(fresh).get("organizations")).isEmpty());
        // Rehire: the same membership row is reactivated with the new roles.
        String token = (String) users.invite(org.admin, email, List.of("manager")).get("token");
        SessionCtx back = auth.resolve(invitations.accept(token, null, fresh));
        assertEquals(employee.membershipId(), back.membershipId());
        assertEquals(2, users.users(org.admin).size());
    }

    @Test
    void suspendedOrganizationCannotBeEntered() {
        Org org = newOrg();
        platform.changeOrganizationStatus(superAdmin, org.id, "suspend");
        assertError(401, "auth.session_revoked", () -> auth.resolve(org.adminToken));
        SessionCtx fresh = auth.resolve(auth.login(org.adminEmail, PASSWORD));
        assertTrue(((List<?>) auth.destinations(fresh).get("organizations")).isEmpty());
        platform.changeOrganizationStatus(superAdmin, org.id, "reactivate");
        assertEquals(1, ((List<?>) auth.destinations(fresh).get("organizations")).size());
    }

    // ── support access ──────────────────────────────────────────────────

    @Test
    void supportAccessIsGrantedLoggedLimitedAndRevocable() {
        Org org = newOrg();
        // With a single Super Admin the grant is approved automatically, and says so.
        Map<String, Object> grant = platform.requestGrant(superAdmin, org.id, "read_only", "Ticket 42: user list looks wrong");
        assertEquals("approved", grant.get("status"));
        UUID grantId = UUID.fromString((String) grant.get("id"));
        Map<String, Object> listed = platform.grants(superAdmin).stream()
                .filter(g -> g.get("id").equals(grant.get("id"))).findFirst().orElseThrow();
        assertEquals(true, listed.get("autoApproved"));

        String token = platform.startSupportSession(superAdmin, grantId);
        SessionCtx support = auth.resolve(token);
        assertEquals("support", support.kind());
        assertEquals(1, users.users(support).size());
        // Read-only: no writes, and never the money or role permissions.
        assertError(403, "permission.denied", () -> users.invite(support, "x@x.test", List.of("employee")));
        assertFalse(support.can("payroll.approve"));
        assertFalse(support.can("role.assign"));
        // The Organization's own audit log shows the session and its reason.
        Map<String, Object> event = users.auditLog(org.admin).stream()
                .filter(e -> e.get("action").equals("support.session_started")).findFirst().orElseThrow();
        assertEquals("support", event.get("actorKind"));
        assertTrue(((String) event.get("detail")).contains("Ticket 42"));

        platform.decideGrant(superAdmin, grantId, "revoke");
        assertError(401, "support.expired", () -> auth.resolve(token));
        assertError(409, "support.expired", () -> platform.startSupportSession(superAdmin, grantId));
    }

    @Test
    void writeModeSupportStillCannotGrantRolesOrApprovePayroll() {
        Org org = newOrg();
        Map<String, Object> grant = platform.requestGrant(superAdmin, org.id, "write", "Ticket 77: fix a stuck invitation");
        SessionCtx support = auth.resolve(platform.startSupportSession(superAdmin, UUID.fromString((String) grant.get("id"))));
        assertTrue(support.canAcrossOrganization("user.manage"));
        assertFalse(support.can("payroll.approve"));
        assertFalse(support.can("payroll.release"));
        assertError(403, "grant.not_allowed", () -> users.invite(support, "x@" + org.slug + ".test", List.of("employee")));
        assertError(403, "permission.denied", () -> users.changeRoles(support, org.admin.membershipId(), List.of("employee")));
    }

    @Test
    void aGrantNeedsASecondPersonWhenOneExists() {
        Org org = newOrg();
        String email = "second-admin-" + SEQUENCE.incrementAndGet() + "@platform.test";
        platform.addPlatformUser(superAdmin, email, "super_admin", PASSWORD);
        try {
            SessionCtx second = auth.resolve(auth.enterConsole(auth.resolve(auth.login(email, PASSWORD))));
            Map<String, Object> grant = platform.requestGrant(superAdmin, org.id, "read_only", "Ticket 99: needs a second approver");
            assertEquals("requested", grant.get("status"));
            UUID grantId = UUID.fromString((String) grant.get("id"));
            assertError(409, "support.expired", () -> platform.startSupportSession(superAdmin, grantId));
            assertError(409, "request.refused", () -> platform.decideGrant(superAdmin, grantId, "approve"));
            platform.decideGrant(second, grantId, "approve");
            assertEquals("support", auth.resolve(platform.startSupportSession(superAdmin, grantId)).kind());
            // A grant belongs to its requester.
            assertError(409, "support.expired", () -> platform.startSupportSession(second, grantId));
        } finally {
            Map<String, Object> row = platform.platformUsers(superAdmin).stream()
                    .filter(u -> u.get("email").equals(email)).findFirst().orElseThrow();
            platform.setPlatformUserStatus(superAdmin, UUID.fromString((String) row.get("id")), false);
        }
    }

    @Test
    void platformRolesAreChecked() {
        Org org = newOrg();
        assertError(403, "permission.denied", () -> platform.organizations(org.admin));
        assertError(403, "permission.denied", () -> platform.createOrganization(org.admin, "X", "xxx", "essential", "a@b.test"));
        assertError(409, "request.refused", () -> platform.setPlatformUserStatus(superAdmin, superAdmin.platformUserId(), false));
        assertError(409, "tenant.slug_taken", () -> platform.createOrganization(superAdmin, "Copy", org.slug, "essential", "a@b.test"));
        assertError(400, "request.invalid", () -> platform.createOrganization(superAdmin, "Bad", "Not A Slug", "essential", "a@b.test"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** An Organization with its first Org Admin signed in. */
    private record Org(UUID id, String slug, String adminEmail, String adminToken, SessionCtx admin) {}

    private static final Map<String, String> TOKENS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Org newOrg() {
        String slug = "org-" + SEQUENCE.incrementAndGet();
        String adminEmail = "admin@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", adminEmail);
        assertEquals("provisioning", created.get("status"));
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, adminEmail, token, auth.resolve(token));
    }

    /** Invites name@slug.test with the roles, accepts, and returns the new user's session. */
    private static SessionCtx join(Org org, String name, List<String> roles) {
        String email = name + "@" + org.slug + ".test";
        String invitation = (String) users.invite(org.admin, email, roles).get("token");
        String token = invitations.accept(invitation, PASSWORD, null);
        TOKENS.put(org.slug + "/" + name, token);
        return auth.resolve(token);
    }

    private static String tokenOf(Org org, String name) {
        return TOKENS.get(org.slug + "/" + name);
    }

    private static Map<String, Object> organization(Org org) {
        return platform.organizations(superAdmin).stream()
                .filter(o -> o.get("id").equals(org.id.toString())).findFirst().orElseThrow();
    }

    private static List<String> actions(Org org) {
        return users.auditLog(org.admin).stream().map(e -> (String) e.get("action")).toList();
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(code, e.code(), e.getMessage());
        assertEquals(status, e.status(), e.getMessage());
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
