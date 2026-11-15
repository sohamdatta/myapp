package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.OfficialEmailService;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SignInSettingsService;
import com.example.loginapp.core.UserService;

/**
 * Moving people from the personal address they were invited at to their company address: the Organization's email domain, HR's
 * proposal, and what the person sees. Needs PostgreSQL as described in {@link UserModuleTest}.
 */
class OfficialEmailServiceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQ = new AtomicInteger();

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
            st.execute("drop database if exists hrms_test_official with (force)");
            st.execute("create database hrms_test_official");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the official email tests");
        url = "jdbc:postgresql://" + host + "/hrms_test_official";
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
    private final UserService users = new UserService(db);
    private final SignInSettingsService settings = new SignInSettingsService(db);
    private final OfficialEmailService official = new OfficialEmailService(db, outbox);

    private String owner() {
        String slug = "official-" + SEQ.incrementAndGet();
        String email = "owner@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, email);
        new InvitationService(db, limits).accept((String) created.get("invitationToken"), PASSWORD, null);
        return email;
    }

    private SessionCtx insideOrganization(String email) {
        SessionCtx identity = auth.resolve(auth.login(email, PASSWORD));
        UUID tenant = UUID.fromString(((Map<?, ?>) ((List<?>) auth.destinations(identity).get("organizations")).get(0)).get("tenantId").toString());
        return auth.resolve(auth.enterOrganization(identity, tenant));
    }

    private void join(SessionCtx admin, String email, String role) {
        new InvitationService(db, limits).accept((String) users.invite(admin, email, List.of(role)).get("token"), PASSWORD, null);
    }

    private UUID membershipOf(SessionCtx admin, String email) {
        return UUID.fromString(users.users(admin).stream().filter(u -> email.equalsIgnoreCase((String) u.get("email")))
                .map(u -> (String) u.get("id")).findFirst().orElseThrow());
    }

    private static String query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static ApiException refusal(org.junit.jupiter.api.function.Executable work) {
        return assertThrows(ApiException.class, work);
    }

    @Test
    void anAddressCanBeProposedOnlyInTheOrganizationsOwnDomainAndOnlyOnceItIsSet() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        String slug = admin.tenantName().toLowerCase().replace(' ', '-');
        String personal = "ravi.personal@" + slug + ".test";
        join(admin, personal, "employee");
        UUID member = membershipOf(admin, personal);

        assertEquals("official_email.no_domain", refusal(() -> official.propose(admin, member, "ravi@acme.com")).code());
        assertEquals(403, refusal(() -> settings.setOfficialEmailDomain(admin, "acme.com", "wrong-password-here")).status());
        assertEquals(400, refusal(() -> settings.setOfficialEmailDomain(admin, "not a domain", PASSWORD)).status());
        assertEquals("acme.com", settings.setOfficialEmailDomain(admin, " ACME.com", PASSWORD).officialEmailDomain());

        assertEquals(400, refusal(() -> official.propose(admin, member, "ravi@evil.com")).status(), "another domain");
        assertEquals(400, refusal(() -> official.propose(admin, member, "ravi@mail.acme.com")).status(), "a sub-domain is not the domain");
        assertEquals(400, refusal(() -> official.propose(admin, member, "not an address")).status());

        official.propose(admin, member, "Ravi@Acme.com");
        assertEquals("ravi@acme.com", query("select proposed_email from app.membership where id = '" + member + "'"));
        assertEquals("1", query("select count(*) from mail.outbox where template = 'official-email' and to_email = '" + personal + "'"));
        official.propose(admin, member, "ravi@acme.com");
        assertEquals("1", query("select count(*) from mail.outbox where template = 'official-email' and to_email = '" + personal + "'"), "the same proposal is not mailed twice");
        assertEquals("ravi@acme.com", users.users(admin).stream().filter(u -> personal.equals(u.get("email"))).findFirst().orElseThrow().get("proposedEmail"));
    }

    @Test
    void thePersonSeesTheProposalUntilTheirAddressIsTheProposedOne() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        settings.setOfficialEmailDomain(admin, "acme.com", PASSWORD);
        String slug = admin.tenantName().toLowerCase().replace(' ', '-');
        String personal = "asha.personal@" + slug + ".test";
        join(admin, personal, "employee");
        official.propose(admin, membershipOf(admin, personal), "asha@acme.com");

        SessionCtx asha = auth.resolve(auth.login(personal, PASSWORD));
        List<OfficialEmailService.Proposal> seen = official.mine(asha);
        assertEquals(1, seen.size());
        assertEquals("asha@acme.com", seen.get(0).email());
        assertEquals(admin.tenantName(), seen.get(0).organization());

        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute("update auth.identity set email = 'asha@acme.com' where email = '" + personal + "'");
        }
        assertEquals(List.of(), official.mine(asha), "done: nothing left to ask");
        assertEquals(List.of(), official.mine(auth.resolve(auth.login(email, PASSWORD))), "nothing for someone who was not asked");
    }

    @Test
    void onlyAdministratorsWithReachCanProposeAndWithdrawingClearsIt() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        settings.setOfficialEmailDomain(admin, "acme.com", PASSWORD);
        String slug = admin.tenantName().toLowerCase().replace(' ', '-');
        String employee = "emp@" + slug + ".test";
        String colleague = "col@" + slug + ".test";
        join(admin, employee, "employee");
        join(admin, colleague, "employee");
        UUID target = membershipOf(admin, colleague);
        SessionCtx emp = insideOrganization(employee);
        assertEquals(403, refusal(() -> official.propose(emp, target, "col@acme.com")).status());
        assertEquals(403, refusal(() -> settings.setOfficialEmailDomain(emp, "acme.com", PASSWORD)).status());

        official.propose(admin, target, "col@acme.com");
        official.withdraw(admin, target);
        assertEquals(null, query("select proposed_email from app.membership where id = '" + target + "'"));
        assertEquals(List.of(), official.mine(auth.resolve(auth.login(colleague, PASSWORD))));
        assertEquals("1", query("select count(*) from app.audit_event where action = 'user.official_email_withdrawn'"));
    }

    @Test
    void aMembershipOfAnotherOrganizationIsNotFound() throws Exception {
        SessionCtx mine = insideOrganization(owner());
        settings.setOfficialEmailDomain(mine, "acme.com", PASSWORD);
        String otherOwner = owner();
        SessionCtx theirs = insideOrganization(otherOwner);
        UUID foreign = membershipOf(theirs, otherOwner);
        assertEquals(404, refusal(() -> official.propose(mine, foreign, "x@acme.com")).status());
        assertEquals(404, refusal(() -> official.withdraw(mine, foreign)).status());
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
